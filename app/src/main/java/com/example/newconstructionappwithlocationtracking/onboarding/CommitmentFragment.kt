package com.example.newconstructionappwithlocationtracking.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R

/**
 * Step 1: Commitment & Honesty Declaration
 *
 * Explains user must provide accurate and truthful information
 */
class CommitmentFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_commitment, container, false)
    }
}

