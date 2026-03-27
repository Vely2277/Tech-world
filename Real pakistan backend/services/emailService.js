/**
 * ============================================================================
 * EMAIL SERVICE - RESEND INTEGRATION
 * ============================================================================
 *
 * Core email service using Resend API for all email communications.
 * Handles template rendering, sending, and logging.
 *
 * ============================================================================
 */

const { Resend } = require('resend');

// Initialize Resend client
let resend = null;

const initializeResend = () => {
  if (!resend) {
    const apiKey = process.env.RESEND_API_KEY;
    if (!apiKey) {
      console.error('❌ RESEND_API_KEY not found in environment variables');
      throw new Error('RESEND_API_KEY is required');
    }
    resend = new Resend(apiKey);
    console.log('✅ Resend email service initialized');
  }
  return resend;
};

/**
 * Get sender email based on email type
 */
const getSenderEmail = (type) => {
  const senders = {
    verification: process.env.EMAIL_VERIFICATION_SENDER || 'verify@yourdomain.com',
    welcome: process.env.EMAIL_ONBOARDING_SENDER || 'welcome@yourdomain.com',
    password: process.env.EMAIL_PASSWORD_SENDER || 'security@yourdomain.com',
    notification: process.env.EMAIL_NOTIFICATION_SENDER || 'notifications@yourdomain.com',
    engagement: process.env.EMAIL_ENGAGEMENT_SENDER || 'hello@yourdomain.com',
    default: process.env.EMAIL_DEFAULT_SENDER || 'noreply@yourdomain.com'
  };
  return senders[type] || senders.default;
};

/**
 * Generate app deep link URL
 * Falls back to download page if app is not installed
 */
const getAppLink = (path = 'home') => {
  const appScheme = process.env.APP_DEEP_LINK_SCHEME || 'constructionapp';
  const downloadUrl = process.env.APP_DOWNLOAD_URL || 'https://yourapp.com/download';

  // This creates a link that tries to open the app, falls back to download page
  return {
    appLink: `${appScheme}://${path}`,
    webFallback: downloadUrl,
    universalLink: `${process.env.APP_UNIVERSAL_LINK_DOMAIN || 'https://yourapp.com'}/${path}`
  };
};

/**
 * Send email using Resend
 */
const sendEmail = async ({ to, subject, html, from, type = 'default' }) => {
  try {
    const client = initializeResend();
    const senderEmail = from || getSenderEmail(type);
    const appName = process.env.APP_NAME || 'Construction App';

    const result = await client.emails.send({
      from: `${appName} <${senderEmail}>`,
      to: [to],
      subject: subject,
      html: html
    });

    console.log(`✅ Email sent successfully to ${to}`, result);
    return { success: true, data: result };
  } catch (error) {
    console.error(`❌ Failed to send email to ${to}:`, error);
    return { success: false, error: error.message };
  }
};

/**
 * Generate email footer with app link button
 */
const generateEmailFooter = (buttonText, targetPage, additionalText = '') => {
  const links = getAppLink(targetPage);
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const year = new Date().getFullYear();

  return `
    <div style="text-align: center; margin-top: 40px; padding-top: 30px; border-top: 1px solid #e5e7eb; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
      ${additionalText ? `<p style="color: #4b5563; font-size: 14px; margin-bottom: 20px; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">${additionalText}</p>` : ''}

      <a href="${links.universalLink}"
         style="display: inline-block; background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
                color: #ffffff; padding: 16px 40px; border-radius: 10px; text-decoration: none;
                font-weight: 600; font-size: 16px; box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4);
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
        ${buttonText}
      </a>

      <p style="color: #6b7280; font-size: 12px; margin-top: 30px; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
        If the button doesn't work, copy and paste this link into your browser:<br>
        <a href="${links.webFallback}" style="color: #6366f1;">${links.webFallback}</a>
      </p>

      <div style="margin-top: 40px; padding-top: 20px; border-top: 1px solid #e5e7eb;">
        <p style="color: #6b7280; font-size: 12px; margin: 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
          © ${year} ${appName}. All rights reserved.
        </p>
        <p style="color: #9ca3af; font-size: 11px; margin-top: 8px; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
          You're receiving this email because you have an account with ${appName}.
        </p>
      </div>
    </div>
  `;
};

