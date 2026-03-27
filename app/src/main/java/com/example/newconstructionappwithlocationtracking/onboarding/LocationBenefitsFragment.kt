package com.example.newconstructionappwithlocationtracking.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R

/**
 * Step 3: Location Services Benefits
 *
 * Explains benefits of enabling location services
 */
class LocationBenefitsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_location_benefits, container, false)
    }
}

