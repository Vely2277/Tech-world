/**
 * ============================================================================
 * EMAIL TEMPLATE: WELCOME EMAIL
 * ============================================================================
 *
 * Sent after successful email verification.
 * Welcomes user and provides getting started tips.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate welcome email template
 * @param {string} firstName - User's first name
 * @param {string} lastName - User's last name
 */
const generateWelcomeEmail = (firstName, lastName) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  const content = `
    <!-- Header with Celebration -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="font-size: 60px; margin-bottom: 20px;">🎉</div>
      <h1 style="color: #1f2937; font-size: 32px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        Welcome to ${appName}!
      </h1>
      <p style="color: #4b5563; font-size: 16px; margin-top: 10px; font-family: ${fontFamily};">
        Your journey to success starts here
      </p>
    </div>

    <!-- Personal Welcome -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName} ${lastName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Congratulations! Your email has been verified and your account is now fully activated.
      You're now part of a thriving community of skilled professionals and eager buyers.
    </p>

    <!-- Benefits Section -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; border: 1px solid #e5e7eb;">
      <h2 style="color: #1f2937; font-size: 18px; margin: 0 0 20px 0; font-family: ${fontFamily};">
        🚀 What you can do now:
      </h2>
      <table style="width: 100%; border-collapse: collapse;">
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">💼</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Browse & Apply to Jobs</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Find opportunities that match your skills and location
            </p>
          </td>
        </tr>
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">⭐</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Build Your Reputation</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Complete jobs, get reviews, and level up your profile
            </p>
          </td>
        </tr>
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">💰</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Earn & Withdraw</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Get paid for your work and withdraw funds easily
            </p>
          </td>
        </tr>
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">💬</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Connect with Buyers</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Chat directly with clients about their projects
            </p>
          </td>
        </tr>
      </table>
    </div>

    <!-- Pro Tips -->
    <div style="background: #f8fafc; border-radius: 10px; padding: 20px; margin: 25px 0; border-left: 4px solid #6366f1;">
      <h3 style="color: #1f2937; font-size: 16px; margin: 0 0 15px 0; font-family: ${fontFamily};">
        💡 Pro Tips to Get Started:
      </h3>
      <ul style="color: #374151; font-size: 14px; line-height: 1.8; margin: 0; padding-left: 20px; font-family: ${fontFamily};">
        <li>Complete your profile with a photo and skills</li>
        <li>Keep location services enabled to find nearby jobs</li>
        <li>Respond quickly to messages for better ratings</li>
        <li>Apply to jobs that match your expertise</li>
      </ul>
    </div>

    <!-- Motivational Message -->
    <div style="text-align: center; padding: 25px 0; margin-top: 20px;">
      <p style="color: #374151; font-size: 18px; font-style: italic; line-height: 1.6; font-family: ${fontFamily};">
        "Every expert was once a beginner. <br>Your success story starts today!"
      </p>
    </div>

    ${generateEmailFooter('Start Exploring Jobs', 'gigs', 'Ready to find your first opportunity?')}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for welcome
 */
const getWelcomeSubject = (firstName) => {
  return `🎉 Welcome to the team, ${firstName}! Let's get started`;
};

module.exports = {
  generateWelcomeEmail,
  getWelcomeSubject
};

