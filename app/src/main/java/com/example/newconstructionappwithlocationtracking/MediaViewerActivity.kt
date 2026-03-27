package com.example.newconstructionappwithlocationtracking

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource

class MediaViewerActivity : AppCompatActivity() {

    private lateinit var closeButton: ImageView
    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView
    private lateinit var playButton: ImageView
    private lateinit var mediaTitle: TextView
    private lateinit var loadingProgress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)

        // Hide system UI for fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        initViews()
        setupMedia()
    }

    private fun initViews() {
        closeButton = findViewById(R.id.closeButton)
        imageView = findViewById(R.id.imageView)
        videoView = findViewById(R.id.videoView)
        playButton = findViewById(R.id.playButton)
        mediaTitle = findViewById(R.id.mediaTitle)
        loadingProgress = findViewById(R.id.loadingProgress)

        closeButton.setOnClickListener { finish() }
    }

    private fun setupMedia() {
        val fileUrl = intent.getStringExtra("FILE_URL") ?: return
        val fileName = intent.getStringExtra("FILE_NAME") ?: "Media File"

        mediaTitle.text = fileName

        if (isImageFile(fileUrl)) {
            setupImageView(fileUrl)
        } else if (isVideoFile(fileUrl)) {
            setupVideoView(fileUrl)
        }
    }

    private fun setupImageView(imageUrl: String) {
        videoView.visibility = View.GONE
        playButton.visibility = View.GONE
        imageView.visibility = View.VISIBLE

        loadingProgress.visibility = View.VISIBLE

        Glide.with(this)
            .load(imageUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    loadingProgress.visibility = View.GONE
                    Toast.makeText(this@MediaViewerActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    loadingProgress.visibility = View.GONE
                    return false
                }
            })
            .into(imageView)
    }

    private fun setupVideoView(videoUrl: String) {
        imageView.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        playButton.visibility = View.VISIBLE

        loadingProgress.visibility = View.VISIBLE

        videoView.setVideoPath(videoUrl)
        videoView.setOnPreparedListener { mediaPlayer ->
            loadingProgress.visibility = View.GONE
            mediaPlayer.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        }

        videoView.setOnErrorListener { _, _, _ ->
            loadingProgress.visibility = View.GONE
            Toast.makeText(this, "Failed to load video", Toast.LENGTH_SHORT).show()
            true
        }

        playButton.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                playButton.setImageResource(android.R.drawable.ic_media_play)
            } else {
                videoView.start()
                playButton.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        videoView.setOnCompletionListener {
            playButton.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun isImageFile(url: String): Boolean {
        val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        return imageExtensions.any { url.lowercase().contains(it) }
    }

    private fun isVideoFile(url: String): Boolean {
        val videoExtensions = listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm")
        return videoExtensions.any { url.lowercase().contains(it) }
    }
}
