/**
 * ============================================================================
 * EMAIL TEMPLATE: INACTIVITY / "WE MISS YOU" EMAIL
 * ============================================================================
 *
 * Sent when user's location data hasn't been received for 12+ hours.
 * Framed as a friendly engagement reminder without mentioning location tracking.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate inactivity email template
 * @param {string} firstName - User's first name
 * @param {number} newJobsCount - Number of new jobs available (optional)
 * @param {Array} jobCategories - Trending job categories (optional)
 */
const generateInactivityEmail = (firstName, newJobsCount = 0, jobCategories = []) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  const content = `
    <!-- Header with Wave -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="font-size: 70px; margin-bottom: 15px;">👋</div>
      <h1 style="color: #1f2937; font-size: 28px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        We Haven't Seen You Lately!
      </h1>
      <p style="color: #4b5563; font-size: 16px; margin-top: 10px; font-family: ${fontFamily};">
        Great opportunities are waiting for you
      </p>
    </div>

    <!-- Personal Message -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      We noticed you haven't applied to any jobs or responded to messages in the last 12 hours.
      <strong>Buyers are actively looking for skilled workers like you!</strong>
    </p>

    <!-- New Jobs Highlight -->
    ${newJobsCount > 0 ? `
    <div style="background: #f8fafc; border-radius: 12px; padding: 30px; margin: 30px 0; text-align: center;
                border: 2px solid #6366f1;">
      <p style="color: #4b5563; font-size: 14px; margin: 0 0 10px 0; text-transform: uppercase; letter-spacing: 1px; font-family: ${fontFamily};">
        🔥 Hot Right Now
      </p>
      <p style="color: #4f46e5; font-size: 48px; font-weight: 800; margin: 0; font-family: ${fontFamily};">
        ${newJobsCount}+
      </p>
      <p style="color: #1f2937; font-size: 18px; margin: 10px 0 0 0; font-weight: 600; font-family: ${fontFamily};">
        New Jobs Posted Today
      </p>
    </div>
    ` : ''}

    <!-- Why Come Back -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; border: 1px solid #e5e7eb;">
      <h2 style="color: #1f2937; font-size: 18px; margin: 0 0 20px 0; font-family: ${fontFamily};">
        🌟 Here's what you might be missing:
      </h2>
      <table style="width: 100%; border-collapse: collapse;">
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">💼</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Fresh Job Opportunities</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              New projects posted daily matching your skills
            </p>
          </td>
        </tr>
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">💬</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Buyers Reaching Out</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Clients may be waiting to discuss projects with you
            </p>
          </td>
        </tr>
        <tr>
          <td style="padding: 12px 0; vertical-align: top; width: 40px;">
            <span style="font-size: 24px;">📈</span>
          </td>
          <td style="padding: 12px 0; padding-left: 15px; font-family: ${fontFamily};">
            <strong style="color: #1f2937;">Grow Your Reputation</strong>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Stay active to maintain your response rate and visibility
            </p>
          </td>
        </tr>
      </table>
    </div>

    <!-- Trending Categories -->
    ${jobCategories.length > 0 ? `
    <div style="background: #f8fafc; border-radius: 10px; padding: 20px; margin: 25px 0; border-left: 4px solid #6366f1;">
      <p style="color: #1f2937; font-size: 14px; font-weight: 600; margin: 0 0 15px 0; font-family: ${fontFamily};">
        📊 Trending Job Categories Today:
      </p>
      <div>
        ${jobCategories.map(cat => `
          <span style="display: inline-block; background: #e0e7ff; color: #4338ca; padding: 6px 14px;
                       border-radius: 20px; font-size: 13px; font-weight: 500; margin: 4px; font-family: ${fontFamily};">
            ${cat}
          </span>
        `).join('')}
      </div>
    </div>
    ` : ''}

    <!-- Motivational Message -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; text-align: center; border: 1px solid #e5e7eb;">
      <p style="color: #1f2937; font-size: 18px; font-weight: 600; margin: 0; line-height: 1.6; font-family: ${fontFamily};">
        💪 We believe in your skills!<br>
        <span style="font-weight: 400; font-size: 16px; color: #4b5563;">
          Don't miss out on opportunities that could be perfect for you.
        </span>
      </p>
    </div>

    <!-- Gentle Reminder -->
    <p style="color: #4b5563; font-size: 14px; line-height: 1.6; text-align: center; margin-top: 25px; font-family: ${fontFamily};">
      Your next big project could be just one tap away! 🚀
    </p>

    ${generateEmailFooter('Browse Jobs Now', 'gigs', 'Ready to find your next opportunity?')}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for inactivity email
 */
const getInactivitySubject = (newJobsCount = 0) => {
  if (newJobsCount > 0) {
    return `👋 ${newJobsCount}+ new jobs waiting for you - Buyers need your skills!`;
  }
  return `👋 We miss you! Buyers are looking for skilled workers like you`;
};

module.exports = {
  generateInactivityEmail,
  getInactivitySubject
};

