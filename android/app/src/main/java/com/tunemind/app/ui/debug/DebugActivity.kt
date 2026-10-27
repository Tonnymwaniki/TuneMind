package com.tunemind.app.ui.debug

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.text.method.ScrollingMovementMethod
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tunemind.app.R
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.media.MediaSessionObserver
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.sync.SyncScheduler
import android.util.Log
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ListeningPatternAnalysis
import com.tunemind.app.domain.model.PatternDataNotice
import com.tunemind.app.domain.patterns.ListeningPatternAnalyzer
import com.tunemind.app.domain.signal.TemporalSignalEvaluator
import com.tunemind.app.domain.signal.TemporalDynamicsEvaluator
import com.tunemind.app.domain.research.PatternResearchEvaluator
import com.tunemind.app.domain.snapshot.ListeningIntelligenceBuilder
import com.tunemind.app.domain.snapshot.ListeningIntelligenceSnapshot
import com.tunemind.app.domain.snapshot.TrackSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Local telemetry inspection screen for development and device validation. */
class DebugActivity : AppCompatActivity() {
    private lateinit var repository: EventBufferRepository
    private lateinit var observationAdapter: PlaybackObservationAdapter
    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var observationsView: RecyclerView
    private lateinit var reconstructedView: RecyclerView
    private lateinit var reconstructedAdapter: ReconstructedEpisodeAdapter
    private lateinit var reconstructionNote: TextView
    private lateinit var reconstructedEmptyView: TextView
    private lateinit var memoryPanel: View
    private lateinit var memorySummaryView: TextView
    private lateinit var memoryNoticesView: TextView
    private lateinit var memoryEmptyView: TextView
    private lateinit var memoryAdapter: ListeningMemoryAdapter
    private lateinit var patternPanel: View
    private lateinit var patternsSummaryView: TextView
    private lateinit var patternsRoutinesView: TextView
    private lateinit var patternsNoticesView: TextView
    private lateinit var patternsEmptyView: TextView
    private lateinit var patternAdapter: ListeningPatternAdapter
    private var showReconstructed = false
    private var showMemory = false
    private var showPatterns = false
    /** Captured from the observation collector for coverage assessment. */
    private var currentRawObservations: List<PlaybackObservation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        repository = EventBufferRepository(TuneMindDatabase.getInstance(this).eventQueueDao())
        statusView = findViewById(R.id.tv_permission_status)
        countView = findViewById(R.id.tv_observation_count)
        emptyView = findViewById(R.id.tv_empty_observations)
        observationsView = findViewById(R.id.rv_observations)
        reconstructedView = findViewById(R.id.rv_reconstructed)
        reconstructionNote = findViewById(R.id.tv_reconstruction_note)
        reconstructedEmptyView = findViewById(R.id.tv_empty_reconstructed)
        memoryPanel = findViewById(R.id.memory_panel)
        memorySummaryView = findViewById(R.id.tv_memory_summary)
        memoryNoticesView = findViewById(R.id.tv_memory_notices)
        memoryNoticesView.movementMethod = ScrollingMovementMethod()
        memoryEmptyView = findViewById(R.id.tv_empty_memory)
        patternPanel = findViewById(R.id.pattern_panel)
        patternsSummaryView = findViewById(R.id.tv_patterns_summary)
        patternsRoutinesView = findViewById(R.id.tv_patterns_routines)
        patternsRoutinesView.movementMethod = ScrollingMovementMethod()
        patternsNoticesView = findViewById(R.id.tv_patterns_notices)
        patternsNoticesView.movementMethod = ScrollingMovementMethod()
        patternsEmptyView = findViewById(R.id.tv_empty_patterns)
        observationAdapter = PlaybackObservationAdapter()
        reconstructedAdapter = ReconstructedEpisodeAdapter()
        observationsView.layoutManager = LinearLayoutManager(this)
        observationsView.adapter = observationAdapter
        reconstructedView.layoutManager = LinearLayoutManager(this)
        reconstructedView.adapter = reconstructedAdapter
        memoryAdapter = ListeningMemoryAdapter(::showTrackEvidence)
        findViewById<RecyclerView>(R.id.rv_memory_tracks).apply {
            layoutManager = LinearLayoutManager(this@DebugActivity)
            adapter = memoryAdapter
        }
        patternAdapter = ListeningPatternAdapter()
        findViewById<RecyclerView>(R.id.rv_patterns).apply {
            layoutManager = LinearLayoutManager(this@DebugActivity)
            adapter = patternAdapter
        }