/**
 * Generate base email template wrapper
 */
const generateEmailWrapper = (content) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';

  return `
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <meta http-equiv="X-UA-Compatible" content="IE=edge">
      <title>${appName}</title>
      <!--[if mso]>
      <noscript>
        <xml>
          <o:OfficeDocumentSettings>
            <o:PixelsPerInch>96</o:PixelsPerInch>
          </o:OfficeDocumentSettings>
        </xml>
      </noscript>
      <![endif]-->
      <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
      <style>
        /* Fallback fonts for email clients that don't support Google Fonts */
        body, table, td, p, a, li, blockquote {
          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif !important;
        }
      </style>
    </head>
    <body style="margin: 0; padding: 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #ffffff; color: #1f2937; line-height: 1.6;">
      <table role="presentation" style="width: 100%; border-collapse: collapse; background-color: #ffffff;">
        <tr>
          <td align="center" style="padding: 40px 20px;">
            <table role="presentation" style="width: 100%; max-width: 600px; border-collapse: collapse; background-color: #ffffff;">
              <!-- Header with Logo -->
              <tr>
                <td style="padding: 0 0 30px 0; text-align: center; border-bottom: 1px solid #e5e7eb;">
                  <h1 style="margin: 0; font-size: 24px; font-weight: 700; color: #6366f1; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                    ${appName}
                  </h1>
                </td>
              </tr>
              <!-- Main Content -->
              <tr>
                <td style="padding: 40px 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                  ${content}
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </table>
    </body>
    </html>
  `;
};

/**
 * Send withdrawal notification email to all admin emails
 * @param {Object} withdrawalData - Withdrawal request data
 */
const sendWithdrawalNotificationToAdmins = async (withdrawalData) => {
  try {
    const client = initializeResend();
    const withdrawalTemplate = require('../templates/withdrawalNotificationEmail');

    // Get admin emails from environment variables
    const adminEmails = [];
    if (process.env.ADMIN_EMAIL_1) adminEmails.push(process.env.ADMIN_EMAIL_1);
    if (process.env.ADMIN_EMAIL_2) adminEmails.push(process.env.ADMIN_EMAIL_2);

    // Allow for additional admin emails (ADMIN_EMAIL_3, ADMIN_EMAIL_4, etc.)
    for (let i = 3; i <= 10; i++) {
      const email = process.env[`ADMIN_EMAIL_${i}`];
      if (email) adminEmails.push(email);
    }

    if (adminEmails.length === 0) {
      console.error('❌ No admin emails configured in environment variables');
      return { success: false, error: 'No admin emails configured' };
    }

    console.log(`📧 Sending withdrawal notification to ${adminEmails.length} admin(s):`, adminEmails);

    // Generate email content
    const emailContent = withdrawalTemplate(withdrawalData);
    const senderEmail = process.env.EMAIL_ADMIN_SENDER || process.env.EMAIL_NOTIFICATION_SENDER || 'withdrawals@yourdomain.com';

    // Send to all admin emails
    const results = await Promise.allSettled(
      adminEmails.map(async (adminEmail) => {
        const result = await client.emails.send({
          from: `New Withdrawal Request <${senderEmail}>`,
          to: [adminEmail],
          subject: emailContent.subject,
          html: emailContent.html,
          text: emailContent.text
        });
        console.log(`✅ Withdrawal notification sent to ${adminEmail}`, result);
        return { email: adminEmail, result };
      })
    );

    // Check results
    const successful = results.filter(r => r.status === 'fulfilled').length;
    const failed = results.filter(r => r.status === 'rejected').length;

    console.log(`📧 Withdrawal notification results: ${successful} sent, ${failed} failed`);

    return {
      success: successful > 0,
      sent: successful,
      failed: failed,
      results: results
    };
  } catch (error) {
    console.error('❌ Failed to send withdrawal notification to admins:', error);
    return { success: false, error: error.message };
  }
};

module.exports = {
  initializeResend,
  sendEmail,
  getSenderEmail,
  getAppLink,
  generateEmailFooter,
  generateEmailWrapper,
  sendWithdrawalNotificationToAdmins
};

