package com.example.newconstructionappwithlocationtracking.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.newconstructionappwithlocationtracking.R

data class JobSelectionItem(
    val id: String,
    val title: String,
    val description: String,
    val price: Int,
    val location: String,
    val imageUrl: String?
)

class JobSelectionAdapter(
    private var jobs: List<JobSelectionItem>,
    private val onJobClick: (JobSelectionItem) -> Unit
) : RecyclerView.Adapter<JobSelectionAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val jobImage: ImageView = itemView.findViewById(R.id.jobImage)
        val imageLoadingIndicator: ProgressBar = itemView.findViewById(R.id.imageLoadingIndicator)
        val jobTitle: TextView = itemView.findViewById(R.id.jobTitle)
        val jobDescription: TextView = itemView.findViewById(R.id.jobDescription)
        val jobLocation: TextView = itemView.findViewById(R.id.jobLocation)
        val jobPrice: TextView = itemView.findViewById(R.id.jobPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_job_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val job = jobs[position]

        holder.jobTitle.text = job.title
        holder.jobDescription.text = job.description
        holder.jobLocation.text = job.location
        holder.jobPrice.text = "$${job.price}"

        // Load job image
        if (!job.imageUrl.isNullOrEmpty()) {
            holder.imageLoadingIndicator.visibility = View.VISIBLE
            
            Glide.with(holder.itemView.context)
                .load(job.imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_placeholder)
                .into(holder.jobImage)
                .also {
                    holder.imageLoadingIndicator.visibility = View.GONE
                }
        } else {
            holder.imageLoadingIndicator.visibility = View.GONE
            holder.jobImage.setImageResource(R.drawable.ic_image_placeholder)
        }

        // Set click listener
        holder.itemView.setOnClickListener {
            onJobClick(job)
        }
    }

    override fun getItemCount(): Int = jobs.size

    fun updateJobs(newJobs: List<JobSelectionItem>) {
        jobs = newJobs
        notifyDataSetChanged()
    }
}
