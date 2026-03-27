package com.example.newconstructionappwithlocationtracking.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.newconstructionappwithlocationtracking.DeliveryActivity
import com.example.newconstructionappwithlocationtracking.R

class DeliveryFileAdapter(
    private val files: MutableList<DeliveryActivity.DeliveryFile>,
    private val onRemoveFile: (DeliveryActivity.DeliveryFile) -> Unit
) : RecyclerView.Adapter<DeliveryFileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.fileName)
        val removeButton: ImageView = view.findViewById(R.id.removeFileButton)
        val uploadProgress: ProgressBar = view.findViewById(R.id.uploadProgress)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_delivery_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]

        holder.fileName.text = file.fileName

        if (file.isUploading) {
            holder.uploadProgress.visibility = View.VISIBLE
            holder.removeButton.visibility = View.GONE
            holder.fileIcon.alpha = 0.5f
        } else {
            holder.uploadProgress.visibility = View.GONE
            holder.removeButton.visibility = View.VISIBLE
            holder.fileIcon.alpha = 1.0f
        }

        holder.removeButton.setOnClickListener {
            onRemoveFile(file)
        }

        // Set file icon based on file type
        val fileExtension = file.fileName.substringAfterLast(".", "").lowercase()
        when (fileExtension) {
            "jpg", "jpeg", "png", "gif" -> holder.fileIcon.setImageResource(R.drawable.ic_image)
            "pdf" -> holder.fileIcon.setImageResource(R.drawable.ic_pdf)
            "doc", "docx" -> holder.fileIcon.setImageResource(R.drawable.ic_doc)
            else -> holder.fileIcon.setImageResource(R.drawable.ic_file)
        }
    }

    override fun getItemCount() = files.size
}
