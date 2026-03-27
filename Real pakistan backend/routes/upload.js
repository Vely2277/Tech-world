const express = require('express');
const router = express.Router();
const multer = require('multer');
const upload = multer();
const { uploadImage, uploadProfileImage, uploadDeliveryFile } = require('../controllers/uploadController');
const { authenticateToken } = require('../middlewares/authMiddleware');

// Test endpoint to verify upload routes work
router.get('/upload-test', (req, res) => {
  res.json({
    success: true,
    message: 'Upload routes are working',
    timestamp: new Date().toISOString(),
    availableEndpoints: [
      'POST /api/upload-image',
      'POST /api/upload-profile-image',
      'POST /api/upload-delivery-file'
    ]
  });
});

router.post('/upload-image', authenticateToken, upload.single('image'), uploadImage);
router.post('/upload-profile-image', authenticateToken, upload.single('image'), uploadProfileImage);
router.post('/upload-delivery-file', authenticateToken, upload.single('file'), uploadDeliveryFile);

module.exports = router;
