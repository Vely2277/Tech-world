/**
 * ============================================================================
 * EMAIL ROUTES
 * ============================================================================
 *
 * API endpoints for email-related operations:
 * - Send verification code
 * - Verify code
 * - Resend verification code
 * - Check verification status
 *
 * ============================================================================
 */

const express = require('express');
const router = express.Router();
const { getFirebaseAdmin } = require('../config/firebase');
const { authenticateToken } = require('../middlewares/authMiddleware');
const {
  sendVerificationCode,
  verifyCode,
  resendVerificationCode,
  sendWelcomeEmail
} = require('../controllers/emailController');

/**
 * POST /api/email/send-verification
 * Send verification code to user's email
 * Requires authentication
 */
router.post('/send-verification', authenticateToken, async (req, res) => {
  try {
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get user data
    const userDoc = await admin.firestore().collection('users').doc(userId).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User not found'
      });
    }

    const userData = userDoc.data();

    // Check if already verified
    if (userData.emailVerified) {
      return res.status(400).json({
        success: false,
        message: 'Email is already verified'
      });
    }

    const result = await sendVerificationCode(userId, userData.email, userData.firstName);

    if (result.success) {
      res.status(200).json({
        success: true,
        message: 'Verification code sent to your email'
      });
    } else {
      res.status(500).json({
        success: false,
        message: result.error || 'Failed to send verification code'
      });
    }
  } catch (error) {
    console.error('Error sending verification code:', error);
    res.status(500).json({
      success: false,
      message: 'Server error while sending verification code'
    });
  }
});

/**
 * POST /api/email/verify-code
 * Verify the code entered by user
 * Requires authentication
 */
router.post('/verify-code', authenticateToken, async (req, res) => {
  try {
    const userId = req.user.uid;
    const { code } = req.body;

    if (!code || code.length !== 6) {
      return res.status(400).json({
        success: false,
        message: 'Please enter a valid 6-digit code'
      });
    }

    const result = await verifyCode(userId, code);

    if (result.success) {
      // Send welcome email after successful verification
      const admin = getFirebaseAdmin();
      const userDoc = await admin.firestore().collection('users').doc(userId).get();
      const userData = userDoc.data();

      // Send welcome email in background (don't wait)
      sendWelcomeEmail(userId, userData.email, userData.firstName, userData.lastName)
        .catch(err => console.error('Failed to send welcome email:', err));

      res.status(200).json({
        success: true,
        message: 'Email verified successfully!'
      });
    } else {
      res.status(400).json({
        success: false,
        message: result.error || 'Verification failed'
      });
    }
  } catch (error) {
    console.error('Error verifying code:', error);
    res.status(500).json({
      success: false,
      message: 'Server error while verifying code'
    });
  }
});

/**
 * POST /api/email/resend-verification
 * Resend verification code
 * Requires authentication
 */
router.post('/resend-verification', authenticateToken, async (req, res) => {
  try {
    const userId = req.user.uid;

    const result = await resendVerificationCode(userId);

    if (result.success) {
      res.status(200).json({
        success: true,
        message: 'New verification code sent to your email'
      });
    } else {
      res.status(400).json({
        success: false,
        message: result.error || 'Failed to resend verification code'
      });
    }
  } catch (error) {
    console.error('Error resending verification code:', error);
    res.status(500).json({
      success: false,
      message: 'Server error while resending verification code'
    });
  }
});

/**
 * GET /api/email/verification-status
 * Check if user's email is verified
 * Requires authentication
 */
router.get('/verification-status', authenticateToken, async (req, res) => {
  try {
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    const userDoc = await admin.firestore().collection('users').doc(userId).get();

    if (!userDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'User not found'
      });
    }

    const userData = userDoc.data();

    res.status(200).json({
      success: true,
      emailVerified: userData.emailVerified || false,
      email: userData.email
    });
  } catch (error) {
    console.error('Error checking verification status:', error);
    res.status(500).json({
      success: false,
      message: 'Server error'
    });
  }
});

