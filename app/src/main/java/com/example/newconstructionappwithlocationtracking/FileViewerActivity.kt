package com.example.newconstructionappwithlocationtracking

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView

class FileViewerActivity : AppCompatActivity() {

    private lateinit var backButton: ImageView
    private lateinit var downloadButton: ImageView
    private lateinit var fileNameText: TextView
    private lateinit var loadingLayout: LinearLayout

    // Different viewers for different file types
    private lateinit var imageViewer: ImageView
    private lateinit var videoViewer: PlayerView
    private lateinit var pdfViewer: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorMessage: TextView

    private var fileUrl: String? = null
    private var fileName: String? = null
    private var fileType: String? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_viewer)

        fileUrl = intent.getStringExtra("fileUrl")
        fileName = intent.getStringExtra("fileName")
        fileType = intent.getStringExtra("fileType")

        if (fileUrl == null) {
            Toast.makeText(this, "Invalid file URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupClickListeners()
        loadFile()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        downloadButton = findViewById(R.id.downloadButton)
        fileNameText = findViewById(R.id.fileNameText)
        loadingLayout = findViewById(R.id.loadingLayout)
        imageViewer = findViewById(R.id.imageViewer)
        videoViewer = findViewById(R.id.videoViewer)
        pdfViewer = findViewById(R.id.pdfViewer)
        errorLayout = findViewById(R.id.errorLayout)
        errorMessage = findViewById(R.id.errorMessage)

        // Set file name
        fileNameText.text = fileName ?: "Unknown File"
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            finish()
        }

        downloadButton.setOnClickListener {
            downloadFile()
        }

        // Add retry button functionality
        val retryButton = findViewById<Button>(R.id.retryButton)
        retryButton.setOnClickListener {
            downloadFile()
        }
    }

    private fun loadFile() {
        showLoading()

        when {
            isImageFile(fileUrl!!) -> loadImage()
            isVideoFile(fileUrl!!) -> loadVideo()
            isPdfFile(fileUrl!!) -> loadPdf()
            else -> showUnsupportedFile()
        }
    }

    private fun loadImage() {
        hideAllViewers()
        imageViewer.visibility = View.VISIBLE

        Glide.with(this)
            .load(fileUrl)
            .error(R.drawable.ic_error)
            .into(imageViewer)

        hideLoading()
    }

    private fun loadVideo() {
        hideAllViewers()
        videoViewer.visibility = View.VISIBLE

        try {
            exoPlayer = ExoPlayer.Builder(this).build()
            videoViewer.player = exoPlayer

            val mediaItem = MediaItem.fromUri(Uri.parse(fileUrl))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = false

            hideLoading()
        } catch (e: Exception) {
            showError("Failed to load video: ${e.message}")
        }
    }

    private fun loadPdf() {
        hideAllViewers()
        pdfViewer.visibility = View.VISIBLE

        try {
            // Configure WebView for PDF viewing
            val webSettings: WebSettings = pdfViewer.settings
            webSettings.javaScriptEnabled = true
            webSettings.domStorageEnabled = true
            webSettings.loadWithOverviewMode = true
            webSettings.useWideViewPort = true
            webSettings.builtInZoomControls = true
            webSettings.displayZoomControls = false

            pdfViewer.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    hideLoading()
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    showError("Failed to load PDF: $description")
                }
            }

            // Use Google Docs Viewer for PDF
            val googleDocsUrl = "https://docs.google.com/gview?embedded=true&url=${Uri.encode(fileUrl)}"
            pdfViewer.loadUrl(googleDocsUrl)

        } catch (e: Exception) {
            showError("Failed to load PDF: ${e.message}")
        }
    }

    private fun showUnsupportedFile() {
        hideAllViewers()
        errorLayout.visibility = View.VISIBLE
        errorMessage.text = "This file type is not supported for in-app viewing. You can download it instead."
        hideLoading()
    }

    private fun hideAllViewers() {
        imageViewer.visibility = View.GONE
        videoViewer.visibility = View.GONE
        pdfViewer.visibility = View.GONE
        errorLayout.visibility = View.GONE
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        hideLoading()
        hideAllViewers()
        errorLayout.visibility = View.VISIBLE
        errorMessage.text = message
    }

    private fun downloadFile() {
        val fileName = this.fileName ?: fileUrl!!.substringAfterLast("/").substringBefore("?")
        val request = DownloadManager.Request(Uri.parse(fileUrl))
            .setTitle("Downloading $fileName")
            .setDescription("Please wait...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "delivery_${System.currentTimeMillis()}_$fileName")

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)

        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    private fun isImageFile(url: String): Boolean {
        val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        return imageExtensions.any { url.lowercase().contains(it) }
    }

    private fun isVideoFile(url: String): Boolean {
        val videoExtensions = listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "3gp")
        return videoExtensions.any { url.lowercase().contains(it) }
    }

    private fun isPdfFile(url: String): Boolean {
        return url.lowercase().contains("pdf")
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }
}
