const { getFirebaseAdmin, admin: firebaseAdmin } = require('../config/firebase');
const { sendPushNotification } = require('./notificationController');

// Get all orders for the authenticated user (as client or worker)
exports.getOrders = async (req, res) => {
  try {
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get orders where user is client (job poster) and worker (job doer) in parallel
    const [clientSnapshot, workerSnapshot] = await Promise.all([
      admin.firestore()
        .collection('orders')
        .where('uid', '==', userId) // uid is the job owner
        .get(),
      admin.firestore()
        .collection('orders')
        .where('sellerId', '==', userId) // sellerId is the assigned worker
        .get()
    ]);

    // Combine all orders
    const allOrders = [
      ...clientSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() })),
      ...workerSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }))
    ];

    if (allOrders.length === 0) {
      return res.json({
        success: true,
        data: { orders: [] }
      });
    }

    // Extract unique job IDs and user IDs for batch fetching
    const jobIds = [...new Set(allOrders.map(order => order.jobId).filter(Boolean))];
    const userIds = [...new Set(allOrders.map(order => order.uid).filter(Boolean))];

    // Batch fetch all jobs and users in parallel
    const [jobDocs, userDocs] = await Promise.all([
      Promise.all(jobIds.map(jobId => 
        admin.firestore().collection('jobs').doc(jobId).get()
      )),
      Promise.all(userIds.map(userId => 
        admin.firestore().collection('users').doc(userId).get()
      ))
    ]);

    // Create lookup maps for O(1) access
    const jobsMap = new Map();
    jobDocs.forEach((doc, index) => {
      if (doc.exists) {
        jobsMap.set(jobIds[index], doc.data());
      }
    });

    const usersMap = new Map();
    userDocs.forEach((doc, index) => {
      if (doc.exists) {
        usersMap.set(userIds[index], doc.data());
      }
    });

    // Map orders with details using lookup maps
    const ordersWithDetails = allOrders.map(order => {
      const jobData = jobsMap.get(order.jobId);
      const clientData = usersMap.get(order.uid);

      return {
        ...order,
        jobTitle: jobData?.title || 'Construction Project',
        jobDescription: jobData?.description || '',
        jobLocation: jobData?.location || '',
        firstImageUrl: jobData?.imageUrls?.[0] || '',
        buyerUsername: clientData?.name || `${clientData?.firstName || ''} ${clientData?.lastName || ''}`.trim() || 'Client'
      };
    });

    res.json({
      success: true,
      data: {
        orders: ordersWithDetails
      }
    });

  } catch (error) {
    console.error('Error fetching orders:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch orders',
      error: error.message
    });
  }
};

// Get order by ID
exports.getOrderById = async (req, res) => {
  try {
    const { orderId } = req.params;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();

    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Verify user has access to this order
    if (orderData.uid !== userId && orderData.sellerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Access denied'
      });
    }

    res.json({
      success: true,
      order: { id: orderDoc.id, ...orderData }
    });

  } catch (error) {
    console.error('Error fetching order:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch order',
      error: error.message
    });
  }
};

// NEW: Get complete order details with job and user information
exports.getOrderDetails = async (req, res) => {
  try {
    const { orderId } = req.params;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get order document
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();

    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Verify user has access to this order
    if (orderData.uid !== userId && orderData.sellerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Access denied'
      });
    }

    // Get job and buyer details in parallel
    const [jobDoc, buyerDoc] = await Promise.all([
      orderData.jobId ? admin.firestore().collection('jobs').doc(orderData.jobId).get() : null,
      orderData.uid ? admin.firestore().collection('users').doc(orderData.uid).get() : null
    ]);

    const jobData = jobDoc?.exists ? jobDoc.data() : null;
    const buyerData = buyerDoc?.exists ? buyerDoc.data() : null;

    // Construct complete order details
    const completeOrderDetails = {
      id: orderDoc.id,
      ...orderData,
      jobTitle: jobData?.title || 'Construction Project',
      jobDescription: jobData?.description || 'No description available',
      jobPrice: orderData.price || jobData?.price || 0,
      jobLocation: jobData?.location || 'Location not specified',
      jobImageUrls: jobData?.imageUrls || [],
      clientName: buyerData?.name || `${buyerData?.firstName || ''} ${buyerData?.lastName || ''}`.trim() || 'Client',
      clientLocation: jobData?.location || buyerData?.location || 'Location not specified',
      clientProfilePic: buyerData?.profilePicUrl || ''
    };

    res.json({
      success: true,
      data: completeOrderDetails
    });

  } catch (error) {
    console.error('Error fetching order details:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch order details',
      error: error.message
    });
  }
};

