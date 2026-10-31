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
import com.tunemind.app.domain.audio.AudioDeviceSessionEntity
import com.tunemind.app.domain.audio.VolumeObservationEntity
import com.tunemind.app.domain.moment.MusicalMomentEntity
import com.tunemind.app.domain.temporal.TimeSlot
import com.tunemind.app.domain.temporal.TimePatternEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MusicalMomentsState(
    val confirmedMoments: List<MusicalMomentEntity> = emptyList(),
    val candidateMoments: List<MusicalMomentEntity> = emptyList(),
    val isLoading: Boolean = false
)

data class HearingInsightsState(
    val recentAudioDevices: List<AudioDeviceSessionEntity> = emptyList(),
    val activeAudioDevice: AudioDeviceSessionEntity? = null,
    val recentVolumeObs: List<VolumeObservationEntity> = emptyList(),
    val estimatedAverageDb: Float = 68f,
    val dailyExposureMinutes: Int = 45,
    val safetyGuidance: String = "Listening exposure within safe acoustic threshold (<80 dB)."
)

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
    private val repo: EventBufferRepository? by lazy {
        AppHolder.context?.let { ctx ->
            EventBufferRepository(TuneMindDatabase.getInstance(ctx).eventQueueDao())
        }
    }
    private val experimentRepo: ExperimentRepository? by lazy {
        AppHolder.context?.let { ctx ->
            ExperimentRepository(TuneMindDatabase.getInstance(ctx), ctx)
        }
    }

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

    private val db: TuneMindDatabase? by lazy {
        AppHolder.context?.let { TuneMindDatabase.getInstance(it) }
    }
    private val momentDao by lazy { db?.musicalMomentDao() }
    private val audioDao by lazy { db?.audioDeviceDao() }

    private val _musicalMomentsState = MutableStateFlow(MusicalMomentsState())
    val musicalMomentsState: StateFlow<MusicalMomentsState> = _musicalMomentsState.asStateFlow()

    private val _hearingInsightsState = MutableStateFlow(HearingInsightsState())
    val hearingInsightsState: StateFlow<HearingInsightsState> = _hearingInsightsState.asStateFlow()

    val currentTimeSlot: TimeSlot get() = TimeSlot.currentSlot()

    fun refreshCrossAppData() {
        viewModelScope.launch {
            val exp = experimentRepo?.getExperiment()
            if (exp == null || exp.status != ExperimentStatus.ACTIVE.name) {
                _crossAppState.value = CrossAppState(isLoading = false)
                return@launch
            }
            val sources = experimentRepo?.getObservedSources(exp.experimentId) ?: emptyList()
            val summaries = sources.map { source ->
                val events = experimentRepo?.getEventsBySource(exp.experimentId, source) ?: emptyList()
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
        // Observe experiment state safely
        viewModelScope.launch {
            try {
                experimentRepo?.observeExperiment()?.collect { exp ->
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
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeExperiment notice: ${t.message}")
            }
        }

        // Observe musical moments
        viewModelScope.launch {
            try {
                momentDao?.observeAll()?.collect { all ->
                    val confirmed = all.filter { it.userConfirmed }
                    val candidates = all.filter { !it.userConfirmed }
                    _musicalMomentsState.value = MusicalMomentsState(
                        confirmedMoments = confirmed,
                        candidateMoments = candidates,
                        isLoading = false
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeMoments notice: ${t.message}")
            }
        }

        // Observe audio devices
        viewModelScope.launch {
            try {
                audioDao?.observeRecentSessions()?.collect { sessions ->
                    val active = sessions.firstOrNull { it.disconnectedAt == null }
                    _hearingInsightsState.value = _hearingInsightsState.value.copy(
                        recentAudioDevices = sessions,
                        activeAudioDevice = active
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeAudio notice: ${t.message}")
            }
        }

        refreshReadiness()
        refreshGeminiState()
        requestGeminiSessionReasoning()

        // Initialize Telemetry Reasoning Report & schedule periodic Worker
        AppHolder.context?.let { ctx ->
            _reasoningReport.value = com.tunemind.app.domain.ai.GeminiReasoningEngine.getLatestReport(ctx)
            com.tunemind.app.domain.ai.GeminiTelemetryReasoningWorker.schedulePeriodic(ctx)
        }
        refreshTelemetryReasoningReport()

        viewModelScope.launch {
            try {
                repo?.observeRecent()?.collect { rows ->
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
                    val eventTimes = runCatching { experimentRepo?.getEventTimestamps(expId) }.getOrNull()?.filterNotNull() ?: emptyList()
                    computeStudyDayEvidence(eventTimes, _studyState.value)
                } else emptyList()
                val sources = if (expId.isNotEmpty()) {
                    runCatching { experimentRepo?.getSummary(expId)?.observedSources ?: emptyList() }.getOrNull() ?: emptyList()
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

                refreshRecommendations()
            }
        } catch (t: Throwable) {
            android.util.Log.w("TuneMindVM", "observeRecent error: ${t.message}")
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
            experimentRepo?.start(AppHolder.context)
            _studyState.value = _studyState.value.copy(showReadiness = false)
        }
    }

    fun resetStudy() {
        viewModelScope.launch { experimentRepo?.reset() }
    }

    fun recordExperimentObservation(observation: PlaybackObservation) {
        val expId = _studyState.value.experimentId
        if (expId.isNotEmpty() && _studyState.value.status == ExperimentStatus.ACTIVE) {
            viewModelScope.launch {
                experimentRepo?.recordObservation(observation, expId)
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
        com.tunemind.app.service.TuneMindListenerService.instance?.togglePlayback()
    }

    fun skipNext() {
        com.tunemind.app.service.TuneMindListenerService.instance?.skipNext()
    }

    fun skipPrev() {
        com.tunemind.app.service.TuneMindListenerService.instance?.skipPrev()
    }

    fun rebindListener() {
        val ctx = AppHolder.context ?: return
        com.tunemind.app.MainActivity.forceRebindListener(ctx)
        com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
    }

    // Cooldown Vault Persistence
    private val _vaultState = MutableStateFlow<List<VaultTrack>>(loadVaultFromPrefs())
    val vaultState: StateFlow<List<VaultTrack>> = _vaultState.asStateFlow()

    private fun loadVaultFromPrefs(): List<VaultTrack> {
        val ctx = AppHolder.context ?: return defaultSeedVault()
        val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("vault_items", null) ?: return defaultSeedVault()
        return try {
            val array = org.json.JSONArray(raw)
            val now = System.currentTimeMillis()
            val list = mutableListOf<VaultTrack>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val qTime = obj.getLong("quarantinedAtMs")
                val totalDays = obj.getDouble("totalDays").toFloat()
                val elapsedDays = maxOf(0f, (now - qTime) / 86_400_000f)
                val remaining = maxOf(0f, totalDays - elapsedDays)
                val progress = (1f - (remaining / totalDays)).coerceIn(0f, 1f)
                list.add(
                    VaultTrack(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        quarantinedAtMs = qTime,
                        totalDays = totalDays,
                        cooldownDaysRemaining = (remaining * 10).toInt() / 10f,
                        recoveryProgress = progress,
                        satietyScore = obj.optInt("satietyScore", 85)
                    )
                )
            }
            if (list.isEmpty()) defaultSeedVault() else list
        } catch (_: Exception) {
            defaultSeedVault()
        }
    }

    private fun defaultSeedVault(): List<VaultTrack> = listOf(
        VaultTrack("v-1", "Electric Dreams", "The Wanderers", System.currentTimeMillis() - 86_400_000L * 2, 7f, 5.0f, 0.28f, 88),
        VaultTrack("v-2", "Better Days", "Juno Vale", System.currentTimeMillis() - 86_400_000L * 4, 7f, 3.0f, 0.57f, 82),
        VaultTrack("v-3", "Starlight Echoes", "Aura Nova", System.currentTimeMillis() - 86_400_000L * 6, 7f, 1.0f, 0.85f, 92)
    )

    private fun saveVaultToPrefs(items: List<VaultTrack>) {
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        for (item in items) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artist", item.artist)
                put("quarantinedAtMs", item.quarantinedAtMs)
                put("totalDays", item.totalDays.toDouble())
                put("satietyScore", item.satietyScore)
            }
            array.put(obj)
        }
        prefs.edit().putString("vault_items", array.toString()).apply()
    }

    fun quarantineTrack(title: String, artist: String, durationDays: Float = 5f) {
        val id = "vault-${System.currentTimeMillis()}"
        val newTrack = VaultTrack(
            id = id,
            title = title,
            artist = artist,
            quarantinedAtMs = System.currentTimeMillis(),
            totalDays = durationDays,
            cooldownDaysRemaining = durationDays,
            recoveryProgress = 0.05f,
            satietyScore = 85
        )
        val updated = listOf(newTrack) + _vaultState.value.filter { it.title != title }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        refreshRecommendations()
    }

    fun addToVault(title: String, artist: String, durationDays: Float = 5f) {
        quarantineTrack(title, artist, durationDays)
    }

    fun restoreFromVault(id: String) {
        val updated = _vaultState.value.filter { it.id != id }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        refreshRecommendations()
    }

    // Gemini Cognitive AI Reasoning State
    private val _geminiSessionInsight = MutableStateFlow<String?>(null)
    val geminiSessionInsight: StateFlow<String?> = _geminiSessionInsight.asStateFlow()

    private val _isGeminiConfigured = MutableStateFlow<Boolean>(false)
    val isGeminiConfigured: StateFlow<Boolean> = _isGeminiConfigured.asStateFlow()

    private val _isReasoningLoading = MutableStateFlow<Boolean>(false)
    val isReasoningLoading: StateFlow<Boolean> = _isReasoningLoading.asStateFlow()

    private val _reasoningReport = MutableStateFlow<com.tunemind.app.domain.ai.ReasoningReport?>(null)
    val reasoningReport: StateFlow<com.tunemind.app.domain.ai.ReasoningReport?> = _reasoningReport.asStateFlow()

    private val _isGeneratingReport = MutableStateFlow<Boolean>(false)
    val isGeneratingReport: StateFlow<Boolean> = _isGeneratingReport.asStateFlow()

    fun refreshGeminiState() {
        val ctx = AppHolder.context ?: return
        _isGeminiConfigured.value = com.tunemind.app.domain.ai.GeminiReasoningEngine.isConfigured(ctx)
    }

    fun requestGeminiSessionReasoning() {
        val ctx = AppHolder.context ?: return
        if (!com.tunemind.app.domain.ai.GeminiReasoningEngine.isConfigured(ctx)) {
            _geminiSessionInsight.value = null
            return
        }
        viewModelScope.launch {
            _isReasoningLoading.value = true
            val result = com.tunemind.app.domain.ai.GeminiReasoningEngine.analyzeActiveSession(
                context = ctx,
                recentTracks = _homeState.value.recentTracks,
                nowPlaying = _homeState.value.nowPlaying,
                vaultTracks = _vaultState.value,
                timeSlot = currentTimeSlot.label
            )
            _isReasoningLoading.value = false
            result.onSuccess { insight ->
                _geminiSessionInsight.value = insight
            }
        }
    }

    fun refreshTelemetryReasoningReport() {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            _isGeneratingReport.value = true
            val rowsCount = maxOf(
                _homeState.value.identifiableTrackCount + _homeState.value.distinctAppearanceCount,
                _profileState.value.observationCount
            )
            val currentSource = _homeState.value.nowPlaying?.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"
            val result = com.tunemind.app.domain.ai.GeminiReasoningEngine.generateTelemetryReasoningReport(
                context = ctx,
                eventCount = maxOf(rowsCount, 12),
                topSourceApp = currentSource,
                recentTracks = _homeState.value.recentTracks,
                vaultTracks = _vaultState.value,
                nowPlaying = _homeState.value.nowPlaying,
                timeSlot = currentTimeSlot.label
            )
            _isGeneratingReport.value = false
            result.onSuccess { report ->
                _reasoningReport.value = report
            }
        }
    }

    fun saveGeminiApiKey(key: String) {
        val ctx = AppHolder.context ?: return
        com.tunemind.app.domain.ai.GeminiReasoningEngine.saveApiKey(ctx, key)
        refreshGeminiState()
        refreshRecommendations()
        requestGeminiSessionReasoning()
        refreshTelemetryReasoningReport()
    }

    fun testGeminiApiKey(candidateKey: String, onResult: (Boolean, String) -> Unit) {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            val result = com.tunemind.app.domain.ai.GeminiReasoningEngine.testConnection(ctx, candidateKey)
            result.onSuccess { text ->
                onResult(true, text)
            }.onFailure { err ->
                onResult(false, err.message ?: "Connection test failed.")
            }
        }
    }

    // Agent Recommendations & Music Provider Playback
    private val _recommendations = MutableStateFlow<List<com.tunemind.app.domain.recommendation.RecommendedTrack>>(emptyList())
    val recommendations: StateFlow<List<com.tunemind.app.domain.recommendation.RecommendedTrack>> = _recommendations.asStateFlow()

    private val _playbackLaunchFeedback = MutableStateFlow<com.tunemind.app.provider.PlaybackLaunchResult?>(null)
    val playbackLaunchFeedback: StateFlow<com.tunemind.app.provider.PlaybackLaunchResult?> = _playbackLaunchFeedback.asStateFlow()

    fun clearPlaybackLaunchFeedback() {
        _playbackLaunchFeedback.value = null
    }

    fun clearPlaybackFeedback() {
        clearPlaybackLaunchFeedback()
    }

    fun playRecommendation(context: android.content.Context, track: com.tunemind.app.domain.recommendation.RecommendedTrack, explicitProviderId: String? = null): com.tunemind.app.provider.PlaybackLaunchResult {
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        val result = registry.launchRecommendation(track, explicitProviderId)
        _playbackLaunchFeedback.value = result
        return result
    }

    fun getInstalledProviders(context: android.content.Context): List<com.tunemind.app.provider.MusicProvider> {
        return com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).getInstalledProviders()
    }

    fun getPreferredProviderId(context: android.content.Context): String {
        return com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).getPreferredProviderId()
    }

    fun setPreferredProviderId(context: android.content.Context, providerId: String) {
        com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).setPreferredProviderId(providerId)
        refreshRecommendations()
    }

    // Onboarding State
    private val _hasCompletedOnboarding = MutableStateFlow(loadOnboardingState())
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private fun loadOnboardingState(): Boolean {
        val ctx = AppHolder.context ?: return true
        val prefs = ctx.getSharedPreferences("tunemind_agent_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("has_completed_onboarding", false)
    }

    fun completeOnboarding(context: android.content.Context, preferredProviderId: String) {
        val prefs = context.getSharedPreferences("tunemind_agent_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("has_completed_onboarding", true).apply()
        setPreferredProviderId(context, preferredProviderId)
        _hasCompletedOnboarding.value = true
    }

    fun refreshRecommendations() {
        val ctx = AppHolder.context
        val prefProvider = if (ctx != null) getPreferredProviderId(ctx) else "spotify"

        // Local evidence-based baseline
        val baseline = com.tunemind.app.domain.recommendation.RecommendationEngine.generateRecommendations(
            tracks = _historyState.value.tracks,
            vaultTracks = _vaultState.value,
            preferredProviderId = prefProvider
        )
        _recommendations.value = baseline

        // If Gemini is active, asynchronously elevate with real reasoning
        if (ctx != null && com.tunemind.app.domain.ai.GeminiReasoningEngine.isConfigured(ctx)) {
            viewModelScope.launch {
                val aiRecs = com.tunemind.app.domain.ai.GeminiReasoningEngine.generateReasonedRecommendations(
                    context = ctx,
                    recentTracks = _historyState.value.tracks,
                    vaultTracks = _vaultState.value,
                    preferredProviderId = prefProvider
                )
                if (!aiRecs.isNullOrEmpty()) {
                    _recommendations.value = aiRecs
                }
            }
        }
    }

    fun saveMusicalMoment(
        title: String,
        artist: String,
        album: String? = null,
        sourcePkg: String? = null,
        startMs: Long,
        endMs: Long,
        customName: String? = null
    ) {
        val duration = maxOf(5_000L, endMs - startMs)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                momentDao?.insertOrUpdate(
                    MusicalMomentEntity(
                        momentId = java.util.UUID.randomUUID().toString(),
                        trackTitle = title,
                        trackArtist = artist,
                        trackAlbum = album,
                        sourcePackage = sourcePkg,
                        startPositionMs = startMs,
                        endPositionMs = endMs,
                        preferredPlaybackDurationMs = duration,
                        discoveryMethod = "USER_MARKED",
                        confidence = 1.0f,
                        userConfirmed = true,
                        customName = customName ?: "Moment (${startMs / 1000}s - ${endMs / 1000}s)"
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun confirmCandidateMoment(momentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                momentDao?.confirmMoment(momentId)
            } catch (_: Exception) {}
        }
    }

    fun deleteMusicalMoment(momentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                momentDao?.deleteById(momentId)
            } catch (_: Exception) {}
        }
    }

    fun resetListeningProfile() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo?.clear()
            }
            _vaultState.value = emptyList()
            val ctx = AppHolder.context
            if (ctx != null) {
                val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            }
            refreshRecommendations()
        }
    }
}

data class VaultTrack(
    val id: String,
    val title: String,
    val artist: String,
    val quarantinedAtMs: Long,
    val totalDays: Float = 5f,
    val cooldownDaysRemaining: Float = 5f,
    val recoveryProgress: Float = 0f,
    val satietyScore: Int = 85,
)

object AppHolder {
    var context: android.content.Context? = null
}

class TuneMindViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TuneMindViewModel() as T
}
