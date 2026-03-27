/**
 * ============================================================================
 * EMAIL TEMPLATE: VERIFICATION CODE
 * ============================================================================
 *
 * Sent during sign up to verify user's email address.
 * Contains 6-digit verification code.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate email verification code template
 * @param {string} firstName - User's first name
 * @param {string} verificationCode - 6-digit verification code
 * @param {number} expirationMinutes - Code expiration time in minutes
 */
const generateVerificationEmail = (firstName, verificationCode, expirationMinutes = 15) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  const content = `
    <!-- Header with Icon -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="width: 80px; height: 80px; background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
                  border-radius: 50%; margin: 0 auto 20px; display: inline-flex; align-items: center;
                  justify-content: center; box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);">
        <span style="font-size: 36px;">✉️</span>
      </div>
      <h1 style="color: #1f2937; font-size: 28px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        Verify Your Email
      </h1>
    </div>

    <!-- Welcome Message -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Welcome to <strong>${appName}</strong>! We're excited to have you on board.
      To complete your registration and start finding amazing job opportunities,
      please verify your email address.
    </p>

    <!-- Verification Code Box -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 30px; text-align: center; margin: 30px 0;
                border: 2px dashed #6366f1;">
      <p style="color: #6b7280; font-size: 14px; margin: 0 0 15px 0; text-transform: uppercase; letter-spacing: 1px; font-family: ${fontFamily};">
        Your Verification Code
      </p>
      <div style="font-size: 42px; font-weight: 800; letter-spacing: 12px; color: #4f46e5;
                  font-family: 'SF Mono', 'Monaco', 'Inconsolata', 'Roboto Mono', monospace;">
        ${verificationCode}
      </div>
      <p style="color: #dc2626; font-size: 13px; margin: 15px 0 0 0; font-family: ${fontFamily};">
        ⏱️ This code expires in <strong>${expirationMinutes} minutes</strong>
      </p>
    </div>

    <!-- Instructions -->
    <div style="background: #fefce8; border-radius: 10px; padding: 20px; margin: 25px 0;
                border-left: 4px solid #eab308;">
      <p style="color: #854d0e; font-size: 14px; margin: 0; line-height: 1.6; font-family: ${fontFamily};">
        <strong>📝 How to verify:</strong><br>
        1. Open the ${appName} app<br>
        2. Enter the 6-digit code shown above<br>
        3. Start exploring opportunities!
      </p>
    </div>

    <!-- Security Notice -->
    <p style="color: #4b5563; font-size: 14px; line-height: 1.6; margin-top: 25px; font-family: ${fontFamily};">
      <strong>🔒 Security tip:</strong> Never share this code with anyone.
      Our team will never ask for your verification code.
    </p>

    <!-- Didn't Request Notice -->
    <p style="color: #6b7280; font-size: 13px; line-height: 1.6; margin-top: 20px; font-family: ${fontFamily};">
      If you didn't create an account with ${appName}, please ignore this email.
    </p>

    ${generateEmailFooter('Open App to Verify', 'verify', '')}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for verification
 */
const getVerificationSubject = (code) => {
  return `🔐 ${code} is your verification code`;
};

module.exports = {
  generateVerificationEmail,
  getVerificationSubject
};

