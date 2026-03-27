# Email System Integration - Complete Documentation

## Overview
This document describes the complete email system integration using Resend for the Construction App.

## Features
1. **Email Verification** - 6-digit code sent during signup
2. **Welcome Email** - Sent after successful verification
3. **Password Reset** - Custom styled password reset emails
4. **Unread Messages Notification** - Sent when messages are unread for 2+ hours
5. **Inactivity Email (Location)** - Sent when no location data for 12+ hours
6. **Re-engagement Email** - Sent after 7 days of inactivity

---

## Setup Instructions

### 1. Backend Setup

#### Install Dependencies
```bash
cd "Real pakistan backend"
npm install resend node-cron --save
```

#### Configure Environment Variables
Edit `.env` file and update these values:

```env
# Get your API key from https://resend.com/api-keys
RESEND_API_KEY=re_your_actual_api_key_here

# Change domain to your verified domain in Resend
EMAIL_VERIFICATION_SENDER=verify@yourdomain.com
EMAIL_ONBOARDING_SENDER=welcome@yourdomain.com
EMAIL_PASSWORD_SENDER=security@yourdomain.com
EMAIL_NOTIFICATION_SENDER=notifications@yourdomain.com
EMAIL_ENGAGEMENT_SENDER=hello@yourdomain.com
EMAIL_DEFAULT_SENDER=noreply@yourdomain.com

# Update these with your actual URLs
APP_DOWNLOAD_URL=https://yourapp.com/download
APP_UNIVERSAL_LINK_DOMAIN=https://yourapp.com
APP_DEEP_LINK_SCHEME=constructionapp
```

### 2. Resend Setup

