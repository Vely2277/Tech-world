const admin = require('firebase-admin');
require('dotenv').config();

let firebaseAdmin = null;

function initializeFirebase() {
  if (firebaseAdmin) {
    console.log('🔥 Firebase Admin already initialized');
    return firebaseAdmin;
  }

  try {
    console.log('🚀 Initializing Firebase Admin...');

    // Use the service account details from .env file
    const serviceAccount = {
      type: "service_account",
      project_id: process.env.FIREBASE_PROJECT_ID,
      private_key_id: process.env.FIREBASE_PRIVATE_KEY_ID,
      private_key: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
      client_email: process.env.FIREBASE_CLIENT_EMAIL,
      client_id: process.env.FIREBASE_CLIENT_ID,
      auth_uri: process.env.FIREBASE_AUTH_URI,
      token_uri: process.env.FIREBASE_TOKEN_URI,
      auth_provider_x509_cert_url: process.env.FIREBASE_AUTH_PROVIDER_X509_CERT_URL,
      client_x509_cert_url: process.env.FIREBASE_CLIENT_X509_CERT_URL
    };

    // Validate required fields
    const requiredFields = ['project_id', 'private_key', 'client_email'];
    for (const field of requiredFields) {
      if (!serviceAccount[field]) {
        throw new Error(`Missing required Firebase config: ${field}`);
      }
    }

    console.log('🔑 Using service account for project:', serviceAccount.project_id);

    firebaseAdmin = admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      databaseURL: `https://${serviceAccount.project_id}-default-rtdb.firebaseio.com`
    });

    console.log('✅ Firebase Admin initialized successfully');
    return firebaseAdmin;

  } catch (error) {
    console.error('❌ Firebase initialization failed:', error.message);
    console.error('Stack trace:', error.stack);
    throw new Error(`Firebase initialization failed: ${error.message}`);
  }
}

function getFirebaseAdmin() {
  if (!firebaseAdmin) {
    throw new Error('Firebase Admin not initialized. Call initializeFirebase() first.');
  }
  return firebaseAdmin;
}

async function testFirebaseConnection() {
  try {
    console.log('🧪 Testing Firebase connection...');
    const admin = getFirebaseAdmin();

    // Test Firestore connection
    const testDoc = await admin.firestore().collection('_test').doc('connection-test').get();
    console.log('✅ Firestore connection successful');

    return true;
  } catch (error) {
    console.error('❌ Firebase connection test failed:', error.message);
    return false;
  }
}

module.exports = {
  initializeFirebase,
  getFirebaseAdmin,
  testFirebaseConnection,
  admin  // Export the admin SDK module for FieldValue access
};

