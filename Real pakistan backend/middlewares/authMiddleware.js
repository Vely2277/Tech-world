const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');

// Cache to track last update times per user (in-memory throttling)
const lastUpdateCache = new Map();
const UPDATE_THROTTLE_MS = 60000; // 1 minute
const CACHE_CLEANUP_INTERVAL = 300000; // 5 minutes

// Clean up old cache entries periodically to prevent memory leaks
setInterval(() => {
  const now = Date.now();
  const cutoff = now - (UPDATE_THROTTLE_MS * 10); // Remove entries older than 10 minutes

  for (const [userId, timestamp] of lastUpdateCache.entries()) {
    if (timestamp < cutoff) {
      lastUpdateCache.delete(userId);
    }
  }
}, CACHE_CLEANUP_INTERVAL);

const authenticateToken = async (req, res, next) => {
  try {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({ success: false, message: 'Access token required' });
    }
    const token = authHeader.split('Bearer ')[1];
    const app = getFirebaseAdmin();
    const decodedToken = await app.auth().verifyIdToken(token);
    req.user = {
      uid: decodedToken.uid,
      email: decodedToken.email,
      email_verified: decodedToken.email_verified
    };

    // Update user's lastLogin timestamp with throttling
    const now = Date.now();
    const lastUpdate = lastUpdateCache.get(decodedToken.uid);

    // Only update if more than 1 minute has passed since last update
    if (!lastUpdate || (now - lastUpdate) > UPDATE_THROTTLE_MS) {
      try {
        await app.firestore()
          .collection('users')
          .doc(decodedToken.uid)
          .update({
            lastLogin: firebaseAdmin.firestore.FieldValue.serverTimestamp()
          });

        // Cache the update time
        lastUpdateCache.set(decodedToken.uid, now);
      } catch (updateError) {
        // Log error but don't fail the request if lastLogin update fails
        console.warn('Failed to update lastLogin for user:', decodedToken.uid, updateError.message);
      }
    }

    next();
  } catch (error) {
    console.error('Auth error:', error.message);
    return res.status(401).json({ success: false, message: 'Invalid or expired token' });
  }
};

const optionalAuth = async (req, res, next) => {
  try {
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
      const token = authHeader.split('Bearer ')[1];
      const app = getFirebaseAdmin();
      const decodedToken = await app.auth().verifyIdToken(token);
      req.user = {
        uid: decodedToken.uid,
        email: decodedToken.email,
        email_verified: decodedToken.email_verified
      };
    }
    next();
  } catch (error) {
    next();
  }
};

module.exports = {
  authenticateToken,
  optionalAuth
};
