package com.example.newconstructionappwithlocationtracking.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.material.button.MaterialButton

/**
 * Utility class to handle error states and connectivity issues across all fragments/activities
 * Provides a consistent user experience when network errors occur
 */
object ErrorStateManager {

    // Default timeout for network requests (30 seconds)
    const val DEFAULT_TIMEOUT_SECONDS = 30L

    // Timeout for slower connections
    const val EXTENDED_TIMEOUT_SECONDS = 45L

    /**
     * Check if device has internet connectivity
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Add error state view to a container
     * @param container The parent ViewGroup to add error state to
     * @param inflater LayoutInflater to use
     * @return The error container view for later manipulation
     */
    fun addErrorStateToContainer(
        container: ViewGroup,
        inflater: LayoutInflater
    ): View {
        // Check if already added
        val existingError = container.findViewById<View>(R.id.errorContainer)
        if (existingError != null) {
            return existingError
        }

        val errorView = inflater.inflate(R.layout.layout_error_state, container, false)
        container.addView(errorView)
        return errorView
    }

    /**
     * Show error state with custom message and retry action
     * @param errorContainer The error container view
     * @param contentView The main content view to hide
     * @param loadingView Optional loading view to hide
     * @param title Error title
     * @param message Error message
     * @param onRetry Callback when retry button is clicked
     */
    fun showError(
        errorContainer: View,
        contentView: View?,
        loadingView: View? = null,
        title: String = "Connection Error",
        message: String = "Unable to load data. Please check your internet connection and try again.",
        iconResId: Int = R.drawable.ic_no_connection,
        onRetry: () -> Unit
    ) {
        // Hide loading and content
        loadingView?.visibility = View.GONE
        contentView?.visibility = View.GONE

        // Configure error view
        errorContainer.apply {
            visibility = View.VISIBLE

            findViewById<TextView>(R.id.errorTitle)?.text = title
            findViewById<TextView>(R.id.errorMessage)?.text = message
            findViewById<ImageView>(R.id.errorIcon)?.setImageResource(iconResId)

            findViewById<MaterialButton>(R.id.retryButton)?.setOnClickListener {
                onRetry()
            }
        }
    }

    /**
     * Hide error state and show content
     */
    fun hideError(
        errorContainer: View,
        contentView: View?
    ) {
        errorContainer.visibility = View.GONE
        contentView?.visibility = View.VISIBLE
    }

    /**
     * Show loading state (hide error and content, show loading)
     */
    fun showLoading(
        errorContainer: View?,
        contentView: View?,
        loadingView: View?
    ) {
        errorContainer?.visibility = View.GONE
        contentView?.visibility = View.GONE
        loadingView?.visibility = View.VISIBLE
    }

    /**
     * Show content (hide error and loading)
     */
    fun showContent(
        errorContainer: View?,
        contentView: View?,
        loadingView: View?
    ) {
        errorContainer?.visibility = View.GONE
        loadingView?.visibility = View.GONE
        contentView?.visibility = View.VISIBLE
    }

    /**
     * Get appropriate error message based on exception type
     */
    fun getErrorMessage(exception: Exception?): String {
        return when {
            exception == null -> "An unknown error occurred. Please try again."
            exception is java.net.UnknownHostException ->
                "Unable to connect to the server. Please check your internet connection."
            exception is java.net.SocketTimeoutException ->
                "The request timed out. Please check your connection and try again."
            exception is java.net.ConnectException ->
                "Could not connect to the server. Please try again later."
            exception is javax.net.ssl.SSLException ->
                "Secure connection failed. Please try again."
            exception.message?.contains("timeout", ignoreCase = true) == true ->
                "The request timed out. Please check your connection and try again."
            else -> "Something went wrong. Please try again."
        }
    }

    /**
     * Get appropriate error title based on exception type
     */
    fun getErrorTitle(exception: Exception?): String {
        return when {
            exception == null -> "Error"
            exception is java.net.UnknownHostException -> "No Internet Connection"
            exception is java.net.SocketTimeoutException -> "Connection Timeout"
            exception is java.net.ConnectException -> "Connection Failed"
            else -> "Connection Error"
        }
    }
}
