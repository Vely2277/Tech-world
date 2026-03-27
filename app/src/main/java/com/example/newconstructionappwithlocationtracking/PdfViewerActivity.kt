package com.example.newconstructionappwithlocationtracking

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var closeButton: ImageView
    private lateinit var pdfTitle: TextView
    private lateinit var loadingProgress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        initViews()
        setupPdfViewer()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        closeButton = findViewById(R.id.closeButton)
        pdfTitle = findViewById(R.id.pdfTitle)
        loadingProgress = findViewById(R.id.loadingProgress)

        closeButton.setOnClickListener { finish() }
    }

    private fun setupPdfViewer() {
        val fileUrl = intent.getStringExtra("FILE_URL") ?: return
        val fileName = intent.getStringExtra("FILE_NAME") ?: "PDF Document"

        pdfTitle.text = fileName

        // Configure WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.setSupportZoom(true)
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadingProgress.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingProgress.visibility = android.view.View.GONE
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                loadingProgress.visibility = android.view.View.GONE
                Toast.makeText(this@PdfViewerActivity, "Failed to load PDF: $description", Toast.LENGTH_LONG).show()
            }
        }

        // Use Google Docs viewer to display PDF
        val googleDocsUrl = "https://docs.google.com/gview?embedded=true&url=$fileUrl"
        webView.loadUrl(googleDocsUrl)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
