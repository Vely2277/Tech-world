const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');

// Create a new review
exports.createReview = async (req, res) => {
  try {
    const { orderId, revieweeId, rating, comment, reviewType } = req.body;
    const reviewerId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Validate required fields
    if (!orderId || !revieweeId || !rating || !reviewType) {
      return res.status(400).json({
        success: false,
        message: 'orderId, revieweeId, rating, and reviewType are required'
      });
    }

    // Validate rating range
    if (rating < 1 || rating > 5) {
      return res.status(400).json({
        success: false,
        message: 'Rating must be between 1 and 5'
      });
    }

    // Validate review type
    if (!['buyer_to_seller', 'seller_to_buyer'].includes(reviewType)) {
      return res.status(400).json({
        success: false,
        message: 'reviewType must be either buyer_to_seller or seller_to_buyer'
      });
    }

    // Check if order exists and user has permission to review
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();
    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Check if order is completed
    if (orderData.status !== 'completed') {
      return res.status(400).json({
        success: false,
        message: 'Order must be completed to leave a review'
      });
    }

    // Validate reviewer permission based on review type
    if (reviewType === 'buyer_to_seller') {
      // Buyer reviewing seller - reviewer should be the job owner (buyer)
      if (orderData.uid !== reviewerId) {
        return res.status(403).json({
          success: false,
          message: 'Only the job owner can review the seller'
        });
      }
    } else if (reviewType === 'seller_to_buyer') {
      // Seller reviewing buyer - reviewer should be the assigned seller
      if (orderData.sellerId !== reviewerId) {
        return res.status(403).json({
          success: false,
          message: 'Only the assigned seller can review the buyer'
        });
      }
    }

    // Check if review already exists for this order and review type
    const existingReview = await admin.firestore()
      .collection('reviews')
      .where('orderId', '==', orderId)
      .where('reviewerId', '==', reviewerId)
      .where('reviewType', '==', reviewType)
      .get();

    if (!existingReview.empty) {
      return res.status(400).json({
        success: false,
        message: 'Review already exists for this order',
        canEdit: true,
        existingReviewId: existingReview.docs[0].id
      });
    }

    // Create review data
    const reviewData = {
      orderId,
      reviewerId,
      revieweeId,
      jobId: orderData.jobId,
      rating: parseInt(rating),
      comment: comment || '',
      reviewType,
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    // Add review to database
    const reviewRef = await admin.firestore().collection('reviews').add(reviewData);

    // Update reviewee's rating statistics
    await updateUserRating(revieweeId);

    res.json({
      success: true,
      message: 'Review created successfully',
      reviewId: reviewRef.id
    });

  } catch (error) {
    console.error('Error creating review:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to create review',
      error: error.message
    });
  }
};

// Update an existing review
exports.updateReview = async (req, res) => {
  try {
    const { reviewId } = req.params;
    const { rating, comment } = req.body;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Validate required fields
    if (!rating) {
      return res.status(400).json({
        success: false,
        message: 'Rating is required'
      });
    }

    // Validate rating range
    if (rating < 1 || rating > 5) {
      return res.status(400).json({
        success: false,
        message: 'Rating must be between 1 and 5'
      });
    }

    // Get review document
    const reviewDoc = await admin.firestore().collection('reviews').doc(reviewId).get();
    if (!reviewDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Review not found'
      });
    }

    const reviewData = reviewDoc.data();

    // Only the reviewer can update their review
    if (reviewData.reviewerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Only the reviewer can update this review'
      });
    }

    // Update review
    await admin.firestore().collection('reviews').doc(reviewId).update({
      rating: parseInt(rating),
      comment: comment || '',
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Update reviewee's rating statistics
    await updateUserRating(reviewData.revieweeId);

    res.json({
      success: true,
      message: 'Review updated successfully'
    });

  } catch (error) {
    console.error('Error updating review:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to update review',
      error: error.message
    });
  }
};

// Reply to a review
exports.replyToReview = async (req, res) => {
  try {
    const { reviewId } = req.params;
    const { reply } = req.body;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    if (!reply || reply.trim() === '') {
      return res.status(400).json({
        success: false,
        message: 'Reply text is required'
      });
    }

    // Get review document
    const reviewDoc = await admin.firestore().collection('reviews').doc(reviewId).get();
    if (!reviewDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Review not found'
      });
    }

    const reviewData = reviewDoc.data();

    // Only the reviewee (seller) can reply to their review
    if (reviewData.revieweeId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Only the reviewed user can reply to this review'
      });
    }

    // Update review with reply
    await admin.firestore().collection('reviews').doc(reviewId).update({
      reply: reply.trim(),
      repliedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    res.json({
      success: true,
      message: 'Reply added successfully'
    });

  } catch (error) {
    console.error('Error replying to review:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to add reply',
      error: error.message
    });
  }
};

