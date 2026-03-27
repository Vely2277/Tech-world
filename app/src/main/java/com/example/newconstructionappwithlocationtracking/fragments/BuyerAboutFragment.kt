package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R
import com.google.android.flexbox.FlexboxLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BuyerAboutFragment : Fragment() {
    private var profileDataJson: JSONObject? = null
    private var profileDataString: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore from savedInstanceState first, then from arguments
        profileDataString = savedInstanceState?.getString("profileDataJson")
            ?: arguments?.getString("profileDataJson")

        profileDataString?.let {
            if (it.isNotEmpty()) {
                try {
                    profileDataJson = JSONObject(it)
                } catch (e: Exception) {
                    android.util.Log.e("BuyerAboutFragment", "Error parsing profile data: ${e.message}")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the profile data string to restore on recreation
        profileDataString?.let { outState.putString("profileDataJson", it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_buyer_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populateUI(view)
    }

    private fun populateUI(view: View) {
        val data = profileDataJson
        if (data == null) {
            android.util.Log.w("BuyerAboutFragment", "Profile data is null, cannot populate UI")
            return
        }

        try {
            val userNameText = view.findViewById<TextView>(R.id.buyerUserNameText)
            val descriptionText = view.findViewById<TextView>(R.id.buyerDescriptionText)
            val locationText = view.findViewById<TextView>(R.id.buyerLocationText)
            val localTimeText = view.findViewById<TextView>(R.id.buyerLocalTimeText)
            val memberSinceText = view.findViewById<TextView>(R.id.buyerMemberSinceText)
            val avgResponseText = view.findViewById<TextView>(R.id.buyerAvgResponseText)
            val recentJobsText = view.findViewById<TextView>(R.id.buyerRecentJobsText)
            val lastActiveText = view.findViewById<TextView>(R.id.buyerLastActiveText)
            val languagesContainer = view.findViewById<FlexboxLayout>(R.id.buyerLanguagesContainer)
            val skillsContainer = view.findViewById<FlexboxLayout>(R.id.buyerSkillsContainer)

            // Name
            val firstName = data.optString("firstName", "User")
            val lastName = data.optString("lastName", "")
            userNameText?.text = "$firstName $lastName".trim()

            // Description
            val description = data.optString("description", "No description added.")
            descriptionText?.text = if (description.isEmpty()) "No description added." else description

            // Location
            val location = data.optString("location", "Pakistan")
            locationText?.text = if (location.isEmpty()) "Pakistan" else location

            // Local Time
            val localTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            localTimeText?.text = localTime

            // Member Since
            val createdAt = data.optJSONObject("createdAt")
            val memberSince = createdAt?.optLong("_seconds")?.let {
                SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(it * 1000))
            } ?: "N/A"
            memberSinceText?.text = "Member since $memberSince"

            // Average Response Time
            val avgResponseTime = data.optString("AverageResponseTime", "1")
            avgResponseText?.text = "Average response time: $avgResponseTime hour"

            // Recent Jobs
            recentJobsText?.text = "You have not completed any jobs yet"

            // Active Status
            val activeStatus = data.optString("ActiveStatus", "N/A")
            lastActiveText?.text = if (activeStatus.isEmpty()) "N/A" else activeStatus

            // Languages - using FlexboxLayout for proper wrapping
            languagesContainer?.let { container ->
                container.removeAllViews()
                val languages = data.optJSONArray("Languages")
                if (languages != null && languages.length() > 0) {
                    for (i in 0 until languages.length()) {
                        val lang = languages.optString(i)
                        val langBox = layoutInflater.inflate(R.layout.language_box, container, false)
                        langBox.findViewById<TextView>(R.id.languageText)?.text = lang
                        langBox.findViewById<TextView>(R.id.languageTag)?.text = "FLUENT"
                        container.addView(langBox)
                    }
                } else {
                    val emptyText = TextView(requireContext()).apply {
                        text = "No languages specified"
                        setTextColor(resources.getColor(android.R.color.darker_gray, null))
                        textSize = 14f
                    }
                    container.addView(emptyText)
                }
            }

            // Skills - using FlexboxLayout for proper wrapping
            skillsContainer?.let { container ->
                container.removeAllViews()
                val skills = data.optJSONArray("skills")
                if (skills != null && skills.length() > 0) {
                    for (i in 0 until skills.length()) {
                        val skill = skills.optString(i)
                        val skillBox = layoutInflater.inflate(R.layout.skill_box, container, false)
                        skillBox.findViewById<TextView>(R.id.skillText)?.text = skill
                        container.addView(skillBox)
                    }
                } else {
                    val emptyText = TextView(requireContext()).apply {
                        text = "No skills specified"
                        setTextColor(resources.getColor(android.R.color.darker_gray, null))
                        textSize = 14f
                    }
                    container.addView(emptyText)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BuyerAboutFragment", "Error populating UI: ${e.message}", e)
        }
    }

    companion object {
        fun newInstance(profileDataJson: JSONObject?): BuyerAboutFragment {
            val fragment = BuyerAboutFragment()
            val args = Bundle()
            args.putString("profileDataJson", profileDataJson?.toString() ?: "")
            fragment.arguments = args
            return fragment
        }
    }
}
