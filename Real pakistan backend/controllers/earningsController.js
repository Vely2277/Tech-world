const { getFirebaseAdmin, admin } = require('../config/firebase');

/**
 * ============================================================================
 * EARNINGS & PERFORMANCE METRICS CONTROLLER
 * ============================================================================
 *
 * All metrics are DYNAMICALLY CALCULATED from real data:
 * - Level: Based on completed orders, earnings, and account age
 * - Success Score: Based on completion rate and on-time delivery
 * - Rating: Average of all reviews
 * - Response Rate: Based on message response times
 * - All earnings: Calculated from actual order data
 *
 * ============================================================================
 */

// Get user earnings data with fully calculated performance metrics
exports.getEarnings = async (req, res) => {
  try {
    const userId = req.user.uid;
    const firebaseApp = getFirebaseAdmin();
    const db = firebaseApp.firestore();

    const userDoc = await db.collection('users').doc(userId).get();

    if (!userDoc.exists) {
      return res.status(404).json({ success: false, message: 'User not found' });
    }

    const userData = userDoc.data();

    // ========================================================================
    // CALCULATE THIS MONTH'S EARNINGS
    // ========================================================================
    const thisMonthStart = new Date();
    thisMonthStart.setDate(1);
    thisMonthStart.setHours(0, 0, 0, 0);

    const thisMonthOrdersSnapshot = await db
      .collection('orders')
      .where('sellerId', '==', userId)
      .where('status', '==', 'completed')
      .where('completedAt', '>=', thisMonthStart)
      .get();

    let thisMonthEarnings = 0;
    thisMonthOrdersSnapshot.forEach(doc => {
      const order = doc.data();
      thisMonthEarnings += order.price || 0;
    });

    // ========================================================================
    // CALCULATE TOTAL COMPLETED ORDERS & TOTAL EARNED
    // ========================================================================
    const allCompletedOrders = await db
      .collection('orders')
      .where('sellerId', '==', userId)
      .where('status', '==', 'completed')
      .get();

    const totalCompletedOrders = allCompletedOrders.size;

    let calculatedTotalEarned = 0;
    allCompletedOrders.forEach(doc => {
      const order = doc.data();
      calculatedTotalEarned += order.price || 0;
    });

    // ========================================================================
    // CALCULATE UNIQUE CLIENTS
    // ========================================================================
    const clientsSet = new Set();
    allCompletedOrders.forEach(doc => {
      const order = doc.data();
      if (order.buyerId || order.uid) {
        clientsSet.add(order.buyerId || order.uid);
      }
    });
    const totalClients = clientsSet.size;

    // ========================================================================
    // CALCULATE PENDING PAYMENTS (from active/delivered orders)
    // ========================================================================
    const pendingOrdersSnapshot = await db
      .collection('orders')
      .where('sellerId', '==', userId)
      .where('status', 'in', ['active', 'delivered'])
      .get();

    let pendingPayments = 0;
    pendingOrdersSnapshot.forEach(doc => {
      const order = doc.data();
      pendingPayments += order.price || 0;
    });

    // ========================================================================
    // CALCULATE SUCCESS SCORE (0-5)
    // Based on: Completion rate, On-time delivery, No cancellations
    // ========================================================================
    const allOrdersSnapshot = await db
      .collection('orders')
      .where('sellerId', '==', userId)
      .get();

    const totalOrders = allOrdersSnapshot.size;
    let cancelledOrders = 0;
    let lateDeliveries = 0;
    let onTimeDeliveries = 0;

    allOrdersSnapshot.forEach(doc => {
      const order = doc.data();
      if (order.status === 'cancelled' && order.cancelledBy === 'seller') {
        cancelledOrders++;
      }
      if (order.status === 'completed') {
        // Check if delivered on time (before deadline)
        if (order.deliveredAt && order.deadline) {
          const deliveredTime = order.deliveredAt.toDate ? order.deliveredAt.toDate() : new Date(order.deliveredAt);
          const deadlineTime = order.deadline.toDate ? order.deadline.toDate() : new Date(order.deadline);
          if (deliveredTime <= deadlineTime) {
            onTimeDeliveries++;
          } else {
            lateDeliveries++;
          }
        } else {
          // If no deadline tracking, assume on-time
          onTimeDeliveries++;
        }
      }
    });

    let successScore = 5; // Start with perfect score

    if (totalOrders > 0) {
      // Deduct for cancellations (max -2 points)
      const cancellationRate = cancelledOrders / totalOrders;
      if (cancellationRate > 0.2) successScore -= 2;
      else if (cancellationRate > 0.1) successScore -= 1;
      else if (cancellationRate > 0.05) successScore -= 0.5;

      // Deduct for late deliveries (max -2 points)
      if (totalCompletedOrders > 0) {
        const lateRate = lateDeliveries / totalCompletedOrders;
        if (lateRate > 0.3) successScore -= 2;
        else if (lateRate > 0.15) successScore -= 1;
        else if (lateRate > 0.05) successScore -= 0.5;
      }

      // Bonus for high completion rate
      const completionRate = totalCompletedOrders / totalOrders;
      if (completionRate >= 0.95) successScore += 0.5;
    }
    // New seller with no orders keeps perfect score of 5

    successScore = Math.round(Math.max(0, Math.min(5, successScore)));

    // ========================================================================
    // CALCULATE RATING (0-5) - Average from reviews
    // ========================================================================
    const reviewsSnapshot = await db
      .collection('reviews')
      .where('sellerId', '==', userId)
      .get();

    let totalRatingSum = 0;
    let reviewCount = 0;

    reviewsSnapshot.forEach(doc => {
      const review = doc.data();
      if (review.rating && typeof review.rating === 'number') {
        totalRatingSum += review.rating;
        reviewCount++;
      }
    });

    const calculatedRating = reviewCount > 0
      ? Math.round((totalRatingSum / reviewCount) * 10) / 10  // Round to 1 decimal
      : 5; // New users with no reviews get perfect 5 rating

    // ========================================================================
    // CALCULATE RESPONSE RATE (0-100%)
    // Based on: Messages responded to within 1 hour / Total messages received
    // ========================================================================
    const chatsSnapshot = await db
      .collection('chats')
      .where('participants', 'array-contains', userId)
      .get();

    let totalMessagesReceived = 0;
    let messagesRespondedQuickly = 0;

    for (const chatDoc of chatsSnapshot.docs) {
      const chatId = chatDoc.id;

      // Get messages in this chat
      const messagesSnapshot = await db
        .collection('chats')
        .doc(chatId)
        .collection('messages')
        .orderBy('createdAt', 'asc')
        .get();

      const messages = messagesSnapshot.docs.map(doc => ({
        ...doc.data(),
        id: doc.id
      }));

      // Analyze response patterns
      for (let i = 0; i < messages.length; i++) {
        const msg = messages[i];

        // If message was sent TO this user (not by this user)
        if (msg.recipientId === userId || (msg.senderId !== userId && msg.recipientId !== msg.senderId)) {
          totalMessagesReceived++;

          // Check if user responded within 1 hour
          const nextUserMessage = messages.slice(i + 1).find(m => m.senderId === userId);

          if (nextUserMessage && msg.createdAt && nextUserMessage.createdAt) {
            const receivedTime = msg.createdAt.toDate ? msg.createdAt.toDate() : new Date(msg.createdAt);
            const respondedTime = nextUserMessage.createdAt.toDate ? nextUserMessage.createdAt.toDate() : new Date(nextUserMessage.createdAt);

            const responseTimeHours = (respondedTime - receivedTime) / (1000 * 60 * 60);

            if (responseTimeHours <= 1) {
              messagesRespondedQuickly++;
            }
          }
        }
      }
    }

    const responseRate = totalMessagesReceived > 0
      ? Math.round((messagesRespondedQuickly / totalMessagesReceived) * 100)
      : 100; // New users with no messages get 100%

    // ========================================================================
    // CALCULATE LEVEL (1-5)
    // Based on: Orders completed, Total earned, Account age, Reviews
    // New users start with bonus points to reach Level 2
    // ========================================================================
    let levelPoints = 0;

    // Calculate account age first
    const createdAt = userData.createdAt?.toDate ? userData.createdAt.toDate() : new Date(userData.createdAt || Date.now());
    const accountAgeDays = (Date.now() - createdAt.getTime()) / (1000 * 60 * 60 * 24);

    // NEW USER BONUS: Give new users (< 30 days, no orders) starting points
    const isNewUser = totalCompletedOrders === 0 && accountAgeDays < 30;
    if (isNewUser) {
      levelPoints += 20; // Enough to reach Level 2
    }

    // Points from completed orders (max 25 points)
    if (totalCompletedOrders >= 50) levelPoints += 25;
    else if (totalCompletedOrders >= 25) levelPoints += 20;
    else if (totalCompletedOrders >= 10) levelPoints += 15;
    else if (totalCompletedOrders >= 5) levelPoints += 10;
    else if (totalCompletedOrders >= 1) levelPoints += 5;

    // Points from total earnings (max 25 points)
    if (calculatedTotalEarned >= 10000) levelPoints += 25;
    else if (calculatedTotalEarned >= 5000) levelPoints += 20;
    else if (calculatedTotalEarned >= 2000) levelPoints += 15;
    else if (calculatedTotalEarned >= 500) levelPoints += 10;
    else if (calculatedTotalEarned >= 100) levelPoints += 5;

    // Points from account age (max 20 points)
    if (accountAgeDays >= 365) levelPoints += 20;
    else if (accountAgeDays >= 180) levelPoints += 15;
    else if (accountAgeDays >= 90) levelPoints += 10;
    else if (accountAgeDays >= 30) levelPoints += 5;

    // Points from reviews (max 15 points)
    if (reviewCount >= 25) levelPoints += 15;
    else if (reviewCount >= 10) levelPoints += 10;
    else if (reviewCount >= 5) levelPoints += 7;
    else if (reviewCount >= 1) levelPoints += 3;

    // Points from rating (max 15 points)
    if (calculatedRating >= 4.8) levelPoints += 15;
    else if (calculatedRating >= 4.5) levelPoints += 12;
    else if (calculatedRating >= 4.0) levelPoints += 8;
    else if (calculatedRating >= 3.5) levelPoints += 4;

    // Convert points to level (0-100 points -> 1-5 levels)
    let calculatedLevel = 1;
    if (levelPoints >= 80) calculatedLevel = 5;
    else if (levelPoints >= 60) calculatedLevel = 4;
    else if (levelPoints >= 40) calculatedLevel = 3;
    else if (levelPoints >= 20) calculatedLevel = 2;
    else calculatedLevel = 1;

    // ========================================================================
    // UPDATE USER DOCUMENT WITH CALCULATED VALUES
    // ========================================================================
    const updatedMetrics = {
      level: calculatedLevel,
      successScore: successScore,
      rating: calculatedRating,
      responseRate: responseRate,
      totalEarned: calculatedTotalEarned,
      totalReviews: reviewCount,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };

    // Update user document with calculated metrics
    await db.collection('users').doc(userId).update(updatedMetrics);

    // ========================================================================
    // BUILD RESPONSE
    // ========================================================================
    const earnings = {
      // User Info
      firstName: userData.firstName || '',
      lastName: userData.lastName || '',
      profilePicUrl: userData.profilePicUrl || '',

      // Performance Metrics (CALCULATED)
      level: calculatedLevel,
      successScore: successScore,
      rating: calculatedRating,
      responseRate: responseRate,

      // Earnings (CALCULATED)
      currency: 'USD',
      totalEarned: calculatedTotalEarned,
      availableBalance: userData.availableBalance || 0,
      pendingPayments: pendingPayments,
      thisMonthEarnings: thisMonthEarnings,

      // Statistics (CALCULATED)
      totalCompletedOrders: totalCompletedOrders,
      totalClients: totalClients,
      totalReviews: reviewCount,

      // Metadata
      lastUpdated: new Date().toISOString()
    };

    console.log(`📊 Performance metrics calculated for user ${userId}:`, {
      level: calculatedLevel,
      successScore,
      rating: calculatedRating,
      responseRate,
      totalEarned: calculatedTotalEarned,
      totalCompletedOrders,
      totalClients
    });

    res.json({ success: true, earnings });
  } catch (error) {
    console.error('Error fetching earnings:', error);
    res.status(500).json({ success: false, message: 'Failed to fetch earnings', error: error.message });
  }
};
