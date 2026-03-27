/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * PRESENCE MONITOR SERVICE — REAL-TIME ONLINE/OFFLINE DETECTION VIA FIREBASE REALTIME DATABASE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * Monitors every user's online/offline status using Firebase Realtime Database presence.
 * When a device goes offline for 7+ minutes, triggers the recovery scheduler.
 * When a device comes back online, instantly cancels any active recovery.
 *
 * HOW IT WORKS:
 * 1. On startup, fetches all user UIDs from Firestore 'users' collection.
 * 2. For each user, attaches a Realtime Database listener on 'presence/{userId}'.
 * 3. The Android app writes { online: true, lastSeen: serverTimestamp } when connected
 *    and registers onDisconnect() which Firebase's server writes { online: false, lastSeen: ... }
 *    the instant the socket drops.
 * 4. When this listener detects online → false:
 *    - Start a 7-minute timer.
 *    - If online flips back to true within 7 minutes → cancel timer, no recovery.
 *    - If 7 minutes pass and still false → call startRecovery(userId, 'presence_monitor').
 * 5. When this listener detects online → true:
 *    - Instantly call cancelRecovery(userId).
 *    - Cancel any pending 7-minute timer.
 * 6. Also listens for new users added to Firestore so they get monitored automatically.
 *
 * WHY THIS IS RELIABLE:
 * - Firebase Realtime Database onDisconnect() is a SERVER-SIDE feature.
 *   It fires even if the app is force-killed, phone dies, or network drops.
 * - No polling, no cron jobs, no heartbeats. Pure event-driven.
 * - Backend is the single source of truth — no dependency on admin page.
 * - 7-minute grace period prevents recovery for brief disconnections.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { startRecovery, cancelRecovery } = require('./recoveryScheduler');

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const GRACE_PERIOD_MS = 7 * 60 * 1000; // 7 minutes before triggering recovery
const TAG = 'PresenceMonitor';

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// STATE
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

// In-memory timers: userId → { timer, wentOfflineAt }
const pendingTimers = new Map();

// Track the last known online state per user to detect transitions
const lastKnownState = new Map();

// Firebase listeners: userId → listener reference (for cleanup)
const activeListeners = new Map();

// Firestore listener for new users
let usersSnapshotUnsubscribe = null;

// Whether the monitor is running
let isRunning = false;

// Safety net interval for periodic health checks
let healthCheckInterval = null;
const HEALTH_CHECK_INTERVAL_MS = 10 * 60 * 1000; // Check every 10 minutes

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// CORE LOGIC
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Handle a presence change for a single user.
 * Called whenever the Realtime Database listener fires for presence/{userId}.
 */
