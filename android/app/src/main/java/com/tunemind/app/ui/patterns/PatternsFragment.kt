package com.tunemind.app.ui.patterns

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

class PatternsFragment : Fragment() {
    private lateinit var repository: EventBufferRepository
    private lateinit var tvSignal: TextView
    private lateinit var tvDynamics: TextView
    private lateinit var tvEvidence: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_patterns, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = EventBufferRepository(TuneMindDatabase.getInstance(requireContext()).eventQueueDao())
        tvSignal = view.findViewById(R.id.tv_signal)
        tvDynamics = view.findViewById(R.id.tv_dynamics)
        tvEvidence = view.findViewById(R.id.tv_evidence)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.observeRecent().collect { rows ->
                    val (signalText, dynamicsText, evidenceText) = withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        val memory = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)

                        val signalSummary = TemporalSignalEvaluator.evaluate(memory)
                        val dynamicsSummary = TemporalDynamicsEvaluator.evaluate(memory)
                        val researchSummary = PatternResearchEvaluator.evaluate(memory)

                        val sig = buildString {
                            val withSignal = signalSummary.trackSignals
                                .filter { it.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT }
                                .sortedByDescending { it.signal.signal }.take(5)
                            if (withSignal.isEmpty()) { append("No signal data yet.") }
                            else withSignal.forEachIndexed { i, entry ->
                                val label = entry.title ?: entry.entryId.take(12)
                                appendLine("${i + 1}. $label: %.3f".format(entry.signal.signal))
                            }
                        }
                        val dyn = buildString {
                            val withDyn = dynamicsSummary.trackDynamics
                                .filter { it.currentEligibleCount > 0 }
                                .sortedByDescending { it.currentSignal }.take(5)
                            if (withDyn.isEmpty()) { append("No dynamics data yet.") }
                            else withDyn.forEachIndexed { i, entry ->
                                val label = entry.title ?: entry.entryId.take(12)
                                appendLine("${i + 1}. $label: ${entry.direction.name.lowercase()} (Δ=${"%+.3f".format(entry.signalDelta)})")
                            }
                            appendLine()
                            appendLine("Increasing: ${dynamicsSummary.increasingCount} · Decreasing: ${dynamicsSummary.decreasingCount} · Stable: ${dynamicsSummary.stableCount}")
                        }
                        val ev = buildString {
                            appendLine("Recurrence acceleration: earlier=${researchSummary.recurrenceAcceleration.earlierWindow.distinctAppearanceCount} later=${researchSummary.recurrenceAcceleration.laterWindow.distinctAppearanceCount}")
                            appendLine("Reappearances after gap: ${researchSummary.reappearances.size}")
                            appendLine("Concentration: ${researchSummary.concentration.distinctIdentifiedTrackCount} tracks, ${researchSummary.concentration.totalIdentifiedAppearanceCount} appearances")
                            researchSummary.concentration.normalizedConcentration?.let { appendLine("Normalized concentration: %.3f".format(it)) }
                            val eq = researchSummary.evidenceQuality
                            appendLine("Identified: ${eq.identifiedTrackCount} · Insufficient: ${eq.insufficientIdentityCount} · Conflicting: ${eq.conflictingMetadataCount}")
                        }
                        Triple(sig.toString(), dyn.toString(), ev.toString())
                    }
                    tvSignal.text = signalText
                    tvDynamics.text = dynamicsText
                    tvEvidence.text = evidenceText
                } }
            }
        }
    }
}
