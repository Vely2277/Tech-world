/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * RECOVERY SCHEDULER SERVICE - ESCALATING FCM WAKE SYSTEM (FIRESTORE + IN-MEMORY TIMERS)
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Manages an escalating FCM recovery schedule for offline devices.
 * When the presence monitor detects a device is offline (7+ minutes), it triggers this system.
 *
 * SCHEDULE:
 * Step 0: Immediately send first FCM (0 min)
 * Step 1: +1 minute  → send second FCM
 * Step 2: +3 minutes → send third FCM
 * Steps 3-14: Every 30 minutes → send FCM (up to 12 more times = 6 hours)
 * After 6 hours: Cycle restarts from step 0
 *
 * ARCHITECTURE:
 * - Uses in-memory setTimeout() for scheduling — lightweight, zero external dependencies
 * - Firestore collection 'fcmRecoveryJobs' is the SINGLE source of truth for persistence
 * - On server restart, restoreFromFirestore() re-creates timers from saved state
 * - On cancelRecovery, the in-memory timer is cleared and Firestore state updated
 *
 * WHY THIS IS RELIABLE:
 * - Firestore is Google-managed, always available, free tier is massive
 * - No external dependency (no Redis, no Bull, no connection drops, no ECONNRESET)
 * - On server restart, all pending recoveries are restored from Firestore
 * - setTimeout in Node.js supports delays up to ~24.8 days (plenty for 30-min steps)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const RECOVERY_COLLECTION = 'fcmRecoveryJobs';
const FCM_TOKENS_COLLECTION = 'fcmTokens';
const DEVICE_NOTIFICATIONS_COLLECTION = 'deviceNotifications';
const FCM_WAKE_HISTORY_COLLECTION = 'fcmWakeHistory';

// Schedule definition: array of delays in milliseconds from the PREVIOUS step
const SCHEDULE_DELAYS = [
  0,          // Step 0: Immediate
  60000,      // Step 1: +1 min after step 0
  180000,     // Step 2: +3 min after step 1
  1800000,    // Step 3: +30 min after step 2
  1800000,    // Step 4: +30 min
  1800000,    // Step 5: +30 min
  1800000,    // Step 6: +30 min
  1800000,    // Step 7: +30 min
  1800000,    // Step 8: +30 min
  1800000,    // Step 9: +30 min
  1800000,    // Step 10: +30 min
  1800000,    // Step 11: +30 min
  1800000,    // Step 12: +30 min
  1800000,    // Step 13: +30 min
  1800000,    // Step 14: +30 min (last step = 15 total FCMs)
];

const MAX_STEPS = SCHEDULE_DELAYS.length; // 15 total FCMs per cycle
const MAX_CYCLES = 100; // Safety limit

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// IN-MEMORY TIMER STATE
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

// Map: userId → { timerId, step, cycle, firesAt }
const activeTimers = new Map();

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const getFirestore = () => {
  const admin = getFirebaseAdmin();
  return admin.firestore();
};

const getMessaging = () => {
  const admin = getFirebaseAdmin();
  return admin.messaging();
};

const generateRequestId = (userId, step, cycle) => {
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 8);
  return `recovery_${userId.substring(0, 8)}_c${cycle}_s${step}_${timestamp}_${random}`;
};

const getUserFCMTokens = async (userId) => {
  const db = getFirestore();
  try {
    const tokensDoc = await db.collection(FCM_TOKENS_COLLECTION).doc(userId).get();
    if (!tokensDoc.exists) return [];
    const data = tokensDoc.data();
    const tokens = data.tokens || [];
    return [...new Set(tokens.map(t => t.token))];
  } catch (error) {
    console.error(`❌ [Recovery] Error getting FCM tokens for ${userId}:`, error);
    return [];
  }
};

