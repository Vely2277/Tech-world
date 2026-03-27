package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class OrdersFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_orders, container, false)

        // Initialize views
        tabLayout = view.findViewById(R.id.tabLayout)
        viewPager = view.findViewById(R.id.viewPager)

        // Set up ViewPager with adapter
        viewPager.adapter = OrdersPagerAdapter(requireActivity())

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = "Active"
                1 -> tab.text = "Delivered"
                2 -> tab.text = "Completed"
                3 -> tab.text = "Canceled"
            }
        }.attach()

        // Handle bell icon click - placeholder for now
        val bellIcon = view.findViewById<View>(R.id.bellIcon)
        bellIcon.setOnClickListener {
            // TODO: Navigate to notifications when NotificationActivity is created
        }

        return view
    }

    // Inner class for ViewPager adapter
    private inner class OrdersPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> OrderTabFragment.newInstance("active")
                1 -> OrderTabFragment.newInstance("delivered")
                2 -> OrderTabFragment.newInstance("completed")
                3 -> OrderTabFragment.newInstance("canceled")
                else -> OrderTabFragment.newInstance("active")
            }
        }
    }
}

// Fragment for individual order tabs
class OrderTabFragment : Fragment() {
    companion object {
        private const val ARG_TAB_TYPE = "tab_type"
        fun newInstance(tabType: String): OrderTabFragment {
            val fragment = OrderTabFragment()
            val args = Bundle()
            args.putString(ARG_TAB_TYPE, tabType)
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var ordersRecyclerView: RecyclerView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var emptyStateIcon: ImageView
    private lateinit var emptyStateText: TextView
    private lateinit var ordersAdapter: OrdersAdapter
    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val tabType = arguments?.getString(ARG_TAB_TYPE) ?: "active"
        val layoutRes = when (tabType) {
            "active" -> R.layout.tab_orders_active
            "delivered" -> R.layout.tab_orders_delivered
            "completed" -> R.layout.tab_orders_completed
            "canceled" -> R.layout.tab_orders_canceled
            else -> R.layout.tab_orders_active
        }
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ordersRecyclerView = view.findViewById(R.id.ordersRecyclerView)
        loadingLayout = view.findViewById(R.id.loadingLayout)
        emptyStateIcon = view.findViewById(R.id.ordersIcon)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        ordersAdapter = OrdersAdapter(emptyList())
        ordersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        ordersRecyclerView.adapter = ordersAdapter
        loadOrders()
    }

    private fun loadOrders() {
        showLoading()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showEmptyState()
            return
        }
        user.getIdToken(true).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val idToken = task.result?.token
                fetchOrders(idToken)
            } else {
                showEmptyState()
            }
        }
    }

    private fun fetchOrders(idToken: String?) {
        if (idToken == null) {
            showEmptyState()
            return
        }
        val backendUrl = getBackendUrl()
        val request = Request.Builder()
            .url("$backendUrl/api/orders")
            .addHeader("Authorization", "Bearer $idToken")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { showEmptyState() }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    activity?.runOnUiThread { showEmptyState() }
                    return
                }
                val json = JSONObject(body)
                val ordersData = json.optJSONObject("data")
                val orders = ordersData?.optJSONArray("orders") ?: JSONArray()
                val tabType = arguments?.getString(ARG_TAB_TYPE) ?: "active"
                val filteredOrders = mutableListOf<OrderItem>()
                for (i in 0 until orders.length()) {
                    val order = orders.getJSONObject(i)
                    if (order.optString("status") == tabType) {
                        filteredOrders.add(
                            OrderItem(
                                id = order.optString("id", ""),
                                price = order.optInt("price", 0),
                                status = order.optString("status", ""),
                                createdAt = order.optString("createdAt", ""),
                                uid = order.optString("uid", ""), // Changed from buyerUid to uid (matches database)
                                jobId = order.optString("jobId", ""),
                                sellerId = order.optString("sellerId", ""), // Changed from sellerId to sellerUid (matches database sellerId field)
                                jobTitle = order.optString("jobTitle", ""),
                                firstImageUrl = order.optString("firstImageUrl", ""),
                                buyerUsername = order.optString("buyerUsername", "")
                            )
                        )
                    }
                }
                activity?.runOnUiThread {
                    if (filteredOrders.isEmpty()) {
                        showEmptyState()
                    } else {
                        ordersAdapter.updateOrders(filteredOrders)
                        ordersRecyclerView.visibility = View.VISIBLE
                        loadingLayout.visibility = View.GONE
                        emptyStateIcon.visibility = View.GONE
                        emptyStateText.visibility = View.GONE
                    }
                }
            }
        })
    }

    private fun showLoading() {
        ordersRecyclerView.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        emptyStateIcon.visibility = View.GONE
        emptyStateText.visibility = View.GONE
    }

    private fun showEmptyState() {
        ordersRecyclerView.visibility = View.GONE
        loadingLayout.visibility = View.GONE
        emptyStateIcon.visibility = View.VISIBLE
        emptyStateText.visibility = View.VISIBLE
    }

    private fun getBackendUrl(): String {
        // TODO: Read from .env file
        return "https://real-pakistan-backend.onrender.com"
    }

    data class OrderItem(
        val id: String,
        val price: Int,
        val status: String,
        val createdAt: String,
        val uid: String, // Changed from buyerUid to uid
        val jobId: String,
        val sellerId: String, // Changed from sellerId to sellerUid
        val jobTitle: String,
        val firstImageUrl: String,
        val buyerUsername: String
    )

    class OrdersAdapter(private var orders: List<OrderItem>) : RecyclerView.Adapter<OrdersAdapter.OrderViewHolder>() {
        class OrderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val jobTitle: TextView = view.findViewById(R.id.jobTitle)
            val price: TextView = view.findViewById(R.id.price)
            val status: TextView = view.findViewById(R.id.status)
            val createdAt: TextView = view.findViewById(R.id.createdAt)
            val buyerUsername: TextView = view.findViewById(R.id.buyerUsername)
            val orderImage: ImageView = view.findViewById(R.id.orderImage)
            val contactClientButton: androidx.appcompat.widget.AppCompatButton = view.findViewById(R.id.contactClientButton)
            val viewDetailsButton: androidx.appcompat.widget.AppCompatButton = view.findViewById(R.id.viewDetailsButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
            return OrderViewHolder(view)
        }

        override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
            val order = orders[position]

            // Set basic data
            holder.jobTitle.text = order.jobTitle.ifBlank { "Construction Project" }
            holder.price.text = order.price.toString()
            holder.buyerUsername.text = order.buyerUsername.ifBlank { "Client" }

            // Format and set creation date
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val date = inputFormat.parse(order.createdAt)
                holder.createdAt.text = date?.let { outputFormat.format(it) } ?: "Recent"
            } catch (e: Exception) {
                holder.createdAt.text = "Recent"
            }

            // Set status with appropriate styling
            holder.status.text = order.status.uppercase()
            updateStatusBadge(holder, order.status)

            // Load order image
            if (order.firstImageUrl.isNotBlank()) {
                Glide.with(holder.orderImage.context)
                    .load(order.firstImageUrl)
                    .placeholder(R.drawable.ic_orders)
                    .error(R.drawable.ic_orders)
                    .centerCrop()
                    .into(holder.orderImage)
            } else {
                holder.orderImage.setImageResource(R.drawable.ic_orders)
            }

            // Handle contact button click
            holder.contactClientButton.setOnClickListener {
                val context = holder.itemView.context

                // Get current user ID from Firebase Auth
                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

                if (currentUserId != null && context is androidx.fragment.app.FragmentActivity) {
                    // Navigate to chat with the buyer - Fixed to use correct field name
                    val chatId = "${currentUserId}_${order.uid}" // Changed from order.buyerUid to order.uid

                    val chatFragment = com.example.newconstructionappwithlocationtracking.fragments.ChatFragment()
                    val bundle = android.os.Bundle().apply {
                        putString("chatId", chatId)
                        putString("senderId", currentUserId)  // Current user as sender
                        putString("receiverId", order.uid)  // Buyer as receiver - Changed from order.buyerUid to order.uid
                        putString("username", order.buyerUsername.ifBlank { "Client" })  // Pass the buyer's username
                    }
                    chatFragment.arguments = bundle

                    context.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, chatFragment)
                        .addToBackStack(null)
                        .commit()
                } else {
                    // Handle case where user is not logged in or context is wrong
                    android.util.Log.e("OrdersFragment", "Cannot navigate to chat: user not authenticated or wrong context")
                }
            }

            // Handle view details button click
            holder.viewDetailsButton.setOnClickListener {
                val context = holder.itemView.context
                val intent = android.content.Intent(context, com.example.newconstructionappwithlocationtracking.OrderDetailsActivity::class.java)
                intent.putExtra("orderId", order.id) // Fixed: changed from "ORDER_ID" to "orderId"
                context.startActivity(intent)
            }

            // Add ripple effect to the entire card
            holder.itemView.setOnClickListener {
                // Optional: Handle card click for viewing order details
            }
        }

        private fun updateStatusBadge(holder: OrderViewHolder, status: String) {
            val context = holder.itemView.context

            // Find the status badge container (parent LinearLayout of the status TextView)
            val statusParent = holder.status.parent
            if (statusParent is LinearLayout) {
                when (status.lowercase()) {
                    "active" -> {
                        statusParent.background = androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.status_badge_active)
                        holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white))
                    }
                    "delivered" -> {
                        statusParent.background = androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.status_badge_delivered)
                        holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white))
                    }
                    "completed" -> {
                        statusParent.background = androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.status_badge_completed)
                        holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white))
                    }
                    "canceled" -> {
                        statusParent.background = androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.status_badge_canceled)
                        holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white))
                    }
                    else -> {
                        statusParent.background = androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.status_badge_background)
                        holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.white))
                    }
                }
            }
        }

        override fun getItemCount(): Int = orders.size

        fun updateOrders(newOrders: List<OrderItem>) {
            orders = newOrders
            notifyDataSetChanged()
        }
    }
}