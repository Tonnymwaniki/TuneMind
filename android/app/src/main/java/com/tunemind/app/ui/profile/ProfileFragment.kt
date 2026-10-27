package com.tunemind.app.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.tunemind.app.R
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.research.PatternResearchEvaluator
import com.tunemind.app.domain.signal.TemporalDynamicsEvaluator
import com.tunemind.app.domain.signal.TemporalSignalEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pager = view.findViewById<ViewPager2>(R.id.viewpager)
        val tabs = view.findViewById<TabLayout>(R.id.tabs)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = ProfileTabFragment.newInstance(when(position) {
                0 -> "overview"; 1 -> "trends"; else -> "evidence"
            })
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = when(position) { 0 -> "Overview"; 1 -> "Trends"; else -> "Evidence" }
        }.attach()
    }
}

class ProfileTabFragment : Fragment() {
    companion object {
        private const val ARG_TAB = "tab"
        fun newInstance(tab: String) = ProfileTabFragment().apply {
            arguments = Bundle().apply { putString(ARG_TAB, tab) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply { setPadding(16, 8, 16, 16) }
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(layout)

        val tab = arguments?.getString(ARG_TAB) ?: "overview"
        val repo = EventBufferRepository(TuneMindDatabase.getInstance(requireContext()).eventQueueDao())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.observeRecent().collect { rows ->
                    val (sig, dyn, res) = withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        val memory = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)

                        val signalSummary = TemporalSignalEvaluator.evaluate(memory)
                        val dynamicsSummary = TemporalDynamicsEvaluator.evaluate(memory)
                        val researchSummary = PatternResearchEvaluator.evaluate(memory)

                        val sigText = signalSummary.trackSignals
                            .filter { it.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT }
                            .sortedByDescending { it.signal.signal }.take(5)
                            .joinToString("\n") { "${it.title ?: it.entryId.take(12)}: %.3f".format(it.signal.signal) }
                            .ifEmpty { "No signal data yet." }

                        val dynText = dynamicsSummary.trackDynamics.filter { it.currentEligibleCount > 0 }
                            .sortedByDescending { it.currentSignal }.take(5)
                            .joinToString("\n") { "${it.title ?: it.entryId.take(12)}: ${it.direction.name.lowercase()} (Δ=%+.3f".format(it.signalDelta) + ")" }
                            .let { "$it\n↑${dynamicsSummary.increasingCount} ↓${dynamicsSummary.decreasingCount} →${dynamicsSummary.stableCount}" }

                        val resText = buildString {
                            val ra = researchSummary.recurrenceAcceleration
                            appendLine("Acceleration: ${ra.earlierWindow.distinctAppearanceCount} → ${ra.laterWindow.distinctAppearanceCount}")
                            appendLine("Reappearances: ${researchSummary.reappearances.size}")
                            val c = researchSummary.concentration
                            appendLine("Concentration: ${c.distinctIdentifiedTrackCount} tracks, ${c.totalIdentifiedAppearanceCount} appearances")
                            val eq = researchSummary.evidenceQuality
                            appendLine("Identified: ${eq.identifiedTrackCount} · Insufficient: ${eq.insufficientIdentityCount}")
                        }
                        Triple(sigText, dynText, resText.toString())
                    }

                    layout.removeAllViews()
                    val content = when(tab) {
                        "overview" -> "Recurrence Signal\n\n$sig\n\nDynamics\n\n$dyn"
                        "trends" -> "Temporal Trends\n\n$dyn\n\nPattern Research\n\n$res"
                        else -> "Evidence Quality\n\n$res\n\nObservations: ${rows.size}"
                    }
                    layout.addView(TextView(requireContext()).apply {
                        text = content; setTextColor(resources.getColor(R.color.tunemind_text_secondary, null)); textSize = 13f
                    })
                } }
            }
        }
        return scroll
    }
}
