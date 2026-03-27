/**
 * ============================================================================
 * EMAIL TEMPLATE: PASSWORD RESET
 * ============================================================================
 *
 * Sent when user requests a password reset.
 * Contains password reset link.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate password reset email template
 * @param {string} firstName - User's first name
 * @param {string} resetLink - Password reset link
 * @param {number} expirationMinutes - Link expiration time in minutes
 */
const generatePasswordResetEmail = (firstName, resetLink, expirationMinutes = 60) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  const content = `
    <!-- Header with Security Icon -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="width: 80px; height: 80px; background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
                  border-radius: 50%; margin: 0 auto 20px; display: inline-flex; align-items: center;
                  justify-content: center; box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);">
        <span style="font-size: 36px;">🔐</span>
      </div>
      <h1 style="color: #1f2937; font-size: 28px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        Reset Your Password
      </h1>
    </div>

    <!-- Greeting -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      We received a request to reset your password for your ${appName} account.
      No worries, it happens to the best of us!
    </p>

    <!-- Reset Button -->
    <div style="text-align: center; margin: 35px 0;">
      <a href="${resetLink}"
         style="display: inline-block; background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
                color: #ffffff; padding: 18px 50px; border-radius: 10px; text-decoration: none;
                font-weight: 600; font-size: 16px; box-shadow: 0 6px 20px rgba(99, 102, 241, 0.4);
                font-family: ${fontFamily};">
        Reset My Password
      </a>
    </div>

    <!-- Expiration Warning -->
    <div style="background: #fefce8; border-radius: 10px; padding: 20px; margin: 25px 0;
                border-left: 4px solid #eab308; text-align: center;">
      <p style="color: #854d0e; font-size: 14px; margin: 0; font-family: ${fontFamily};">
        ⏱️ This link will expire in <strong>${expirationMinutes} minutes</strong> for security reasons.
      </p>
    </div>

    <!-- Alternative Link -->
    <div style="background: #f8fafc; border-radius: 10px; padding: 20px; margin: 25px 0; border: 1px solid #e5e7eb;">
      <p style="color: #4b5563; font-size: 14px; margin: 0 0 10px 0; font-family: ${fontFamily};">
        If the button doesn't work, copy and paste this link:
      </p>
      <p style="color: #6366f1; font-size: 13px; word-break: break-all; margin: 0;
                background: #ffffff; padding: 12px; border-radius: 8px; font-family: 'SF Mono', 'Monaco', monospace; border: 1px solid #e5e7eb;">
        ${resetLink}
      </p>
    </div>

    <!-- Security Notice -->
    <div style="background: #fef2f2; border-radius: 10px; padding: 20px; margin: 25px 0;
                border-left: 4px solid #ef4444;">
      <h3 style="color: #991b1b; font-size: 14px; margin: 0 0 10px 0; font-family: ${fontFamily};">
        🛡️ Security Notice
      </h3>
      <ul style="color: #7f1d1d; font-size: 13px; margin: 0; padding-left: 20px; line-height: 1.8; font-family: ${fontFamily};">
        <li>If you didn't request this, please ignore this email</li>
        <li>Your password won't change until you create a new one</li>
        <li>Never share this link with anyone</li>
        <li>Our team will never ask for your password</li>
      </ul>
    </div>

    <!-- Support -->
    <p style="color: #4b5563; font-size: 14px; line-height: 1.6; margin-top: 25px; font-family: ${fontFamily};">
      Need help? Contact our support team.
    </p>

    ${generateEmailFooter('Open App', 'home', '')}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for password reset
 */
const getPasswordResetSubject = () => {
  return `🔐 Password Reset Request - Action Required`;
};

module.exports = {
  generatePasswordResetEmail,
  getPasswordResetSubject
};