        findViewById<Button>(R.id.btn_show_raw).setOnClickListener { setReconstructionVisible(false) }
        findViewById<Button>(R.id.btn_show_reconstructed).setOnClickListener { setReconstructionVisible(true) }
        findViewById<Button>(R.id.btn_show_memory).setOnClickListener { setMemoryVisible() }
        findViewById<Button>(R.id.btn_show_patterns).setOnClickListener { setPatternsVisible() }

        findViewById<Button>(R.id.btn_grant_notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.btn_clear_observations).setOnClickListener { confirmClear() }

        findViewById<Button>(R.id.btn_sync_now).setOnClickListener {
            SyncScheduler.schedule(this)
            lifecycleScope.launch {
                val count = runCatching { TuneMindDatabase.getInstance(this@DebugActivity).eventQueueDao().unsyncedCount() }.getOrNull() ?: -1
                android.widget.Toast.makeText(this@DebugActivity, "Sync enqueued ($count unsynced)", android.widget.Toast.LENGTH_SHORT).show()
                Log.i("TuneMindDebug", "Sync Now pressed; $count unsynced events")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.observeRecent().collect { rows ->
                    observationAdapter.submit(rows)
                    currentRawObservations = rows
                    emptyView.visibility = if (rows.isEmpty() && !showReconstructed && !showMemory && !showPatterns) View.VISIBLE else View.GONE
                    val analysisZone = ZoneId.systemDefault()
                    val (episodes, memory, patterns) = withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val derivedEpisodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        val derivedMemory = ListeningMemoryBuilder().build(rows, derivedEpisodes, retainedHistoryMayBeIncomplete = truncated)
                        val derivedPatterns = ListeningPatternAnalyzer().analyze(rows, derivedEpisodes, derivedMemory, analysisZone, truncated)
                        Triple(derivedEpisodes, derivedMemory, derivedPatterns)
                    }
                    reconstructedAdapter.submit(episodes)
                    reconstructedEmptyView.isVisible = showReconstructed && episodes.isEmpty()
                    memoryAdapter.submit(memory.tracks)
                    memorySummaryView.text = "Identifiable track identities: ${memory.summary.identifiableTrackCount}\n" +
                        "Distinct reconstructed appearances: ${memory.summary.distinctReconstructedAppearanceCount}\n" +
                        "Recurring track identities (2+ appearances): ${memory.summary.recurringTrackIdentityCount}\n" +
                        "Artist summaries: ${memory.summary.identifiableArtistSummaryCount} · Unidentified appearances: ${memory.summary.unidentifiedAppearanceCount}\n" +
                        "History may be incomplete: ${memory.summary.historyMayBeIncomplete}"
                    memoryNoticesView.text = buildList {
                        if (memory.artists.isNotEmpty()) add("Artist summaries (case-insensitive labels; not verified artist identities):")
                        memory.artists.forEach { add("${it.artistLabel}: ${it.distinctIdentifiedTrackCount} identified tracks, ${it.distinctReconstructedAppearanceCount} reconstructed appearances") }
                        if (memory.notices.isNotEmpty()) add("Data quality: ${memory.notices.joinToString { it.name.lowercase().replace('_', ' ') }}")
                    }.joinToString("\n")
                    val signalSummary = withContext(Dispatchers.Default) {
                        TemporalSignalEvaluator.evaluate(memory)
                    }
                    val signalText = buildString {
                        appendLine("Recurrence signal (half-life: 7 days; weight: 1.0 per appearance)")
                        val withSignal = signalSummary.trackSignals.filter { it.identityBasis != com.tunemind.app.domain.model.TrackIdentityBasis.INSUFFICIENT }.sortedByDescending { it.signal.signal }
                        withSignal.take(5).forEach { entry ->
                            val label = entry.title ?: entry.entryId.take(12)
                            val sig = "%.3f".format(entry.signal.signal)
                            appendLine("  $label: $sig (${entry.signal.eligibleAppearanceCount} appearances)")
                        }
                        if (withSignal.size > 5) appendLine("  ... and ${withSignal.size - 5} more")
                        if (signalSummary.notices.isNotEmpty()) {
                            appendLine("Notices: ${signalSummary.notices.joinToString("; ")}")
                        }
                        appendLine("Evaluation time: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(signalSummary.evaluationTimeMs))}")
                        appendLine("The recurrence signal weights distinct observed track appearances by recency. It is not a play count, preference score, or fatigue diagnosis.")
                    }
                    findViewById<TextView>(R.id.tv_signal_summary).text = signalText

                    val dynamicsSummary = withContext(Dispatchers.Default) {
                        TemporalDynamicsEvaluator.evaluate(memory)
                    }
                    val dynamicsText = buildString {
                        appendLine("Temporal dynamics (window: 7 days, tolerance: 0.05)")
                        val withDynamics = dynamicsSummary.trackDynamics
                            .filter { it.currentEligibleCount > 0 }
                            .sortedByDescending { it.currentSignal }
                        withDynamics.take(5).forEach { entry ->
                            val label = entry.title ?: entry.entryId.take(12)
                            appendLine("  $label: ${"%.3f".format(entry.currentSignal)} (${entry.direction.name.lowercase()}, Δ=${"%+.3f".format(entry.signalDelta)})")
                        }
                        if (withDynamics.size > 5) appendLine("  ... and ${withDynamics.size - 5} more")
                        appendLine("  Increasing: ${dynamicsSummary.increasingCount} · Decreasing: ${dynamicsSummary.decreasingCount} · Stable: ${dynamicsSummary.stableCount} · Insufficient data: ${dynamicsSummary.insufficientDataCount}")
                        appendLine("Temporal direction describes changes in the recency-weighted recurrence signal. It does not establish preference, attention, or listening fatigue.")
                    }
                    // Append to signal summary view
                    findViewById<TextView>(R.id.tv_signal_summary).append("\n\n")
                    findViewById<TextView>(R.id.tv_signal_summary).append(dynamicsText)

                    val researchSummary = withContext(Dispatchers.Default) {
                        PatternResearchEvaluator.evaluate(memory)
                    }
                    val researchText = buildString {
                        appendLine("\nPattern research")
                        appendLine("  Recurrence acceleration (7d window): earlier=${researchSummary.recurrenceAcceleration.earlierWindow.distinctAppearanceCount} later=${researchSummary.recurrenceAcceleration.laterWindow.distinctAppearanceCount} Δ=${"%+d".format(researchSummary.recurrenceAcceleration.absoluteChange)}")
                        appendLine("  Reappearances after 7d gap: ${researchSummary.reappearances.size} track(s)")
                        appendLine("  Concentration: ${researchSummary.concentration.distinctIdentifiedTrackCount} tracks, ${researchSummary.concentration.totalIdentifiedAppearanceCount} appearances")
                        researchSummary.concentration.normalizedConcentration?.let { appendLine("  Normalized concentration: %.3f".format(it)) }
                        val eq = researchSummary.evidenceQuality
                        appendLine("  Evidence: ${eq.identifiedTrackCount} identified, ${eq.insufficientIdentityCount} insufficient, ${eq.conflictingMetadataCount} conflicting")
                        if (eq.historyMayBeIncomplete) appendLine("  History may be incomplete.")
                        appendLine("Pattern research describes recorded observations, not preference, attention, or fatigue.")
                    }
                    findViewById<TextView>(R.id.tv_signal_summary).append(researchText)

                    val snapshot = withContext(Dispatchers.Default) {
                        ListeningIntelligenceBuilder.build(memory, System.currentTimeMillis())
                    }
                    val snapshotText = buildString {
                        appendLine("\nListening intelligence snapshot")
                        appendLine("  Identified tracks: ${snapshot.identifiedTrackCount}/${snapshot.totalTracksInMemory}")
                        appendLine("  Recurrence acceleration: ${snapshot.recurrenceAccelerationEarlierCount} → ${snapshot.recurrenceAccelerationLaterCount} (Δ=${"%+d".format(snapshot.recurrenceAccelerationAbsoluteChange)})")
                        appendLine("  Reappearances: ${snapshot.reappearanceAfterGapCount}")
                        appendLine("  Concentration: ${snapshot.concentrationDistinctTrackCount} tracks, ${snapshot.concentrationIdentifiedAppearanceCount} appearances")
                        snapshot.normalizedConcentration?.let { appendLine("  Normalized concentration: %.3f".format(it)) }
                        appendLine("  Evidence: ${snapshot.evidenceQuality.identifiedTrackCount} identified, ${snapshot.evidenceQuality.insufficientIdentityCount} insufficient")
                        snapshot.evidenceQuality.notices.take(3).forEach { appendLine("  - $it") }
                        appendLine("Snapshot summarizes observed and derived metrics. It does not establish preference, attention, or fatigue.")
                    }
                    findViewById<TextView>(R.id.tv_signal_summary).append(snapshotText)
                    memoryEmptyView.isVisible = showMemory && memory.tracks.isEmpty()
                    val recurring = patterns.trackPatterns.filter { it.recurrenceEligible && it.distinctAppearanceCount >= 2 }
                    patternAdapter.submit(recurring, analysisZone)
                    patternsSummaryView.text = formatPatternCoverage(patterns, analysisZone)
                    patternsRoutinesView.text = formatPatternRoutines(patterns)
                    patternsNoticesView.text = patterns.notices.joinToString("\n", transform = ::formatPatternNotice)
                    patternsEmptyView.isVisible = showPatterns && recurring.isEmpty()
                } }
                launch { repository.observeCount().collect { count ->
                    countView.text = "Stored observations: $count (maximum ${EventBufferRepository.MAX_OBSERVATIONS})"
                } }
            }
        }
    }

    private fun setReconstructionVisible(visible: Boolean) {
        showReconstructed = visible
        showMemory = false
        showPatterns = false
        reconstructedView.isVisible = visible
        observationsView.isVisible = !visible
        memoryPanel.isVisible = false
        patternPanel.isVisible = false
        emptyView.isVisible = !visible && observationAdapter.itemCount == 0
        reconstructionNote.isVisible = visible
        reconstructedEmptyView.isVisible = visible && reconstructedAdapter.itemCount == 0
    }

    private fun setMemoryVisible() {
        showReconstructed = false
        showMemory = true
        showPatterns = false
        observationsView.isVisible = false
        reconstructedView.isVisible = false
        memoryPanel.isVisible = true
        patternPanel.isVisible = false
        emptyView.isVisible = false
        reconstructedEmptyView.isVisible = false
        reconstructionNote.isVisible = false
        memoryEmptyView.isVisible = memoryAdapter.itemCount == 0
    }

    private fun setPatternsVisible() {
        showReconstructed = false
        showMemory = false
        showPatterns = true
        observationsView.isVisible = false
        reconstructedView.isVisible = false
        memoryPanel.isVisible = false
        patternPanel.isVisible = true
        emptyView.isVisible = false
        reconstructedEmptyView.isVisible = false
        reconstructionNote.isVisible = false
        memoryEmptyView.isVisible = false
        patternsEmptyView.isVisible = patternAdapter.itemCount == 0
    }

    private fun formatPatternCoverage(analysis: ListeningPatternAnalysis, zoneId: ZoneId): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.getDefault()).withZone(zoneId)
        val start = analysis.coverage.windowStartMs?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Unavailable"
        val end = analysis.coverage.windowEndMs?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Unavailable"
        val history = when (analysis.coverage.historyStatus) {
            com.tunemind.app.domain.model.PatternHistoryStatus.NO_VALID_WINDOW -> "No valid timestamp window"
            com.tunemind.app.domain.model.PatternHistoryStatus.OBSERVED_WINDOW_ONLY -> "Observed callback window only; lifetime coverage is unknown"
            com.tunemind.app.domain.model.PatternHistoryStatus.RETENTION_LIMIT_REACHED -> "500-record retention limit reached; older history may be missing"
        }
        return "Observed callback window: $start — $end\nTimezone: ${analysis.coverage.timeZoneId}\n" +
            "Raw media-session observations considered: ${analysis.coverage.rawObservationCount}\n" +
            "Supported reconstructed episodes: ${analysis.coverage.usableReconstructedEpisodeCount}\n" +
            "Reconstructed appearances: ${analysis.coverage.usableReconstructedAppearanceCount}\n" +
            "Identifiable track identities: ${analysis.coverage.identifiableTrackIdentityCount}\n$history\n" +
            "Period comparisons: ${analysis.comparisonAvailability.explanation}"
    }

    private fun formatPatternRoutines(analysis: ListeningPatternAnalysis): String = buildString {
        appendLine("Observed playback activity by local day")
        if (analysis.routineByDay.isEmpty()) appendLine("No valid observed dates.")
        analysis.routineByDay.forEach { day ->
            append("${day.date}: ${day.observedMediaSessionEventCount} observed events; ")
            append("${day.reconstructedEpisodeCount} reconstructed episodes")
            if (day.estimatedActivePlaybackMs != null) {
                val estimated = day.estimatedActivePlaybackMs
                append("; estimated active playback ${android.text.format.DateUtils.formatElapsedTime(estimated / 1_000)}")
                if (day.durationStatus == com.tunemind.app.domain.model.DailyDurationStatus.PARTIAL_ESTIMATE) append(" (partial bounded estimate)")
                else append(" (bounded estimate)")
            } else if (day.durationStatus == com.tunemind.app.domain.model.DailyDurationStatus.UNAVAILABLE) {
                append("; active duration unavailable")
            }
            appendLine()
        }
        appendLine("\nObserved media-session events by local hour")
        if (analysis.routineByHour.isEmpty()) appendLine("No valid observed hours.")
        analysis.routineByHour.forEach { hour ->
            appendLine("${hour.localHour.toString().padStart(2, '0')}:00 — ${hour.observedMediaSessionEventCount} observed events")
        }
        append("Episode counts are not plays. Estimated duration reuses callback-bounded episode estimates; cross-day and overlapping bounds are not apportioned.")
    }

    private fun formatPatternNotice(notice: PatternDataNotice): String = when (notice) {
        PatternDataNotice.NO_RAW_OBSERVATIONS -> "No raw observations are retained."
        PatternDataNotice.NO_SUPPORTED_RECONSTRUCTED_EPISODES -> "No reconstructed episodes have supporting retained raw observations."
        PatternDataNotice.NO_IDENTIFIED_TRACKS -> "No identified tracks are supported by this evidence."
        PatternDataNotice.HISTORY_COVERAGE_NOT_PROVEN -> "Callback coverage is incomplete; unobserved periods do not prove no playback."
        PatternDataNotice.RETENTION_LIMIT_REACHED -> "The 500-record retention limit was reached; older history may have been pruned."
        PatternDataNotice.INVALID_OBSERVATION_TIMESTAMP -> "Some timestamps were invalid and excluded from calendar metrics."
        PatternDataNotice.MISSING_SOURCE_PACKAGE -> "Some observations have no source package; cross-app identity cannot be established."
        PatternDataNotice.AMBIGUOUS_RECONSTRUCTION -> "Some episode or track evidence has uncertain boundaries or ordering."
        PatternDataNotice.INSUFFICIENT_TRACK_IDENTITY -> "Some appearances lack sufficient identity and are excluded from recurrence."
        PatternDataNotice.MISSING_TRACK_METADATA -> "Some track metadata is missing."
        PatternDataNotice.RAW_EVIDENCE_UNAVAILABLE -> "Some derived appearances do not link to retained raw observations."
        PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION -> "Some episode durations are unavailable, invalid, or not bounded by callbacks."
        PatternDataNotice.CROSS_DAY_DURATION_NOT_APPORTIONED -> "Cross-day episode estimates are not assigned to individual dates."
        PatternDataNotice.OVERLAPPING_EPISODE_BOUNDS -> "Overlapping observed bounds prevent a non-duplicated daily duration total."
        PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES -> "Some appearance gaps have duplicate or invalid timestamps and are unavailable."
        PatternDataNotice.COMPARISONS_UNAVAILABLE -> "Period comparisons are withheld because capture coverage cannot be measured reliably."
    }

    private fun showTrackEvidence(item: TrackMemoryEntry) {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        val first = item.firstObservedAppearanceAtMs?.let { formatter.format(Date(it)) } ?: "Unavailable"
        val last = item.lastObservedAppearanceAtMs?.let { formatter.format(Date(it)) } ?: "Unavailable"
        val now = System.currentTimeMillis()

        // Build snapshot for this track at current and historical evaluation times
        val earlierEvalTime = now - 7 * 24 * 60 * 60 * 1000L // 7 days ago
        // We need the full memory; compute inline from the latest available data
        val snapshot = runCatching {
            lifecycleScope.launch(Dispatchers.Default) {
                // Build from repository data
            }
        }

        // Use the snapshot if available, otherwise show basic info
        AlertDialog.Builder(this)
            .setTitle(item.title ?: "Unidentified appearance")
            .setMessage(buildString {
                appendLine("=== Identity ===")
                appendLine("Source: ${item.sourcePackage ?: "Unavailable"}")
                appendLine("Identity: ${item.identityBasis} (${item.evidenceQuality})")
                appendLine("Artist: ${item.artist ?: "Unavailable"}")
                appendLine("Album: ${item.album ?: "Unavailable"}")
                appendLine("Provider track ID: ${item.providerTrackId ?: "Unavailable"}")
                appendLine()
                appendLine("=== Appearances ===")
                appendLine("Distinct appearances: ${item.distinctAppearanceCount}")
                appendLine("First: $first")
                appendLine("Last: $last")
                appendLine()
                appendLine("=== Appearance Timeline ===")
                if (item.appearances.isEmpty()) {
                    appendLine("No individual appearance records available.")
                } else {
                    item.appearances.sortedBy { it.firstObservedAtMs }.forEachIndexed { i, app ->
                        val t = formatter.format(Date(app.firstObservedAtMs))
                        val evidence = app.evidenceStatus.name.lowercase().replace('_', ' ')
                        appendLine("  ${i + 1}. $t ($evidence)")
                    }
                }
                appendLine()
                appendLine("=== Recurrence Signal (now) ===")
                // Compute signal for this track at the current time
                val signalNow = runCatching {
                    val timestamps = item.appearances.filter { it.firstObservedAtMs > 0L }.map { it.firstObservedAtMs }
                    com.tunemind.app.domain.signal.TemporalSignalEngine().evaluate(now, timestamps)
                }.getOrNull()
                if (signalNow != null) {
                    appendLine("  Signal: %.3f".format(signalNow.signal))
                    appendLine("  Eligible appearances: ${signalNow.eligibleAppearanceCount}")
                    if (signalNow.futureAppearanceCount > 0) appendLine("  Future appearances: ${signalNow.futureAppearanceCount}")
                    if (signalNow.notices.isNotEmpty()) appendLine("  Notices: ${signalNow.notices.joinToString("; ")}")
                } else {
                    appendLine("  Unavailable")
                }
                appendLine()
                appendLine("=== Historical Comparison (7 days ago → now) ===")
                val signalEarlier = runCatching {
                    val timestamps = item.appearances.filter { it.firstObservedAtMs > 0L }.map { it.firstObservedAtMs }
                    com.tunemind.app.domain.signal.TemporalSignalEngine().evaluate(earlierEvalTime, timestamps)
                }.getOrNull()
                if (signalNow != null && signalEarlier != null) {
                    val delta = signalNow.signal - signalEarlier.signal
                    val direction = when {
                        delta > 0.05 -> "INCREASING"
                        delta < -0.05 -> "DECREASING"
                        else -> "STABLE"
                    }
                    appendLine("  7 days ago: %.3f".format(signalEarlier.signal))
                    appendLine("  Now:         %.3f".format(signalNow.signal))
                    appendLine("  Delta:       %+.3f".format(delta))
                    appendLine("  Direction:   $direction")
                } else {
                    appendLine("  Historical data unavailable.")
                }
                appendLine()
                appendLine("=== Reappearance After Gap ===")
                val gapResult = runCatching {
                    val timestamps = item.appearances.filter { it.firstObservedAtMs > 0L }.map { it.firstObservedAtMs }
                    com.tunemind.app.domain.research.detectReappearanceAfterGap(
                        entryId = item.entryId, title = item.title, artist = item.artist,
                        appearanceTimestampsMs = timestamps,
                        minGapMs = 7 * 24 * 60 * 60 * 1000L,
                    )
                }.getOrNull()
                if (gapResult != null && gapResult.hasReappearance) {
                    appendLine("  Gap of ${gapResult.gapMs / 86_400_000L} day(s) detected")
                    appendLine("  Earlier: ${formatter.format(Date(gapResult.earlierAppearanceMs))}")
                    appendLine("  Later:   ${formatter.format(Date(gapResult.laterAppearanceMs))}")
                } else {
                    appendLine("  No reappearance after 7-day gap detected.")
                }
                appendLine()
                appendLine("=== Evidence ===")
                appendLine("History may be incomplete: ${item.historyMayBeIncomplete}")
                appendLine("Supporting observation IDs: ${item.supportingObservationIds.size}")
                if (item.appearances.any { it.hasTimestampOrderingAmbiguity }) appendLine("  Has timestamp ambiguity")
                if (item.appearances.any { it.hasObservationGap }) appendLine("  Has observation gap")
                appendLine()
                appendLine()
                appendLine("=== Observation Coverage ===")
                val coverage = runCatching {
                    com.tunemind.app.domain.coverage.CoverageAssessor.assessFromRawEvents(currentRawObservations)
                }.getOrNull()
                if (coverage != null) {
                    if (coverage.earliestObservedMs != null) {
                        appendLine("  Earliest known event: ${formatter.format(Date(coverage.earliestObservedMs))}")
                    }
                    if (coverage.latestObservedMs != null) {
                        appendLine("  Latest known event:   ${formatter.format(Date(coverage.latestObservedMs))}")
                    }
                    appendLine("  Sessions: ${coverage.distinctObserverSessionCount} known, ${coverage.unassignedEventCount} unassigned")
                    if (coverage.hasObservationStopEvents) {
                        appendLine("  OBSERVATION_STOPPED found (best-effort session-end signals).")
                    } else {
                        appendLine("  No OBSERVATION_STOPPED — session end times unknown.")
                    }
                    if (coverage.retentionMayHavePruned) appendLine("  History may be incomplete (500-event limit).")
                    // Show per-session summary (up to 3 sessions)
                    coverage.sessionCoverages.take(3).forEach { sc ->
                        val earliest = sc.earliestMs?.let { formatter.format(Date(it)) } ?: "?"
                        val latest = sc.latestMs?.let { formatter.format(Date(it)) } ?: "?"
                        appendLine("  Session ${sc.sessionId.take(8)}: ${sc.totalEventCount} events, $earliest — $latest${if (sc.hasObservationStop) " (stopped)" else " (open)"}")
                    }
                    if (coverage.sessionCoverages.size > 3) {
                        appendLine("  ... and ${coverage.sessionCoverages.size - 3} more session(s)")
                    }
                    coverage.notices.take(2).forEach { appendLine("  - $it") }
                }
                appendLine("Event timestamps do not prove continuous observation between recorded events.")
            })
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasAccess = NotificationAccess.isEnabled(this)
        val serviceStatus = if (TuneMindListenerService.isConnected) "Listener service: connected" else "Listener service: not connected"
        statusView.text = if (hasAccess) {
            "Notification Access: enabled\n$serviceStatus\n${MediaSessionObserver.diagnosticStatus}"
        } else {
            "Notification Access: not enabled\n$serviceStatus\n${MediaSessionObserver.diagnosticStatus}\nGrant access in Android Settings to capture playback observations."
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear local observations?")
            .setMessage("This permanently deletes the observations stored on this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> lifecycleScope.launch { repository.clear() } }
            .show()
    }
}
