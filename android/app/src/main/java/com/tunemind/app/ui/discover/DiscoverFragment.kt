package com.tunemind.app.ui.discover

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.google.android.material.card.MaterialCardView
import com.tunemind.app.R

class DiscoverFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_discover, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialCardView>(R.id.card_history)?.setOnClickListener {
            Navigation.findNavController(view).navigate(R.id.nav_history)
        }
    }
}