// Get reviews for a user
exports.getUserReviews = async (req, res) => {
  try {
    const { userId } = req.params;
    const admin = getFirebaseAdmin();

    const reviewsSnapshot = await admin.firestore()
      .collection('reviews')
      .where('revieweeId', '==', userId)
      .orderBy('createdAt', 'desc')
      .get();

    const reviews = [];
    for (const doc of reviewsSnapshot.docs) {
      const reviewData = { id: doc.id, ...doc.data() };

      // Get reviewer information
      const reviewerDoc = await admin.firestore().collection('users').doc(reviewData.reviewerId).get();
      if (reviewerDoc.exists) {
        const reviewerData = reviewerDoc.data();
        reviewData.reviewerName = reviewerData.name || `${reviewerData.firstName} ${reviewerData.lastName}`;
        reviewData.reviewerProfilePic = reviewerData.profilePicUrl || '';
      }

      // Get job information
      if (reviewData.jobId) {
        const jobDoc = await admin.firestore().collection('jobs').doc(reviewData.jobId).get();
        if (jobDoc.exists) {
          const jobData = jobDoc.data();
          reviewData.jobTitle = jobData.title;
        }
      }

      reviews.push(reviewData);
    }

    res.json({
      success: true,
      reviews
    });

  } catch (error) {
    console.error('Error fetching reviews:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch reviews',
      error: error.message
    });
  }
};

// Get review for a specific order
exports.getOrderReview = async (req, res) => {
  try {
    const { orderId } = req.params;
    const admin = getFirebaseAdmin();

    const reviewSnapshot = await admin.firestore()
      .collection('reviews')
      .where('orderId', '==', orderId)
      .limit(1)
      .get();

    if (reviewSnapshot.empty) {
      return res.json({
        success: true,
        hasReview: false,
        review: null
      });
    }

    const reviewDoc = reviewSnapshot.docs[0];
    const reviewData = { id: reviewDoc.id, ...reviewDoc.data() };

    // Get reviewer information
    const reviewerDoc = await admin.firestore().collection('users').doc(reviewData.reviewerId).get();
    if (reviewerDoc.exists) {
      const reviewerData = reviewerDoc.data();
      reviewData.reviewerName = reviewerData.name || `${reviewerData.firstName} ${reviewerData.lastName}`;
      reviewData.reviewerProfilePic = reviewerData.profilePicUrl || '';
    }

    res.json({
      success: true,
      hasReview: true,
      review: reviewData
    });

  } catch (error) {
    console.error('Error fetching order review:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch review',
      error: error.message
    });
  }
};

// Get reviews for a specific order
exports.getOrderReviews = async (req, res) => {
  try {
    const { orderId } = req.params;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get order to verify user access
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();
    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Only buyer or seller can view reviews for this order
    if (orderData.uid !== userId && orderData.sellerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Access denied'
      });
    }

    // Get all reviews for this order
    const reviewsSnapshot = await admin.firestore()
      .collection('reviews')
      .where('orderId', '==', orderId)
      .orderBy('createdAt', 'desc')
      .get();

    const reviews = [];
    for (const doc of reviewsSnapshot.docs) {
      const reviewData = doc.data();

      // Get reviewer profile
      const reviewerDoc = await admin.firestore().collection('users').doc(reviewData.reviewerId).get();
      const reviewerProfile = reviewerDoc.exists ? reviewerDoc.data() : {};

      reviews.push({
        id: doc.id,
        ...reviewData,
        createdAt: reviewData.createdAt,
        updatedAt: reviewData.updatedAt,
        reviewer: {
          firstName: reviewerProfile.firstName || '',
          lastName: reviewerProfile.lastName || '',
          profilePicUrl: reviewerProfile.profilePicUrl || ''
        }
      });
    }

    res.json({
      success: true,
      reviews,
      orderData: {
        uid: orderData.uid,
        sellerId: orderData.sellerId,
        status: orderData.status
      }
    });

  } catch (error) {
    console.error('Error fetching order reviews:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch reviews',
      error: error.message
    });
  }
};

// Helper function to update user's rating statistics
async function updateUserRating(userId) {
  try {
    const admin = getFirebaseAdmin();
    // Get all reviews for this user
    const reviewsSnapshot = await admin.firestore()
      .collection('reviews')
      .where('revieweeId', '==', userId)
      .get();

    if (reviewsSnapshot.empty) return;

    let totalRating = 0;
    let reviewCount = 0;

    reviewsSnapshot.forEach(doc => {
      const reviewData = doc.data();
      totalRating += reviewData.rating;
      reviewCount++;
    });

    const averageRating = totalRating / reviewCount;

    // Update user's rating in users collection
    await admin.firestore().collection('users').doc(userId).update({
      rating: Math.round(averageRating * 10) / 10, // Round to 1 decimal place
      totalReviews: reviewCount,
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

  } catch (error) {
    console.error('Error updating user rating:', error);
  }
}