const removeInvalidToken = async (userId, token) => {
  const db = getFirestore();
  try {
    const tokensDoc = await db.collection(FCM_TOKENS_COLLECTION).doc(userId).get();
    if (tokensDoc.exists) {
      const data = tokensDoc.data();
      const tokens = data.tokens || [];
      const tokenToRemove = tokens.find(t => t.token === token);
      if (tokenToRemove) {
        await db.collection(FCM_TOKENS_COLLECTION).doc(userId).update({
          tokens: firebaseAdmin.firestore.FieldValue.arrayRemove(tokenToRemove)
        });
      }
    }
  } catch (error) {
    console.error(`❌ [Recovery] Error removing invalid token:`, error);
  }
};

const sendRecoveryFCM = async (userId, requestId, step, cycle) => {
  const messaging = getMessaging();
  const tokens = await getUserFCMTokens(userId);

  if (tokens.length === 0) {
    console.log(`⚠️ [Recovery] No FCM tokens for user ${userId}`);
    return { success: false, reason: 'NO_TOKENS', successCount: 0, failureCount: 0 };
  }

  const message = {
    data: {
      type: 'WAKE_SERVICE',
      action: 'CHECK_STATUS',
      requestId: requestId,
      reason: `auto_recovery_c${cycle}_s${step}`,
      timestamp: Date.now().toString(),
      priority: 'high',
      recoveryStep: step.toString(),
      recoveryCycle: cycle.toString()
    },
    android: {
      priority: 'high',
      ttl: 86400000
    }
  };

  let successCount = 0;
  let failureCount = 0;

  for (const token of tokens) {
    try {
      await messaging.send({ ...message, token });
      successCount++;
      console.log(`✅ [Recovery] FCM sent to ${token.substring(0, 20)}... (step ${step}, cycle ${cycle})`);
    } catch (error) {
      failureCount++;
      console.error(`❌ [Recovery] FCM failed for ${token.substring(0, 20)}...:`, error.message);
      if (error.code === 'messaging/invalid-registration-token' ||
          error.code === 'messaging/registration-token-not-registered') {
        await removeInvalidToken(userId, token);
      }
    }
  }

  return { success: successCount > 0, successCount, failureCount };
};

const logRecoveryNotification = async (userId, requestId, step, cycle, fcmResult, isRestart = false) => {
  const db = getFirestore();

  const stepLabel = step === 0 ? 'Immediate' :
                    step === 1 ? '+1 min' :
                    step === 2 ? '+3 min' :
                    `+30 min (#${step - 2})`;

  const message = isRestart
    ? `🔄 Recovery cycle restarted (cycle ${cycle}). Sending FCM immediately.`
    : `🚨 Recovery FCM sent (Step ${step + 1}/${MAX_STEPS}, Cycle ${cycle + 1}) — ${stepLabel}`;

  try {
    await db.collection(DEVICE_NOTIFICATIONS_COLLECTION).add({
      userId,
      type: 'FCM_RECOVERY_SENT',
      message: message,
      timestamp: Date.now(),
      requestId,
      reason: `auto_recovery_c${cycle}_s${step}`,
      fcmSentCount: fcmResult.successCount,
      fcmFailedCount: fcmResult.failureCount,
      recoveryStep: step,
      recoveryCycle: cycle,
      isRecovery: true,
      read: false,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });
  } catch (error) {
    console.error(`❌ [Recovery] Error logging notification:`, error);
  }
};