1. Go to [https://resend.com](https://resend.com)
2. Create an account
3. Add and verify your domain
4. Create API key
5. Add the API key to your `.env` file

### 3. Android App Setup

The app is already configured. Just rebuild the project:
```bash
./gradlew clean build
```

---

## API Endpoints

### Email Verification

#### Send Verification Code
```
POST /api/email/send-verification
Authorization: Bearer <token>

Response:
{
  "success": true,
  "message": "Verification code sent to your email"
}
```

#### Verify Code
```
POST /api/email/verify-code
Authorization: Bearer <token>
Content-Type: application/json

Body:
{
  "code": "123456"
}

Response:
{
  "success": true,
  "message": "Email verified successfully!"
}
```

#### Resend Verification Code
```
POST /api/email/resend-verification
Authorization: Bearer <token>

Response:
{
  "success": true,
  "message": "New verification code sent to your email"
}
```

#### Check Verification Status
```
GET /api/email/verification-status
Authorization: Bearer <token>

Response:
{
  "success": true,
  "emailVerified": true,
  "email": "user@example.com"
}
```

---

## Email Templates

### 1. Verification Email (`templates/verificationEmail.js`)
- **Trigger:** After signup
- **Contains:** 6-digit verification code
- **Expiration:** 15 minutes
- **Sender:** verify@yourdomain.com

### 2. Welcome Email (`templates/welcomeEmail.js`)
- **Trigger:** After successful email verification
- **Contains:** Welcome message, getting started tips
- **Sender:** welcome@yourdomain.com

### 3. Password Reset Email (`templates/passwordResetEmail.js`)
- **Trigger:** User requests password reset
- **Contains:** Reset link (expires in 60 minutes)
- **Sender:** security@yourdomain.com

### 4. Unread Messages Email (`templates/unreadMessagesEmail.js`)
- **Trigger:** Unread messages for 2+ hours
- **Cooldown:** 24 hours
- **Sender:** notifications@yourdomain.com

### 5. Inactivity Email (`templates/inactivityEmail.js`)
- **Trigger:** No location data for 12+ hours
- **Cooldown:** 48 hours
- **Content:** Encouraging message about job opportunities
- **Note:** Does NOT mention location tracking
- **Sender:** hello@yourdomain.com

### 6. Re-engagement Email (`templates/reengagementEmail.js`)
- **Trigger:** No activity for 7+ days
- **Cooldown:** 7 days
- **Sender:** hello@yourdomain.com

---

## Cron Jobs Schedule

| Job | Schedule | Description |
|-----|----------|-------------|
| Location Inactivity | Every hour at :00 | Checks for users without location data for 12h |
| Unread Messages | Every hour at :30 | Checks for unread messages older than 2h |
| Re-engagement | Daily at 10:00 AM UTC | Checks for users inactive for 7+ days |

---

## Deep Links

Email buttons use deep links to open the app:

| Link | Destination |
|------|-------------|
| `constructionapp://home` | Home page |
| `constructionapp://gigs` | Jobs/Gigs page |
| `constructionapp://inbox` | Inbox/Messages page |
| `constructionapp://orders` | Orders page |
| `constructionapp://account` | Account page |
| `constructionapp://verify` | Email verification page |

If app is not installed, user is redirected to `APP_DOWNLOAD_URL`.

---

## Database Fields Added

### users collection
```javascript
{
  emailVerified: false,           // Whether email is verified
  emailVerifiedAt: Timestamp,     // When email was verified
  welcomeEmailSent: false,        // Whether welcome email was sent
  welcomeEmailSentAt: Timestamp,  // When welcome email was sent
  lastUnreadEmailSent: Timestamp, // Last unread notification email
  lastInactivityEmailSent: Timestamp, // Last inactivity email
  lastReengagementEmailSent: Timestamp // Last re-engagement email
}
```

### verification_codes collection
```javascript
{
  code: "123456",
  email: "user@example.com",
  userId: "uid",
  createdAt: Timestamp,
  expiresAt: Timestamp,
  used: false,
  attempts: 0,
  verifiedAt: Timestamp
}
```

---

## File Structure

```
Real pakistan backend/
├── services/
│   ├── emailService.js    # Core email sending service
│   └── cronJobs.js        # Scheduled tasks
├── controllers/
│   └── emailController.js # Email business logic
├── templates/
│   ├── verificationEmail.js
│   ├── welcomeEmail.js
│   ├── passwordResetEmail.js
│   ├── unreadMessagesEmail.js
│   ├── inactivityEmail.js
│   └── reengagementEmail.js
├── routes/
│   └── email.js           # Email API endpoints
└── .env                   # Configuration

app/src/main/java/.../
├── api/
│   └── EmailService.kt    # Android email API service
└── auth/
    └── EmailVerificationActivity.kt # Verification UI

app/src/main/res/
├── layout/
│   └── activity_email_verification.xml
└── drawable/
    ├── bg_email_verification_header.xml
    ├── bg_otp_input.xml
    └── ic_email_verification.xml
```

---

## Testing

### Test Email (Development Only)
```
POST /api/email/test-send
Content-Type: application/json

Body:
{
  "email": "your-email@example.com",
  "type": "verification" // or "welcome" or "inactivity"
}
```

### Manual Cron Trigger
You can trigger cron jobs manually for testing by calling:
```javascript
const { runManualChecks } = require('./services/cronJobs');
await runManualChecks();
```

---

## Troubleshooting

### Emails not sending
1. Check `RESEND_API_KEY` is set correctly
2. Verify domain is verified in Resend
3. Check server logs for errors

### Verification code not working
1. Check if code is expired (15 minutes)
2. Check if code was already used
3. Check attempts count (max 5)

### Cron jobs not running
1. Ensure server is running continuously
2. Check logs for cron initialization
3. Verify Firebase connection is working

---

## Security Notes

1. Verification codes expire after 15 minutes
2. Maximum 5 attempts per code
3. 1-minute cooldown between resend requests
4. Password reset links expire after 60 minutes
5. Email cooldowns prevent spam:
   - Unread messages: 24 hours
   - Inactivity: 48 hours
   - Re-engagement: 7 days

---

## Future Improvements

1. Add email preferences management in app
2. Add unsubscribe links to marketing emails
3. Add email open tracking
4. Add A/B testing for email subjects
5. Add email templates preview endpoint

