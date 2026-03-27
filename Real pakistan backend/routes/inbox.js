const express = require('express');
const router = express.Router();

const { getInboxMessages, sendMessage } = require('../controllers/inboxController');
const { authenticateToken } = require('../middlewares/authMiddleware');


// Get all messages for a user
router.get('/', authenticateToken, getInboxMessages);

// Get all messages for a specific chatId
const { getChatMessages } = require('../controllers/inboxController');
router.get('/chat/:chatId/messages', authenticateToken, getChatMessages);

// Send a new message
router.post('/', authenticateToken, sendMessage);

module.exports = router;

