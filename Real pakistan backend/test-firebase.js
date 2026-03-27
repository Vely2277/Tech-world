// Test script to verify Firebase configuration
require('dotenv').config();
const { initializeFirebase, testFirebaseConnection } = require('./config/firebase');

async function runTest() {
  try {
    console.log('🧪 Running Firebase configuration test...');

    // Initialize Firebase
    await initializeFirebase();
    console.log('✅ Firebase initialized successfully');

    // Test connection
    const connectionOk = await testFirebaseConnection();
    if (connectionOk) {
      console.log('✅ All tests passed! Backend should work correctly now.');
    } else {
      console.log('⚠️ Connection test failed, but initialization worked.');
    }

  } catch (error) {
    console.error('❌ Test failed:', error.message);
    process.exit(1);
  }
}

runTest();