function handlePresenceChange(userId, snapshot) {
  const data = snapshot.val();

  // If no presence data exists yet, treat as offline (user never connected)
  const isOnline = data ? data.online === true : false;
  const lastSeen = data ? data.lastSeen : null;

  const previousState = lastKnownState.get(userId);
  lastKnownState.set(userId, isOnline);

  // ─── TRANSITION: was online (or unknown) → now offline ───
  if (!isOnline && previousState !== false) {
    console.log(`🔴 [${TAG}] User ${userId} went OFFLINE (lastSeen: ${lastSeen ? new Date(lastSeen).toISOString() : 'unknown'})`);

    // Don't start a duplicate timer
    if (pendingTimers.has(userId)) {
      console.log(`  ⏳ [${TAG}] Timer already pending for ${userId}, skipping`);
      return;
    }

    // Start 7-minute grace period timer
    const timer = setTimeout(() => {
      // After 7 minutes, check if user is STILL offline
      pendingTimers.delete(userId);
      const currentState = lastKnownState.get(userId);

      if (currentState === false) {
        console.log(`🚨 [${TAG}] User ${userId} still OFFLINE after 7 minutes — triggering recovery`);
        startRecovery(userId, 'presence_monitor')
          .then(result => {
            if (result.alreadyActive) {
              console.log(`  ℹ️ [${TAG}] Recovery already active for ${userId}`);
            } else {
              console.log(`  ✅ [${TAG}] Recovery started for ${userId}: ${result.message}`);
            }
          })
          .catch(error => {
            console.error(`  ❌ [${TAG}] Failed to start recovery for ${userId}:`, error.message);
          });
      } else {
        console.log(`  ✅ [${TAG}] User ${userId} came back online before 7 minutes — no recovery needed`);
      }
    }, GRACE_PERIOD_MS);

    pendingTimers.set(userId, {
      timer,
      wentOfflineAt: Date.now()
    });

    console.log(`  ⏳ [${TAG}] 7-minute timer started for ${userId}`);
  }

  // ─── TRANSITION: was offline (or unknown) → now online ───
  if (isOnline && previousState !== true) {
    console.log(`🟢 [${TAG}] User ${userId} came ONLINE`);

    // Cancel any pending 7-minute timer
    const pending = pendingTimers.get(userId);
    if (pending) {
      clearTimeout(pending.timer);
      pendingTimers.delete(userId);
      const waitedMs = Date.now() - pending.wentOfflineAt;
      console.log(`  ✅ [${TAG}] Cancelled pending timer for ${userId} (was offline for ${Math.round(waitedMs / 1000)}s)`);
    }

    // Cancel any active recovery
    cancelRecovery(userId)
      .then(result => {
        if (result.wasActive) {
          console.log(`  ✅ [${TAG}] Recovery cancelled for ${userId}: ${result.message}`);
        }
      })
      .catch(error => {
        console.error(`  ❌ [${TAG}] Failed to cancel recovery for ${userId}:`, error.message);
      });
  }
}

/**
 * Start monitoring a single user's presence in Realtime Database.
 */
function monitorUser(userId) {
  // Don't double-attach listeners
  if (activeListeners.has(userId)) {
    return;
  }

  const admin = getFirebaseAdmin();
  const db = admin.database();
  const presenceRef = db.ref(`presence/${userId}`);

  const listener = presenceRef.on('value', (snapshot) => {
    handlePresenceChange(userId, snapshot);
  }, (error) => {
    console.error(`❌ [${TAG}] Listener error for ${userId}:`, error.message);
  });

  activeListeners.set(userId, { ref: presenceRef, listener });
}

/**
 * Stop monitoring a single user's presence.
 */
