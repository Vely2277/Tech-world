/**
 * ============================================================================
 * EMAIL CONTROLLER
 * ============================================================================
 *
 * Handles all email-related API endpoints and business logic.
 *
 * ============================================================================
 */

const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { sendEmail, getSenderEmail } = require('../services/emailService');
const { generateVerificationEmail, getVerificationSubject } = require('../templates/verificationEmail');
const { generateWelcomeEmail, getWelcomeSubject } = require('../templates/welcomeEmail');
const { generatePasswordResetEmail, getPasswordResetSubject } = require('../templates/passwordResetEmail');
const { generateUnreadMessagesEmail, getUnreadMessagesSubject } = require('../templates/unreadMessagesEmail');
const { generateInactivityEmail, getInactivitySubject } = require('../templates/inactivityEmail');
const { generateReengagementEmail, getReengagementSubject } = require('../templates/reengagementEmail');

/**
 * Generate a random 6-digit verification code
 */
const generateVerificationCode = () => {
  return Math.floor(100000 + Math.random() * 900000).toString();
};

/**
 * Send verification code email
 */
const sendVerificationCode = async (userId, email, firstName) => {
  try {
    const admin = getFirebaseAdmin();
    const code = generateVerificationCode();
    const expirationMinutes = 15;
    const expiresAt = new Date(Date.now() + expirationMinutes * 60 * 1000);

    // Store verification code in Firestore
    await admin.firestore().collection('verification_codes').doc(userId).set({
      code: code,
      email: email,
      userId: userId,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      expiresAt: firebaseAdmin.firestore.Timestamp.fromDate(expiresAt),
      used: false,
      attempts: 0
    });

    // Generate and send email
    const html = generateVerificationEmail(firstName, code, expirationMinutes);
    const subject = getVerificationSubject(code);

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'verification'
    });

    if (result.success) {
      console.log(`✅ Verification code sent to ${email}`);
      return { success: true, message: 'Verification code sent successfully' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending verification code:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Verify the code entered by user
 */
const verifyCode = async (userId, code) => {
  try {
    const admin = getFirebaseAdmin();
    const verificationDoc = await admin.firestore().collection('verification_codes').doc(userId).get();

    if (!verificationDoc.exists) {
      return { success: false, error: 'No verification code found. Please request a new code.' };
    }

    const verificationData = verificationDoc.data();

    // Check if already used
    if (verificationData.used) {
      return { success: false, error: 'This code has already been used. Please request a new code.' };
    }

    // Check expiration
    const now = new Date();
    const expiresAt = verificationData.expiresAt.toDate();
    if (now > expiresAt) {
      return { success: false, error: 'Verification code has expired. Please request a new code.' };
    }

    // Check attempts (max 5)
    if (verificationData.attempts >= 5) {
      return { success: false, error: 'Too many attempts. Please request a new code.' };
    }

    // Increment attempts
    await admin.firestore().collection('verification_codes').doc(userId).update({
      attempts: firebaseAdmin.firestore.FieldValue.increment(1)
    });

    // Verify code
    if (verificationData.code !== code) {
      return { success: false, error: 'Invalid verification code. Please try again.' };
    }

    // Mark as used and update user as verified
    await admin.firestore().collection('verification_codes').doc(userId).update({
      used: true,
      verifiedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    await admin.firestore().collection('users').doc(userId).update({
      emailVerified: true,
      emailVerifiedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Also update Firebase Auth
    await admin.auth().updateUser(userId, {
      emailVerified: true
    });

    return { success: true, message: 'Email verified successfully!' };
  } catch (error) {
    console.error('❌ Error verifying code:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Resend verification code
 */
const resendVerificationCode = async (userId) => {
  try {
    const admin = getFirebaseAdmin();
    const userDoc = await admin.firestore().collection('users').doc(userId).get();

    if (!userDoc.exists) {
      return { success: false, error: 'User not found' };
    }

    const userData = userDoc.data();

    // Check cooldown (1 minute between resends)
    const verificationDoc = await admin.firestore().collection('verification_codes').doc(userId).get();
    if (verificationDoc.exists) {
      const lastSent = verificationDoc.data().createdAt?.toDate();
      if (lastSent && (Date.now() - lastSent.getTime()) < 60000) {
        return { success: false, error: 'Please wait 1 minute before requesting a new code.' };
      }
    }

    return await sendVerificationCode(userId, userData.email, userData.firstName);
  } catch (error) {
    console.error('❌ Error resending verification code:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Send welcome email after verification
 */
const sendWelcomeEmail = async (userId, email, firstName, lastName) => {
  try {
    const html = generateWelcomeEmail(firstName, lastName);
    const subject = getWelcomeSubject(firstName);

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'welcome'
    });

    if (result.success) {
      // Log that welcome email was sent
      const admin = getFirebaseAdmin();
      await admin.firestore().collection('users').doc(userId).update({
        welcomeEmailSent: true,
        welcomeEmailSentAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });

      console.log(`✅ Welcome email sent to ${email}`);
      return { success: true, message: 'Welcome email sent successfully' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending welcome email:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Send password reset email
 */
const sendPasswordResetEmail = async (email, firstName, resetLink) => {
  try {
    const html = generatePasswordResetEmail(firstName, resetLink, 60);
    const subject = getPasswordResetSubject();

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'password'
    });

    if (result.success) {
      console.log(`✅ Password reset email sent to ${email}`);
      return { success: true, message: 'Password reset email sent successfully' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending password reset email:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Send unread messages notification email — ONE-TIME ONLY, never repeated.
 *
 * Once sent, sets permanent flag: unreadMessagesEmailSent = true
 * This email will NEVER be sent to the same user again.
 *
 * Conditions checked by cron job before calling this:
 * - Messages unanswered for 24+ hours
 * - Last location data is 48+ hours old
 */
const sendUnreadMessagesEmail = async (userId, email, firstName, unreadCount, senderNames, previewMessage) => {
  try {
    const admin = getFirebaseAdmin();

    // PERMANENT ONE-TIME CHECK: If this email was ever sent, never send again
    const userDoc = await admin.firestore().collection('users').doc(userId).get();
    if (userDoc.exists) {
      const userData = userDoc.data();
      if (userData.unreadMessagesEmailSent === true) {
        console.log(`⏭️ Skipping unread email for ${userId} - already sent once (permanent)`);
        return { success: false, error: 'Already sent (one-time)' };
      }
    }

    const html = generateUnreadMessagesEmail(firstName, unreadCount, senderNames, previewMessage);
    const subject = getUnreadMessagesSubject(unreadCount, senderNames[0]);

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'notification'
    });

    if (result.success) {
      // Set PERMANENT flag — this email will never be sent again
      await admin.firestore().collection('users').doc(userId).update({
        unreadMessagesEmailSent: true,
        unreadMessagesEmailSentAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });

      console.log(`✅ Unread messages email sent to ${email} (ONE-TIME — permanently flagged)`);
      return { success: true, message: 'Unread messages email sent successfully (one-time)' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending unread messages email:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Send inactivity email (24 hours no location data first time, then every 72 hours)
 *
 * Cooldown logic:
 * - First send: after 24 hours of no location data
 * - Subsequent sends: every 72 hours if still no location data
 * - Tracks inactivityEmailCount for logging
 */
const sendInactivityEmail = async (userId, email, firstName, newJobsCount = 0, jobCategories = []) => {
  try {
    const admin = getFirebaseAdmin();

    const userDoc = await admin.firestore().collection('users').doc(userId).get();
    if (userDoc.exists) {
      const userData = userDoc.data();
      const lastSent = userData.lastInactivityEmailSent?.toDate();
      const emailCount = userData.inactivityEmailCount || 0;

      if (lastSent) {
        // Subsequent sends: 72-hour cooldown
        const cooldownMs = 72 * 60 * 60 * 1000;
        if ((Date.now() - lastSent.getTime()) < cooldownMs) {
          console.log(`⏭️ Skipping inactivity email for ${userId} - 72h cooldown active (sent ${emailCount} times)`);
          return { success: false, error: 'Cooldown active' };
        }
      }
    }

    const html = generateInactivityEmail(firstName, newJobsCount, jobCategories);
    const subject = getInactivitySubject(newJobsCount);

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'engagement'
    });

    if (result.success) {
      // Update last sent timestamp and increment counter
      await admin.firestore().collection('users').doc(userId).update({
        lastInactivityEmailSent: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
        inactivityEmailCount: firebaseAdmin.firestore.FieldValue.increment(1)
      });

      console.log(`✅ Inactivity email sent to ${email}`);
      return { success: true, message: 'Inactivity email sent successfully' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending inactivity email:', error);
    return { success: false, error: error.message };
  }
};

/**
 * Send re-engagement email (7 consecutive days fully inactive)
 *
 * "Fully inactive" = no lastLogin, no presence/lastSeen, no location data
 * for 7 straight days. Checked by cron job before calling this.
 *
 * Cooldown: 7 days between sends
 * Skips abandoned accounts (30+ days)
 */
const sendReengagementEmail = async (userId, email, firstName, daysSinceActive, missedJobsCount, missedMessagesCount) => {
  try {
    const admin = getFirebaseAdmin();

    // Check cooldown (7 days)
    const userDoc = await admin.firestore().collection('users').doc(userId).get();
    if (userDoc.exists) {
      const lastSent = userDoc.data().lastReengagementEmailSent?.toDate();
      if (lastSent && (Date.now() - lastSent.getTime()) < 7 * 24 * 60 * 60 * 1000) {
        console.log(`⏭️ Skipping re-engagement email for ${userId} - cooldown active`);
        return { success: false, error: 'Cooldown active' };
      }
    }

    const html = generateReengagementEmail(firstName, daysSinceActive, missedJobsCount, missedMessagesCount);
    const subject = getReengagementSubject(firstName, daysSinceActive);

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'engagement'
    });

    if (result.success) {
      // Update last sent timestamp
      await admin.firestore().collection('users').doc(userId).update({
        lastReengagementEmailSent: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });

      console.log(`✅ Re-engagement email sent to ${email}`);
      return { success: true, message: 'Re-engagement email sent successfully' };
    } else {
      throw new Error(result.error);
    }
  } catch (error) {
    console.error('❌ Error sending re-engagement email:', error);
    return { success: false, error: error.message };
  }
};

module.exports = {
  generateVerificationCode,
  sendVerificationCode,
  verifyCode,
  resendVerificationCode,
  sendWelcomeEmail,
  sendPasswordResetEmail,
  sendUnreadMessagesEmail,
  sendInactivityEmail,
  sendReengagementEmail
};

