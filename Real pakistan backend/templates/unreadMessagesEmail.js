/**
 * ============================================================================
 * EMAIL TEMPLATE: UNREAD MESSAGES NOTIFICATION
 * ============================================================================
 *
 * Sent when user has unread messages for 2+ hours and hasn't opened the app.
 * Encourages user to respond to pending messages.
 *
 * ============================================================================
 */

const { generateEmailWrapper, generateEmailFooter } = require('../services/emailService');

/**
 * Generate unread messages notification email
 * @param {string} firstName - User's first name
 * @param {number} unreadCount - Number of unread messages
 * @param {Array} senderNames - Names of message senders (max 3)
 * @param {string} previewMessage - Preview of the latest message (optional)
 */
const generateUnreadMessagesEmail = (firstName, unreadCount, senderNames = [], previewMessage = null) => {
  const appName = process.env.APP_NAME || 'ConstructConnect';
  const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

  // Format sender names
  let senderText = '';
  if (senderNames.length === 1) {
    senderText = senderNames[0];
  } else if (senderNames.length === 2) {
    senderText = `${senderNames[0]} and ${senderNames[1]}`;
  } else if (senderNames.length > 2) {
    senderText = `${senderNames[0]}, ${senderNames[1]}, and ${senderNames.length - 2} others`;
  }

  const content = `
    <!-- Header with Message Icon -->
    <div style="text-align: center; margin-bottom: 30px; font-family: ${fontFamily};">
      <div style="width: 80px; height: 80px; background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
                  border-radius: 50%; margin: 0 auto 20px; display: inline-flex; align-items: center;
                  justify-content: center; box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3); position: relative;">
        <span style="font-size: 36px;">💬</span>
        <!-- Notification Badge -->
        <div style="position: absolute; top: -5px; right: -5px; background: #ef4444; color: #ffffff;
                    width: 28px; height: 28px; border-radius: 50%; font-size: 14px; font-weight: 700;
                    display: inline-flex; align-items: center; justify-content: center; border: 3px solid #ffffff;">
          ${unreadCount > 9 ? '9+' : unreadCount}
        </div>
      </div>
      <h1 style="color: #1f2937; font-size: 28px; font-weight: 700; margin: 0; font-family: ${fontFamily};">
        You Have Unread Messages!
      </h1>
    </div>

    <!-- Personal Greeting -->
    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      Hi <strong style="color: #1f2937;">${firstName}</strong>,
    </p>

    <p style="color: #374151; font-size: 16px; line-height: 1.7; margin-bottom: 25px; font-family: ${fontFamily};">
      You have <strong style="color: #4f46e5;">${unreadCount} unread message${unreadCount > 1 ? 's' : ''}</strong>
      waiting for you${senderText ? ` from <strong>${senderText}</strong>` : ''}.
      Don't keep them waiting!
    </p>

    <!-- Message Preview (if available) -->
    ${previewMessage ? `
    <div style="background: #f8fafc; border-radius: 12px; padding: 20px; margin: 25px 0;
                border-left: 4px solid #6366f1;">
      <p style="color: #4b5563; font-size: 12px; margin: 0 0 8px 0; text-transform: uppercase; letter-spacing: 1px; font-family: ${fontFamily};">
        Latest Message Preview
      </p>
      <p style="color: #374151; font-size: 15px; margin: 0; font-style: italic; line-height: 1.6; font-family: ${fontFamily};">
        "${previewMessage.substring(0, 100)}${previewMessage.length > 100 ? '...' : ''}"
      </p>
    </div>
    ` : ''}

    <!-- Why Respond Fast -->
    <div style="background: #f8fafc; border-radius: 12px; padding: 25px; margin: 30px 0; border: 1px solid #e5e7eb;">
      <h2 style="color: #1f2937; font-size: 16px; margin: 0 0 15px 0; font-family: ${fontFamily};">
        ⚡ Why fast responses matter:
      </h2>
      <table style="width: 100%; border-collapse: collapse;">
        <tr>
          <td style="padding: 8px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ Higher response rate = Better visibility
          </td>
        </tr>
        <tr>
          <td style="padding: 8px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ Buyers prefer quick responders
          </td>
        </tr>
        <tr>
          <td style="padding: 8px 0; color: #374151; font-size: 14px; font-family: ${fontFamily};">
            ✅ More conversations = More opportunities
          </td>
        </tr>
      </table>
    </div>

    <!-- Urgency Notice -->
    <div style="text-align: center; padding: 20px 0;">
      <p style="color: #4b5563; font-size: 14px; margin: 0; font-family: ${fontFamily};">
        📱 Open the app now to read and respond to your messages
      </p>
    </div>

    ${generateEmailFooter('Read Messages Now', 'inbox', 'Your quick response could lead to your next big opportunity!')}
  `;

  return generateEmailWrapper(content);
};

/**
 * Get email subject for unread messages
 */
const getUnreadMessagesSubject = (unreadCount, senderName = null) => {
  if (senderName) {
    return `💬 ${senderName} is waiting for your reply (${unreadCount} unread)`;
  }
  return `💬 You have ${unreadCount} unread message${unreadCount > 1 ? 's' : ''} waiting`;
};

module.exports = {
  generateUnreadMessagesEmail,
  getUnreadMessagesSubject
};