// NEW: Deliver order - seller submits work
exports.deliverOrder = async (req, res) => {
  try {
    const { orderId } = req.params;
    const { deliveryText, deliveryFileUrls } = req.body; // Changed to support multiple files
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get order document
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();

    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Verify user is the assigned seller
    if (orderData.sellerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Only the assigned worker can deliver this order'
      });
    }

    // Verify order status is active
    if (orderData.status !== 'active') {
      return res.status(400).json({
        success: false,
        message: 'Order must be active to deliver'
      });
    }

    // Validate delivery content
    if (!deliveryText || deliveryText.trim() === '') {
      return res.status(400).json({
        success: false,
        message: 'Delivery text is required'
      });
    }

    // Update order with delivery information
    const updateData = {
      status: 'delivered',
      deliveryText: deliveryText.trim(),
      deliveredAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    // Support multiple delivery files
    if (deliveryFileUrls && Array.isArray(deliveryFileUrls) && deliveryFileUrls.length > 0) {
      updateData.deliveryFileUrls = deliveryFileUrls;
    }

    await admin.firestore().collection('orders').doc(orderId).update(updateData);

    // Get seller's name and job details for notification
    let sellerName = 'Worker';
    let jobTitle = 'Job';
    try {
      const [sellerDoc, jobDoc] = await Promise.all([
        admin.firestore().collection('users').doc(userId).get(),
        orderData.jobId ? admin.firestore().collection('jobs').doc(orderData.jobId).get() : null
      ]);

      if (sellerDoc.exists) {
        const sellerData = sellerDoc.data();
        sellerName = sellerData.name ||
          `${sellerData.firstName || ''} ${sellerData.lastName || ''}`.trim() ||
          'Worker';
      }

      if (jobDoc?.exists) {
        jobTitle = jobDoc.data().title || 'Job';
      }
    } catch (profileError) {
      console.error('Error fetching profiles for notification:', profileError);
    }

    // Send push notification to buyer (async, don't wait)
    setImmediate(async () => {
      try {
        const notificationData = {
          type: 'order_delivered',
          title: '📦 Order Delivered!',
          body: `${sellerName} has delivered the work for "${jobTitle}". Tap to review.`,
          senderName: sellerName,
          senderId: userId,
          orderId: orderId,
          jobTitle: jobTitle,
          timestamp: new Date().toISOString()
        };

        await sendPushNotification(orderData.uid, notificationData);
        console.log(`📬 Order delivery notification sent to buyer: ${orderData.uid}`);
      } catch (notifError) {
        console.error('Error sending order delivery notification:', notifError);
      }
    });

    res.json({
      success: true,
      message: 'Order delivered successfully',
      data: {
        orderId: orderId,
        status: 'delivered',
        deliveryText: deliveryText.trim(),
        deliveryFileUrls: deliveryFileUrls || [],
        deliveredAt: new Date().toISOString()
      }
    });

  } catch (error) {
    console.error('Error delivering order:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to deliver order',
      error: error.message
    });
  }
};

