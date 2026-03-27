package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.R
import com.example.newconstructionappwithlocationtracking.adapters.JobsAdapter
import com.example.newconstructionappwithlocationtracking.api.ApiClient
import com.example.newconstructionappwithlocationtracking.api.JobsApiService
import com.example.newconstructionappwithlocationtracking.models.Job
import com.example.newconstructionappwithlocationtracking.utils.ErrorStateManager
import kotlinx.coroutines.launch

class GigFragment : Fragment() {

    private lateinit var jobsRecyclerView: RecyclerView
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var errorContainer: FrameLayout
    private lateinit var jobsAdapter: JobsAdapter
    private lateinit var jobsApiService: JobsApiService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_gig, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupRecyclerView()
        setupClickListeners(view)
        setupApiService()
        fetchJobs()
    }

    override fun onResume() {
        super.onResume()
        checkAutoStartSettings()
    }

    private fun checkAutoStartSettings() {
        val context = context ?: return

        if (!com.example.newconstructionappwithlocationtracking.location.OemBatteryHandler.isAggressiveOem()) {
            return
        }

        val prefs = context.getSharedPreferences("autostart_prefs", android.content.Context.MODE_PRIVATE)
        val lastShownTime = prefs.getLong("autostart_dialog_shown", 0L)
        val hoursSinceLastShown = (System.currentTimeMillis() - lastShownTime) / (1000 * 60 * 60)

        if (hoursSinceLastShown < 24) {
            return
        }

        showAutoStartDialog()
    }

    private fun showAutoStartDialog() {
        val context = context ?: return
        val oemInfo = com.example.newconstructionappwithlocationtracking.location.OemBatteryHandler.detectOem()

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("⚠️ Important Setup Required")
            .setMessage(
                "For reliable job notifications and location tracking, " +
                "please enable AutoStart for Construct Connect.\n\n" +
                (oemInfo.guidance ?: "Enable AutoStart in your device settings.")
            )
            .setPositiveButton("Enable Now") { dialog, _ ->
                val opened = com.example.newconstructionappwithlocationtracking.location.OemBatteryHandler.openBatterySettings(context)
                if (opened) {
                    val prefs = context.getSharedPreferences("autostart_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putLong("autostart_dialog_shown", System.currentTimeMillis()).apply()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Remind Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun initializeViews(view: View) {
        jobsRecyclerView = view.findViewById(R.id.jobsRecyclerView)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)
        loadingLayout = view.findViewById(R.id.loadingLayout)
        errorContainer = view.findViewById(R.id.errorContainer)
    }

    private fun setupRecyclerView() {
        jobsAdapter = JobsAdapter(emptyList())
        jobsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = jobsAdapter
        }
    }

    private fun setupClickListeners(view: View) {
        val bellIcon = view.findViewById<ImageView>(R.id.bellIcon)
        bellIcon?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, NotificationFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupApiService() {
        val apiClient = ApiClient.getClient(requireContext())
        jobsApiService = apiClient.create(JobsApiService::class.java)
    }

    private fun fetchJobs() {
        showLoading()

        // Check connectivity first
        if (!ErrorStateManager.isNetworkAvailable(requireContext())) {
            showNetworkError(null)
            return
        }

        lifecycleScope.launch {
            try {
                val response = jobsApiService.getJobs()

                if (response.isSuccessful) {
                    val jobsResponse = response.body()
                    if (jobsResponse?.success == true) {
                        val jobs = jobsResponse.data.jobs
                        handleJobsResponse(jobs)
                    } else {
                        showNetworkError(Exception(jobsResponse?.message ?: "Failed to load jobs"))
                    }
                } else {
                    showNetworkError(Exception("Network error: ${response.code()}"))
                }
            } catch (e: Exception) {
                showNetworkError(e)
            }
        }
    }

    private fun showNetworkError(exception: Exception?) {
        ErrorStateManager.showError(
            errorContainer = errorContainer,
            contentView = jobsRecyclerView,
            loadingView = loadingLayout,
            title = ErrorStateManager.getErrorTitle(exception),
            message = ErrorStateManager.getErrorMessage(exception),
            onRetry = { fetchJobs() }
        )
        emptyStateLayout.visibility = View.GONE
    }

    private fun handleJobsResponse(jobs: List<Job>) {
        if (jobs.isEmpty()) {
            showEmptyState()
        } else {
            showJobs(jobs)
        }
    }

    private fun showLoading() {
        errorContainer.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        jobsRecyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.GONE
    }

    private fun showJobs(jobs: List<Job>) {
        jobsAdapter.updateJobs(jobs)
        errorContainer.visibility = View.GONE
        jobsRecyclerView.visibility = View.VISIBLE
        loadingLayout.visibility = View.GONE
        emptyStateLayout.visibility = View.GONE
    }

    private fun showEmptyState() {
        errorContainer.visibility = View.GONE
        emptyStateLayout.visibility = View.VISIBLE
        jobsRecyclerView.visibility = View.GONE
        loadingLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
