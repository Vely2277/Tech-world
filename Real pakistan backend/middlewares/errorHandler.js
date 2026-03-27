const errorHandler = (error, req, res, next) => {
  let statusCode = 500;
  let message = 'Internal Server Error';

  console.error('🚨 Error caught by handler:', {
    name: error.name,
    message: error.message,
    code: error.code,
    stack: error.stack,
    url: req.url,
    method: req.method,
    timestamp: new Date().toISOString()
  });

  // Firebase Auth errors
  if (typeof error.code === 'string' && error.code.startsWith('auth/')) {
    statusCode = 401;
    switch (error.code) {
      case 'auth/user-not-found':
        message = 'User not found';
        break;
      case 'auth/wrong-password':
        message = 'Incorrect password';
        break;
      case 'auth/email-already-in-use':
        message = 'Email is already registered';
        break;
      case 'auth/weak-password':
        message = 'Password is too weak';
        break;
      case 'auth/invalid-email':
        message = 'Invalid email address';
        break;
      default:
        message = 'Authentication failed';
    }
  }

  // Firestore errors
  if (error.code && (error.code.includes('firestore') || error.code.includes('permission-denied'))) {
    statusCode = 403;
    message = 'Database permission denied';
  }

  if (error.code === 'not-found') {
    statusCode = 404;
    message = 'Document not found';
  }

  if (error.code === 'already-exists') {
    statusCode = 409;
    message = 'Document already exists';
  }

  if (error.code === 'invalid-argument') {
    statusCode = 400;
    message = 'Invalid data provided';
  }

  // Validation errors
  if (error.name === 'ValidationError') {
    statusCode = 400;
    message = error.message;
  }

  // JWT errors
  if (error.name === 'JsonWebTokenError') {
    statusCode = 401;
    message = 'Invalid token';
  }

  if (error.name === 'TokenExpiredError') {
    statusCode = 401;
    message = 'Token expired';
  }

  // Custom errors
  if (error.statusCode) {
    statusCode = error.statusCode;
    message = error.message;
  }

  res.status(statusCode).json({
    success: false,
    message: message,
    ...(process.env.NODE_ENV === 'development' && {
      stack: error.stack,
      code: error.code,
      details: error.details
    })
  });
};

module.exports = errorHandler;
