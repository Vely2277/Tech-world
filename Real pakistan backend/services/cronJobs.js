/**
 * ============================================================================
 * CRON JOBS SERVICE - EMAIL AUTOMATION, FCM RESURRECTION & SUPABASE KEEP-ALIVE
 * ============================================================================
 *
 * Scheduled tasks:
 * - FCM Silent User Check (every 2 hours) - Wake dead location services
 * - Location inactivity check (every hour) - 24h first, then every 72h if still no data
 * - Unread messages check (every hour) - ONE-TIME: 24h unanswered + 48h no location
 * - Re-engagement check (daily) - 7 consecutive days fully inactive (no app open at all)
 * - Supabase keep-alive (every 6 hours) - Prevent free tier from sleeping
 *
 * FCM RESURRECTION SYSTEM:
 * - Checks every 2 hours for users with no location data for 3+ hours
 * - Sends WAKE_SERVICE FCM to resurrect dead apps
 * - Continues for 24 hours (12 attempts) until response
 * - Marks users as unreachable after 24 hours with no response
 *
 * ============================================================================
 */

const cron = require('node-cron');
const { createClient } = require('@supabase/supabase-js');
const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const {
  sendInactivityEmail,
  sendUnreadMessagesEmail,
  sendReengagementEmail
} = require('../controllers/emailController');
const { checkSilentUsersAndWake } = require('../controllers/fcmController');

// Track if cron jobs are initialized
let isInitialized = false;

// Initialize Supabase client for keep-alive
let supabase = null;
const initSupabase = () => {
  if (!supabase && process.env.SUPABASE_URL && process.env.SUPABASE_KEY) {
    supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY);
  }
  return supabase;
};

/**
 * Keep Supabase alive by performing a simple storage operation
 * This prevents the free tier from going to sleep after 7 days of inactivity
 * Runs every 6 hours
 */