function unmonitorUser(userId) {
  const entry = activeListeners.get(userId);
  if (entry) {
    entry.ref.off('value', entry.listener);
    activeListeners.delete(userId);
  }

  // Clean up any pending timer
  const pending = pendingTimers.get(userId);
  if (pending) {
    clearTimeout(pending.timer);
    pendingTimers.delete(userId);
  }

  lastKnownState.delete(userId);
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// STARTUP & SHUTDOWN
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Start the presence monitor.
 * Fetches all users from Firestore and sets up Realtime DB listeners for each.
 * Also listens for new users being added.
 */
async function startPresenceMonitoring() {
  if (isRunning) {
    console.log(`⚠️ [${TAG}] Already running, skipping duplicate start`);
    return;
  }

  console.log(`🚀 [${TAG}] Starting presence monitoring...`);

  const admin = getFirebaseAdmin();
  const firestore = admin.firestore();

  // 1. Fetch all existing users and start monitoring each one
  try {
    const usersSnapshot = await firestore.collection('users').get();
    let count = 0;

    usersSnapshot.forEach(doc => {
      const userId = doc.id;
      if (userId) {
        monitorUser(userId);
        count++;
      }
    });

    console.log(`✅ [${TAG}] Monitoring ${count} users from Firestore`);
  } catch (error) {
    console.error(`❌ [${TAG}] Failed to fetch users:`, error.message);
    // Continue anyway — the Firestore listener below will pick up users
  }

  // 2. Listen for NEW users added to Firestore (so they get monitored automatically)
  try {
    usersSnapshotUnsubscribe = firestore.collection('users').onSnapshot(
      (snapshot) => {
        snapshot.docChanges().forEach(change => {
          const userId = change.doc.id;
          if (change.type === 'added' && !activeListeners.has(userId)) {
            console.log(`👤 [${TAG}] New user detected: ${userId} — starting monitor`);
            monitorUser(userId);
          } else if (change.type === 'removed') {
            console.log(`👤 [${TAG}] User removed: ${userId} — stopping monitor`);
            unmonitorUser(userId);
          }
        });
      },
      (error) => {
        console.error(`❌ [${TAG}] Firestore users listener error:`, error.message);
      }
    );
    console.log(`👁️ [${TAG}] Listening for new user registrations`);
  } catch (error) {
    console.error(`❌ [${TAG}] Failed to set up users listener:`, error.message);
  }

  isRunning = true;

  // 3. Start periodic health check — safety net to re-attach any silently dropped listeners
  healthCheckInterval = setInterval(async () => {
    try {
      const admin = getFirebaseAdmin();
      const firestore = admin.firestore();
      const usersSnapshot = await firestore.collection('users').get();
      let reattached = 0;

      usersSnapshot.forEach(doc => {
        const userId = doc.id;
        if (userId && !activeListeners.has(userId)) {
          monitorUser(userId);
          reattached++;
        }
      });

      if (reattached > 0) {
        console.log(`🔧 [${TAG}] Health check: re-attached ${reattached} listeners`);
      }
    } catch (error) {
      console.error(`⚠️ [${TAG}] Health check error:`, error.message);
    }
  }, HEALTH_CHECK_INTERVAL_MS);

  console.log(`═══════════════════════════════════════════════════════`);
  console.log(`✅ [${TAG}] Presence monitoring ACTIVE`);
  console.log(`   Grace period: ${GRACE_PERIOD_MS / 1000}s (${GRACE_PERIOD_MS / 60000} minutes)`);
  console.log(`   Users monitored: ${activeListeners.size}`);
  console.log(`   Health check: every ${HEALTH_CHECK_INTERVAL_MS / 60000} minutes`);
  console.log(`═══════════════════════════════════════════════════════`);
}

/**
 * Stop the presence monitor and clean up all listeners and timers.
 */
function shutdownPresenceMonitoring() {
  console.log(`🛑 [${TAG}] Shutting down...`);

  // Stop health check interval
  if (healthCheckInterval) {
    clearInterval(healthCheckInterval);
    healthCheckInterval = null;
  }

  // Stop Firestore users listener
  if (usersSnapshotUnsubscribe) {
    usersSnapshotUnsubscribe();
    usersSnapshotUnsubscribe = null;
  }

  // Stop all Realtime DB listeners
  for (const [userId] of activeListeners) {
    unmonitorUser(userId);
  }

  // Clear all pending timers (already cleared by unmonitorUser, but be safe)
  for (const [userId, pending] of pendingTimers) {
    clearTimeout(pending.timer);
  }
  pendingTimers.clear();
  lastKnownState.clear();
  activeListeners.clear();

  isRunning = false;
  console.log(`✅ [${TAG}] Shutdown complete`);
}

/**
 * Get current monitoring stats (for health checks / debugging).
 */
function getMonitoringStats() {
  const stats = {
    isRunning,
    totalUsersMonitored: activeListeners.size,
    pendingTimers: pendingTimers.size,
    pendingDetails: []
  };

  for (const [userId, pending] of pendingTimers) {
    const elapsedMs = Date.now() - pending.wentOfflineAt;
    const remainingMs = Math.max(0, GRACE_PERIOD_MS - elapsedMs);
    stats.pendingDetails.push({
      userId,
      wentOfflineAt: new Date(pending.wentOfflineAt).toISOString(),
      elapsedSeconds: Math.round(elapsedMs / 1000),
      remainingSeconds: Math.round(remainingMs / 1000)
    });
  }

  return stats;
}

module.exports = {
  startPresenceMonitoring,
  shutdownPresenceMonitoring,
  getMonitoringStats
};

