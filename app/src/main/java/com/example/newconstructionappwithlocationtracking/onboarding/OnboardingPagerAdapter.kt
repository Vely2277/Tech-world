package com.example.newconstructionappwithlocationtracking.onboarding

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adapter for Onboarding ViewPager
 * Manages the 3 onboarding screens
 */
class OnboardingPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CommitmentFragment()
            1 -> ProfileImportanceFragment()
            2 -> LocationBenefitsFragment()
            else -> CommitmentFragment()
        }
    }
}

