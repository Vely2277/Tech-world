/**
 * Withdrawal Notification Email Template
 * Sent to admin emails when a user submits a withdrawal request
 */

const withdrawalNotificationEmail = (data) => {
    const {
        userName,
        userEmail,
        userId,
        amount,
        accountHolderName,
        bankName,
        accountNumber,
        iban,
        bankBranch,
        city,
        phoneNumber,
        notes,
        requestDate
    } = data;

    const formattedDate = new Date(requestDate).toLocaleString('en-US', {
        weekday: 'long',
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        timeZoneName: 'short'
    });

    return {
        subject: `🏦 New Withdrawal Request - $${amount} from ${userName}`,
        html: `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Withdrawal Request Notification</title>
</head>
<body style="margin: 0; padding: 0; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f4f4f4;">
    <table role="presentation" style="width: 100%; border-collapse: collapse;">
        <tr>
            <td align="center" style="padding: 40px 0;">
                <table role="presentation" style="width: 600px; border-collapse: collapse; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);">

                    <!-- Header -->
                    <tr>
                        <td style="background: linear-gradient(135deg, #7C4DFF 0%, #5E35B1 100%); padding: 40px 30px; text-align: center;">
                            <div style="width: 70px; height: 70px; background-color: rgba(255,255,255,0.2); border-radius: 50%; margin: 0 auto 20px; display: flex; align-items: center; justify-content: center;">
                                <span style="font-size: 36px;">🏦</span>
                            </div>
                            <h1 style="color: #ffffff; margin: 0; font-size: 28px; font-weight: 600;">New Withdrawal Request</h1>
                            <p style="color: rgba(255,255,255,0.9); margin: 10px 0 0; font-size: 16px;">A user has submitted a withdrawal request</p>
                        </td>
                    </tr>

                    <!-- Alert Banner -->
                    <tr>
                        <td style="padding: 0 30px;">
                            <div style="background-color: #FFF3E0; border-left: 4px solid #FF9800; padding: 16px 20px; margin-top: 25px; border-radius: 0 8px 8px 0;">
                                <p style="margin: 0; color: #E65100; font-weight: 600; font-size: 14px;">
                                    ⚡ ACTION REQUIRED: Please review and process this withdrawal request
                                </p>
                            </div>
                        </td>
                    </tr>

                    <!-- Amount Section -->
                    <tr>
                        <td style="padding: 30px 30px 0;">
                            <div style="background: linear-gradient(135deg, #E8F5E9 0%, #C8E6C9 100%); border-radius: 12px; padding: 25px; text-align: center;">
                                <p style="margin: 0 0 5px; color: #2E7D32; font-size: 14px; text-transform: uppercase; letter-spacing: 1px;">Withdrawal Amount</p>
                                <p style="margin: 0; color: #1B5E20; font-size: 42px; font-weight: 700;">$${amount}</p>
                            </div>
                        </td>
                    </tr>

                    <!-- User Information Section -->
                    <tr>
                        <td style="padding: 30px 30px 0;">
                            <h2 style="margin: 0 0 20px; color: #333; font-size: 18px; font-weight: 600; border-bottom: 2px solid #7C4DFF; padding-bottom: 10px;">
                                👤 User Information
                            </h2>
                            <table style="width: 100%; border-collapse: collapse;">
                                <tr>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #666; width: 40%;">User Name</td>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #333; font-weight: 500;">${userName}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #666;">Email Address</td>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #333; font-weight: 500;">
                                        <a href="mailto:${userEmail}" style="color: #7C4DFF; text-decoration: none;">${userEmail}</a>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #666;">User ID</td>
                                    <td style="padding: 12px 0; border-bottom: 1px solid #eee; color: #333; font-family: monospace; font-size: 12px;">${userId}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 12px 0; color: #666;">Request Date</td>
                                    <td style="padding: 12px 0; color: #333; font-weight: 500;">${formattedDate}</td>
                                </tr>
                            </table>
                        </td>
                    </tr>

                    <!-- Bank Details Section -->
                    <tr>
                        <td style="padding: 30px 30px 0;">
                            <h2 style="margin: 0 0 20px; color: #333; font-size: 18px; font-weight: 600; border-bottom: 2px solid #7C4DFF; padding-bottom: 10px;">
                                🏦 Bank Account Details
                            </h2>
                            <table style="width: 100%; border-collapse: collapse; background-color: #FAFAFA; border-radius: 8px; overflow: hidden;">
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666; width: 40%;">Account Holder Name</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 600;">${accountHolderName}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666;">Bank Name</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 600;">${bankName}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666;">Account Number</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 600; font-family: monospace; font-size: 15px;">${accountNumber}</td>
                                </tr>
                                ${iban ? `
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666;">IBAN</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 600; font-family: monospace; font-size: 14px;">${iban}</td>
                                </tr>
                                ` : ''}
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666;">Bank Branch</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 500;">${bankBranch}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #666;">City</td>
                                    <td style="padding: 14px 16px; border-bottom: 1px solid #eee; color: #333; font-weight: 500;">${city}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 14px 16px; color: #666;">Phone Number</td>
                                    <td style="padding: 14px 16px; color: #333; font-weight: 600;">
                                        <a href="tel:${phoneNumber}" style="color: #7C4DFF; text-decoration: none;">${phoneNumber}</a>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>

                    <!-- Notes Section (if provided) -->
                    ${notes ? `
                    <tr>
                        <td style="padding: 30px 30px 0;">
                            <h2 style="margin: 0 0 15px; color: #333; font-size: 18px; font-weight: 600; border-bottom: 2px solid #7C4DFF; padding-bottom: 10px;">
                                📝 Additional Notes
                            </h2>
                            <div style="background-color: #F5F5F5; border-radius: 8px; padding: 16px; border-left: 4px solid #7C4DFF;">
                                <p style="margin: 0; color: #555; font-style: italic; line-height: 1.6;">"${notes}"</p>
                            </div>
                        </td>
                    </tr>
                    ` : ''}

                    <!-- Action Reminder -->
                    <tr>
                        <td style="padding: 30px;">
                            <div style="background: linear-gradient(135deg, #E3F2FD 0%, #BBDEFB 100%); border-radius: 12px; padding: 20px; text-align: center;">
                                <p style="margin: 0 0 10px; color: #1565C0; font-weight: 600; font-size: 16px;">📋 Next Steps</p>
                                <p style="margin: 0; color: #1976D2; font-size: 14px; line-height: 1.6;">
                                    1. Verify the user's account details<br>
                                    2. Process the bank transfer<br>
                                    3. Update the withdrawal status in the admin panel
                                </p>
                            </div>
                        </td>
                    </tr>

                    <!-- Footer -->
                    <tr>
                        <td style="background-color: #FAFAFA; padding: 25px 30px; text-align: center; border-top: 1px solid #eee;">
                            <p style="margin: 0 0 10px; color: #999; font-size: 13px;">
                                This is an automated notification from ConstructConnect
                            </p>
                            <p style="margin: 0; color: #bbb; font-size: 12px;">
                                © ${new Date().getFullYear()} ConstructConnect. All rights reserved.
                            </p>
                        </td>
                    </tr>

                </table>
            </td>
        </tr>
    </table>
</body>
</html>
        `,
        text: `
NEW WITHDRAWAL REQUEST

Amount: $${amount}

USER INFORMATION:
- User Name: ${userName}
- Email: ${userEmail}
- User ID: ${userId}
- Request Date: ${formattedDate}

BANK ACCOUNT DETAILS:
- Account Holder Name: ${accountHolderName}
- Bank Name: ${bankName}
- Account Number: ${accountNumber}
${iban ? `- IBAN: ${iban}` : ''}
- Bank Branch: ${bankBranch}
- City: ${city}
- Phone Number: ${phoneNumber}

${notes ? `ADDITIONAL NOTES:\n${notes}` : ''}

Please process this withdrawal request promptly.

---
ConstructConnect Automated Notification
        `
    };
};

module.exports = withdrawalNotificationEmail;