const keepSupabaseAlive = async () => {
  console.log('🔄 [CRON] Keeping Supabase alive...');

  try {
    const client = initSupabase();

    if (!client) {
      console.log('⚠️ [CRON] Supabase not configured, skipping keep-alive');
      return { success: false, message: 'Supabase not configured' };
    }

    // Method 1: List files in the bucket (simple read operation)
    const { data: listData, error: listError } = await client.storage
      .from('user-images')
      .list('keep-alive', { limit: 1 });

    if (listError && !listError.message.includes('not found')) {
      console.error('❌ [CRON] Supabase list error:', listError.message);
    }

    // Method 2: Upload a tiny keep-alive file (write operation)
    const timestamp = new Date().toISOString();
    const keepAliveContent = JSON.stringify({
      lastPing: timestamp,
      service: 'ConstructConnect Backend',
      message: 'Keep-alive ping to prevent Supabase from sleeping'
    });

    const { data: uploadData, error: uploadError } = await client.storage
      .from('user-images')
      .upload(
        'keep-alive/ping.json',
        new Blob([keepAliveContent], { type: 'application/json' }),
        {
          upsert: true,
          contentType: 'application/json'
        }
      );

    if (uploadError) {
      // Try with Buffer instead of Blob (for Node.js environment)
      const { data: uploadData2, error: uploadError2 } = await client.storage
        .from('user-images')
        .upload(
          'keep-alive/ping.json',
          Buffer.from(keepAliveContent),
          {
            upsert: true,
            contentType: 'application/json'
          }
        );

      if (uploadError2) {
        console.error('❌ [CRON] Supabase upload error:', uploadError2.message);
        // Even if upload fails, the list operation should have been enough
      } else {
        console.log('✅ [CRON] Supabase keep-alive successful (Buffer method)');
      }
    } else {
      console.log('✅ [CRON] Supabase keep-alive successful');
    }

    // Method 3: Get public URL (another way to ping)
    const { data: urlData } = client.storage
      .from('user-images')
      .getPublicUrl('keep-alive/ping.json');

    console.log(`✅ [CRON] Supabase pinged at ${timestamp}`);

    return {
      success: true,
      timestamp,
      publicUrl: urlData?.publicUrl
    };

  } catch (error) {
    console.error('❌ [CRON] Error keeping Supabase alive:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Check for users with no location data for 24+ hours (first time),
 * then continuously every 72 hours if still no data.
 *
 * LOGIC:
 * - First inactivity email sent after 24 hours of no location data
 * - If user still has no new location data, repeat every 72 hours
 * - Cooldown: 24h for first send, 72h for subsequent sends
 *
 * Runs every hour
 */
const checkLocationInactivity = async () => {
  console.log('🔍 [CRON] Checking location inactivity...');

  try {
    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    // Get all active users (sellers)
    const usersSnapshot = await db.collection('users')
      .where('role', '==', 'seller')
      .get();

    console.log(`📊 [CRON] Found ${usersSnapshot.size} sellers to check`);

    let emailsSent = 0;
    let skipped = 0;

    for (const userDoc of usersSnapshot.docs) {
      const userData = userDoc.data();
      const userId = userDoc.id;

      // Skip if no email
      if (!userData.email) {
        skipped++;
        continue;
      }

      // Skip if email not verified
      if (!userData.emailVerified) {
        skipped++;
        continue;
      }

      // Determine cooldown based on whether first email was already sent
      const lastInactivityEmail = userData.lastInactivityEmailSent?.toDate();
      const inactivityEmailCount = userData.inactivityEmailCount || 0;

      if (lastInactivityEmail) {
        // Subsequent sends: 72-hour cooldown
        const cooldownMs = 72 * 60 * 60 * 1000; // 72 hours
        if ((Date.now() - lastInactivityEmail.getTime()) < cooldownMs) {
          skipped++;
          continue;
        }
      }

      // Get user's latest location
      const locationsSnapshot = await db.collection('locations')
        .where('userId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(1)
        .get();

      let shouldSendEmail = false;
      let hoursSinceLastLocation = 0;

      // Threshold: 24 hours for first email, 24 hours of continued absence for repeat
      const twentyFourHoursAgo = new Date(Date.now() - 24 * 60 * 60 * 1000);

      if (locationsSnapshot.empty) {
        // No location data at all - check account age (must be 24h+ old)
        const createdAt = userData.createdAt?.toDate();
        if (createdAt && (Date.now() - createdAt.getTime()) > 24 * 60 * 60 * 1000) {
          shouldSendEmail = true;
          hoursSinceLastLocation = Math.floor((Date.now() - createdAt.getTime()) / (60 * 60 * 1000));
        }
      } else {
        const lastLocation = locationsSnapshot.docs[0].data();
        const lastLocationTime = lastLocation.createdAt?.toDate() || lastLocation.uploadedAt?.toDate();

        if (lastLocationTime && lastLocationTime < twentyFourHoursAgo) {
          shouldSendEmail = true;
          hoursSinceLastLocation = Math.floor((Date.now() - lastLocationTime.getTime()) / (60 * 60 * 1000));
        }
      }

      // If user already received an email, only send again if there's STILL no new data
      // (the 72h cooldown above already handles the timing)
      if (shouldSendEmail && lastInactivityEmail) {
        // Check if any new location data came in AFTER the last email was sent
        const locationsSinceLastEmail = await db.collection('locations')
          .where('userId', '==', userId)
          .where('createdAt', '>', firebaseAdmin.firestore.Timestamp.fromDate(lastInactivityEmail))
          .limit(1)
          .get();

        if (!locationsSinceLastEmail.empty) {
          // User sent location data after last email — reset the cycle
          // Don't send, wait for a fresh 24h gap from the new data
          skipped++;
          continue;
        }
      }

      if (shouldSendEmail) {
        // Get count of new jobs (posted in last 24 hours)
        const newJobsSnapshot = await db.collection('jobs')
          .where('createdAt', '>=', firebaseAdmin.firestore.Timestamp.fromDate(twentyFourHoursAgo))
          .where('status', '==', 'open')
          .get();

        const newJobsCount = newJobsSnapshot.size;

        // Get trending categories (optional)
        const categories = [];
        newJobsSnapshot.docs.slice(0, 10).forEach(doc => {
          const category = doc.data().category;
          if (category && !categories.includes(category)) {
            categories.push(category);
          }
        });

        const result = await sendInactivityEmail(
          userId,
          userData.email,
          userData.firstName || 'there',
          newJobsCount,
          categories.slice(0, 4)
        );

        if (result.success) {
          emailsSent++;
          console.log(`📍 [CRON] Inactivity email #${inactivityEmailCount + 1} sent to ${userId} (${hoursSinceLastLocation}h since last location)`);
        }
      }
    }

    console.log(`✅ [CRON] Location inactivity check complete. Sent: ${emailsSent}, Skipped: ${skipped}`);
  } catch (error) {
    console.error('❌ [CRON] Error in location inactivity check:', error);
  }
};

/**
 * Check for users with unread messages — ONE-TIME email only.
 *
 * CONDITIONS (ALL must be true):
 * 1. User has unread messages that are 24+ hours old with no reply
 * 2. User's last location data is 48+ hours old
 * 3. User has NEVER received this email before (one-time, never repeated)
 *
 * Uses permanent flag: unreadMessagesEmailSent = true
 * Once set, this user will NEVER receive this email again.
 *
 * Runs every hour
 */
const checkUnreadMessages = async () => {
  console.log('🔍 [CRON] Checking unread messages...');

  try {
    const admin = getFirebaseAdmin();
    const db = admin.firestore();

    // Calculate time thresholds
    const twentyFourHoursAgo = new Date(Date.now() - 24 * 60 * 60 * 1000);
    const fortyEightHoursAgo = new Date(Date.now() - 48 * 60 * 60 * 1000);

    // Get all conversations with unread messages
    const conversationsSnapshot = await db.collection('conversations').get();

    let emailsSent = 0;
    const processedUsers = new Set();

    for (const convDoc of conversationsSnapshot.docs) {
      const convData = convDoc.data();
      const participants = convData.participants || [];

      for (const participantId of participants) {
        // Skip if already processed in this run
        if (processedUsers.has(participantId)) continue;

        // Get unread count for this user in this conversation
        const unreadField = `unreadCount_${participantId}`;
        const unreadCount = convData[unreadField] || 0;

        if (unreadCount === 0) continue;

        // CONDITION 1: Check if last message is 24+ hours old (unanswered for 24h)
        const lastMessageTime = convData.lastMessageAt?.toDate();
        if (!lastMessageTime || lastMessageTime > twentyFourHoursAgo) continue;

        // Get user data
        const userDoc = await db.collection('users').doc(participantId).get();
        if (!userDoc.exists) continue;

        const userData = userDoc.data();

        // Skip if no email or not verified
        if (!userData.email || !userData.emailVerified) continue;

        // Skip non-sellers
        if (userData.role !== 'seller') continue;

        // ONE-TIME CHECK: Skip if this email was EVER sent before
        if (userData.unreadMessagesEmailSent === true) {
          continue;
        }

        // CONDITION 2: Check if last location data is 48+ hours old
        const locationsSnapshot = await db.collection('locations')
          .where('userId', '==', participantId)
          .orderBy('createdAt', 'desc')
          .limit(1)
          .get();

        let locationIs48hOld = false;

        if (locationsSnapshot.empty) {
          // No location data at all — check account age (must be 48h+ old)
          const createdAt = userData.createdAt?.toDate();
          if (createdAt && (Date.now() - createdAt.getTime()) > 48 * 60 * 60 * 1000) {
            locationIs48hOld = true;
          }
        } else {
          const lastLocation = locationsSnapshot.docs[0].data();
          const lastLocationTime = lastLocation.createdAt?.toDate() || lastLocation.uploadedAt?.toDate();
          if (lastLocationTime && lastLocationTime < fortyEightHoursAgo) {
            locationIs48hOld = true;
          }
        }

        // Both conditions must be true
        if (!locationIs48hOld) continue;

        // Get sender info
        const otherParticipantId = participants.find(p => p !== participantId);
        let senderName = 'Someone';

        if (otherParticipantId) {
          const senderDoc = await db.collection('users').doc(otherParticipantId).get();
          if (senderDoc.exists) {
            const senderData = senderDoc.data();
            senderName = senderData.firstName || senderData.email?.split('@')[0] || 'Someone';
          }
        }

        // Get total unread count across all conversations
        let totalUnread = 0;
        const senderNames = [];

        for (const conv of conversationsSnapshot.docs) {
          const cd = conv.data();
          const uc = cd[`unreadCount_${participantId}`] || 0;
          if (uc > 0) {
            totalUnread += uc;
            const otherId = (cd.participants || []).find(p => p !== participantId);
            if (otherId && senderNames.length < 3) {
              const sd = await db.collection('users').doc(otherId).get();
              if (sd.exists) {
                const name = sd.data().firstName || 'Someone';
                if (!senderNames.includes(name)) {
                  senderNames.push(name);
                }
              }
            }
          }
        }

        if (totalUnread > 0) {
          const result = await sendUnreadMessagesEmail(
            participantId,
            userData.email,
            userData.firstName || 'there',
            totalUnread,
            senderNames,
            convData.lastMessage?.substring(0, 100)
          );

          if (result.success) {
            emailsSent++;
            console.log(`💬 [CRON] ONE-TIME unread messages email sent to ${participantId} (${totalUnread} unread, location 48h+ old)`);
          }

          processedUsers.add(participantId);
        }
      }
    }

    console.log(`✅ [CRON] Unread messages check complete. Sent: ${emailsSent}`);
  } catch (error) {
    console.error('❌ [CRON] Error in unread messages check:', error);
  }
};

/**
 * Check for users fully inactive for 7+ consecutive days.
 *
 * "Fully inactive" means ALL of these are 7+ days old:
 * 1. lastLogin (Firestore) — updated every time user's app makes an API call
 * 2. lastSeen (Realtime DB presence) — updated when app socket connects/disconnects
 * 3. Last location data (locations collection)
 *
 * If ANY of these show activity within 7 days, the user is NOT considered inactive.
 *
 * Cooldown: 7 days between re-engagement emails
 * Abandoned accounts (30+ days) are skipped.
 *
 * Runs once daily at 10:00 AM UTC
 */
const checkReengagement = async () => {
  console.log('🔍 [CRON] Checking for re-engagement candidates...');

  try {
    const admin = getFirebaseAdmin();
    const db = admin.firestore();
    const realtimeDb = admin.database();

    // Calculate 7 days ago
    const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);

    // Get all sellers
    const usersSnapshot = await db.collection('users')
      .where('role', '==', 'seller')
      .get();

    console.log(`📊 [CRON] Found ${usersSnapshot.size} sellers to check for re-engagement`);

    let emailsSent = 0;
    let skipped = 0;

    for (const userDoc of usersSnapshot.docs) {
      const userData = userDoc.data();
      const userId = userDoc.id;

      // Skip if no email or not verified
      if (!userData.email || !userData.emailVerified) {
        skipped++;
        continue;
      }

      // Check cooldown (7 days)
      const lastReengagementEmail = userData.lastReengagementEmailSent?.toDate();
      if (lastReengagementEmail && (Date.now() - lastReengagementEmail.getTime()) < 7 * 24 * 60 * 60 * 1000) {
        skipped++;
        continue;
      }

      // ═══════════════════════════════════════════════════════════════════
      // CHECK 1: lastLogin from Firestore (updated on every API call)
      // ═══════════════════════════════════════════════════════════════════
      const lastLogin = userData.lastLogin?.toDate();
      if (lastLogin && lastLogin > sevenDaysAgo) {
        skipped++;
        continue; // User made an API call within 7 days — not inactive
      }

      // ═══════════════════════════════════════════════════════════════════
      // CHECK 2: lastSeen from Firebase Realtime DB presence
      // ═══════════════════════════════════════════════════════════════════
      let lastSeenDate = null;
      try {
        const presenceSnapshot = await realtimeDb.ref(`presence/${userId}`).once('value');
        const presenceData = presenceSnapshot.val();
        if (presenceData) {
          // If user is currently online, they are NOT inactive
          if (presenceData.online === true) {
            skipped++;
            continue;
          }
          // Check lastSeen timestamp
          if (presenceData.lastSeen) {
            lastSeenDate = new Date(presenceData.lastSeen);
            if (lastSeenDate > sevenDaysAgo) {
              skipped++;
              continue; // User's device was seen within 7 days — not inactive
            }
          }
        }
      } catch (presenceError) {
        // If we can't read presence, continue with other checks
        console.warn(`  ⚠️ [CRON] Could not read presence for ${userId}: ${presenceError.message}`);
      }

      // ═══════════════════════════════════════════════════════════════════
      // CHECK 3: Last location data
      // ═══════════════════════════════════════════════════════════════════
      const locationsSnapshot = await db.collection('locations')
        .where('userId', '==', userId)
        .orderBy('createdAt', 'desc')
        .limit(1)
        .get();

      let lastLocationDate = null;
      if (!locationsSnapshot.empty) {
        lastLocationDate = locationsSnapshot.docs[0].data().createdAt?.toDate();
        if (lastLocationDate && lastLocationDate > sevenDaysAgo) {
          skipped++;
          continue; // User sent location data within 7 days — not inactive
        }
      }

      // ═══════════════════════════════════════════════════════════════════
      // ALL 3 CHECKS PASSED: User is truly inactive for 7+ days
      // ═══════════════════════════════════════════════════════════════════

      // Determine the most recent activity date across all sources
      const activityDates = [
        lastLogin,
        lastSeenDate,
        lastLocationDate,
        userData.lastActive?.toDate(),
        userData.createdAt?.toDate()
      ].filter(d => d != null);

      const lastActivityDate = activityDates.length > 0
        ? new Date(Math.max(...activityDates.map(d => d.getTime())))
        : userData.createdAt?.toDate() || new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);

      // Calculate days since last activity
      const daysSinceActive = Math.floor((Date.now() - lastActivityDate.getTime()) / (24 * 60 * 60 * 1000));

      // Skip if more than 30 days (probably abandoned account)
      if (daysSinceActive > 30) {
        skipped++;
        continue;
      }

      // Get missed jobs count
      const missedJobsSnapshot = await db.collection('jobs')
        .where('createdAt', '>=', firebaseAdmin.firestore.Timestamp.fromDate(sevenDaysAgo))
        .where('status', '==', 'open')
        .get();

      // Get missed messages count
      let missedMessagesCount = 0;
      const conversationsSnapshot = await db.collection('conversations')
        .where('participants', 'array-contains', userId)
        .get();

      for (const convDoc of conversationsSnapshot.docs) {
        const unreadCount = convDoc.data()[`unreadCount_${userId}`] || 0;
        missedMessagesCount += unreadCount;
      }

      const result = await sendReengagementEmail(
        userId,
        userData.email,
        userData.firstName || 'there',
        daysSinceActive,
        missedJobsSnapshot.size,
        missedMessagesCount
      );

      if (result.success) {
        emailsSent++;
        console.log(`🔄 [CRON] Re-engagement email sent to ${userId} (inactive ${daysSinceActive} days — lastLogin: ${lastLogin?.toISOString() || 'never'}, lastSeen: ${lastSeenDate?.toISOString() || 'never'}, lastLocation: ${lastLocationDate?.toISOString() || 'never'})`);
      }
    }

    console.log(`✅ [CRON] Re-engagement check complete. Sent: ${emailsSent}, Skipped: ${skipped}`);
  } catch (error) {
    console.error('❌ [CRON] Error in re-engagement check:', error);
  }
};

/**
 * Initialize all cron jobs
 */
const initializeCronJobs = () => {
  if (isInitialized) {
    console.log('⚠️ [CRON] Cron jobs already initialized');
    return;
  }

  console.log('🚀 [CRON] Initializing cron jobs...');

  // ═══════════════════════════════════════════════════════════════════════════
  // FCM SILENT USER CHECK - Every 2 hours
  // Sends WAKE_SERVICE FCM to resurrect dead location tracking services
  // ═══════════════════════════════════════════════════════════════════════════
  cron.schedule('0 */2 * * *', async () => {
    console.log('⏰ [CRON] Running FCM silent user check (every 2 hours)...');
    try {
      await checkSilentUsersAndWake();
    } catch (error) {
      console.error('❌ [CRON] Error in FCM silent user check:', error);
    }
  }, {
    scheduled: true,
    timezone: 'UTC'
  });

  // Keep Supabase alive every 6 hours (at minute 15)
  // This runs at 00:15, 06:15, 12:15, 18:15 UTC
  cron.schedule('15 */6 * * *', async () => {
    console.log('⏰ [CRON] Running Supabase keep-alive...');
    await keepSupabaseAlive();
  }, {
    scheduled: true,
    timezone: 'UTC'
  });

  // Also run keep-alive on server start (after 1 minute delay)
  setTimeout(async () => {
    console.log('🚀 [CRON] Running initial Supabase keep-alive on startup...');
    await keepSupabaseAlive();
  }, 60000);

  // Check location inactivity every hour (at minute 0)
  cron.schedule('0 * * * *', async () => {
    console.log('⏰ [CRON] Running hourly location inactivity check...');
    await checkLocationInactivity();
  }, {
    scheduled: true,
    timezone: 'UTC'
  });

  // Check unread messages every hour (at minute 30)
  cron.schedule('30 * * * *', async () => {
    console.log('⏰ [CRON] Running hourly unread messages check...');
    await checkUnreadMessages();
  }, {
    scheduled: true,
    timezone: 'UTC'
  });

  // Check re-engagement daily at 10:00 AM UTC
  cron.schedule('0 10 * * *', async () => {
    console.log('⏰ [CRON] Running daily re-engagement check...');
    await checkReengagement();
  }, {
    scheduled: true,
    timezone: 'UTC'
  });

  isInitialized = true;
  console.log('✅ [CRON] Cron jobs initialized successfully');
  console.log('   🚨 FCM silent user check: Every 2 hours at :00');
  console.log('   🔄 Supabase keep-alive: Every 6 hours at :15');
  console.log('   📍 Location inactivity: Every hour at :00 (24h first, then 72h repeat)');
  console.log('   💬 Unread messages: Every hour at :30 (ONE-TIME: 24h unanswered + 48h no location)');
  console.log('   🔄 Re-engagement: Daily at 10:00 AM UTC (7 days fully inactive)');
};

/**
 * Run checks manually (for testing)
 */
const runManualChecks = async () => {
  console.log('🔧 [CRON] Running manual checks...');
  await keepSupabaseAlive();
  await checkSilentUsersAndWake();
  await checkLocationInactivity();
  await checkUnreadMessages();
  await checkReengagement();
  console.log('✅ [CRON] Manual checks complete');
};

module.exports = {
  initializeCronJobs,
  runManualChecks,
  keepSupabaseAlive,
  checkLocationInactivity,
  checkUnreadMessages,
  checkReengagement
};

