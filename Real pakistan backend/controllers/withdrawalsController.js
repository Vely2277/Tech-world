const { getFirebaseAdmin, admin } = require('../config/firebase');
const { sendWithdrawalNotificationToAdmins } = require('../services/emailService');

// Submit withdrawal request
exports.createWithdrawal = async (req, res) => {
    try {
        const {
            amount,
            accountHolderName,
            bankName,
            accountNumber,
            iban,
            bankBranch,
            city,
            phoneNumber,
            notes
        } = req.body;

        const uid = req.user.uid;
        const firebaseApp = getFirebaseAdmin();

        // Validate required fields
        if (!amount || !accountHolderName || !bankName || !accountNumber || !bankBranch || !city || !phoneNumber) {
            return res.status(400).json({
                success: false,
                message: 'Missing required fields'
            });
        }

        // Validate amount is positive
        if (amount <= 0) {
            return res.status(400).json({
                success: false,
                message: 'Invalid withdrawal amount'
            });
        }

        const db = firebaseApp.firestore();

        // Check user's available balance
        const userDoc = await db.collection('users').doc(uid).get();
        if (!userDoc.exists) {
            return res.status(404).json({
                success: false,
                message: 'User not found'
            });
        }

        const userData = userDoc.data();
        const availableBalance = userData.availableBalance || 0;

        if (amount > availableBalance) {
            return res.status(400).json({
                success: false,
                message: 'Insufficient balance for withdrawal'
            });
        }

        // Create withdrawal request
        const withdrawalRef = db.collection('withdrawals').doc();
        const userName = `${userData.firstName || ''} ${userData.lastName || ''}`.trim() || 'Unknown User';
        const userEmail = userData.email || 'No email provided';

        const withdrawalData = {
            id: withdrawalRef.id,
            uid: uid,
            amount: amount,
            accountHolderName: accountHolderName.trim(),
            bankName: bankName.trim(),
            accountNumber: accountNumber.trim(),
            iban: iban ? iban.trim() : '',
            bankBranch: bankBranch.trim(),
            city: city.trim(),
            phoneNumber: phoneNumber.trim(),
            notes: notes ? notes.trim() : '',
            status: 'pending',
            requestedAt: admin.firestore.FieldValue.serverTimestamp(),
            processedAt: null,
            userEmail: userEmail,
            userName: userName,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        };

        // Save withdrawal request
        await withdrawalRef.set(withdrawalData);

        // Update user's available balance (subtract the withdrawal amount)
        await db.collection('users').doc(uid).update({
            availableBalance: admin.firestore.FieldValue.increment(-amount),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // Log the withdrawal for audit trail
        console.log(`Withdrawal request created: ${withdrawalRef.id} for user ${uid}, amount: $${amount}`);

        // Send email notification to admins (async - don't wait for it to complete)
        sendWithdrawalNotificationToAdmins({
            userName: userName,
            userEmail: userEmail,
            userId: uid,
            amount: amount,
            accountHolderName: accountHolderName.trim(),
            bankName: bankName.trim(),
            accountNumber: accountNumber.trim(),
            iban: iban ? iban.trim() : null,
            bankBranch: bankBranch.trim(),
            city: city.trim(),
            phoneNumber: phoneNumber.trim(),
            notes: notes ? notes.trim() : null,
            requestDate: new Date().toISOString()
        }).then(result => {
            if (result.success) {
                console.log(`✅ Withdrawal notification email sent to ${result.sent} admin(s)`);
            } else {
                console.error(`❌ Failed to send withdrawal notification email:`, result.error);
            }
        }).catch(err => {
            console.error('❌ Error sending withdrawal notification email:', err);
        });

        res.json({
            success: true,
            message: 'Withdrawal request submitted successfully',
            withdrawalId: withdrawalRef.id,
            status: 'pending'
        });

    } catch (error) {
        console.error('Error creating withdrawal request:', error);
        res.status(500).json({
            success: false,
            message: 'Internal server error'
        });
    }
};

// Get user's withdrawal history
exports.getWithdrawals = async (req, res) => {
    try {
        const uid = req.user.uid;
        const firebaseApp = getFirebaseAdmin();
        const db = firebaseApp.firestore();

        // Get user's withdrawals ordered by request date (newest first)
        const withdrawalsSnapshot = await db.collection('withdrawals')
            .where('uid', '==', uid)
            .orderBy('requestedAt', 'desc')
            .limit(50)
            .get();

        const withdrawals = [];
        withdrawalsSnapshot.forEach(doc => {
            const data = doc.data();
            withdrawals.push({
                id: data.id,
                amount: data.amount,
                status: data.status,
                bankName: data.bankName,
                accountNumber: data.accountNumber,
                requestedAt: data.requestedAt,
                processedAt: data.processedAt
            });
        });

        res.json({
            success: true,
            withdrawals: withdrawals
        });

    } catch (error) {
        console.error('Error fetching withdrawal history:', error);
        res.status(500).json({
            success: false,
            message: 'Internal server error'
        });
    }
};

// Get withdrawal by ID (for user's own withdrawals only)
exports.getWithdrawalById = async (req, res) => {
    try {
        const uid = req.user.uid;
        const withdrawalId = req.params.withdrawalId;
        const firebaseApp = getFirebaseAdmin();
        const db = firebaseApp.firestore();

        const withdrawalDoc = await db.collection('withdrawals').doc(withdrawalId).get();

        if (!withdrawalDoc.exists) {
            return res.status(404).json({
                success: false,
                message: 'Withdrawal not found'
            });
        }

        const withdrawalData = withdrawalDoc.data();

        // Check if withdrawal belongs to the requesting user
        if (withdrawalData.uid !== uid) {
            return res.status(403).json({
                success: false,
                message: 'Access denied'
            });
        }

        res.json({
            success: true,
            withdrawal: withdrawalData
        });

    } catch (error) {
        console.error('Error fetching withdrawal:', error);
        res.status(500).json({
            success: false,
            message: 'Internal server error'
        });
    }
};

// Admin endpoint to view all withdrawals (for testing - remove in production)
exports.getAllWithdrawals = async (req, res) => {
    try {
        const firebaseApp = getFirebaseAdmin();
        const db = firebaseApp.firestore();

        const withdrawalsSnapshot = await db.collection('withdrawals')
            .orderBy('requestedAt', 'desc')
            .limit(100)
            .get();

        const withdrawals = [];
        withdrawalsSnapshot.forEach(doc => {
            withdrawals.push({
                id: doc.id,
                ...doc.data()
            });
        });

        res.json({
            success: true,
            withdrawals: withdrawals,
            count: withdrawals.length
        });

    } catch (error) {
        console.error('Error fetching all withdrawals:', error);
        res.status(500).json({
            success: false,
            message: 'Internal server error'
        });
    }
};