const recordRecoveryAttempt = async (userId, requestId, step, cycle, fcmResult) => {
  const db = getFirestore();
  try {
    await db.collection(FCM_WAKE_HISTORY_COLLECTION).add({
      userId,
      requestId,
      reason: `auto_recovery_c${cycle}_s${step}`,
      sentAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      fcmResult: {
        success: fcmResult.success,
        successCount: fcmResult.successCount,
        failureCount: fcmResult.failureCount
      },
      status: 'pending',
      isRecovery: true,
      recoveryStep: step,
      recoveryCycle: cycle,
      responseReceivedAt: null,
      response: null
    });
  } catch (error) {
    console.error(`❌ [Recovery] Error recording attempt:`, error);
  }
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// FIRESTORE STATE MANAGEMENT
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const saveRecoveryState = async (userId, stateData) => {
  const db = getFirestore();
  try {
    await db.collection(RECOVERY_COLLECTION).doc(userId).set({
      userId,
      status: stateData.status,
      currentStep: stateData.currentStep,
      currentCycle: stateData.currentCycle,
      totalFcmsSent: stateData.totalFcmsSent,
      startedAt: stateData.startedAt,
      lastFcmSentAt: stateData.lastFcmSentAt,
      nextFireAt: stateData.nextFireAt,
      triggeredBy: stateData.triggeredBy || 'presence_monitor',
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
  } catch (error) {
    console.error(`❌ [Recovery] Error saving state for ${userId}:`, error);
  }
};

const clearRecoveryState = async (userId) => {
  const db = getFirestore();
  try {
    await db.collection(RECOVERY_COLLECTION).doc(userId).set({
      userId,
      status: 'cancelled',
      cancelledAt: Date.now(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
  } catch (error) {
    console.error(`❌ [Recovery] Error clearing state for ${userId}:`, error);
  }
};

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// TIMER SCHEDULING — SELF-CHAINING setTimeout LOOP
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Schedule the next recovery step for a user.
 * Sets a setTimeout that will fire processStep() after the given delay.
 */
function scheduleNextStep(userId, step, cycle, delay) {
  // Clear any existing timer for this user (safety — prevent duplicates)
  const existing = activeTimers.get(userId);
  if (existing) {
    clearTimeout(existing.timerId);
  }

  const timerId = setTimeout(() => {
    // Remove from active timers before processing (it will re-add itself if needed)
    activeTimers.delete(userId);
    processStep(userId, step, cycle).catch(err => {
      console.error(`❌ [Recovery] Unhandled error in processStep for ${userId}:`, err);
    });
  }, delay);

  activeTimers.set(userId, { timerId, step, cycle, firesAt: Date.now() + delay });
}

/**
 * Process a single recovery step: send FCM, log, schedule next step.
 * This is the core engine — it self-chains via scheduleNextStep().
 */
async function processStep(userId, step, cycle) {
  // Check if recovery was cancelled while waiting
  const db = getFirestore();
  let recoveryDoc;
  try {
    recoveryDoc = await db.collection(RECOVERY_COLLECTION).doc(userId).get();
    if (recoveryDoc.exists) {
      const docData = recoveryDoc.data();
      if (docData.status === 'cancelled' || docData.status === 'completed') {
        console.log(`🛑 [Recovery] Cancelled/completed before step ${step} for ${userId}. Skipping.`);
        return;
      }
    }
  } catch (e) {
    console.error(`❌ [Recovery] Error checking state for ${userId}:`, e.message);
    // Continue anyway — better to send an FCM than skip
  }

  console.log(`═══════════════════════════════════════════════════════`);
  console.log(`🚨 [Recovery] Executing step ${step + 1}/${MAX_STEPS} (cycle ${cycle + 1}) for ${userId}`);
  console.log(`═══════════════════════════════════════════════════════`);

  const requestId = generateRequestId(userId, step, cycle);
  const fcmResult = await sendRecoveryFCM(userId, requestId, step, cycle);

  // Log notification and record attempt (non-critical — don't let failures break the chain)
  try {
    await logRecoveryNotification(userId, requestId, step, cycle, fcmResult, step === 0 && cycle > 0);
  } catch (e) {
    console.error(`⚠️ [Recovery] Non-critical: failed to log notification for ${userId}:`, e.message);
  }
  try {
    await recordRecoveryAttempt(userId, requestId, step, cycle, fcmResult);
  } catch (e) {
    console.error(`⚠️ [Recovery] Non-critical: failed to record attempt for ${userId}:`, e.message);
  }

  const now = Date.now();
  const prevTotalSent = recoveryDoc && recoveryDoc.exists ? (recoveryDoc.data().totalFcmsSent || 0) : 0;
  const prevStartedAt = recoveryDoc && recoveryDoc.exists ? (recoveryDoc.data().startedAt || now) : now;
  const prevTriggeredBy = recoveryDoc && recoveryDoc.exists ? recoveryDoc.data().triggeredBy : 'presence_monitor';

  // ═══════════════════════════════════════════════════════════════════════════
  // CRITICAL: Schedule the next step. Wrapped in try/catch with retry so a
  // single Firestore hiccup can NEVER permanently break the recovery chain.
  // ═══════════════════════════════════════════════════════════════════════════
  try {
    await scheduleAndPersistNextStep(userId, step, cycle, now, prevTotalSent, prevStartedAt, prevTriggeredBy);
  } catch (scheduleError) {
    console.error(`❌ [Recovery] CRITICAL: Failed to schedule next step for ${userId}:`, scheduleError.message);
    console.log(`🔄 [Recovery] Retrying in 10 seconds...`);
    // Retry once after 10 seconds — if this also fails, the recovery will be picked up
    // by restoreFromFirestore() on next server restart (Firestore state still says 'recovering')
    scheduleNextStep(userId, step, cycle, 10000);
  }
}

/**
 * Compute and persist the next step, then schedule the in-memory timer.
 * Extracted so processStep can retry this critical section on failure.
 */
async function scheduleAndPersistNextStep(userId, step, cycle, now, prevTotalSent, prevStartedAt, prevTriggeredBy) {
  const nextStep = step + 1;

  if (nextStep < MAX_STEPS) {
    // Schedule next step in this cycle
    const delay = SCHEDULE_DELAYS[nextStep];

    await saveRecoveryState(userId, {
      status: 'recovering',
      currentStep: step,
      currentCycle: cycle,
      totalFcmsSent: prevTotalSent + 1,
      startedAt: prevStartedAt,
      lastFcmSentAt: now,
      nextFireAt: now + delay,
      triggeredBy: prevTriggeredBy
    });

    scheduleNextStep(userId, nextStep, cycle, delay);
    console.log(`⏰ [Recovery] Next step ${nextStep + 1} scheduled in ${delay / 1000}s for ${userId}`);

  } else {
    // All steps in this cycle done — restart cycle
    const newCycle = cycle + 1;

    if (newCycle >= MAX_CYCLES) {
      console.log(`⚠️ [Recovery] Max cycles (${MAX_CYCLES}) reached for ${userId}. Stopping.`);
      await saveRecoveryState(userId, {
        status: 'completed',
        currentStep: step,
        currentCycle: cycle,
        totalFcmsSent: prevTotalSent + 1,
        startedAt: prevStartedAt,
        lastFcmSentAt: now,
        nextFireAt: null,
        triggeredBy: prevTriggeredBy
      });
      return;
    }

    console.log(`🔄 [Recovery] Cycle ${cycle + 1} complete for ${userId}. Restarting as cycle ${newCycle + 1}.`);

    const restartDelay = 5000; // 5 seconds before restarting cycle

    await saveRecoveryState(userId, {
      status: 'recovering',
      currentStep: 0,
      currentCycle: newCycle,
      totalFcmsSent: prevTotalSent + 1,
      startedAt: prevStartedAt,
      lastFcmSentAt: now,
      nextFireAt: now + restartDelay,
      triggeredBy: prevTriggeredBy
    });

    scheduleNextStep(userId, 0, newCycle, restartDelay);
  }
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// PUBLIC API — SAME SIGNATURES AS BEFORE (drop-in replacement)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Start recovery for a user. Sends first FCM immediately, schedules escalating follow-ups.
 */
const startRecovery = async (userId, triggeredBy = 'presence_monitor') => {
  // Fast-path: already have an active timer for this user
  if (activeTimers.has(userId)) {
    console.log(`⚠️ [Recovery] Timer already active for ${userId}, checking Firestore...`);
  }

  // Check Firestore for active recovery
  const db = getFirestore();
  try {
    const existingDoc = await db.collection(RECOVERY_COLLECTION).doc(userId).get();
    if (existingDoc.exists) {
      const existingData = existingDoc.data();
      if (existingData.status === 'recovering') {
        console.log(`⚠️ [Recovery] Already active for ${userId} (step ${existingData.currentStep + 1}, cycle ${existingData.currentCycle + 1})`);
        return {
          success: true,
          alreadyActive: true,
          message: 'Recovery already active for this device',
          state: {
            currentStep: existingData.currentStep,
            currentCycle: existingData.currentCycle,
            totalFcmsSent: existingData.totalFcmsSent,
            startedAt: existingData.startedAt
          }
        };
      }
    }
  } catch (e) {
    // Continue
  }

  const now = Date.now();
  const stateData = {
    status: 'recovering',
    currentStep: 0,
    currentCycle: 0,
    totalFcmsSent: 0,
    startedAt: now,
    lastFcmSentAt: null,
    nextFireAt: now,
    triggeredBy
  };

  await saveRecoveryState(userId, stateData);

  console.log(`🚀 [Recovery] Started for ${userId} by ${triggeredBy}`);

  // Schedule step 0 immediately (delay = 0)
  scheduleNextStep(userId, 0, 0, 0);

  return {
    success: true,
    alreadyActive: false,
    message: 'Recovery started — FCM queued for immediate delivery, follow-ups scheduled automatically',
    state: {
      currentStep: 0,
      currentCycle: 0,
      totalFcmsSent: 0,
      startedAt: now,
      schedule: getScheduleDescription()
    }
  };
};

/**
 * Cancel recovery for a user. Clears the timer and updates Firestore.
 */
const cancelRecovery = async (userId) => {
  const db = getFirestore();
  let wasActive = false;
  let totalFcmsSent = 0;

  try {
    const doc = await db.collection(RECOVERY_COLLECTION).doc(userId).get();
    if (doc.exists && doc.data().status === 'recovering') {
      wasActive = true;
      totalFcmsSent = doc.data().totalFcmsSent || 0;
    }
  } catch (e) {
    // Continue
  }

  // Clear the in-memory timer
  const existing = activeTimers.get(userId);
  if (existing) {
    clearTimeout(existing.timerId);
    activeTimers.delete(userId);
    console.log(`🗑️ [Recovery] Cleared active timer for ${userId}`);
  }

  await clearRecoveryState(userId);

  if (wasActive) {
    console.log(`✅ [Recovery] Cancelled for ${userId} (sent ${totalFcmsSent} FCMs total)`);

    try {
      await db.collection(DEVICE_NOTIFICATIONS_COLLECTION).add({
        userId,
        type: 'FCM_RECOVERY_CANCELLED',
        message: `✅ Recovery cancelled — device is back online. Sent ${totalFcmsSent} FCMs during recovery.`,
        timestamp: Date.now(),
        totalFcmsSent,
        isRecovery: true,
        read: false,
        createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });
    } catch (error) {
      console.error(`❌ [Recovery] Error logging cancellation:`, error);
    }
  }

  return {
    success: true,
    wasActive,
    message: wasActive
      ? `Recovery cancelled. ${totalFcmsSent} FCMs were sent during recovery.`
      : 'No active recovery to cancel for this device.',
    totalFcmsSent
  };
};

/**
 * Get recovery status for a user.
 */
const getRecoveryStatus = async (userId) => {
  const db = getFirestore();
  try {
    const doc = await db.collection(RECOVERY_COLLECTION).doc(userId).get();
    if (doc.exists) {
      const data = doc.data();
      return {
        active: data.status === 'recovering',
        source: 'firestore',
        ...data,
        schedule: getScheduleDescription()
      };
    }
  } catch (error) {
    console.error(`❌ [Recovery] Error getting status:`, error);
  }

  return { active: false, message: 'No recovery state found' };
};

/**
 * Restore pending recovery timers from Firestore on server restart.
 * Reads all 'recovering' docs and re-creates setTimeout timers.
 */
const restoreFromFirestore = async () => {
  console.log('🔄 [Recovery] Checking for pending recovery jobs in Firestore...');

  const db = getFirestore();
  try {
    const snapshot = await db.collection(RECOVERY_COLLECTION)
      .where('status', '==', 'recovering')
      .get();

    if (snapshot.empty) {
      console.log('✅ [Recovery] No pending recovery jobs in Firestore.');
      return;
    }

    let restored = 0;
    const now = Date.now();

    for (const doc of snapshot.docs) {
      const data = doc.data();
      const userId = data.userId;

      if (!userId) continue;

      // Don't double-schedule if timer already exists
      if (activeTimers.has(userId)) {
        console.log(`  ⏩ Timer already active for ${userId}, skipping`);
        continue;
      }

      const nextFireAt = data.nextFireAt || now;
      const currentStep = data.currentStep || 0;
      const currentCycle = data.currentCycle || 0;

      // Calculate delay: how long until this step should fire
      const delay = Math.max(0, nextFireAt - now);

      // If the fire time has passed, advance to the next step and fire immediately
      const resumeStep = delay === 0 ? Math.min(currentStep + 1, MAX_STEPS - 1) : currentStep;
      const resumeCycle = resumeStep >= MAX_STEPS ? currentCycle + 1 : currentCycle;
      const actualStep = resumeStep >= MAX_STEPS ? 0 : resumeStep;
      const actualCycle = resumeStep >= MAX_STEPS ? resumeCycle : currentCycle;

      scheduleNextStep(userId, actualStep, actualCycle, delay);

      restored++;
      console.log(`  📡 Restored recovery for ${userId}: step ${actualStep + 1}, cycle ${actualCycle + 1}, fires in ${Math.round(delay / 1000)}s`);
    }

    if (restored > 0) {
      console.log(`✅ [Recovery] Restored ${restored} pending recovery jobs from Firestore.`);
    }
  } catch (error) {
    console.error('❌ [Recovery] Error restoring from Firestore:', error);
  }
};

/**
 * Get human-readable schedule description.
 */
const getScheduleDescription = () => {
  return {
    totalSteps: MAX_STEPS,
    steps: [
      { step: 1, delay: 'Immediate', description: 'First FCM sent instantly' },
      { step: 2, delay: '+1 minute', description: 'Second FCM after 1 minute' },
      { step: 3, delay: '+3 minutes', description: 'Third FCM after 3 more minutes' },
      { step: '4-15', delay: 'Every 30 minutes', description: '12 more FCMs, every 30 minutes' },
    ],
    totalDuration: '~6 hours per cycle',
    cycleRestart: 'After all 15 steps, cycle restarts from step 1',
    engine: 'Firestore + in-memory setTimeout (zero external dependencies)'
  };
};

/**
 * Get all active recoveries from Firestore.
 */
const getActiveRecoveries = async () => {
  const db = getFirestore();
  try {
    const snapshot = await db.collection(RECOVERY_COLLECTION)
      .where('status', '==', 'recovering')
      .get();

    const result = [];
    snapshot.forEach(doc => {
      result.push({ userId: doc.id, ...doc.data() });
    });
    return result;
  } catch (error) {
    console.error('❌ [Recovery] Error getting active recoveries:', error);
    return [];
  }
};

/**
 * Shutdown — clear all in-memory timers.
 */
const shutdown = async () => {
  console.log('🛑 [Recovery] Shutting down...');
  let cleared = 0;
  for (const [userId, entry] of activeTimers) {
    clearTimeout(entry.timerId);
    cleared++;
  }
  activeTimers.clear();
  console.log(`✅ [Recovery] Shutdown complete. Cleared ${cleared} active timers.`);
};

module.exports = {
  startRecovery,
  cancelRecovery,
  getRecoveryStatus,
  restoreFromFirestore,
  getActiveRecoveries,
  getScheduleDescription,
  shutdown
};
