// Firestore schema definitions and validation using Joi
// This file defines the expected structure for each collection/document
// and provides validation functions for use in your routes/controllers.

const Joi = require('joi');

// USERS COLLECTION
const userSchema = Joi.object({
  uid: Joi.string().required(),
  firstName: Joi.string().min(2).required(),
  lastName: Joi.string().min(2).required(),
  email: Joi.string().email().required(),
  role: Joi.string().valid('buyer', 'seller', 'admin').required(),
  profilePicUrl: Joi.string().uri().allow(null, ''),
  level: Joi.number().integer().min(1).default(1),
  successScore: Joi.number().integer().min(0).default(0),
  rating: Joi.number().min(0).max(5).default(0),
  responseRate: Joi.number().min(0).max(100).default(0),
  earnings: Joi.number().min(0).default(0),
  locationUpdateInterval: Joi.number().integer().min(60).default(300),
  currentLocation: Joi.object({
    latitude: Joi.number().min(-90).max(90),
    longitude: Joi.number().min(-180).max(180),
    lastUpdated: Joi.date().iso()
  }).allow(null),
  lastLogin: Joi.date().iso().allow(null),
  createdAt: Joi.date().iso(),
  updatedAt: Joi.date().iso()
});

// JOBS COLLECTION
const jobSchema = Joi.object({
  title: Joi.string().min(3).required(),
  description: Joi.string().min(10).required(),
  budget: Joi.number().min(0).required(),
  location: Joi.object({
    address: Joi.string().required(),
    latitude: Joi.number().min(-90).max(90).allow(null),
    longitude: Joi.number().min(-180).max(180).allow(null)
  }).required(),
  category: Joi.string().default('general'),
  imageUrls: Joi.array().items(Joi.string().uri()),
  createdBy: Joi.string().required(),
  createdByName: Joi.string().required(),
  status: Joi.string().valid('open', 'closed', 'in_progress', 'completed').default('open'),
  applicationsCount: Joi.number().integer().min(0).default(0),
  createdAt: Joi.date().iso(),
  updatedAt: Joi.date().iso()
});

// LOCATIONS COLLECTION - COMPREHENSIVE SCHEMA FOR ANDROID APP
const locationSchema = Joi.object({
  // ============================================================================
  // REQUIRED FIELDS
  // ============================================================================
  userId: Joi.string().required(),
  latitude: Joi.number().min(-90).max(90).required(),
  longitude: Joi.number().min(-180).max(180).required(),

  // ============================================================================
  // CORE LOCATION DATA
  // ============================================================================
  accuracy: Joi.number().min(0).allow(null),
  altitude: Joi.number().allow(null),
  bearing: Joi.number().min(0).max(360).allow(null),
  speed: Joi.number().min(0).allow(null),
  provider: Joi.string().default('unknown'),

  // ============================================================================
  // TIMESTAMPS
  // ============================================================================
  timestamp: Joi.number().allow(null),           // Android sends as long (ms)
  clientTimestamp: Joi.date().iso().allow(null), // Also accept ISO format
  capturedAt: Joi.number().allow(null),          // Android capture time
  serverTimestamp: Joi.date().iso().allow(null), // Backend generates
  createdAt: Joi.date().iso().allow(null),       // Backend generates

  // ============================================================================
  // BATTERY & POWER
  // ============================================================================
  batteryLevel: Joi.number().min(0).max(100).allow(null),
  batteryThresholdActive: Joi.boolean().allow(null),
  forcedInterval: Joi.boolean().allow(null),
  isCharging: Joi.boolean().allow(null),

  // ============================================================================
  // NETWORK
  // ============================================================================
  networkType: Joi.string().valid('wifi', 'cellular', 'offline', 'unknown').allow(null),
  networkQuality: Joi.string().valid('excellent', 'good', 'poor', 'offline').allow(null),

  // ============================================================================
  // DEVICE INFO
  // ============================================================================
  deviceModel: Joi.string().allow(null, ''),
  osVersion: Joi.number().allow(null),
  appVersion: Joi.string().allow(null, ''),

  // ============================================================================
  // TRACKING MODES & FLAGS
  // ============================================================================
  forceCheckEnabled: Joi.boolean().allow(null),
  realtimeMode: Joi.boolean().allow(null),
  emergencyMode: Joi.boolean().allow(null),

  // ============================================================================
  // MOVEMENT & STATUS
  // ============================================================================
  movementStatus: Joi.string().valid('moving', 'stationary', 'unknown').allow(null),

  // ============================================================================
  // METADATA & ADDITIONAL INFO
  // ============================================================================
  id: Joi.string().allow(null),                  // Android local ID
  metadata: Joi.object().allow(null),            // Additional data

  // ============================================================================
  // ACQUISITION INFO
  // ============================================================================
  acquisitionDurationMs: Joi.number().allow(null),
  gpsRetryAttempts: Joi.number().allow(null),

  // ============================================================================
  // PRIORITY & MODE
  // ============================================================================
  priority: Joi.number().allow(null),
  intervalAppliedMs: Joi.number().allow(null),
  intervalType: Joi.string().allow(null)
});

// CONTACTS COLLECTION
const contactSchema = Joi.object({
  jobId: Joi.string().required(),
  jobTitle: Joi.string().required(),
  sellerId: Joi.string().required(),
  sellerName: Joi.string().required(),
  buyerId: Joi.string().required(),
  message: Joi.string().min(10).required(),
  status: Joi.string().valid('pending', 'accepted', 'rejected').default('pending'),
  createdAt: Joi.date().iso()
});

// EARNINGS LOGIC (mocked for now)
async function getEarningsForUser(userId) {
  // In production, fetch from Firestore or your DB
  // For now, return a mock value
  return { userId, total: 0, currency: 'USD', lastUpdated: new Date().toISOString() };
}

module.exports = {
  userSchema,
  jobSchema,
  locationSchema,
  contactSchema,
  getEarningsForUser
};
