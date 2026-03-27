const express = require('express');
const router = express.Router();

const { getOrders, createOrder, updateOrderStatus, getOrderById, getOrderDetails, deliverOrder, approveOrder } = require('../controllers/ordersController');
const { authenticateToken } = require('../middlewares/authMiddleware');

// Get all orders for a user
router.get('/', authenticateToken, getOrders);

// Get specific order details
router.get('/:orderId', authenticateToken, getOrderById);

// Get complete order details with job and user information (NEW)
router.get('/:orderId/details', authenticateToken, getOrderDetails);

// Create a new order
router.post('/', authenticateToken, createOrder);

// Update order status (complete, cancel, etc.)
router.patch('/:orderId/status', authenticateToken, updateOrderStatus);

// Deliver order - seller submits work (NEW)
router.post('/:orderId/deliver', authenticateToken, deliverOrder);

// Approve delivery - buyer approves completed work (NEW)
router.post('/:orderId/approve', authenticateToken, approveOrder);

module.exports = router;
