package com.tunemind.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase

import com.tunemind.app.domain.coverage.CoverageAssessor
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import com.tunemind.app.domain.research.PatternResearchEvaluator
import com.tunemind.app.domain.signal.TemporalDynamicsEvaluator
import com.tunemind.app.domain.signal.TemporalSignalEvaluator
import com.tunemind.app.domain.coverage.CoverageAssessment
import com.tunemind.app.domain.experiment.ExperimentRepository
import com.tunemind.app.domain.experiment.ExperimentStatus
import com.tunemind.app.domain.experiment.PlaybackIntervalEstimator
import com.tunemind.app.domain.model.PlaybackObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NowPlayingObservation(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val sourcePackage: String? = null,
    val isPlaying: Boolean = false,
    val playbackStateCode: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val updatedAtMs: Long = 0L,
)

data class HomeState(
    val identifiableTrackCount: Int = 0,
    val distinctAppearanceCount: Int = 0,
    val recurringTrackCount: Int = 0,
    val recentTracks: List<TrackMemoryEntry> = emptyList(),
    val nowPlaying: NowPlayingObservation? = null,
    val coverage: CoverageAssessment? = null,
    val isLoading: Boolean = true,
)

data class HistoryState(
    val tracks: List<TrackMemoryEntry> = emptyList(),
    val filterMode: String = "all",
    val isLoading: Boolean = true,
)

data class StudyDayEvidence(
    val dayNumber: Int,
    val dayLabel: String,
    val hasEvents: Boolean,
    val eventCount: Int,
    val identifiableTrackCount: Int,
    val isPast: Boolean,
)

data class ReadinessItem(
    val label: String,
    val explanation: String,
    val isGranted: Boolean,
    val isRequired: Boolean = true,
    val settingsAction: String? = null,
)

data class StudyState(
    val experimentId: String = "",
    val status: ExperimentStatus = ExperimentStatus.NOT_STARTED,
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val totalDays: Int = 7,
    val observationCount: Int = 0,
    val lastObservationMs: Long? = null,
    val observedSources: List<String> = emptyList(),
    val dayEvidence: List<StudyDayEvidence> = emptyList(),
    val daysWithEvents: Int = 0,
    val coverageNotice: String = "",
    val showReadiness: Boolean = false,
    val readinessItems: List<ReadinessItem> = emptyList(),
    val allRequiredGranted: Boolean = false,
)

data class SourceAppSummary(
    val sourcePackage: String,
    val displayName: String,
    val eventCount: Int,
    val observedPlaybackMs: Long,
    val intervalCount: Int,
    val latestEventMs: Long?,
)

data class CrossAppState(
    val sources: List<SourceAppSummary> = emptyList(),
    val totalObservedPlaybackMs: Long = 0,
    val totalEventCount: Int = 0,
    val isLoading: Boolean = true,
)

data class ProfileState(
    val signalText: String = "No signal data yet.",
    val dynamicsText: String = "No dynamics data yet.",
    val researchText: String = "No research data yet.",
    val observationCount: Int = 0,
)

class TuneMindViewModel : ViewModel() {
    private val repo = EventBufferRepository(TuneMindDatabase.getInstance(AppHolder.context!!).eventQueueDao())
    private val experimentRepo = ExperimentRepository(TuneMindDatabase.getInstance(AppHolder.context!!), AppHolder.context)

    private val _homeState = MutableStateFlow(HomeState())
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    private val _historyState = MutableStateFlow(HistoryState())
    val historyState: StateFlow<HistoryState> = _historyState.asStateFlow()

    private val _profileState = MutableStateFlow(ProfileState())
    val profileState: StateFlow<ProfileState> = _profileState.asStateFlow()

    private val _studyState = MutableStateFlow(StudyState())
    val studyState: StateFlow<StudyState> = _studyState.asStateFlow()

    private val _crossAppState = MutableStateFlow(CrossAppState())
    val crossAppState: StateFlow<CrossAppState> = _crossAppState.asStateFlow()

    fun refreshCrossAppData() {
        viewModelScope.launch {
            val exp = experimentRepo.getExperiment()
            if (exp == null || exp.status != ExperimentStatus.ACTIVE.name) {
                _crossAppState.value = CrossAppState(isLoading = false)
                return@launch
            }
            val sources = experimentRepo.getObservedSources(exp.experimentId)
            val summaries = sources.map { source ->
                val events = experimentRepo.getEventsBySource(exp.experimentId, source)
                val result = PlaybackIntervalEstimator.estimate(source, events)
                SourceAppSummary(
                    sourcePackage = source,
                    displayName = source.substringAfterLast(".").replaceFirstChar { it.uppercase() }.ifEmpty { source },
                    eventCount = result.eventCount,
                    observedPlaybackMs = result.observedPlaybackMs,
                    intervalCount = result.intervalCount,
                    latestEventMs = result.latestEventMs,
                )
            }
            _crossAppState.value = CrossAppState(
                sources = summaries,
                totalObservedPlaybackMs = summaries.sumOf { it.observedPlaybackMs },
                totalEventCount = summaries.sumOf { it.eventCount },
                isLoading = false,
            )
        }
    }

