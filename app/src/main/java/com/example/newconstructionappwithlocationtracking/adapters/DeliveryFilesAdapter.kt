package com.example.newconstructionappwithlocationtracking.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.newconstructionappwithlocationtracking.R

class DeliveryFilesAdapter(
    private val fileUrls: List<String>,
    private val listener: OnFileItemClickListener
) : RecyclerView.Adapter<DeliveryFilesAdapter.FileViewHolder>() {

    interface OnFileItemClickListener {
        fun onFileView(fileUrl: String)
        fun onFileDownload(fileUrl: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_delivery_file_display, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val fileUrl = fileUrls[position]
        holder.bind(fileUrl, listener)
    }

    override fun getItemCount(): Int = fileUrls.size

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileIcon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val downloadIcon: ImageView = itemView.findViewById(R.id.downloadIcon)

        fun bind(fileUrl: String, listener: OnFileItemClickListener) {
            // Extract file name from URL
            val name = fileUrl.substringAfterLast("/").substringBefore("?")
            fileName.text = name.ifEmpty { "Delivery File" }

            // Determine file type and set appropriate icon
            when {
                isImageFile(fileUrl) -> {
                    // Load image thumbnail
                    Glide.with(itemView.context)
                        .load(fileUrl)
                        .thumbnail(0.1f)
                        .centerCrop()
                        .into(fileIcon)
                }
                isPdfFile(fileUrl) -> {
                    fileIcon.setImageResource(android.R.drawable.ic_menu_edit)
                }
                isVideoFile(fileUrl) -> {
                    fileIcon.setImageResource(android.R.drawable.ic_media_play)
                }
                else -> {
                    fileIcon.setImageResource(android.R.drawable.ic_menu_save)
                }
            }

            // Set click listeners - main item click for viewing
            itemView.setOnClickListener {
                listener.onFileView(fileUrl)
            }

            // Download icon click for downloading
            downloadIcon.setOnClickListener {
                listener.onFileDownload(fileUrl)
            }
        }

        private fun isImageFile(url: String): Boolean {
            val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
            return imageExtensions.any { url.toLowerCase().contains(it) }
        }

        private fun isPdfFile(url: String): Boolean {
            return url.toLowerCase().contains("pdf")
        }

        private fun isVideoFile(url: String): Boolean {
            val videoExtensions = listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm")
            return videoExtensions.any { url.toLowerCase().contains(it) }
        }
    }
}