// NEW: Approve delivery - buyer approves completed work and updates earnings
exports.approveOrder = async (req, res) => {
  try {
    const { orderId } = req.params;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    // Get order document
    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();

    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Verify user is the job owner (buyer)
    if (orderData.uid !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Only the job owner can approve this order'
      });
    }

    // Verify order status is delivered
    if (orderData.status !== 'delivered') {
      return res.status(400).json({
        success: false,
        message: 'Order must be delivered to approve'
      });
    }

    // Start a batch write for atomic updates
    const batch = admin.firestore().batch();

    // Update order status to completed
    const orderRef = admin.firestore().collection('orders').doc(orderId);
    batch.update(orderRef, {
      status: 'completed',
      paymentStatus: 'completed', // Add this line to mark payment as completed
      completedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Update seller's earnings
    const sellerRef = admin.firestore().collection('users').doc(orderData.sellerId);
    const sellerDoc = await sellerRef.get();

    if (sellerDoc.exists) {
      const sellerData = sellerDoc.data();
      const orderPrice = orderData.price || 0;

      // Get current month for thisMonthEarnings calculation
      const currentDate = new Date();
      const currentMonth = currentDate.getMonth();
      const currentYear = currentDate.getFullYear();

      // Check if we need to update thisMonthEarnings (if order was completed this month)
      let thisMonthEarnings = sellerData.thisMonthEarnings || 0;
      const completedAt = new Date(); // Since we're completing it now
      if (completedAt.getMonth() === currentMonth && completedAt.getFullYear() === currentYear) {
        thisMonthEarnings += orderPrice;
      }

      batch.update(sellerRef, {
        totalEarned: (sellerData.totalEarned || 0) + orderPrice,
        availableBalance: (sellerData.availableBalance || 0) + orderPrice,
        thisMonthEarnings: thisMonthEarnings,
        totalCompletedOrders: (sellerData.totalCompletedOrders || 0) + 1,
        updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
      });
    }

    // Execute the batch write
    await batch.commit();

    // Get buyer's name and job details for notification
    let buyerName = 'Client';
    let jobTitle = 'Job';
    const orderPrice = orderData.price || 0;

    try {
      const [buyerDoc, jobDoc] = await Promise.all([
        admin.firestore().collection('users').doc(userId).get(),
        orderData.jobId ? admin.firestore().collection('jobs').doc(orderData.jobId).get() : null
      ]);

      if (buyerDoc.exists) {
        const buyerData = buyerDoc.data();
        buyerName = buyerData.name ||
          `${buyerData.firstName || ''} ${buyerData.lastName || ''}`.trim() ||
          'Client';
      }

      if (jobDoc?.exists) {
        jobTitle = jobDoc.data().title || 'Job';
      }
    } catch (profileError) {
      console.error('Error fetching profiles for notification:', profileError);
    }

    // Send push notification to seller (async, don't wait)
    setImmediate(async () => {
      try {
        const notificationData = {
          type: 'order_approved',
          title: '✅ Payment Released!',
          body: `${buyerName} has approved your work for "${jobTitle}". $${orderPrice} has been added to your balance!`,
          senderName: buyerName,
          senderId: userId,
          orderId: orderId,
          jobTitle: jobTitle,
          amount: orderPrice.toString(),
          timestamp: new Date().toISOString()
        };

        await sendPushNotification(orderData.sellerId, notificationData);
        console.log(`📬 Order approval notification sent to seller: ${orderData.sellerId}`);
      } catch (notifError) {
        console.error('Error sending order approval notification:', notifError);
      }
    });

    res.json({
      success: true,
      message: 'Order approved successfully. Seller earnings have been updated.',
      orderId: orderId
    });

  } catch (error) {
    console.error('Error approving order:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to approve order',
      error: error.message
    });
  }
};

// Create a new order
exports.createOrder = async (req, res) => {
  try {
    const { jobId, sellerId, price } = req.body;
    const userId = req.user.uid; // This will be the job owner (uid)
    const admin = getFirebaseAdmin();

    if (!jobId || !sellerId || !price) {
      return res.status(400).json({
        success: false,
        message: 'jobId, sellerId, and price are required'
      });
    }

    const orderData = {
      jobId,
      uid: userId, // Job owner
      sellerId, // Assigned worker
      price,
      status: 'active',
      paymentStatus: 'pending',
      createdAt: firebaseAdmin.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    };

    const orderRef = await admin.firestore().collection('orders').add(orderData);

    res.json({
      success: true,
      message: 'Order created successfully',
      orderId: orderRef.id
    });

  } catch (error) {
    console.error('Error creating order:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to create order',
      error: error.message
    });
  }
};

// Update order status
exports.updateOrderStatus = async (req, res) => {
  try {
    const { orderId } = req.params;
    const { status } = req.body;
    const userId = req.user.uid;
    const admin = getFirebaseAdmin();

    const orderDoc = await admin.firestore().collection('orders').doc(orderId).get();

    if (!orderDoc.exists) {
      return res.status(404).json({
        success: false,
        message: 'Order not found'
      });
    }

    const orderData = orderDoc.data();

    // Verify user has access to update this order
    if (orderData.uid !== userId && orderData.sellerId !== userId) {
      return res.status(403).json({
        success: false,
        message: 'Access denied'
      });
    }

    await admin.firestore().collection('orders').doc(orderId).update({
      status,
      updatedAt: firebaseAdmin.firestore.FieldValue.serverTimestamp()
    });

    res.json({
      success: true,
      message: 'Order status updated successfully'
    });

  } catch (error) {
    console.error('Error updating order status:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to update order status',
      error: error.message
    });
  }
};
