package com.example.newconstructionappwithlocationtracking.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R

/**
 * Step 2: Profile Information Importance
 *
 * Explains importance of accurate profile completion
 */
class ProfileImportanceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile_importance, container, false)
    }
}