    init {
        // Observe experiment state
        viewModelScope.launch {
            experimentRepo.observeExperiment().collect { exp ->
                val status = exp?.status?.let { runCatching { ExperimentStatus.valueOf(it) }.getOrNull() } ?: ExperimentStatus.NOT_STARTED
                _studyState.value = _studyState.value.copy(
                    experimentId = exp?.experimentId ?: "",
                    status = status,
                    startDateMs = exp?.startDateMs,
                    endDateMs = exp?.endDateMs,
                    observationCount = exp?.observationCount ?: 0,
                    lastObservationMs = exp?.lastObservationMs,
                )
            }
        }

        refreshReadiness()

        viewModelScope.launch {
            repo.observeRecent().collect { rows ->
                val memory: ListeningMemory
                val coverage: CoverageAssessment
                withContext(Dispatchers.Default) {
                    val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                    val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                    memory = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)
                    coverage = CoverageAssessor.assessFromRawEvents(rows)
                }

                val summary = memory.summary
                val recent = memory.tracks
                    .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
                    .sortedByDescending { it.lastObservedAppearanceAtMs }.take(5)

                val latestStateObs = rows.firstOrNull { it.playbackStateCode != null }
                val latestTrackMeta = rows.firstOrNull { !it.trackTitle.isNullOrBlank() }
                val isPlayingNow = latestStateObs?.playbackStateCode == 3 // PlaybackState.STATE_PLAYING

                val nowPlayingObs = if (latestTrackMeta != null || latestStateObs != null) {
                    NowPlayingObservation(
                        title = latestTrackMeta?.trackTitle ?: latestStateObs?.trackTitle,
                        artist = latestTrackMeta?.trackArtist ?: latestStateObs?.trackArtist,
                        album = latestTrackMeta?.trackAlbum ?: latestStateObs?.trackAlbum,
                        sourcePackage = latestStateObs?.sourcePackage ?: latestTrackMeta?.sourcePackage,
                        isPlaying = isPlayingNow,
                        playbackStateCode = latestStateObs?.playbackStateCode,
                        positionMs = latestStateObs?.positionMs ?: 0L,
                        durationMs = latestTrackMeta?.durationMs ?: latestStateObs?.durationMs ?: 0L,
                        speed = latestStateObs?.playbackSpeed ?: 1.0f,
                        updatedAtMs = latestStateObs?.eventTimeMs ?: System.currentTimeMillis()
                    )
                } else null

                _homeState.value = HomeState(
                    identifiableTrackCount = summary.identifiableTrackCount,
                    distinctAppearanceCount = summary.distinctReconstructedAppearanceCount,
                    recurringTrackCount = summary.recurringTrackIdentityCount,
                    recentTracks = recent,
                    nowPlaying = nowPlayingObs,
                    coverage = coverage,
                    isLoading = false,
                )

                _historyState.value = HistoryState(
                    tracks = memory.tracks.sortedByDescending { it.lastObservedAppearanceAtMs },
                    isLoading = false,
                )

                // Profile data
                var sigText: String = "No signal data yet."
                var dynText: String = "No dynamics data yet."
                var resText: String = "No research data yet."
                withContext(Dispatchers.Default) {
                    val signalSummary = TemporalSignalEvaluator.evaluate(memory)
                    val dynamicsSummary = TemporalDynamicsEvaluator.evaluate(memory)
                    val researchSummary = PatternResearchEvaluator.evaluate(memory)

                    sigText = signalSummary.trackSignals
                        .filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
                        .sortedByDescending { it.signal.signal }.take(5)
                        .joinToString("\n") { "${it.title ?: it.entryId.take(12)}: %.3f".format(it.signal.signal) }
                        .ifEmpty { "No signal data yet." }

                    dynText = dynamicsSummary.trackDynamics.filter { it.currentEligibleCount > 0 }
                        .sortedByDescending { it.currentSignal }.take(5)
                        .joinToString("\n") { "${it.title ?: it.entryId.take(12)}: ${it.direction.name.lowercase()} (Δ=%+.3f".format(it.signalDelta) + ")" }
                        .let { "$it\n↑${dynamicsSummary.increasingCount} ↓${dynamicsSummary.decreasingCount} →${dynamicsSummary.stableCount}" }

                    resText = buildString {
                        val ra = researchSummary.recurrenceAcceleration
                        appendLine("Acceleration: ${ra.earlierWindow.distinctAppearanceCount} → ${ra.laterWindow.distinctAppearanceCount}")
                        appendLine("Reappearances: ${researchSummary.reappearances.size}")
                        val c = researchSummary.concentration
                        appendLine("Concentration: ${c.distinctIdentifiedTrackCount} tracks, ${c.totalIdentifiedAppearanceCount} appearances")
                        val eq = researchSummary.evidenceQuality
                        appendLine("Identified: ${eq.identifiedTrackCount} · Insufficient: ${eq.insufficientIdentityCount}")
                    }
                }

                // Study day evidence from experiment events
                val expId = _studyState.value.experimentId
                val studyDays = if (expId.isNotEmpty() && _studyState.value.status == ExperimentStatus.ACTIVE) {
                    val eventTimes = runCatching { experimentRepo.getEventTimestamps(expId) }.getOrNull() ?: emptyList()
                    computeStudyDayEvidence(eventTimes, _studyState.value)
                } else emptyList()
                val sources = if (expId.isNotEmpty()) {
                    runCatching { experimentRepo.getSummary(expId)?.observedSources ?: emptyList() }.getOrNull() ?: emptyList()
                } else emptyList()

                _studyState.value = _studyState.value.copy(
                    dayEvidence = studyDays,
                    daysWithEvents = studyDays.count { it.hasEvents },
                    observedSources = sources,
                )

                _profileState.value = ProfileState(
                    signalText = sigText, dynamicsText = dynText, researchText = resText,
                    observationCount = rows.size,
                )
            }
        }
    }

    fun showReadinessScreen() {
        refreshReadiness()
        _studyState.value = _studyState.value.copy(showReadiness = true)
    }

    fun refreshReadiness() {
        val ctx = AppHolder.context ?: return
        val hasAccess = com.tunemind.app.service.NotificationAccess.isEnabled(ctx)
        val connected = com.tunemind.app.service.TuneMindListenerService.isConnected
        val hasCoverage = _homeState.value.coverage?.distinctObserverSessionCount ?: 0 > 0
        val effectiveConnected = connected || hasCoverage
        val items = listOf(
            ReadinessItem("Notification Access", "Required to observe media playback from supported apps", hasAccess, true, "notification_listener"),
            ReadinessItem("Listener Connected", "TuneMind must be actively observing", effectiveConnected, true),
        )
        val allGranted = items.all { if (it.isRequired) it.isGranted else true }
        _studyState.value = _studyState.value.copy(
            showReadiness = true,
            readinessItems = items,
            allRequiredGranted = allGranted,
        )
    }

    fun startStudy() {
        viewModelScope.launch {
            experimentRepo.start(AppHolder.context)
            _studyState.value = _studyState.value.copy(showReadiness = false)
        }
    }

    fun resetStudy() {
        viewModelScope.launch { experimentRepo.reset() }
    }

    fun recordExperimentObservation(observation: PlaybackObservation) {
        val expId = _studyState.value.experimentId
        if (expId.isNotEmpty() && _studyState.value.status == ExperimentStatus.ACTIVE) {
            viewModelScope.launch {
                experimentRepo.recordObservation(observation, expId)
            }
        }
    }

    private fun computeStudyDayEvidence(eventTimestampsMs: List<Long>, study: StudyState): List<StudyDayEvidence> {
        val startMs = study.startDateMs ?: return emptyList()
        val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val days = mutableListOf<StudyDayEvidence>()
        val validTimes = eventTimestampsMs.filter { it > 0L }
        val now = System.currentTimeMillis()

        for (i in 0 until study.totalDays) {
            val dayStart = startMs + i * 86_400_000L
            val dayEnd = dayStart + 86_400_000L
            val dayEvents = validTimes.count { it in dayStart until dayEnd }
            val isPast = dayEnd <= now

            days.add(StudyDayEvidence(
                dayNumber = i + 1,
                dayLabel = dayLabels[i % 7],
                hasEvents = dayEvents > 0,
                eventCount = dayEvents,
                identifiableTrackCount = 0,
                isPast = isPast,
            ))
        }
        return days
    }

    fun setHistoryFilter(mode: String) {
        _historyState.value = _historyState.value.copy(filterMode = mode)
    }

    fun getFilteredTracks(): List<TrackMemoryEntry> {
        val state = _historyState.value
        return when (state.filterMode) {
            "identified" -> state.tracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
            "recurring" -> state.tracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT && it.distinctAppearanceCount >= 2 }
            else -> state.tracks
        }
    }

    fun togglePlayback() {
        com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.togglePlayback()
    }

    fun skipNext() {
        com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.skipNext()
    }

    fun skipPrev() {
        com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.skipPrev()
    }
}

object AppHolder {
    var context: android.content.Context? = null
}

class TuneMindViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TuneMindViewModel() as T
}
