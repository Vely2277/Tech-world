/**
 * ============================================================================
 * EMAIL TEMPLATE: RE-ENGAGEMENT (7 DAYS INACTIVE)
 * ============================================================================
 *
 * Sent when user hasn't opened the app in 7 days.
 * More urgent tone, highlights what they've missed.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate re-engagement email template
 * @param {string} firstName - User's first name
 * @param {number} daysSinceActive - Number of days since last activity
 * @param {number} missedJobsCount - Number of jobs posted since last visit
 * @param {number} missedMessagesCount - Number of messages received
 */
const generateReengagementEmail = (firstName, daysSinceActive = 7, missedJobsCount = 0, missedMessagesCount = 0) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  const content = `
    <!-- Header with Miss You Theme -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="font-size: 70px; margin-bottom: 15px;">🥺</div>
      <h1 style="color: #1f2937; font-size: 28px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        We Really Miss You, ${firstName}!
      </h1>
      <p style="color: #4b5563; font-size: 16px; margin-top: 10px; font-family: ${fontFamily};">
        It's been ${daysSinceActive} days since your last visit
      </p>
    </div>

    <!-- Personal Message -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      It's been a while since we've seen you on ${appName}. We hope everything is going well!
      While you were away, a lot has been happening...
    </p>

    <!-- What You Missed Stats -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 30px; margin: 30px 0; border: 1px solid #e5e7eb;">
      <h2 style="color: #1f2937; font-size: 18px; margin: 0 0 20px 0; text-align: center; font-family: ${fontFamily};">
        📊 Here's What You Missed:
      </h2>
      <table style="width: 100%; border-collapse: collapse;">
        <tr>
          ${missedJobsCount > 0 ? `
          <td style="text-align: center; padding: 10px; width: 33%;">
            <p style="color: #4f46e5; font-size: 36px; font-weight: 800; margin: 0; font-family: ${fontFamily};">
              ${missedJobsCount}+
            </p>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              New Jobs Posted
            </p>
          </td>
          ` : ''}
          ${missedMessagesCount > 0 ? `
          <td style="text-align: center; padding: 10px; width: 33%; ${missedJobsCount > 0 ? 'border-left: 1px solid #e5e7eb;' : ''}">
            <p style="color: #4f46e5; font-size: 36px; font-weight: 800; margin: 0; font-family: ${fontFamily};">
              ${missedMessagesCount}
            </p>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Unread Messages
            </p>
          </td>
          ` : ''}
          <td style="text-align: center; padding: 10px; width: 33%; ${(missedJobsCount > 0 || missedMessagesCount > 0) ? 'border-left: 1px solid #e5e7eb;' : ''}">
            <p style="color: #4f46e5; font-size: 36px; font-weight: 800; margin: 0; font-family: ${fontFamily};">
              ${daysSinceActive}
            </p>
            <p style="color: #4b5563; font-size: 14px; margin: 5px 0 0 0; font-family: ${fontFamily};">
              Days Away
            </p>
          </td>
        </tr>
      </table>
    </div>

    <!-- Special Offer / Incentive -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; text-align: center;
                border: 2px dashed #6366f1;">
      <p style="color: #4b5563; font-size: 14px; margin: 0 0 10px 0; text-transform: uppercase; letter-spacing: 1px; font-family: ${fontFamily};">
        🌟 Special For You
      </p>
      <p style="color: #1f2937; font-size: 20px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        Your profile is still active & ready!
      </p>
      <p style="color: #4b5563; font-size: 14px; margin: 10px 0 0 0; font-family: ${fontFamily};">
        Jump back in and continue where you left off
      </p>
    </div>

    <!-- Reasons to Come Back -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; border-left: 4px solid #6366f1;">
      <h2 style="color: #1f2937; font-size: 16px; margin: 0 0 20px 0; font-family: ${fontFamily};">
        🚀 Great reasons to come back:
      </h2>
      <table style="width: 100%; border-collapse: collapse;">
        <tr>
          <td style="padding: 10px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ Your skills are in demand right now
          </td>
        </tr>
        <tr>
          <td style="padding: 10px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ New buyers joined looking for workers like you
          </td>
        </tr>
        <tr>
          <td style="padding: 10px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ Your profile rank may decrease with inactivity
          </td>
        </tr>
        <tr>
          <td style="padding: 10px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ Quick wins await - some jobs can be done in hours!
          </td>
        </tr>
      </table>
    </div>

    <!-- Personal Touch -->
    <div style="text-align: center; padding: 25px 0; margin-top: 20px;">
      <p style="color: #374151; font-size: 18px; font-style: italic; line-height: 1.6; font-family: ${fontFamily};">
        "Success doesn't wait for anyone.<br>
        <strong>Your next opportunity is waiting!</strong>"
      </p>
    </div>

    <!-- Unsubscribe Notice -->
    <p style="color: #6b7280; font-size: 12px; text-align: center; margin-top: 20px; font-family: ${fontFamily};">
      Don't want to receive these reminders?
      Update your notification preferences in the app.
    </p>

    ${generateEmailFooter('Come Back Now', 'home', "We'd love to see you again!")}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for re-engagement
 */
const getReengagementSubject = (firstName, daysSinceActive) => {
  return `😢 ${firstName}, we miss you! ${daysSinceActive} days of opportunities waiting`;
};

module.exports = {
  generateReengagementEmail,
  getReengagementSubject
};