/**
 * POST /api/email/test-send (Development only)
 * Test email sending functionality
 */
router.post('/test-send', async (req, res) => {
  if (process.env.NODE_ENV === 'production') {
    return res.status(403).json({
      success: false,
      message: 'Test endpoint not available in production'
    });
  }

  try {
    const { email, type } = req.body;

    if (!email) {
      return res.status(400).json({
        success: false,
        message: 'Email is required'
      });
    }

    const { sendEmail } = require('../services/emailService');
    const { generateVerificationEmail } = require('../templates/verificationEmail');
    const { generateWelcomeEmail } = require('../templates/welcomeEmail');
    const { generateInactivityEmail } = require('../templates/inactivityEmail');

    let html, subject;

    switch (type) {
      case 'verification':
        html = generateVerificationEmail('Test User', '123456', 15);
        subject = '🔐 123456 is your verification code';
        break;
      case 'welcome':
        html = generateWelcomeEmail('Test', 'User');
        subject = '🎉 Welcome to the team, Test!';
        break;
      case 'inactivity':
        html = generateInactivityEmail('Test', 25, ['Plumbing', 'Electrical', 'Carpentry']);
        subject = '👋 25+ new jobs waiting for you';
        break;
      default:
        html = generateVerificationEmail('Test User', '123456', 15);
        subject = '🔐 Test Email';
    }

    const result = await sendEmail({
      to: email,
      subject: subject,
      html: html,
      type: 'verification'
    });

    res.status(result.success ? 200 : 500).json(result);
  } catch (error) {
    console.error('Error sending test email:', error);
    res.status(500).json({
      success: false,
      message: error.message
    });
  }
});

/**
 * POST /api/email/admin-send
 * Send a custom email from admin panel
 * Requires admin authentication
 */
router.post('/admin-send', authenticateToken, async (req, res) => {
  try {
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Verify user is admin
    const userDoc = await admin.firestore().collection('users').doc(userId).get();
    if (!userDoc.exists || userDoc.data().role !== 'admin') {
      return res.status(403).json({
        success: false,
        message: 'Admin access required'
      });
    }

    const { to, from, subject, text, templateType } = req.body;

    if (!to || !subject || !text) {
      return res.status(400).json({
        success: false,
        message: 'Missing required fields: to, subject, text'
      });
    }

    const { sendEmail, generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

    // Convert plain text to HTML with proper formatting
    const htmlBody = text
      .split('\n\n')
      .map(paragraph => `<p style="margin: 0 0 16px 0; line-height: 1.7; color: #4b5563;">${paragraph.replace(/\n/g, '<br>')}</p>`)
      .join('');

    // Determine the deep link target based on template type
    let deepLinkTarget = 'home';
    if (templateType === 'verification') deepLinkTarget = 'verify';
    else if (templateType === 'unreadMessages') deepLinkTarget = 'inbox';
    else if (templateType === 'inactivity' || templateType === 'reengagement') deepLinkTarget = 'gigs';

    // Wrap in email template
    const html = generateEmailWrapper(`
      ${htmlBody}
      ${generateEmailFooter('Open App', deepLinkTarget, '')}
    `);

    const result = await sendEmail({
      to,
      from: from || process.env.EMAIL_DEFAULT_SENDER,
      subject,
      html,
      type: templateType || 'custom'
    });

    if (result.success) {
      console.log(`[ADMIN EMAIL] Sent ${templateType || 'custom'} email to ${to}`);
      res.status(200).json({
        success: true,
        message: 'Email sent successfully'
      });
    } else {
      res.status(500).json({
        success: false,
        message: result.error || 'Failed to send email'
      });
    }
  } catch (error) {
    console.error('Error sending admin email:', error);
    res.status(500).json({
      success: false,
      message: error.message || 'Server error while sending email'
    });
  }
});

module.exports = router;

