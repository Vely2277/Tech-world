# Construction Job App Backend

Node.js backend API for a construction job marketplace app with location tracking.

## Features

- 🔐 Firebase Authentication integration
- 👤 User profile management (buyers/sellers)
- 💼 Job posting and browsing system
- 📍 Real-time location tracking
- 🔒 JWT token authentication
- 📱 Designed for Android app integration

## API Endpoints

### Authentication
- `POST /api/auth/signup` - Register new user
- `POST /api/auth/login` - Login user
- `POST /api/auth/forgot-password` - Send password reset email
- `POST /api/auth/verify-token` - Verify Firebase ID token

### Profile
- `GET /api/profile` - Get current user profile
- `PUT /api/profile` - Update user profile
- `GET /api/profile/stats` - Get user statistics

### Jobs
- `GET /api/jobs` - Get all jobs (with filters)
- `POST /api/jobs` - Create new job (buyers only)
- `GET /api/jobs/:id` - Get job details
- `PUT /api/jobs/:id` - Update job (owner only)
- `DELETE /api/jobs/:id` - Delete job (owner only)
- `POST /api/jobs/:id/contact` - Contact job owner

### Location Tracking
- `POST /api/location` - Update user location
- `GET /api/location/interval` - Get location update interval
- `PUT /api/location/interval` - Update location interval
- `GET /api/location/history` - Get location history
- `GET /api/location/current` - Get current location
- `POST /api/location/batch` - Batch location updates

## Setup

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Configure Firebase:**
   - Create a Firebase project
   - Download service account key and save as `firebase-service-account.json`
   - Update `.env` file with your Firebase credentials

3. **Environment Variables:**
   Copy `.env` and update with your values:
   ```
   PORT=3000
   FIREBASE_PROJECT_ID=your-project-id
   JWT_SECRET=your-secret-key
   ```

4. **Start the server:**
   ```bash
   npm start
   # or for development
   npm run dev
   ```

## Deployment

Deploy to Render, Heroku, or any Node.js hosting service.

Make sure to:
- Set environment variables in your hosting platform
- Upload Firebase service account JSON securely
- Enable HTTPS in production

## Android Integration

Your Android app should:
1. Use Firebase Auth SDK for authentication
2. Send Firebase ID tokens as Bearer tokens to this API
3. Call location endpoints every X minutes (configurable per user)
4. Handle job posting and browsing through the jobs endpoints

## Security

- All endpoints except `/health` and some auth endpoints require authentication
- Rate limiting enabled (100 requests per 15 minutes per IP)
- CORS configured for security
- Input validation on all endpoints
- Location data encrypted in transit

## Database Structure

### Firestore Collections:
- `users` - User profiles and settings
- `jobs` - Construction job postings
- `locations` - User location history
- `contacts` - Job application/contact requests

## Support

For issues or questions, check the API responses for detailed error messages.