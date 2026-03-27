package com.example.newconstructionappwithlocationtracking.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.newconstructionappwithlocationtracking.R

class NotificationFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notification, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val backArrow = view.findViewById<android.widget.ImageView>(R.id.backArrow)
        backArrow?.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }
}
