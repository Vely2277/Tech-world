const express = require('express');
const router = express.Router();
const reviewsController = require('../controllers/reviewsController');
const { authenticateToken } = require('../middlewares/authMiddleware');

// All routes require authentication
router.use(authenticateToken);

// Create a new review
router.post('/', reviewsController.createReview);

// Reply to a review
router.post('/:reviewId/reply', reviewsController.replyToReview);

// Get reviews for a user
router.get('/user/:userId', reviewsController.getUserReviews);
 
// Get review for a specific order
router.get('/order/:orderId', reviewsController.getOrderReview);

module.exports = router;
