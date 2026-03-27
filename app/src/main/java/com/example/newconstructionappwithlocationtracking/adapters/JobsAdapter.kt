package com.example.newconstructionappwithlocationtracking.adapters

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.models.Job
import com.example.newconstructionappwithlocationtracking.location.LocationPermissionManager
import com.example.newconstructionappwithlocationtracking.location.JobActionPermissionDialogHelper
import com.squareup.picasso.Picasso
import android.os.Handler
import android.os.Looper
import android.util.Log

class JobsAdapter(private var jobs: List<Job>) : RecyclerView.Adapter<JobsAdapter.JobViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_job_card, parent, false)
        return JobViewHolder(view)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        holder.bind(jobs[position])
    }

    override fun getItemCount(): Int = jobs.size

    fun updateJobs(newJobs: List<Job>) {
        jobs = newJobs
        notifyDataSetChanged()
    }

    inner class JobViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageSlideshow: ViewPager2 = itemView.findViewById(R.id.imageSlideshow)
        private val prevButton: View = itemView.findViewById(R.id.prevButton)
        private val nextButton: View = itemView.findViewById(R.id.nextButton)
        private val imageCounter: TextView = itemView.findViewById(R.id.imageCounter)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val descriptionText: TextView = itemView.findViewById(R.id.descriptionText)
        private val locationText: TextView = itemView.findViewById(R.id.locationText)
        private val datePostedText: TextView = itemView.findViewById(R.id.datePostedText)
        private val priceText: TextView = itemView.findViewById(R.id.priceText)
        private val buyerProfileBtn: View = itemView.findViewById(R.id.buyerProfileBtn)
        private val viewBtn: View = itemView.findViewById(R.id.viewBtn)
        private val applyBtn: View = itemView.findViewById(R.id.applyBtn)

        private var autoSlideHandler: Handler? = null
        private var autoSlideRunnable: Runnable? = null

        fun bind(job: Job) {
            titleText.text = job.title
            descriptionText.text = job.description
            locationText.text = job.location
            datePostedText.text = job.datePosted.toFormattedDate()
            priceText.text = "$${job.price}"

            // Setup image slideshow
            val imageAdapter = ImageSlideshowAdapter(job.imageUrls)
            imageSlideshow.adapter = imageAdapter

            // Setup dynamic image counter based on actual image count
            val totalImages = job.imageUrls.size
            if (totalImages <= 1) {
                imageCounter.visibility = View.GONE
            } else {
                imageCounter.visibility = View.VISIBLE
                imageCounter.text = "1/$totalImages"
            }

            // Update counter when page changes
            imageSlideshow.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (totalImages > 1) {
                        imageCounter.text = "${position + 1}/$totalImages"
                    }
                }
            })

            // Setup navigation buttons
            prevButton.setOnClickListener {
                val currentItem = imageSlideshow.currentItem
                if (currentItem > 0) {
                    imageSlideshow.currentItem = currentItem - 1
                }
            }

            nextButton.setOnClickListener {
                val currentItem = imageSlideshow.currentItem
                if (currentItem < job.imageUrls.size - 1) {
                    imageSlideshow.currentItem = currentItem + 1
                }
            }

            // Auto-slide every 3 seconds
            startAutoSlide(job.imageUrls.size)

            // Button click listeners - with permission checks
            buyerProfileBtn.setOnClickListener {
                handleJobActionWithPermissionCheck(job) {
                    val context = itemView.context
                    val intent = android.content.Intent(context, com.example.newconstructionappwithlocationtracking.BuyerProfileActivity::class.java)
                    intent.putExtra("uid", job.uid)
                    context.startActivity(intent)
                }
            }

            viewBtn.setOnClickListener {
                handleJobActionWithPermissionCheck(job) {
                    val context = itemView.context
                    val intent = android.content.Intent(context, com.example.newconstructionappwithlocationtracking.JobDetailsActivity::class.java)
                    intent.putExtra("jobId", job.id)
                    context.startActivity(intent)
                }
            }

            applyBtn.setOnClickListener {
                handleJobActionWithPermissionCheck(job) {
                val context = itemView.context
                val currentUserUID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

                if (currentUserUID != null) {
                    // Create job details JSON for the chat
                    val jobDetailsJson = org.json.JSONObject().apply {
                        put("jobId", job.id)
                        put("title", job.title)
                        put("description", if (job.description.length > 100)
                            "${job.description.substring(0, 100)}..."
                            else job.description)
                        put("imageUrl", if (job.imageUrls.isNotEmpty()) job.imageUrls[0] else "")
                        put("price", job.price)
                        put("buyerId", job.uid)
                    }

                    // Check if we're already in MainActivity (from GigFragment)
                    if (context is com.example.newconstructionappwithlocationtracking.MainActivity) {
                        // We're already in MainActivity - use fragment navigation to avoid crash
                        val chatId = listOf(currentUserUID, job.uid).sorted().joinToString("_")

                        val chatFragment = com.example.newconstructionappwithlocationtracking.fragments.ChatFragment()
                        val args = android.os.Bundle().apply {
                            putString("chatId", chatId)
                            putString("senderId", currentUserUID)
                            putString("currentUserId", currentUserUID)
                            putString("recipientId", job.uid)
                            putString("username", null)
                            putString("pendingJobDetails", jobDetailsJson.toString())
                        }
                        chatFragment.arguments = args

                        // Use fragment transaction to navigate - preserves back stack
                        context.supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, chatFragment)
                            .addToBackStack(null)
                            .commit()
                    } else {
                        // We're in a different activity (like JobDetailsActivity) - use intent
                        val intent = android.content.Intent(context, com.example.newconstructionappwithlocationtracking.MainActivity::class.java).apply {
                            putExtra("openChat", true)
                            putExtra("recipientId", job.uid)
                            putExtra("currentUserId", currentUserUID)
                            putExtra("jobDetails", jobDetailsJson.toString())
                            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(intent)
                    }
                } else {
                    android.widget.Toast.makeText(context, "Please sign in to apply for jobs", android.widget.Toast.LENGTH_SHORT).show()
                }
                }
            }
        }

        private fun startAutoSlide(imageCount: Int) {
            stopAutoSlide()
            if (imageCount > 1) {
                autoSlideHandler = Handler(Looper.getMainLooper())
                autoSlideRunnable = object : Runnable {
                    override fun run() {
                        val currentItem = imageSlideshow.currentItem
                        val nextItem = if (currentItem == imageCount - 1) 0 else currentItem + 1
                        imageSlideshow.currentItem = nextItem
                        autoSlideHandler?.postDelayed(this, 3000)
                    }
                }
                autoSlideHandler?.postDelayed(autoSlideRunnable!!, 3000)
            }
        }

        private fun stopAutoSlide() {
            autoSlideHandler?.removeCallbacks(autoSlideRunnable!!)
            autoSlideHandler = null
            autoSlideRunnable = null
        }

        /**
         * Handle job action with permission check
         * This enforces location permissions before allowing job actions
         *
         * FLOW:
         * 1. Show loading dialog while checking permission status
         * 2. If no permissions granted: show rationale dialog -> request permission
         * 3. If permission granted: check if GPS is ON
         * 4. If GPS is OFF: show system GPS dialog -> custom dialog if rejected
         * 5. If GPS is ON: proceed with action
         */
        private fun handleJobActionWithPermissionCheck(job: Job, onPermissionsGranted: () -> Unit) {
            val context = itemView.context

            // Must be an Activity to request permissions
            if (context !is Activity) {
                android.widget.Toast.makeText(context, "Unable to check permissions", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val permissionManager = LocationPermissionManager(context)

            // Get the GPS dialog helper from MainActivity (it has the registered launcher)
            val dialogHelper: JobActionPermissionDialogHelper = if (context is com.example.newconstructionappwithlocationtracking.MainActivity) {
                context.gpsDialogHelper
            } else {
                JobActionPermissionDialogHelper(context)
            }

            // Show loading dialog
            val loadingDialog = dialogHelper.showLoadingDialog()

            // Simulate brief check delay (optional, for UX smoothness)
            context.window?.decorView?.postDelayed({
                loadingDialog.dismiss()

                // Check permissions
                val canProceed = permissionManager.checkPermissionsForJobAction(context, object : LocationPermissionManager.JobActionCallback {
                    override fun onPermissionsGranted() {
                        // All permissions granted - now check GPS
                        android.util.Log.d("JOB_CARD_PERMISSION", "✅ All permissions granted - checking GPS...")
                        dialogHelper.resetGpsAttempts()
                        dialogHelper.promptEnableGps(
                            onGpsEnabled = {
                                android.util.Log.d("JOB_CARD_PERMISSION", "✅ GPS is ON - proceeding with action")
                                onPermissionsGranted()
                            },
                            onGpsFlowEnded = {
                                android.util.Log.d("JOB_CARD_PERMISSION", "🏠 GPS flow ended - user returns to page")
                                // User rejected GPS twice - they stay on the page
                            }
                        )
                    }

                    override fun onPermissionDenied(showSettingsDialog: Boolean, message: String) {
                        // Permission denied - DO NOT show dialog here!
                        // The rationale dialog will handle showing the Settings dialog after user clicks Okay
                        android.util.Log.d("JOB_CARD_PERMISSION", "Permission denied in callback, rationale dialog will handle it")
                    }

                    override fun onBackgroundPermissionNeeded() {
                        // Fine location granted, need background permission
                        // Check GPS FIRST, then request background permission
                        android.util.Log.d("JOB_CARD_PERMISSION", "✅ Fine location granted - checking GPS before background permission...")
                        dialogHelper.resetGpsAttempts()
                        dialogHelper.promptEnableGps(
                            onGpsEnabled = {
                                android.util.Log.d("JOB_CARD_PERMISSION", "✅ GPS is ON - now requesting background permission")
                                dialogHelper.showBackgroundPermissionNeededDialog {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        permissionManager.requestBackgroundLocationPermission(context as Activity)
                                    }
                                }
                            },
                            onGpsFlowEnded = {
                                android.util.Log.d("JOB_CARD_PERMISSION", "🏠 GPS flow ended - user returns to page")
                                // User rejected GPS twice - they stay on the page
                            }
                        )
                    }
                })

                // Only handle cases where we need to request FINE location permission
                // Do NOT run this block if fine location is already granted (SCENARIO 3 - background needed)
                // because onBackgroundPermissionNeeded callback already handles that case
                if (!canProceed && !permissionManager.hasFineLocationPermission()) {
                    // Need to request fine location permission
                    // Use GLOBAL denial counter (shared across all features)
                    val globalCount = permissionManager.getGlobalFineLocationDenialCount()
                    val isBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

                    android.util.Log.d("JOB_CARD_PERMISSION", "❌ Permissions not granted")
                    android.util.Log.d("JOB_CARD_PERMISSION", "Global count: $globalCount, isBlocking: $isBlocking")

                    // ALWAYS show rationale dialog first, then check count in the Okay callback
                    dialogHelper.showPermissionRationaleDialog {
                        android.util.Log.d("JOB_CARD_PERMISSION", "===== Rationale Okay clicked =====")

                        // Re-check count when Okay is clicked
                        val currentCount = permissionManager.getGlobalFineLocationDenialCount()
                        val currentBlocking = permissionManager.isAndroidBlockingFineLocationPermission()

                        android.util.Log.d("JOB_CARD_PERMISSION", "After Okay - count: $currentCount, blocking: $currentBlocking")

                        if (currentBlocking) {
                            // After 2+ denials - show "Go to Settings" dialog
                            android.util.Log.d("JOB_CARD_PERMISSION", "🚫 Count >= 2, showing Settings dialog")
                            dialogHelper.showPermissionDeniedDialog(
                                "Geographical area not detected. This job opportunity is not currently applicable in your region. Please try again.\n\nTo apply for jobs and verify you're in the right area, please enable location access in Settings.",
                                true
                            ) {
                                android.util.Log.d("JOB_CARD_PERMISSION", "Opening Settings")
                                permissionManager.openBackgroundLocationSettings(context)
                            }
                        } else {
                            // First or second attempt - request permission
                            android.util.Log.d("JOB_CARD_PERMISSION", "📱 Requesting permission (attempt ${currentCount + 1})")
                            permissionManager.requestFineLocationForJobAction(context)
                        }
                    }
                }
            }, 600) // Small delay to let dialog show briefly
        }
    }
}

class ImageSlideshowAdapter(private val imageUrls: List<String>) : RecyclerView.Adapter<ImageSlideshowAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_slideshow_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(imageUrls[position])
    }

    override fun getItemCount(): Int = imageUrls.size

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.slideshowImage)

        fun bind(imageUrl: String) {
            Picasso.get()
                .load(imageUrl)
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_placeholder)
                .into(imageView)
        }
    }
}
