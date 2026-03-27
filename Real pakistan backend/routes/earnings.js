const express = require('express');
const router = express.Router();

const { getEarnings } = require('../controllers/earningsController');
const { authenticateToken } = require('../middlewares/authMiddleware');

// Get earnings for a user
router.get('/', authenticateToken, getEarnings);

module.exports = router;

