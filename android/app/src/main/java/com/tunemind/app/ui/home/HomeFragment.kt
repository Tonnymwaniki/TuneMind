package com.tunemind.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.google.android.material.card.MaterialCardView
import com.tunemind.app.R
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.coverage.CoverageAssessor
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.study.StudyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repo = EventBufferRepository(TuneMindDatabase.getInstance(requireContext()).eventQueueDao())
        val studyRepo = StudyRepository(TuneMindDatabase.getInstance(requireContext()))
        val nav = view.findNavController()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.observeRecent().collect { rows ->
                    val (memory, coverage) = withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        val mem = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)
                        val cov = CoverageAssessor.assessFromRawEvents(rows)
                        Pair(mem, cov)
                    }
                    view.findViewById<TextView>(R.id.tv_identified).text = memory.summary.identifiableTrackCount.toString()
                    view.findViewById<TextView>(R.id.tv_appearances).text = memory.summary.distinctReconstructedAppearanceCount.toString()
                    view.findViewById<TextView>(R.id.tv_recurring).text = memory.summary.recurringTrackIdentityCount.toString()

                    val recent = memory.tracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
                        .sortedByDescending { it.lastObservedAppearanceAtMs }.take(3)
                    view.findViewById<TextView>(R.id.tv_recent).text = if (recent.isEmpty()) "No listening data yet."
                    else recent.joinToString("\n") { "${it.title ?: "Unknown"} — ${it.artist ?: "Unknown"}" }

                    val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                    view.findViewById<TextView>(R.id.tv_coverage).text = buildString {
                        append("${coverage.distinctObserverSessionCount} session(s)")
                        if (coverage.hasObservationStopEvents) append(" · stopped")
                        if (coverage.retentionMayHavePruned) append(" · may be incomplete")
                        coverage.earliestObservedMs?.let { appendLine("\nFrom: ${fmt.format(java.util.Date(it))}") }
                        coverage.latestObservedMs?.let { appendLine("To: ${fmt.format(java.util.Date(it))}") }
                    }
                } }

                launch { studyRepo.observeStatus().collect { study ->
                    val text = when (study?.status) {
                        "ACTIVE" -> "Study in progress (day ${study.completedDays + 1}/${study.totalDays})"
                        "COMPLETED" -> "Study completed — view your report"
                        else -> "Your 7-day study hasn't started yet."
                    }
                    view.findViewById<TextView>(R.id.tv_study).text = text
                } }
            }
        }

        view.findViewById<MaterialCardView>(R.id.card_recent).setOnClickListener { nav.navigate(R.id.nav_history) }
        view.findViewById<MaterialCardView>(R.id.card_coverage).setOnClickListener { nav.navigate(R.id.nav_settings) }
        view.findViewById<MaterialCardView>(R.id.card_study).setOnClickListener { nav.navigate(R.id.nav_study) }
    }
}
