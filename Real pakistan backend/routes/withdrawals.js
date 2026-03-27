const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middlewares/authMiddleware');
const withdrawalsController = require('../controllers/withdrawalsController');

// Submit withdrawal request
router.post('/', authenticateToken, withdrawalsController.createWithdrawal);

// Get user's withdrawal history
router.get('/', authenticateToken, withdrawalsController.getWithdrawals);

// Get withdrawal by ID (for user's own withdrawals only)
router.get('/:withdrawalId', authenticateToken, withdrawalsController.getWithdrawalById);

// Admin endpoint to view all withdrawals (for testing - remove in production)
router.get('/admin/all', withdrawalsController.getAllWithdrawals);

module.exports = router;
