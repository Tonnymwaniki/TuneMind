package com.tunemind.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.data.local.TuneMindPlaylistEntity
import com.tunemind.app.data.local.TuneMindPlaylistTrackEntity
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks

import com.tunemind.app.domain.coverage.CoverageAssessor
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackIdentityResolver
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
import com.tunemind.app.domain.temporal.TimeSlot
import com.tunemind.app.domain.temporal.TimePatternEngine
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.memory.UnifiedTrackStats
import com.tunemind.app.data.ingestion.UniversalMusicVisualRepository
import com.tunemind.app.data.local.ArtistProfileCacheEntity
import com.tunemind.app.data.local.CrossAppPlayLogEntity
import com.tunemind.app.data.local.CrossAppPlayRecorder
import com.tunemind.app.data.local.CrossAppTasteShiftSummary
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.DetectedPlayerTab
import com.tunemind.app.data.local.TrackVisualCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HearingInsightsState(
    val recentAudioDevices: List<AudioDeviceSessionEntity> = emptyList(),
    val activeAudioDevice: AudioDeviceSessionEntity? = null,
    val activeSnapshot: com.tunemind.app.domain.audio.AudioDeviceSnapshot? = null,
    val recentVolumeObs: List<VolumeObservationEntity> = emptyList(),
    val estimatedAverageDb: Float = 58f,
    val currentHeadroomDb: Float = 27f,
    val dailyExposureMinutes: Int = 0,
    val dailyExposureMs: Long = 0L,
    val soundDoseFraction: Float = 0f,
    val safeHoursRemaining: Float = 24f,
    val safetyGuidance: String = "Listening exposure within comfortable limits (<80 dBA)."
)

data class UserProfileState(
    val name: String = "Guest Listener",
    val email: String = "",
    val tier: String = "Acoustic Intelligence Tier 1",
    val archetype: String = "Adaptive Listener",
    val archetypeDescription: String = "Evolving across your listening sessions, recovery pauses, and daily flow."
)

enum class AudioFocusBehavior {
    DUCK,
    PAUSE
}

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
    val trackId: String = "",
    val spotifyUri: String? = null,
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
    private val audioDao by lazy { db?.audioDeviceDao() }
    private val aiRecDao by lazy { db?.aiRecommendationDao() }
    private val tuneMindPlaylistDao by lazy { db?.tuneMindPlaylistDao() }

    private val _tuneMindPlaylists = MutableStateFlow<List<TuneMindPlaylistWithTracks>>(emptyList())
    val tuneMindPlaylists: StateFlow<List<TuneMindPlaylistWithTracks>> = _tuneMindPlaylists.asStateFlow()

    private val _sleepTimerEndsAtMs = MutableStateFlow<Long?>(null)
    val sleepTimerEndsAtMs: StateFlow<Long?> = _sleepTimerEndsAtMs.asStateFlow()
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    private val _hearingInsightsState = MutableStateFlow(HearingInsightsState())
    val hearingInsightsState: StateFlow<HearingInsightsState> = _hearingInsightsState.asStateFlow()

    // Authoritative Deduplicated Listening & Cross-App Stats
    private val crossAppDao by lazy { db?.crossAppLibraryDao() }
    private val crossAppRecorder by lazy {
        AppHolder.context?.let { CrossAppPlayRecorder.getInstance(it) }
    }
    private val _unifiedTracks = MutableStateFlow<List<UnifiedTrackStats>>(emptyList())
    val unifiedTracks: StateFlow<List<UnifiedTrackStats>> = _unifiedTracks.asStateFlow()

    private val _cleanPlayHistory = MutableStateFlow<List<UnifiedPlayEvent>>(emptyList())
    val cleanPlayHistory: StateFlow<List<UnifiedPlayEvent>> = _cleanPlayHistory.asStateFlow()

    private val _listeningStats = MutableStateFlow(OverallListeningStats())
    val listeningStats: StateFlow<OverallListeningStats> = _listeningStats.asStateFlow()

    private val _playCountByKey = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _playCountByUri = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _playCountByTrackId = MutableStateFlow<Map<String, Int>>(emptyMap())

    // Permanent Cross-App Library & Detected Player Tabs
    private val _crossAppLibraryTracks = MutableStateFlow<List<CrossAppTrackEntity>>(emptyList())
    val crossAppLibraryTracks: StateFlow<List<CrossAppTrackEntity>> = _crossAppLibraryTracks.asStateFlow()

    private val _crossAppPlayLogs = MutableStateFlow<List<CrossAppPlayLogEntity>>(emptyList())
    val crossAppPlayLogs: StateFlow<List<CrossAppPlayLogEntity>> = _crossAppPlayLogs.asStateFlow()

    private val _detectedPlayerTabs = MutableStateFlow<List<DetectedPlayerTab>>(emptyList())
    val detectedPlayerTabs: StateFlow<List<DetectedPlayerTab>> = _detectedPlayerTabs.asStateFlow()

    private val visualRepo by lazy {
        AppHolder.context?.let { UniversalMusicVisualRepository.getInstance(it) }
    }
    private val _trackVisuals = MutableStateFlow<Map<String, TrackVisualCacheEntity>>(emptyMap())
    val trackVisuals: StateFlow<Map<String, TrackVisualCacheEntity>> = _trackVisuals.asStateFlow()

    private val _artistProfiles = MutableStateFlow<Map<String, ArtistProfileCacheEntity>>(emptyMap())
    val artistProfiles: StateFlow<Map<String, ArtistProfileCacheEntity>> = _artistProfiles.asStateFlow()

    private val _tasteShiftSummary = MutableStateFlow(CrossAppTasteShiftSummary())
    val tasteShiftSummary: StateFlow<CrossAppTasteShiftSummary> = _tasteShiftSummary.asStateFlow()

    private val _libraryActionFeedback = MutableStateFlow<String?>(null)
    val libraryActionFeedback: StateFlow<String?> = _libraryActionFeedback.asStateFlow()

    private val _upNextQueueState = MutableStateFlow(DopamineCapsuleState())
    val upNextQueueState: StateFlow<DopamineCapsuleState> = _upNextQueueState.asStateFlow()

    val upNextAutoOverrideEnabled: StateFlow<Boolean> =
        com.tunemind.app.domain.voice.AgentSettingsStore.upNextAutoOverrideEnabled

    private val _nowPlayingActionStatus = MutableStateFlow<String?>(null)
    val nowPlayingActionStatus: StateFlow<String?> = _nowPlayingActionStatus.asStateFlow()

    private val playedOrConsumedQueueKeys = LinkedHashSet<String>()
    @Volatile
    private var queueRotationOffset: Int = 0
    @Volatile
    private var queueHandoverInFlight: Boolean = false
    @Volatile
    private var lastQueueHandoverAtMs: Long = 0L
    @Volatile
    private var lastQueueHandoverSourceTitle: String = ""
    @Volatile
    private var queueMonitoredTitle: String = ""
    @Volatile
    private var queueMonitoredArtist: String = ""
    @Volatile
    private var queueMonitoredPackage: String = ""
    @Volatile
    private var queueMonitoredDurationMs: Long = 0L
    @Volatile
    private var queueMonitoredMaxPosMs: Long = 0L
    @Volatile
    private var queueMonitoredStartedAtMs: Long = 0L
    @Volatile
    private var expectedHandoverTargetTitle: String = ""
    @Volatile
    private var expectedHandoverTargetTrack: DopamineCapsuleTrack? = null
    @Volatile
    private var expectedHandoverWindowUntilMs: Long = 0L
    @Volatile
    private var expectedHandoverRetryCount: Int = 0
    @Volatile
    private var lastHandoverRetryAtMs: Long = 0L
    @Volatile
    private var preResolvedQueueKey: String = ""
    @Volatile
    private var latestLiveSnapshot: com.tunemind.app.media.ActivePlaybackSnapshot? = null
    @Volatile
    private var latestLiveSnapshotWallMs: Long = 0L
    @Volatile
    private var manualLaunchPendingTitle: String? = null
    @Volatile
    private var manualLaunchPendingUntilMs: Long = 0L
    @Volatile
    private var uiForegroundActive: Boolean = false
    @Volatile
    private var statusClearJob: kotlinx.coroutines.Job? = null
    private var snapshotMonitorListener: ((com.tunemind.app.media.ActivePlaybackSnapshot) -> Unit)? = null
    private var inAppCompletionQueueListener: ((String, String, String) -> Unit)? = null

    @Volatile
    private var cachedRowsForStats: List<PlaybackObservation> = emptyList()
    @Volatile
    private var cachedCrossAppTracksForStats: List<CrossAppTrackEntity> = emptyList()
    @Volatile
    private var cachedCrossAppLogsForStats: List<CrossAppPlayLogEntity> = emptyList()
    @Volatile
    private var cachedMergeDuplicatesForStats: Boolean = true
    @Volatile
    private var cachedRepetitionSensitivityForStats: Float = 0.75f
    @Volatile
    private var liveStatsRecomputeJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var queueSyncJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastVisualTasteSize: Int = -1
    @Volatile
    private var cachedRestoredVaultMap: Map<String, Long>? = null

    fun setUiForegroundActive(active: Boolean) {
        uiForegroundActive = active
        if (active) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
                    publishAuthoritativeNowPlayingNow()
                }
            }
        }
    }

    private fun isInAppPlayerCurrent(): Boolean {
        val ctxPkg = AppHolder.context?.packageName
        val nowPkg = _homeState.value.nowPlaying?.sourcePackage
        return com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying() ||
            com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
            ((com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() ||
                com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()) &&
                (nowPkg == null || nowPkg == ctxPkg))
    }

    private fun resolveBestNowPlayingForStats(
        rows: List<PlaybackObservation> = cachedRowsForStats
    ): NowPlayingObservation? {
        val nowMs = System.currentTimeMillis()
        val appPkg = AppHolder.context?.packageName ?: "com.tunemind.app"

        val ytActive = com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo()
        val ytTitle = com.tunemind.app.provider.TuneMindYouTubeEmbedController.currentTitleFlow.value?.takeIf { it.isNotBlank() }
        val ytObs = if (ytActive && ytTitle != null) {
            val playing = com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
            val ytArtist = com.tunemind.app.provider.TuneMindYouTubeEmbedController.currentArtistFlow.value
            val ytId = com.tunemind.app.provider.TuneMindYouTubeEmbedController.activeVideoIdFlow.value
            NowPlayingObservation(
                title = ytTitle,
                artist = ytArtist,
                album = null,
                sourcePackage = appPkg,
                isPlaying = playing,
                playbackStateCode = if (playing) 3 else 2,
                positionMs = com.tunemind.app.provider.TuneMindYouTubeEmbedController.positionMsFlow.value,
                durationMs = com.tunemind.app.provider.TuneMindYouTubeEmbedController.durationMsFlow.value,
                speed = 1.0f,
                updatedAtMs = com.tunemind.app.provider.TuneMindYouTubeEmbedController.lastStateUpdatedMs.takeIf { it > 0L } ?: nowMs,
                trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    title = ytTitle,
                    artist = ytArtist
                ),
                spotifyUri = ytId?.let { "https://music.youtube.com/watch?v=$it" }
            )
        } else null

        val inAppActive = com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()
        val inAppTitle = com.tunemind.app.provider.TuneMindInAppPlayer.currentTitleFlow.value?.takeIf { it.isNotBlank() }
        val inAppObs = ytObs ?: if (inAppActive && inAppTitle != null) {
            val playing = com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying()
            val inAppArtist = com.tunemind.app.provider.TuneMindInAppPlayer.currentArtistFlow.value
            NowPlayingObservation(
                title = inAppTitle,
                artist = inAppArtist,
                album = null,
                sourcePackage = appPkg,
                isPlaying = playing,
                playbackStateCode = if (playing) 3 else 2,
                positionMs = com.tunemind.app.provider.TuneMindInAppPlayer.positionMsFlow.value,
                durationMs = com.tunemind.app.provider.TuneMindInAppPlayer.durationMsFlow.value,
                speed = 1.0f,
                updatedAtMs = com.tunemind.app.provider.TuneMindInAppPlayer.lastStateUpdatedMs.takeIf { it > 0L } ?: nowMs,
                trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    title = inAppTitle,
                    artist = inAppArtist
                )
            )
        } else null

        val serviceSnap = com.tunemind.app.service.TuneMindListenerService.instance?.getActivePlaybackSnapshot()
        val cachedSnap = latestLiveSnapshot?.takeIf { (nowMs - latestLiveSnapshotWallMs) < 12 * 60 * 1000L }
        val activeSnap = when {
            serviceSnap != null && serviceSnap.title.isNotBlank() -> serviceSnap
            cachedSnap != null && cachedSnap.title.isNotBlank() -> cachedSnap
            else -> null
        }
        val liveSnapObs = activeSnap?.let { snap ->
            val ageMs = (android.os.SystemClock.elapsedRealtime() - snap.updatedAtElapsedMs).coerceIn(0L, 12 * 60 * 1000L)
            NowPlayingObservation(
                title = snap.title,
                artist = snap.artist,
                album = snap.album,
                sourcePackage = snap.packageName,
                isPlaying = snap.isPlaying,
                playbackStateCode = snap.playbackStateCode,
                positionMs = snap.positionMs,
                durationMs = snap.durationMs,
                speed = snap.speed,
                updatedAtMs = nowMs - ageMs,
                trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    title = snap.title,
                    artist = snap.artist
                )
            )
        }

        val deviceObs = rows.filter { it.observerSessionId != "spotify_web_api_ingestion" }
        val latestStateObs = deviceObs.firstOrNull {
            it.playbackStateCode != null &&
                !ListeningPlayDeduplicator.isExcludedBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
        }
        val latestTrackMeta = deviceObs.firstOrNull {
            !it.trackTitle.isNullOrBlank() &&
                !ListeningPlayDeduplicator.isExcludedBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
        }
        val latestObsTimeMs = maxOf(latestStateObs?.eventTimeMs ?: 0L, latestTrackMeta?.eventTimeMs ?: 0L)
        val isRecentObservation = (nowMs - latestObsTimeMs) < 12 * 60 * 1000L
        val obsIsPlayingNow = (latestStateObs?.playbackStateCode == 3) && (nowMs - latestObsTimeMs) < 90_000L
        val deviceObsCandidate = if ((latestTrackMeta != null || latestStateObs != null) && isRecentObservation) {
            val metaTitle = latestTrackMeta?.trackTitle
            val stateTitle = latestStateObs?.trackTitle
            val useStateMeta = !stateTitle.isNullOrBlank() && (latestStateObs?.eventTimeMs ?: 0L) >= (latestTrackMeta?.eventTimeMs ?: 0L)
            val resolvedDeviceTitle = if (useStateMeta) stateTitle else (metaTitle ?: stateTitle)
            val resolvedDeviceArtist = if (useStateMeta) (latestStateObs?.trackArtist ?: latestTrackMeta?.trackArtist) else (latestTrackMeta?.trackArtist ?: latestStateObs?.trackArtist)
            val rawDeviceTrackId = latestTrackMeta?.trackId ?: latestStateObs?.trackId
            val resolvedDeviceUri = TrackIdentityResolver.normalizeSpotifyUri(rawDeviceTrackId)
            NowPlayingObservation(
                title = resolvedDeviceTitle,
                artist = resolvedDeviceArtist,
                album = latestTrackMeta?.trackAlbum ?: latestStateObs?.trackAlbum,
                sourcePackage = latestStateObs?.sourcePackage ?: latestTrackMeta?.sourcePackage,
                isPlaying = obsIsPlayingNow,
                playbackStateCode = if (obsIsPlayingNow) 3 else (latestStateObs?.playbackStateCode ?: 2),
                positionMs = latestStateObs?.positionMs ?: 0L,
                durationMs = (if (useStateMeta) latestStateObs?.durationMs else latestTrackMeta?.durationMs)
                    ?: latestTrackMeta?.durationMs
                    ?: latestStateObs?.durationMs
                    ?: 0L,
                speed = latestStateObs?.playbackSpeed ?: 1.0f,
                updatedAtMs = latestObsTimeMs,
                trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    trackId = rawDeviceTrackId,
                    spotifyUri = resolvedDeviceUri,
                    title = resolvedDeviceTitle,
                    artist = resolvedDeviceArtist
                ),
                spotifyUri = resolvedDeviceUri
            )
        } else null

        val overrideObs = manualActiveTrackOverride
        val hasOptimisticPending = overrideObs != null && nowMs < manualLaunchPendingUntilMs

        if (hasOptimisticPending && overrideObs != null) {
            val pendingTitle = manualLaunchPendingTitle
            val liveAlreadySwitched = !pendingTitle.isNullOrBlank() &&
                liveSnapObs != null &&
                liveSnapObs.isPlaying &&
                ListeningPlayDeduplicator.isSameTrack(
                    liveSnapObs.title, liveSnapObs.artist, null,
                    pendingTitle, null, null
                )
            if (!liveAlreadySwitched) {
                return overrideObs
            }
        }

        // 1. Any actively playing real source wins immediately
        if (inAppObs != null && inAppObs.isPlaying) return inAppObs
        if (liveSnapObs != null && liveSnapObs.isPlaying) return liveSnapObs
        if (deviceObsCandidate != null && deviceObsCandidate.isPlaying &&
            (liveSnapObs == null || liveSnapObs.sourcePackage == deviceObsCandidate.sourcePackage)
        ) {
            return deviceObsCandidate
        }

        // 2. When paused/stopped: pick newest non-playing source
        val candidates = listOfNotNull(
            liveSnapObs,
            inAppObs,
            deviceObsCandidate
        )
        return candidates.maxByOrNull { it.updatedAtMs }
            ?: overrideObs
            ?: _homeState.value.nowPlaying?.copy(isPlaying = false, playbackStateCode = 2)
    }

    private fun publishAuthoritativeNowPlayingNow(incomingSnap: com.tunemind.app.media.ActivePlaybackSnapshot? = null) {
        val nowMs = System.currentTimeMillis()
        val appPkg = AppHolder.context?.packageName
        if (incomingSnap != null) {
            latestLiveSnapshot = incomingSnap
            latestLiveSnapshotWallMs = nowMs
            if (incomingSnap.title.isNotBlank() && incomingSnap.packageName != appPkg) {
                if (incomingSnap.isPlaying) {
                    com.tunemind.app.provider.TuneMindInAppPlayer.clearIfNotPlaying()
                    com.tunemind.app.provider.TuneMindYouTubeEmbedController.clearIfNotPlaying()
                }
                val pendingTitle = manualLaunchPendingTitle
                val matchesPending = !pendingTitle.isNullOrBlank() &&
                    ListeningPlayDeduplicator.isSameTrack(
                        incomingSnap.title, incomingSnap.artist, null,
                        pendingTitle, null, null
                    )
                if (matchesPending) {
                    manualLaunchPendingUntilMs = 0L
                    manualLaunchPendingTitle = null
                    manualActiveTrackOverride = null
                } else if (pendingTitle == null && manualActiveTrackOverride?.isPlaying == false && !incomingSnap.isPlaying) {
                    // External player confirmed pause cleanly
                    manualLaunchPendingUntilMs = 0L
                    manualActiveTrackOverride = null
                } else if (manualActiveTrackOverride != null) {
                    val matchesOverride = ListeningPlayDeduplicator.isSameTrack(
                        incomingSnap.title, incomingSnap.artist, null,
                        manualActiveTrackOverride?.title, manualActiveTrackOverride?.artist, null
                    )
                    if (!matchesOverride && (incomingSnap.isPlaying || nowMs >= manualLaunchPendingUntilMs)) {
                        manualActiveTrackOverride = null
                    }
                }
            } else if (incomingSnap.title.isBlank() && !incomingSnap.isPlaying) {
                // External MediaSession destroyed or stopped cleanly
                val cur = _homeState.value.nowPlaying
                if (cur != null && cur.sourcePackage == incomingSnap.packageName && nowMs >= manualLaunchPendingUntilMs) {
                    manualActiveTrackOverride = null
                    _homeState.value = _homeState.value.copy(
                        nowPlaying = cur.copy(
                            isPlaying = false,
                            playbackStateCode = incomingSnap.playbackStateCode,
                            updatedAtMs = nowMs
                        )
                    )
                    recomputeAdaptiveTheme()
                    refreshLiveListeningStatsNow()
                }
                return
            }
        }

        val best = resolveBestNowPlayingForStats(cachedRowsForStats) ?: return
        val prev = _homeState.value.nowPlaying
        val trackOrStateChanged = prev == null ||
            prev.title != best.title ||
            prev.artist != best.artist ||
            prev.isPlaying != best.isPlaying ||
            prev.sourcePackage != best.sourcePackage
        val positionOrDurationChanged = prev == null ||
            kotlin.math.abs(prev.positionMs - best.positionMs) >= 1_500L ||
            prev.durationMs != best.durationMs

        if (trackOrStateChanged || positionOrDurationChanged) {
            _homeState.value = _homeState.value.copy(nowPlaying = best)
            if (trackOrStateChanged) {
                recomputeAdaptiveTheme()
                syncUpNextQueueWithSources(forceRebuild = false)
                evaluateActiveForYouSessionTransition(best)
                refreshLiveListeningStatsNow()
            }
            if (!best.title.isNullOrBlank()) {
                visualRepo?.enqueueVisualEnrichment(listOf(best.title to best.artist.orEmpty()))
            }
        }
    }

    fun refreshLiveListeningStatsNow() {
        val rows = cachedRowsForStats
        val crossTracks = cachedCrossAppTracksForStats
        val crossLogs = cachedCrossAppLogsForStats
        if (rows.isEmpty() && crossTracks.isEmpty() && _homeState.value.nowPlaying == null) return
        if (liveStatsRecomputeJob?.isActive == true) return
        liveStatsRecomputeJob = viewModelScope.launch(Dispatchers.Default) {
            val nowMs = System.currentTimeMillis()
            val np = resolveBestNowPlayingForStats(rows)
            val restoredMap = loadRestoredVaultMapFromPrefs()
            val activeVaultFreshness = buildActiveVaultFreshnessMap(nowMs)
            val snapshot = ListeningPlayDeduplicator.buildSnapshot(
                rawObservations = rows,
                crossAppTracks = crossTracks,
                crossAppLogs = crossLogs,
                nowPlayingTitle = np?.title,
                nowPlayingArtist = np?.artist,
                nowPlayingSourcePkg = np?.sourcePackage,
                isMusicPlayingNow = np?.isPlaying == true,
                nowMs = nowMs,
                mergeDuplicatePlays = cachedMergeDuplicatesForStats,
                repetitionSensitivity = cachedRepetitionSensitivityForStats,
                nowPlayingPositionMs = np?.positionMs ?: 0L,
                nowPlayingDurationMs = np?.durationMs ?: 0L,
                nowPlayingUpdatedAtMs = np?.updatedAtMs ?: 0L,
                restoredAtByKey = restoredMap,
                activeVaultFreshnessByKey = activeVaultFreshness
            )
            _unifiedTracks.value = snapshot.unifiedTracks
            _cleanPlayHistory.value = snapshot.cleanPlayHistory
            _listeningStats.value = snapshot.stats
            _playCountByKey.value = snapshot.playCountByTrackKey
            _playCountByUri.value = snapshot.playCountByUri
            _playCountByTrackId.value = snapshot.playCountByTrackId
            reconcileVaultLifecycle(
                unifiedList = snapshot.unifiedTracks,
                cleanEvents = snapshot.cleanPlayHistory,
                syncBackend = false
            )
            refreshHearingInsights()
        }
    }

    fun resolvePlayCount(
        title: String?,
        artist: String?,
        spotifyUri: String? = null,
        trackId: String? = null
    ): Int {
        val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId,
            spotifyUri = spotifyUri,
            title = title,
            artist = artist
        )
        _playCountByTrackId.value[canonical.trackId]?.let { return it }
        val cleanUri = canonical.spotifyUri?.lowercase()?.takeIf { it.isNotBlank() }
        if (cleanUri != null) {
            _playCountByUri.value[cleanUri]?.let { return it }
        }
        val detId = TrackIdentityResolver.buildDeterministicTrackId(title, artist)
        _playCountByTrackId.value[detId]?.let { return it }
        val key = ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
        _playCountByKey.value[key]?.let { return it }
        val normKey = TrackIdentityResolver.buildNormalizedMatchKey(title, artist)
        if (normKey.isNotBlank() && normKey != "|") {
            _playCountByKey.value[normKey]?.let { return it }
        }
        val cleanTitle = ListeningPlayDeduplicator.normalizeText(title)
        if (cleanTitle.isNotBlank()) {
            val fuzzy = _unifiedTracks.value.firstOrNull {
                (canonical.trackId.isNotBlank() && it.trackId == canonical.trackId) ||
                    ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, canonical.spotifyUri) ||
                    (normKey.isNotBlank() && normKey != "|" && TrackIdentityResolver.buildNormalizedMatchKey(it.title, it.artist) == normKey)
            }
            if (fuzzy != null) return fuzzy.verifiedPlayCount
        }
        return 0
    }

    fun isTrackCurrentlyPlaying(title: String?, artist: String?, spotifyUri: String? = null): Boolean {
        if (title.isNullOrBlank()) return false
        val nowPlaying = _homeState.value.nowPlaying ?: return false
        if (!nowPlaying.isPlaying) return false
        return ListeningPlayDeduplicator.isSameTrack(
            nowPlaying.title,
            nowPlaying.artist,
            nowPlaying.spotifyUri,
            title,
            artist,
            spotifyUri
        )
    }

    val spotifyLibrarySyncState: StateFlow<com.tunemind.app.data.ingestion.SpotifyLibrarySyncUiState> =
        com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.syncState

    private val _isExportingSpotifyPlaylist = MutableStateFlow(false)
    val isExportingSpotifyPlaylist: StateFlow<Boolean> = _isExportingSpotifyPlaylist.asStateFlow()

    fun toggleLibraryTrackLike(canonicalTrackKey: String, liked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = crossAppDao?.getTrackByKey(canonicalTrackKey)
                crossAppDao?.setTrackLiked(canonicalTrackKey, liked)
                val ctx = AppHolder.context
                if (ctx != null && existing != null && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(ctx)) {
                    val spotifyUri = existing.trackId.takeIf { it.startsWith("spotify:track:") }
                    if (liked) {
                        com.tunemind.app.provider.SpotifyPremiumConnectManager.likeTrackOnSpotify(
                            context = ctx,
                            title = existing.title,
                            artist = existing.artist,
                            spotifyUri = spotifyUri
                        )
                    } else {
                        com.tunemind.app.provider.SpotifyPremiumConnectManager.unlikeTrackOnSpotify(
                            context = ctx,
                            title = existing.title,
                            artist = existing.artist,
                            spotifyUri = spotifyUri
                        )
                    }
                }
            }
        }
    }

    fun saveTrackToCrossAppLiked(title: String, artist: String, sourcePkg: String? = null, trackId: String? = null) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val cleanArtist = artist.trim().ifBlank { "Unknown Artist" }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = crossAppDao ?: return@launch
                val key = TrackIdentityResolver.buildNormalizedMatchKey(cleanTitle, cleanArtist)
                val existing = dao.getTrackByKey(key)
                val newLikedState = if (existing != null) !existing.isLiked else true
                if (existing != null) {
                    dao.setTrackLiked(key, newLikedState)
                    _libraryActionFeedback.value = if (newLikedState) {
                        "Saved \"$cleanTitle\" to Liked"
                    } else {
                        "Removed \"$cleanTitle\" from Liked"
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val pkg = sourcePkg?.takeIf { it.isNotBlank() } ?: "unknown_player"
                    dao.upsertLibraryTrack(
                        CrossAppTrackEntity(
                            canonicalTrackKey = key,
                            trackId = trackId?.takeIf { it.isNotBlank() }
                                ?: TrackIdentityResolver.buildDeterministicTrackId(cleanTitle, cleanArtist),
                            title = cleanTitle,
                            artist = cleanArtist,
                            primarySourcePackage = pkg,
                            sourcePackagesCsv = pkg,
                            isLiked = true,
                            firstPlayedAtMs = now,
                            lastPlayedAtMs = now,
                            updatedAtMs = now
                        )
                    )
                    visualRepo?.enqueueVisualEnrichment(listOf(cleanTitle to cleanArtist))
                    _libraryActionFeedback.value = "Saved \"$cleanTitle\" to Liked"
                }

                // 2-way sync with Spotify Liked Songs when Spotify is connected
                val ctx = AppHolder.context
                if (ctx != null && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(ctx)) {
                    val spotifyUri = (trackId ?: existing?.trackId)?.takeIf { it.startsWith("spotify:track:") }
                    if (newLikedState) {
                        com.tunemind.app.provider.SpotifyPremiumConnectManager.likeTrackOnSpotify(
                            context = ctx,
                            title = cleanTitle,
                            artist = cleanArtist,
                            spotifyUri = spotifyUri
                        )
                    } else {
                        com.tunemind.app.provider.SpotifyPremiumConnectManager.unlikeTrackOnSpotify(
                            context = ctx,
                            title = cleanTitle,
                            artist = cleanArtist,
                            spotifyUri = spotifyUri
                        )
                    }
                }
            }
        }
    }

    fun syncSpotifyLibraryNow(
        forceRefresh: Boolean = true,
        onComplete: ((com.tunemind.app.data.ingestion.SpotifyLibrarySyncResult) -> Unit)? = null
    ) {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.syncAll(
                context = ctx,
                forceRefresh = forceRefresh
            )
            if (result.message.isNotBlank()) {
                _libraryActionFeedback.value = result.message
                postNowPlayingStatus(result.message)
            }
            if (result.success) {
                runCatching { com.tunemind.app.sync.SyncScheduler.schedule(ctx) }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onComplete?.invoke(result)
            }
        }
    }

    fun exportTuneMindPlaylistToSpotify(
        playlistIdOrName: String,
        onComplete: ((com.tunemind.app.provider.SpotifyExportPlaylistResult) -> Unit)? = null
    ) {
        val ctx = AppHolder.context ?: return
        val query = playlistIdOrName.trim()
        val playlists = _tuneMindPlaylists.value
        val target = playlists.firstOrNull { it.playlist.playlistId == query }
            ?: playlists.firstOrNull { it.playlist.name.equals(query, ignoreCase = true) }
            ?: if (query.isNotBlank()) {
                playlists.firstOrNull { it.playlist.name.contains(query, ignoreCase = true) }
            } else {
                playlists.firstOrNull { !com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(it.playlist.playlistId) }
                    ?: playlists.firstOrNull()
            }

        if (target == null) {
            val res = com.tunemind.app.provider.SpotifyExportPlaylistResult(
                success = false,
                message = if (query.isBlank()) "Create a TuneMind playlist first to export to Spotify."
                else "Could not find a TuneMind playlist matching \"$query\"."
            )
            _libraryActionFeedback.value = res.message
            postNowPlayingStatus(res.message)
            onComplete?.invoke(res)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isExportingSpotifyPlaylist.value = true
            try {
                _libraryActionFeedback.value = "Exporting \"${target.playlist.name}\" to Spotify..."
                val trackInputs = target.tracks.map { tr ->
                    Triple(
                        tr.title,
                        tr.artist,
                        tr.externalUri.takeIf { it.isNotBlank() } ?: tr.trackId.takeIf { it.startsWith("spotify:track:") }
                    )
                }
                val result = com.tunemind.app.provider.SpotifyPremiumConnectManager.exportPlaylistToSpotify(
                    context = ctx,
                    playlistName = target.playlist.name,
                    description = target.playlist.description.ifBlank { "Curated in TuneMind" },
                    tracks = trackInputs
                )
                _libraryActionFeedback.value = result.message
                postNowPlayingStatus(result.message)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete?.invoke(result)
                }
            } finally {
                _isExportingSpotifyPlaylist.value = false
            }
        }
    }

    // Persistent Chrono AI Recommendation Crate
    private val _aiDiscoveryCrate = MutableStateFlow<List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>>(emptyList())
    val aiDiscoveryCrate: StateFlow<List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>> = _aiDiscoveryCrate.asStateFlow()

    private val _savedAiRecommendations = MutableStateFlow<List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>>(emptyList())
    val savedAiRecommendations: StateFlow<List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>> = _savedAiRecommendations.asStateFlow()

    private val _isDiscovering = MutableStateFlow<Boolean>(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _playedRecCount = MutableStateFlow(0)
    val playedRecCount: StateFlow<Int> = _playedRecCount.asStateFlow()

    private val _savedRecCount = MutableStateFlow(0)
    val savedRecCount: StateFlow<Int> = _savedRecCount.asStateFlow()

    private val _dismissedRecCount = MutableStateFlow(0)
    val dismissedRecCount: StateFlow<Int> = _dismissedRecCount.asStateFlow()

    private val _forYouLearningState = MutableStateFlow(loadInitialForYouLearningState())
    val forYouLearningState: StateFlow<com.tunemind.app.domain.ai.ForYouLearningSummary> = _forYouLearningState.asStateFlow()

    private fun loadInitialForYouLearningState(): com.tunemind.app.domain.ai.ForYouLearningSummary {
        val ctx = AppHolder.context
        return if (ctx != null) {
            com.tunemind.app.domain.ai.ClaudeReasoningEngine.getSavedLearningSummary(ctx)
        } else {
            com.tunemind.app.domain.ai.ForYouLearningSummary(
                dropDateKey = com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
            )
        }
    }

    // Personal Taste Genome & Explicit Anchors (e.g. Afrobeats, Hip-Hop, Burna Boy, Drake)
    private val _userTasteAnchors = MutableStateFlow<List<String>>(loadTasteAnchorsFromPrefs())
    val userTasteAnchors: StateFlow<List<String>> = _userTasteAnchors.asStateFlow()

    private fun loadTasteAnchorsFromPrefs(): List<String> {
        val ctx = AppHolder.context ?: return listOf("Afrobeats", "Synthwave", "Lo-Fi", "Neo-Soul", "Hip-Hop")
        val prefs = ctx.getSharedPreferences("tunemind_taste_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("taste_anchors", null)
            ?: return listOf("Afrobeats", "Synthwave", "Lo-Fi", "Neo-Soul", "Hip-Hop")
        val list = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return if (list.isEmpty()) listOf("Afrobeats", "Synthwave", "Lo-Fi", "Neo-Soul", "Hip-Hop") else list
    }

    fun saveTasteAnchors(anchors: List<String>) {
        _userTasteAnchors.value = anchors.distinct()
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_taste_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("taste_anchors", anchors.joinToString(",")).apply()
        triggerChronoDiscovery(forceRefresh = false)
    }

    fun addTasteAnchor(anchor: String) {
        val clean = anchor.trim()
        if (clean.isBlank()) return
        val current = _userTasteAnchors.value.toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            saveTasteAnchors(current)
        }
    }

    fun removeTasteAnchor(anchor: String) {
        val current = _userTasteAnchors.value.toMutableList()
        if (current.remove(anchor)) {
            saveTasteAnchors(current)
        }
    }

    // User Profile & Acoustic Archetype
    private val _userProfile = MutableStateFlow(loadUserProfile())
    val userProfile: StateFlow<UserProfileState> = _userProfile.asStateFlow()

    private fun loadUserProfile(): UserProfileState {
        val ctx = AppHolder.context ?: return UserProfileState()
        val prefs = ctx.getSharedPreferences("tunemind_user_prefs", android.content.Context.MODE_PRIVATE)
        return UserProfileState(
            name = prefs.getString("user_name", null)?.takeIf { it.isNotBlank() } ?: "Guest Listener",
            email = prefs.getString("user_email", null).orEmpty(),
            tier = prefs.getString("user_tier", "Acoustic Intelligence Tier 1") ?: "Acoustic Intelligence Tier 1",
            archetype = prefs.getString("user_archetype", null)?.takeIf { it.isNotBlank() } ?: "Adaptive Listener",
            archetypeDescription = prefs.getString(
                "user_archetype_desc",
                "Evolving across your listening sessions, recovery pauses, and daily flow."
            ) ?: "Evolving across your listening sessions, recovery pauses, and daily flow."
        )
    }

    fun updateUserProfile(name: String, archetype: String) {
        val current = _userProfile.value
        val updated = current.copy(name = name, archetype = archetype)
        _userProfile.value = updated
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_user_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", name)
            .putString("user_archetype", archetype)
            .apply()
    }

    fun syncIdentityFromAuth(name: String, email: String, archetype: String? = null) {
        val current = _userProfile.value
        val resolvedName = name.trim().ifBlank { current.name }
        val resolvedEmail = email.trim().ifBlank { current.email }
        val resolvedArchetype = archetype?.trim()?.takeIf { it.isNotBlank() } ?: current.archetype
        _userProfile.value = current.copy(
            name = resolvedName,
            email = resolvedEmail,
            archetype = resolvedArchetype
        )
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_user_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", resolvedName)
            .putString("user_email", resolvedEmail)
            .putString("user_archetype", resolvedArchetype)
            .apply()
    }

    // System Switchboard: Battery Optimization, Audio Focus, Decibel Safety Guard
    private val _isBatteryOptimizationIgnored = MutableStateFlow<Boolean>(false)
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    private val _audioFocusBehavior = MutableStateFlow(loadAudioFocusBehavior())
    val audioFocusBehavior: StateFlow<AudioFocusBehavior> = _audioFocusBehavior.asStateFlow()

    private val _isDecibelSafetyAlertEnabled = MutableStateFlow(loadDecibelSafetyAlert())
    val isDecibelSafetyAlertEnabled: StateFlow<Boolean> = _isDecibelSafetyAlertEnabled.asStateFlow()

    private fun loadAudioFocusBehavior(): AudioFocusBehavior {
        val ctx = AppHolder.context ?: return AudioFocusBehavior.DUCK
        val prefs = ctx.getSharedPreferences("tunemind_system_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("audio_focus_behavior", "DUCK")
        return runCatching { AudioFocusBehavior.valueOf(raw ?: "DUCK") }.getOrDefault(AudioFocusBehavior.DUCK)
    }

    fun setAudioFocusBehavior(behavior: AudioFocusBehavior) {
        _audioFocusBehavior.value = behavior
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_system_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("audio_focus_behavior", behavior.name).apply()
    }

    private fun loadDecibelSafetyAlert(): Boolean {
        val ctx = AppHolder.context ?: return true
        val prefs = ctx.getSharedPreferences("tunemind_system_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("decibel_safety_alert", true)
    }

    fun setDecibelSafetyAlertEnabled(enabled: Boolean) {
        _isDecibelSafetyAlertEnabled.value = enabled
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_system_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("decibel_safety_alert", enabled).apply()
    }

    fun checkBatteryOptimization(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            _isBatteryOptimizationIgnored.value = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            _isBatteryOptimizationIgnored.value = true
        }
    }

    fun requestIgnoreBatteryOptimization(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                // Opens standard system battery optimization settings without invoking restricted
                // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS manifest permission (Play Store policy safe).
                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val appDetails = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(appDetails)
                } catch (_: Exception) {}
            }
        }
    }

    // Cooldown Vault Persistence & Mathematical Lifecycle
    private val _recentlyGraduatedVaultTracks = MutableStateFlow<List<GraduatedVaultSummary>>(emptyList())
    val recentlyGraduatedVaultTracks: StateFlow<List<GraduatedVaultSummary>> = _recentlyGraduatedVaultTracks.asStateFlow()

    private val _vaultState = MutableStateFlow<List<VaultTrack>>(loadVaultFromPrefs())
    val vaultState: StateFlow<List<VaultTrack>> = _vaultState.asStateFlow()

    private val _manualFreshTracks = MutableStateFlow<List<ManualFreshTrack>>(loadManualFreshFromPrefs())
    val manualFreshTracks: StateFlow<List<ManualFreshTrack>> = _manualFreshTracks.asStateFlow()

    private val _freshExcludedKeys = MutableStateFlow<Set<String>>(loadFreshExcludedKeysFromPrefs())
    val freshExcludedKeys: StateFlow<Set<String>> = _freshExcludedKeys.asStateFlow()

    @Volatile
    private var lastBackendVaultSyncMs: Long = 0L

    @Volatile
    private var hasHydratedRemoteVault: Boolean = false

    // Claude Cognitive AI Reasoning State
    private val _claudeSessionInsight = MutableStateFlow<String?>(null)
    val claudeSessionInsight: StateFlow<String?> = _claudeSessionInsight.asStateFlow()

    private val _isClaudeConfigured = MutableStateFlow<Boolean>(false)
    val isClaudeConfigured: StateFlow<Boolean> = _isClaudeConfigured.asStateFlow()

    private data class TrackedForYouSession(
        val rec: com.tunemind.app.domain.recommendation.AiRecommendationEntity,
        val startedAtMs: Long,
        var matchedNowPlayingAtMs: Long? = null,
        var observedTrackDurationMs: Long = 0L,
        var reportedOutcome: Boolean = false
    )

    @Volatile
    private var activeTrackedForYouSession: TrackedForYouSession? = null

    @Volatile
    private var autoTriggeredNoonCycleKey: String? = null

    private val _isReasoningLoading = MutableStateFlow<Boolean>(false)
    val isReasoningLoading: StateFlow<Boolean> = _isReasoningLoading.asStateFlow()

    private val _reasoningReport = MutableStateFlow<com.tunemind.app.domain.ai.ReasoningReport?>(null)
    val reasoningReport: StateFlow<com.tunemind.app.domain.ai.ReasoningReport?> = _reasoningReport.asStateFlow()

    private val _isGeneratingReport = MutableStateFlow<Boolean>(false)
    val isGeneratingReport: StateFlow<Boolean> = _isGeneratingReport.asStateFlow()

    // Agent Recommendations & Music Provider Playback
    private val _recommendations = MutableStateFlow<List<com.tunemind.app.domain.recommendation.RecommendedTrack>>(emptyList())
    val recommendations: StateFlow<List<com.tunemind.app.domain.recommendation.RecommendedTrack>> = _recommendations.asStateFlow()

    private val _playbackLaunchFeedback = MutableStateFlow<com.tunemind.app.provider.PlaybackLaunchResult?>(null)
    val playbackLaunchFeedback: StateFlow<com.tunemind.app.provider.PlaybackLaunchResult?> = _playbackLaunchFeedback.asStateFlow()

    // Onboarding State
    private val _hasCompletedOnboarding = MutableStateFlow(loadOnboardingState())
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    // Adaptive Personal Atmosphere Theme Engine
    private val themePrefsRepo: com.tunemind.app.ui.theme.ThemePreferencesRepository? by lazy {
        AppHolder.context?.let { com.tunemind.app.ui.theme.ThemePreferencesRepository(it) }
    }
    private val _themePreferences = MutableStateFlow(
        themePrefsRepo?.loadPreferences() ?: com.tunemind.app.ui.theme.ThemePreferences()
    )
    val themePreferences: StateFlow<com.tunemind.app.ui.theme.ThemePreferences> = _themePreferences.asStateFlow()

    private val _adaptiveThemeState = MutableStateFlow(
        com.tunemind.app.ui.theme.AdaptiveThemeEngine.composeTheme(
            preferences = _themePreferences.value
        )
    )
    val adaptiveThemeState: StateFlow<com.tunemind.app.ui.theme.AdaptiveThemeState> = _adaptiveThemeState.asStateFlow()

    fun updateThemePreferences(newPrefs: com.tunemind.app.ui.theme.ThemePreferences) {
        _themePreferences.value = newPrefs
        themePrefsRepo?.savePreferences(newPrefs)
        recomputeAdaptiveTheme()
    }

    fun resetThemePreferencesToDefaults() {
        val defaults = themePrefsRepo?.resetToDefaults() ?: com.tunemind.app.ui.theme.ThemePreferences()
        _themePreferences.value = defaults
        recomputeAdaptiveTheme()
    }

    fun recomputeAdaptiveTheme(overrideTitle: String? = null, overrideArtist: String? = null) {
        val currentNowPlaying = _homeState.value.nowPlaying
        val latestRecent = _homeState.value.recentTracks.firstOrNull()
        val effectiveTitle = overrideTitle ?: currentNowPlaying?.title ?: latestRecent?.title
        val effectiveArtist = overrideArtist ?: currentNowPlaying?.artist ?: latestRecent?.artist
        _adaptiveThemeState.value = com.tunemind.app.ui.theme.AdaptiveThemeEngine.composeTheme(
            preferences = _themePreferences.value,
            trackTitle = effectiveTitle,
            trackArtist = effectiveArtist,
            trackEnergy = if (currentNowPlaying?.isPlaying == true) 0.78f else 0.55f
        )
    }

    fun cycleAtmospherePalette() {
        val cycleOrder = listOf("morning", "afternoon", "evening", "night")
        val currentId = _adaptiveThemeState.value.activePalette.id
        val nextIndex = (cycleOrder.indexOf(currentId) + 1) % cycleOrder.size
        val nextId = cycleOrder[nextIndex]
        val updated = _themePreferences.value.copy(
            behavior = com.tunemind.app.ui.theme.ThemeBehavior.FIXED,
            fixedPaletteId = nextId,
            customAccentHex = null,
            enableMoodAdaptation = true
        )
        updateThemePreferences(updated)
    }

    fun setAtmospherePaletteById(paletteId: String) {
        val clean = paletteId.lowercase().trim()
        if (clean == "auto" || clean == "time") {
            val updated = _themePreferences.value.copy(
                behavior = com.tunemind.app.ui.theme.ThemeBehavior.ADAPTIVE,
                customAccentHex = null,
                enableMoodAdaptation = true
            )
            updateThemePreferences(updated)
            return
        }
        val targetId = when {
            clean.contains("morning") || clean.contains("glacier") || clean.contains("cyan") -> "morning"
            clean.contains("afternoon") || clean.contains("emerald") || clean.contains("green") -> "afternoon"
            clean.contains("evening") || clean.contains("solar") || clean.contains("amber") || clean.contains("gold") -> "evening"
            clean.contains("night") || clean.contains("violet") || clean.contains("purple") -> "night"
            else -> "night"
        }
        val updated = _themePreferences.value.copy(
            behavior = com.tunemind.app.ui.theme.ThemeBehavior.FIXED,
            fixedPaletteId = targetId,
            customAccentHex = null,
            enableMoodAdaptation = true
        )
        updateThemePreferences(updated)
    }

    fun setCustomThemeAccentHex(hex: String?, behavior: com.tunemind.app.ui.theme.ThemeBehavior? = null) {
        val normalizedHex = hex?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.startsWith("#")) raw else "#$raw"
        }
        val nextBehavior = behavior ?: _themePreferences.value.behavior
        val updated = _themePreferences.value.copy(
            behavior = nextBehavior,
            customAccentHex = normalizedHex
        )
        updateThemePreferences(updated)
    }

    fun setThemeBehavior(behavior: com.tunemind.app.ui.theme.ThemeBehavior) {
        val updated = _themePreferences.value.copy(behavior = behavior)
        updateThemePreferences(updated)
    }

    fun syncAtmosphereToTrack(title: String, artist: String, explicitArtworkUrl: String? = null) {
        val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(title, artist)
        val targetPaletteId = when (mood) {
            com.tunemind.app.ui.theme.MusicalMood.CALM -> "morning"
            com.tunemind.app.ui.theme.MusicalMood.ENERGETIC -> "afternoon"
            com.tunemind.app.ui.theme.MusicalMood.UPLIFTING -> "evening"
            com.tunemind.app.ui.theme.MusicalMood.REFLECTIVE,
            com.tunemind.app.ui.theme.MusicalMood.INTENSE -> "night"
            com.tunemind.app.ui.theme.MusicalMood.UNKNOWN -> _adaptiveThemeState.value.activePalette.id
        }
        val updated = _themePreferences.value.copy(
            behavior = com.tunemind.app.ui.theme.ThemeBehavior.FIXED,
            fixedPaletteId = targetPaletteId,
            enableMoodAdaptation = true
        )
        _themePreferences.value = updated
        themePrefsRepo?.savePreferences(updated)
        recomputeAdaptiveTheme(overrideTitle = title, overrideArtist = artist)

        // Zero-token Chromatic Sync from album cover artwork
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            val key = TrackIdentityResolver.buildNormalizedMatchKey(title, artist)
            val artUrl = explicitArtworkUrl?.takeIf { it.isNotBlank() }
                ?: _trackVisuals.value[key]?.albumArtUrl
                ?: _crossAppLibraryTracks.value.firstOrNull { it.canonicalTrackKey == key }?.albumArtUrl
            if (!artUrl.isNullOrBlank()) {
                val hex = com.tunemind.app.ui.theme.AlbumArtPaletteExtractor.extractDominantAccentHex(ctx, artUrl)
                if (!hex.isNullOrBlank()) {
                    setCustomThemeAccentHex(hex, com.tunemind.app.ui.theme.ThemeBehavior.FIXED)
                }
            } else {
                visualRepo?.enqueueVisualEnrichment(listOf(title to artist))
            }
        }
    }

    @Volatile
    private var manualActiveTrackOverride: NowPlayingObservation? = null

    fun setAgentActiveTrack(
        title: String,
        artist: String,
        sourcePackage: String = "unknown_player",
        spotifyUri: String? = null,
        trackId: String? = null
    ) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim().ifBlank { "Autonomous Studio Flow" }
        val now = System.currentTimeMillis()
        val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId,
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = cleanArtist
        )
        val nowObs = NowPlayingObservation(
            title = cleanTitle,
            artist = cleanArtist,
            album = null,
            sourcePackage = sourcePackage,
            isPlaying = true,
            playbackStateCode = 3,
            positionMs = 0L,
            durationMs = 210_000L,
            speed = 1.0f,
            updatedAtMs = now,
            trackId = canonical.trackId,
            spotifyUri = canonical.spotifyUri
        )
        manualLaunchPendingTitle = cleanTitle
        manualLaunchPendingUntilMs = now + 7_000L
        manualActiveTrackOverride = nowObs
        queueMonitoredTitle = cleanTitle
        queueMonitoredArtist = cleanArtist
        queueMonitoredPackage = sourcePackage
        queueMonitoredDurationMs = 0L
        queueMonitoredMaxPosMs = 0L
        queueMonitoredStartedAtMs = now
        lastQueueHandoverSourceTitle = ""
        expectedHandoverTargetTitle = ""
        expectedHandoverTargetTrack = null
        expectedHandoverWindowUntilMs = 0L
        expectedHandoverRetryCount = 0
        queueHandoverInFlight = false
        _homeState.value = _homeState.value.copy(nowPlaying = nowObs)
        markTrackPlayedFromQueueOrExternal(cleanTitle, cleanArtist, null)
        syncAtmosphereToTrack(cleanTitle, cleanArtist)
        refreshLiveListeningStatsNow()
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_400L)
            com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
            publishAuthoritativeNowPlayingNow()
        }
    }

    fun setAgentPlaybackPaused() {
        val current = _homeState.value.nowPlaying ?: return
        val now = System.currentTimeMillis()
        val pausedObs = current.copy(
            isPlaying = false,
            playbackStateCode = 2,
            updatedAtMs = now
        )
        manualLaunchPendingTitle = null
        manualLaunchPendingUntilMs = now + 2_000L
        manualActiveTrackOverride = pausedObs
        _homeState.value = _homeState.value.copy(nowPlaying = pausedObs)
        recomputeAdaptiveTheme()
        refreshLiveListeningStatsNow()
    }

    fun setAgentPlaybackResumed() {
        val current = _homeState.value.nowPlaying ?: return
        val now = System.currentTimeMillis()
        val resumedObs = current.copy(
            isPlaying = true,
            playbackStateCode = 3,
            updatedAtMs = now
        )
        manualLaunchPendingTitle = current.title
        manualLaunchPendingUntilMs = now + 2_000L
        manualActiveTrackOverride = resumedObs
        _homeState.value = _homeState.value.copy(nowPlaying = resumedObs)
        recomputeAdaptiveTheme()
        refreshLiveListeningStatsNow()
    }

    fun restoreTrackFromVaultByTitle(titleQuery: String) {
        val clean = titleQuery.trim().lowercase()
        val match = _vaultState.value.firstOrNull {
            it.title.lowercase().contains(clean) || clean.contains(it.title.lowercase())
        } ?: _vaultState.value.firstOrNull()
        if (match != null) {
            restoreFromVault(match.id)
        }
    }

    private fun postNowPlayingStatus(msg: String?) {
        _nowPlayingActionStatus.value = msg
        statusClearJob?.cancel()
        if (!msg.isNullOrBlank()) {
            statusClearJob = viewModelScope.launch {
                kotlinx.coroutines.delay(3800L)
                if (_nowPlayingActionStatus.value == msg) {
                    _nowPlayingActionStatus.value = null
                }
            }
        }
    }

    fun clearNowPlayingActionStatus() {
        statusClearJob?.cancel()
        _nowPlayingActionStatus.value = null
    }

    fun setUpNextAutoOverrideEnabled(enabled: Boolean) {
        val ctx = AppHolder.context ?: return
        com.tunemind.app.domain.voice.AgentSettingsStore.setUpNextAutoOverrideEnabled(ctx, enabled)
        postNowPlayingStatus(
            if (enabled) "Auto Queue active · TuneMind will guard Up Next and transition smoothly"
            else "Manual Queue active · Tap a queued song or Next when ready"
        )
    }

    private fun recordConsumedQueueTrack(title: String?, artist: String?) {
        val key = ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
        if (key.isBlank() || key == "|") return
        synchronized(playedOrConsumedQueueKeys) {
            playedOrConsumedQueueKeys.remove(key)
            playedOrConsumedQueueKeys.add(key)
            while (playedOrConsumedQueueKeys.size > 40) {
                val oldest = playedOrConsumedQueueKeys.firstOrNull() ?: break
                playedOrConsumedQueueKeys.remove(oldest)
            }
        }
    }

    private fun isConsumedKey(key: String): Boolean = synchronized(playedOrConsumedQueueKeys) {
        playedOrConsumedQueueKeys.contains(key)
    }

    val currentTimeSlot: TimeSlot
        get() = TimeSlot.currentSlot()

    fun getRestoredVaultTimestampsSnapshot(): Map<String, Long> = loadRestoredVaultMapFromPrefs()

    private fun buildFreshQueueCandidates(): List<DopamineCapsuleTrack> {
        val slot = currentTimeSlot
        val nowMs = System.currentTimeMillis()
        val vault = _vaultState.value
        val coolingKeys = vault
            .filter {
                !com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                    quarantinedAtMs = it.quarantinedAtMs,
                    totalDays = it.totalDays,
                    baseSatietyScore = it.satietyScore,
                    nowMs = nowMs
                ).isGraduated
            }
            .map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }
            .toSet()

        val inAppCurrent = isInAppPlayerCurrent()
        val activeTitle = if (inAppCurrent) {
            com.tunemind.app.provider.TuneMindInAppPlayer.currentTitleFlow.value
        } else {
            _homeState.value.nowPlaying?.title
        }
        val activeArtist = if (inAppCurrent) {
            com.tunemind.app.provider.TuneMindInAppPlayer.currentArtistFlow.value
        } else {
            _homeState.value.nowPlaying?.artist
        }
        val activeKey = ListeningPlayDeduplicator.canonicalTrackKey(activeTitle, activeArtist)
        val activeCleanTitle = ListeningPlayDeduplicator.normalizeText(activeTitle)

        val excludedFresh = runCatching { _freshExcludedKeys.value }.getOrDefault(emptySet())
        val rawCandidates = mutableListOf<DopamineCapsuleTrack>()
        val seenKeys = mutableSetOf<String>()

        fun addCandidateIfEligible(track: DopamineCapsuleTrack) {
            val cleanTitle = track.title.trim()
            if (cleanTitle.length < 2) return
            val key = ListeningPlayDeduplicator.canonicalTrackKey(cleanTitle, track.artist)
            val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(cleanTitle, track.artist)
            val normTitle = ListeningPlayDeduplicator.normalizeText(cleanTitle)
            if (key == activeKey || (activeCleanTitle.isNotBlank() && normTitle == activeCleanTitle)) return
            if (key in coolingKeys) return
            if (normKey in excludedFresh || track.resolvedCanonicalTrackId() in excludedFresh) return
            if (!seenKeys.add(key)) return
            rawCandidates.add(track)
        }

        // 0. Manually added Fresh tracks (user-curated Peak Fresh)
        runCatching { _manualFreshTracks.value }.getOrDefault(emptyList()).forEach { mf ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(mf.spotifyUri)
            val mfId = mf.resolvedCanonicalTrackId()
            val plays = resolvePlayCount(mf.title, mf.artist, cleanUri, mfId)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = mf.title,
                    artist = mf.artist,
                    spotifyUri = cleanUri,
                    freshnessPercent = 99,
                    reason = "Added to Fresh · Ready now",
                    trackId = mfId,
                    playCount = plays
                )
            )
        }

        // 1. Graduated Vault comebacks (completed full cooldown & ready for peak return)
        _recentlyGraduatedVaultTracks.value.forEach { grad ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(grad.spotifyUri)
            val plays = resolvePlayCount(grad.title, grad.artist, cleanUri, grad.trackId)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = grad.title,
                    artist = grad.artist,
                    spotifyUri = cleanUri,
                    freshnessPercent = 98,
                    reason = "Fresh Comeback (${grad.totalRestedDays}d rested)",
                    trackId = grad.trackId,
                    playCount = plays
                )
            )
        }

        // 2. Played Songs in TuneMind ranked by Fresh Readiness (no completely new AI discoveries)
        val unifiedRanked = _unifiedTracks.value
            .filter { !it.isCoolingDown && it.title.isNotBlank() }
            .map { ut ->
                val readiness = com.tunemind.app.domain.vault.RestingVaultEngine.computeFreshReadiness(
                    baseFreshnessPercent = ut.freshnessPercent,
                    playCount = ut.verifiedPlayCount,
                    lastPlayedAtMs = ut.lastPlayedAtMs,
                    nowMs = nowMs
                )
                ut to readiness.readinessScore
            }
            .sortedWith(
                compareByDescending<Pair<UnifiedTrackStats, Int>> { it.second }
                    .thenBy { it.first.verifiedPlayCount }
            )

        unifiedRanked.forEach { (ut, score) ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(ut.spotifyUri)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = ut.title,
                    artist = ut.artist,
                    spotifyUri = cleanUri,
                    freshnessPercent = score,
                    reason = "Fresh · ${ut.verifiedPlayCount.coerceAtLeast(1)}x played",
                    trackId = ut.trackId,
                    playCount = ut.verifiedPlayCount
                )
            )
        }

        // 3. Fallback from User's TuneMind Playlists & Cross-App Permanent Library (Boomplay, YT Music, Spotify Passive, etc.)
        _tuneMindPlaylists.value.take(10).forEach { pl ->
            pl.tracks.forEach { tr ->
                val resolvedPlays = resolvePlayCount(tr.title, tr.artist, tr.externalUri.takeIf { it.isNotBlank() }, tr.trackId)
                val vStatus = resolveTrackVaultStatus(tr.title, tr.artist, tr.externalUri.takeIf { it.isNotBlank() }, tr.trackId)
                if (vStatus.isInVault || vStatus.isCoolingDown) return@forEach
                val readiness = com.tunemind.app.domain.vault.RestingVaultEngine.computeFreshReadiness(
                    baseFreshnessPercent = if (resolvedPlays == 0) 97 else vStatus.freshnessPercent,
                    playCount = resolvedPlays,
                    isLikedOrSaved = true,
                    sourceLabel = pl.playlist.name.take(18).ifBlank { "Playlist" },
                    nowMs = nowMs
                )
                addCandidateIfEligible(
                    DopamineCapsuleTrack(
                        title = tr.title,
                        artist = tr.artist.ifBlank { "TuneMind Playlist" },
                        spotifyUri = tr.externalUri.takeIf { it.isNotBlank() },
                        freshnessPercent = readiness.readinessScore,
                        reason = if (resolvedPlays > 0) "${pl.playlist.name.take(16).ifBlank { "Playlist" }} · ${resolvedPlays}x played" else pl.playlist.name.take(20).ifBlank { "TuneMind Playlist" },
                        trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                            trackId = tr.trackId,
                            spotifyUri = tr.externalUri.takeIf { it.isNotBlank() },
                            title = tr.title,
                            artist = tr.artist
                        ),
                        playCount = resolvedPlays
                    )
                )
            }
        }

        _crossAppLibraryTracks.value
            .sortedWith(compareByDescending<CrossAppTrackEntity> { it.isLiked }.thenByDescending { it.lastPlayedAtMs })
            .take(80)
            .forEach { lib ->
                val resolvedPlays = resolvePlayCount(lib.title, lib.artist, null, lib.trackId).coerceAtLeast(lib.playCount)
                val vStatus = resolveTrackVaultStatus(lib.title, lib.artist, null, lib.trackId)
                if (vStatus.isInVault || vStatus.isCoolingDown) return@forEach
                val playerLabel = CrossAppPlayRecorder.resolveFriendlyPlayerName(lib.primarySourcePackage)
                val readiness = com.tunemind.app.domain.vault.RestingVaultEngine.computeFreshReadiness(
                    baseFreshnessPercent = if (resolvedPlays == 0) 98 else vStatus.freshnessPercent,
                    playCount = resolvedPlays,
                    isLikedOrSaved = lib.isLiked,
                    sourceLabel = if (lib.isLiked) "Liked" else playerLabel,
                    nowMs = nowMs
                )
                val deepLink = lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                    ?: lib.deezerUrl?.takeIf { it.isNotBlank() }
                addCandidateIfEligible(
                    DopamineCapsuleTrack(
                        title = lib.title,
                        artist = lib.artist.ifBlank { playerLabel },
                        spotifyUri = deepLink,
                        freshnessPercent = readiness.readinessScore,
                        reason = if (lib.isLiked) {
                            if (resolvedPlays > 0) "Liked · ${resolvedPlays}x played" else "Liked · ${slot.label}"
                        } else {
                            "$playerLabel · ${resolvedPlays.coerceAtLeast(1)}x played"
                        },
                        trackId = lib.trackId,
                        playCount = resolvedPlays
                    )
                )
            }

        val unconsumed = rawCandidates.filter { candidate ->
            val key = ListeningPlayDeduplicator.canonicalTrackKey(candidate.title, candidate.artist)
            !isConsumedKey(key)
        }

        if (unconsumed.isNotEmpty()) {
            val rotated = if (queueRotationOffset > 0 && unconsumed.size > 1) {
                val offset = queueRotationOffset % unconsumed.size
                unconsumed.drop(offset) + unconsumed.take(offset)
            } else {
                unconsumed
            }
            if (rotated.size >= 5) return rotated.take(5)
            // Top up remaining slots only with non-recently-played candidates if unconsumed pool is small
            val recentBlockSet = synchronized(playedOrConsumedQueueKeys) {
                playedOrConsumedQueueKeys.toList().takeLast(minOf(6, maxOf(1, rawCandidates.size - 1))).toSet()
            }
            val topUp = rawCandidates.filter { candidate ->
                val key = ListeningPlayDeduplicator.canonicalTrackKey(candidate.title, candidate.artist)
                key !in recentBlockSet && rotated.none {
                    ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) == key
                }
            }
            return (rotated + topUp).take(5)
        }

        // Entire library was cycled cleanly: trim consumed history down to just the most recent few and rotate
        val keepRecent = synchronized(playedOrConsumedQueueKeys) {
            val recentOnly = playedOrConsumedQueueKeys.toList().takeLast(minOf(5, maxOf(1, rawCandidates.size - 1)))
            playedOrConsumedQueueKeys.clear()
            playedOrConsumedQueueKeys.addAll(recentOnly)
            recentOnly.toSet()
        }
        val recycled = rawCandidates.filter { candidate ->
            val key = ListeningPlayDeduplicator.canonicalTrackKey(candidate.title, candidate.artist)
            key !in keepRecent
        }
        return (if (recycled.isNotEmpty()) recycled else rawCandidates).take(5)
    }

    private fun syncUpNextQueueWithSources(forceRebuild: Boolean = false) {
        if (forceRebuild) {
            queueSyncJob?.cancel()
        } else if (queueSyncJob?.isActive == true) {
            return
        }
        queueSyncJob = viewModelScope.launch(Dispatchers.Default) {
            computeUpNextQueueSync(forceRebuild)
        }
    }

    private fun computeUpNextQueueSync(forceRebuild: Boolean = false) {
        val slot = currentTimeSlot
        val inAppCurrent = isInAppPlayerCurrent()
        val activeTitle = if (inAppCurrent) {
            com.tunemind.app.provider.TuneMindInAppPlayer.currentTitleFlow.value
        } else {
            _homeState.value.nowPlaying?.title
        }
        val activeArtist = if (inAppCurrent) {
            com.tunemind.app.provider.TuneMindInAppPlayer.currentArtistFlow.value
        } else {
            _homeState.value.nowPlaying?.artist
        }
        if (!activeTitle.isNullOrBlank()) {
            recordConsumedQueueTrack(activeTitle, activeArtist)
        }

        val nowMs = System.currentTimeMillis()
        val coolingKeys = _vaultState.value
            .filter {
                !com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                    quarantinedAtMs = it.quarantinedAtMs,
                    totalDays = it.totalDays,
                    baseSatietyScore = it.satietyScore,
                    nowMs = nowMs
                ).isGraduated
            }
            .map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }
            .toSet()

        val excludedFresh = _freshExcludedKeys.value
        val activeNormTitle = ListeningPlayDeduplicator.normalizeText(activeTitle)
        val existingFiltered = if (forceRebuild) {
            emptyList()
        } else {
            _upNextQueueState.value.tracks.filter { item ->
                val key = ListeningPlayDeduplicator.canonicalTrackKey(item.title, item.artist)
                val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(item.title, item.artist)
                val itemNormTitle = ListeningPlayDeduplicator.normalizeText(item.title)
                val matchesActive = activeNormTitle.isNotBlank() && itemNormTitle == activeNormTitle
                val isExcluded = normKey in excludedFresh || item.resolvedCanonicalTrackId() in excludedFresh
                !matchesActive && !isConsumedKey(key) && key !in coolingKeys && !isExcluded
            }
        }

        val candidates = buildFreshQueueCandidates()
        val combined = existingFiltered.toMutableList()
        val existingKeys = combined.map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }.toMutableSet()
        for (cand in candidates) {
            if (combined.size >= 5) break
            val key = ListeningPlayDeduplicator.canonicalTrackKey(cand.title, cand.artist)
            if (existingKeys.add(key)) {
                combined.add(cand)
            }
        }

        val finalized = combined.take(5)
        _upNextQueueState.value = DopamineCapsuleState(
            slotLabel = slot.label.uppercase(),
            headline = "${slot.label} Dopamine Capsule",
            tracks = finalized
        )

        val head = finalized.firstOrNull()
        val ctx = AppHolder.context
        if (ctx != null && head != null) {
            val headKey = ListeningPlayDeduplicator.canonicalTrackKey(head.title, head.artist)
            if (headKey != preResolvedQueueKey) {
                preResolvedQueueKey = headKey
                viewModelScope.launch(Dispatchers.IO) {
                    val resolved = runCatching {
                        com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                            context = ctx,
                            cleanTitle = head.title,
                            cleanArtist = head.artist,
                            fullQuery = "${head.title} ${head.artist}".trim()
                        )
                    }.getOrNull()
                    val link = resolved?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                        ?: resolved?.audiusStreamUrl?.takeIf { it.isNotBlank() }
                        ?: resolved?.previewUrl?.takeIf { it.isNotBlank() }
                        ?: resolved?.deezerUrl?.takeIf { it.isNotBlank() }
                    if (!link.isNullOrBlank()) {
                        val curList = _upNextQueueState.value.tracks
                        var patchedAny = false
                        val patched = curList.map { item ->
                            if (ListeningPlayDeduplicator.canonicalTrackKey(item.title, item.artist) == headKey &&
                                item.spotifyUri.isNullOrBlank()
                            ) {
                                patchedAny = true
                                item.copy(spotifyUri = link)
                            } else {
                                item
                            }
                        }
                        if (patchedAny) {
                            _upNextQueueState.value = _upNextQueueState.value.copy(tracks = patched)
                        }
                    }
                }
            }
        }
    }

    fun compileDopamineCapsule(): DopamineCapsuleState {
        val current = _upNextQueueState.value
        if (current.tracks.isEmpty()) {
            computeUpNextQueueSync(forceRebuild = true)
        }
        return _upNextQueueState.value
    }

    fun markTrackPlayedFromQueueOrExternal(
        title: String?,
        artist: String?,
        spotifyUri: String? = null
    ) {
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isBlank()) return
        recordConsumedQueueTrack(cleanTitle, artist)
        val filtered = _upNextQueueState.value.tracks.filterNot { item ->
            ListeningPlayDeduplicator.isSameTrack(
                item.title,
                item.artist,
                item.spotifyUri,
                cleanTitle,
                artist,
                spotifyUri
            )
        }
        if (filtered.size != _upNextQueueState.value.tracks.size) {
            _upNextQueueState.value = _upNextQueueState.value.copy(tracks = filtered)
        }
        syncUpNextQueueWithSources(forceRebuild = false)
    }

    fun removeFromUpNextQueue(track: DopamineCapsuleTrack) {
        recordConsumedQueueTrack(track.title, track.artist)
        val remaining = _upNextQueueState.value.tracks.filterNot {
            ListeningPlayDeduplicator.isSameTrack(
                it.title,
                it.artist,
                it.spotifyUri,
                track.title,
                track.artist,
                track.spotifyUri
            )
        }
        _upNextQueueState.value = _upNextQueueState.value.copy(tracks = remaining)
        syncUpNextQueueWithSources(forceRebuild = false)
        postNowPlayingStatus("Removed \"${track.title}\" · Next track ready")
    }

    fun moveUpNextTrackToTop(track: DopamineCapsuleTrack) {
        val current = _upNextQueueState.value.tracks.toMutableList()
        val idx = current.indexOfFirst {
            ListeningPlayDeduplicator.isSameTrack(
                it.title,
                it.artist,
                it.spotifyUri,
                track.title,
                track.artist,
                track.spotifyUri
            )
        }
        if (idx > 0) {
            val moved = current.removeAt(idx)
            current.add(0, moved)
            _upNextQueueState.value = _upNextQueueState.value.copy(tracks = current)
            postNowPlayingStatus("\"${track.title}\" moved to #1 Up Next")
        }
    }

    fun enqueueUpNextTrack(
        title: String,
        artist: String,
        spotifyUri: String? = null,
        reason: String = "Queued by TuneMind",
        freshnessPercent: Int = 92,
        playNext: Boolean = true
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val cleanArtist = artist.trim().ifBlank { "Your Library" }
        val key = ListeningPlayDeduplicator.canonicalTrackKey(cleanTitle, cleanArtist)
        synchronized(playedOrConsumedQueueKeys) {
            playedOrConsumedQueueKeys.remove(key)
        }
        val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = cleanArtist
        )
        val newItem = DopamineCapsuleTrack(
            title = cleanTitle,
            artist = cleanArtist,
            spotifyUri = canonical.spotifyUri,
            freshnessPercent = freshnessPercent.coerceIn(50, 99),
            reason = reason,
            trackId = canonical.trackId
        )
        val current = _upNextQueueState.value.tracks.filterNot {
            ListeningPlayDeduplicator.isSameTrack(
                it.title,
                it.artist,
                it.spotifyUri,
                cleanTitle,
                cleanArtist,
                spotifyUri
            )
        }.toMutableList()
        if (playNext) {
            current.add(0, newItem)
        } else {
            current.add(newItem)
        }
        _upNextQueueState.value = _upNextQueueState.value.copy(tracks = current.take(15))
        postNowPlayingStatus("Queued \"$cleanTitle\" up next")
    }

    fun refreshUpNextQueue() {
        _upNextQueueState.value.tracks.forEach { t ->
            recordConsumedQueueTrack(t.title, t.artist)
        }
        queueRotationOffset += 1
        syncUpNextQueueWithSources(forceRebuild = true)
        postNowPlayingStatus("Up Next refreshed with fresh tracks")
    }

    fun getRecommendedFreshPoolForQueue(limit: Int = 6): List<DopamineCapsuleTrack> {
        val queuedKeys = _upNextQueueState.value.tracks
            .map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }
            .toSet()
        val nowMs = System.currentTimeMillis()
        val coolingKeys = _vaultState.value
            .filter {
                !com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                    quarantinedAtMs = it.quarantinedAtMs,
                    totalDays = it.totalDays,
                    baseSatietyScore = it.satietyScore,
                    nowMs = nowMs
                ).isGraduated
            }
            .map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }
            .toSet()
        val activeTitle = _homeState.value.nowPlaying?.title
        val activeArtist = _homeState.value.nowPlaying?.artist
        val activeKey = ListeningPlayDeduplicator.canonicalTrackKey(activeTitle, activeArtist)
        val excluded = _freshExcludedKeys.value

        val recs = mutableListOf<DopamineCapsuleTrack>()
        val seen = mutableSetOf<String>()
        fun tryAdd(item: DopamineCapsuleTrack) {
            if (recs.size >= limit) return
            val key = ListeningPlayDeduplicator.canonicalTrackKey(item.title, item.artist)
            val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(item.title, item.artist)
            if (key in queuedKeys || key in coolingKeys || key == activeKey) return
            if (normKey in excluded || item.resolvedCanonicalTrackId() in excluded) return
            if (!seen.add(key)) return
            recs.add(item)
        }

        _manualFreshTracks.value.forEach { mf ->
            tryAdd(
                DopamineCapsuleTrack(
                    title = mf.title,
                    artist = mf.artist,
                    spotifyUri = TrackIdentityResolver.normalizeSpotifyUri(mf.spotifyUri),
                    freshnessPercent = 99,
                    reason = "Fresh Pick · Ready now",
                    trackId = mf.resolvedCanonicalTrackId()
                )
            )
        }
        _unifiedTracks.value.filter { !it.isCoolingDown }.sortedByDescending { it.freshnessPercent }.forEach { ut ->
            tryAdd(
                DopamineCapsuleTrack(
                    title = ut.title,
                    artist = ut.artist,
                    spotifyUri = TrackIdentityResolver.normalizeSpotifyUri(ut.spotifyUri),
                    freshnessPercent = ut.freshnessPercent,
                    reason = "${ut.freshnessPercent}% fresh · ${ut.verifiedPlayCount.coerceAtLeast(1)}x played",
                    trackId = ut.trackId
                )
            )
        }
        _tuneMindPlaylists.value.take(10).forEach { pl ->
            pl.tracks.forEach { tr ->
                val extUri = tr.externalUri.takeIf { it.isNotBlank() }
                val plays = resolvePlayCount(tr.title, tr.artist, extUri, tr.trackId)
                tryAdd(
                    DopamineCapsuleTrack(
                        title = tr.title,
                        artist = tr.artist.ifBlank { "TuneMind Playlist" },
                        spotifyUri = extUri,
                        freshnessPercent = if (plays == 0) 97 else (100 - minOf(80, plays * 18)),
                        reason = if (plays > 0) "${pl.playlist.name.take(16)} · ${plays}x played" else pl.playlist.name.take(20),
                        trackId = TrackIdentityResolver.resolveCanonicalTrackId(tr.trackId, extUri, tr.title, tr.artist)
                    )
                )
            }
        }
        _crossAppLibraryTracks.value
            .sortedWith(compareByDescending<CrossAppTrackEntity> { it.isLiked }.thenByDescending { it.lastPlayedAtMs })
            .take(50)
            .forEach { lib ->
                val plays = resolvePlayCount(lib.title, lib.artist, null, lib.trackId).coerceAtLeast(lib.playCount)
                val playerLabel = CrossAppPlayRecorder.resolveFriendlyPlayerName(lib.primarySourcePackage)
                val link = lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                    ?: lib.deezerUrl?.takeIf { it.isNotBlank() }
                tryAdd(
                    DopamineCapsuleTrack(
                        title = lib.title,
                        artist = lib.artist.ifBlank { playerLabel },
                        spotifyUri = link,
                        freshnessPercent = if (plays == 0) 99 else (100 - minOf(80, plays * 18)),
                        reason = if (lib.isLiked) "Liked · ${if (plays == 0) "Untouched" else "$plays plays"}" else "$playerLabel · $plays plays",
                        trackId = lib.trackId
                    )
                )
            }
        return recs
    }

    fun autoAddRecommendedFreshToQueue(count: Int = 2) {
        val candidates = getRecommendedFreshPoolForQueue(limit = count.coerceIn(1, 5))
        if (candidates.isEmpty()) {
            postNowPlayingStatus("No additional Fresh tracks available to add")
            return
        }
        candidates.forEach { item ->
            enqueueUpNextTrack(
                title = item.title,
                artist = item.artist,
                spotifyUri = item.spotifyUri,
                reason = item.reason,
                freshnessPercent = item.freshnessPercent,
                playNext = false
            )
        }
        postNowPlayingStatus("Added ${candidates.size} recommended Fresh tracks to Up Next")
    }

    fun playQueuedTrack(
        track: DopamineCapsuleTrack,
        targetPlayer: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val ctx = AppHolder.context ?: return
        val resolvedPkg = targetPlayer?.takeIf { it.isNotBlank() }
            ?: resolveActiveMediaPackage()
            ?: "tunemind_inapp"

        markTrackPlayedFromQueueOrExternal(track.title, track.artist, track.spotifyUri)
        val now = System.currentTimeMillis()
        queueMonitoredTitle = track.title
        queueMonitoredArtist = track.artist
        queueMonitoredPackage = resolvedPkg
        queueMonitoredDurationMs = 0L
        queueMonitoredMaxPosMs = 0L
        lastQueueHandoverAtMs = now
        expectedHandoverTargetTitle = track.title
        expectedHandoverTargetTrack = track
        expectedHandoverWindowUntilMs = now + 15_000L
        expectedHandoverRetryCount = 0

        val resolvedIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = track.trackId.ifBlank { null },
            spotifyUri = track.spotifyUri,
            title = track.title,
            artist = track.artist
        )
        setAgentActiveTrack(
            title = track.title,
            artist = track.artist,
            sourcePackage = resolvedPkg,
            spotifyUri = resolvedIdentity.spotifyUri,
            trackId = resolvedIdentity.trackId
        )
        expectedHandoverTargetTitle = track.title
        expectedHandoverTargetTrack = track
        expectedHandoverWindowUntilMs = now + 20_000L
        expectedHandoverRetryCount = 0
        postNowPlayingStatus("Playing \"${track.title}\" from Up Next…")

        viewModelScope.launch(Dispatchers.IO) {
            var ok = false
            var msg = "Launched \"${track.title}\""
            try {
                // Ensure current external/internal song stops before launching queued track
                pauseExternalAndLocalMediaQuietly(ctx, resolvedPkg)
                val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx)
                val matchedProvider = registry.getAllProviders().firstOrNull {
                    it.packageName.equals(resolvedPkg, ignoreCase = true)
                }
                val providerId = matchedProvider?.providerId ?: "pkg_$resolvedPkg"
                val displayName = matchedProvider?.displayName
                    ?: ListeningPlayDeduplicator.resolveAppDisplayName(resolvedPkg)

                val isSpotifyTarget = resolvedPkg.contains("spotify", ignoreCase = true)
                val isYouTubeTarget = resolvedPkg.contains("youtube", ignoreCase = true)
                val isAudiomackTarget = resolvedPkg.contains("audiomack", ignoreCase = true)
                val directSpotifyUri = com.tunemind.app.provider.MusicPlayerAutoLauncher.extractSpotifyTrackUri(track.spotifyUri)
                val openResolved = if (isSpotifyTarget && !directSpotifyUri.isNullOrBlank()) {
                    null
                } else {
                    com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                        context = ctx,
                        cleanTitle = track.title,
                        cleanArtist = track.artist,
                        fullQuery = "${track.title} ${track.artist}".trim()
                    )
                }
                val resolvedDeepLink = when {
                    isYouTubeTarget && !openResolved?.ytmVideoId.isNullOrBlank() ->
                        "https://music.youtube.com/watch?v=${openResolved!!.ytmVideoId}"
                    isSpotifyTarget && !directSpotifyUri.isNullOrBlank() -> directSpotifyUri
                    isSpotifyTarget && !openResolved?.spotifyUri.isNullOrBlank() -> openResolved!!.spotifyUri
                    isSpotifyTarget && !openResolved?.spotifyUrl.isNullOrBlank() -> openResolved!!.spotifyUrl
                    isAudiomackTarget && !openResolved?.audiomackUrl.isNullOrBlank() -> openResolved!!.audiomackUrl
                    !isSpotifyTarget && !openResolved?.deezerUrl.isNullOrBlank() -> openResolved!!.deezerUrl
                    !track.spotifyUri.isNullOrBlank() -> track.spotifyUri
                    else -> null
                }

                val recTrack = com.tunemind.app.domain.recommendation.RecommendedTrack(
                    id = resolvedIdentity.trackId,
                    title = track.title,
                    artist = track.artist,
                    reason = track.reason,
                    confidence = (track.freshnessPercent / 100f).coerceIn(0.5f, 0.99f),
                    category = com.tunemind.app.domain.recommendation.RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = providerId,
                    providerTrackId = openResolved?.ytmVideoId ?: resolvedIdentity.spotifyUri,
                    observationCountBasis = resolvePlayCount(track.title, track.artist, resolvedIdentity.spotifyUri, resolvedIdentity.trackId),
                    trackId = resolvedIdentity.trackId,
                    spotifyUri = openResolved?.spotifyUri ?: resolvedIdentity.spotifyUri
                )
                val result = com.tunemind.app.provider.MusicPlayerAutoLauncher.launchWithAutoPlay(
                    context = ctx,
                    providerId = providerId,
                    displayName = displayName,
                    packageName = resolvedPkg,
                    track = recTrack,
                    query = "${track.title} ${track.artist}".trim(),
                    deepLinkUri = resolvedDeepLink
                )
                _playbackLaunchFeedback.value = result
                ok = result is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched
                msg = when (result) {
                    is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched ->
                        "Now playing \"${track.title}\" on ${result.providerDisplayName}"
                    is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed ->
                        result.reason
                    else -> "Launched \"${track.title}\""
                }
                postNowPlayingStatus(msg)
                kotlinx.coroutines.delay(450L)
                com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
                publishAuthoritativeNowPlayingNow()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                msg = "Could not launch \"${track.title}\": ${e.message}"
                postNowPlayingStatus(msg)
            } finally {
                onComplete?.invoke(ok, msg)
            }
        }
    }

    fun playNextFromQueue(targetPlayer: String? = null): Boolean {
        val currentQueue = compileDopamineCapsule().tracks
        val nextTrack = currentQueue.firstOrNull() ?: return false
        playQueuedTrack(nextTrack, targetPlayer)
        return true
    }

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

        // Observe audio devices and volume from Room DB
        viewModelScope.launch {
            try {
                audioDao?.observeRecentSessions()?.collect { sessions ->
                    val active = sessions.firstOrNull { it.disconnectedAt == null }
                    refreshHearingInsights()
                    _hearingInsightsState.value = _hearingInsightsState.value.copy(
                        recentAudioDevices = sessions,
                        activeAudioDevice = active
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeAudio notice: ${t.message}")
            }
        }

        viewModelScope.launch {
            try {
                audioDao?.observeRecentVolume()?.collect { obs ->
                    refreshHearingInsights()
                    _hearingInsightsState.value = _hearingInsightsState.value.copy(
                        recentVolumeObs = obs
                    )
                }
            } catch (_: Throwable) {}
        }

        // Foreground live volume + headset + Bluetooth monitor so UI reacts immediately even if listener service is asleep
        registerForegroundHearingMonitor()
        refreshHearingInsights()

        refreshReadiness()
        refreshClaudeState()
        requestClaudeSessionReasoning()

        // Hook in-app 30s preview / local playback completion into For You closed-loop learning AND Up Next Auto-Play
        val completionListener: (String, String, String) -> Unit = { doneTitle, doneArtist, pkg ->
            val session = activeTrackedForYouSession
            if (session != null &&
                !session.reportedOutcome &&
                ListeningPlayDeduplicator.isSameTrack(doneTitle, doneArtist, null, session.rec.title, session.rec.artist, session.rec.spotifyUri)
            ) {
                val listenedMs = (System.currentTimeMillis() - (session.matchedNowPlayingAtMs ?: session.startedAtMs)).coerceAtLeast(25_000L)
                finalizeTrackedForYouOutcome(session, outcome = "COMPLETED", listenedMs = listenedMs, durationMs = 30_000L)
            }
            val ctx = AppHolder.context
            if (ctx != null &&
                com.tunemind.app.domain.voice.AgentSettingsStore.isUpNextAutoOverrideEnabled(ctx)
            ) {
                triggerAutoQueueHandover(
                    finishedTitle = doneTitle,
                    targetPkg = pkg.ifBlank { resolveActiveMediaPackage() }
                )
            }
        }
        inAppCompletionQueueListener = completionListener
        com.tunemind.app.provider.TuneMindInAppPlayer.addOnTrackCompletedListener(completionListener)

        // Real-time Auto Queue Override + immediate Now Playing sync loop
        val snapListener: (com.tunemind.app.media.ActivePlaybackSnapshot) -> Unit = { snap ->
            publishAuthoritativeNowPlayingNow(snap)
            handleSnapshotForUpNextAutoOverride(snap)
        }
        snapshotMonitorListener = snapListener
        com.tunemind.app.service.TuneMindListenerService.addPlaybackSnapshotListener(snapListener)
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val ctx = AppHolder.context
                val overrideOn = ctx != null && com.tunemind.app.domain.voice.AgentSettingsStore.isUpNextAutoOverrideEnabled(ctx)
                val isPlayingNow = _homeState.value.nowPlaying?.isPlaying == true
                kotlinx.coroutines.delay(
                    when {
                        overrideOn && (isPlayingNow || expectedHandoverTargetTrack != null) -> 550L
                        uiForegroundActive -> 1200L
                        else -> 3500L
                    }
                )
                if (ctx == null) continue
                val listener = com.tunemind.app.service.TuneMindListenerService.instance
                if (uiForegroundActive || (overrideOn && isPlayingNow)) {
                    listener?.observerOrNull()?.refreshSessions()
                }
                val snap = listener?.getActivePlaybackSnapshot()
                if (snap != null) {
                    publishAuthoritativeNowPlayingNow(snap)
                    if (overrideOn) {
                        handleSnapshotForUpNextAutoOverride(snap)
                    }
                } else {
                    publishAuthoritativeNowPlayingNow()
                }
            }
        }

        // Observe AI Recommendation Crate from Room DB
        viewModelScope.launch(Dispatchers.Default) {
            try {
                aiRecDao?.observeAll()?.collect { crate ->
                    _aiDiscoveryCrate.value = crate
                }
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeCrate notice: ${t.message}")
            }
        }

        viewModelScope.launch {
            try {
                aiRecDao?.observeSaved()?.collect { saved ->
                    _savedAiRecommendations.value = saved
                }
            } catch (t: Throwable) {
                android.util.Log.w("TuneMindVM", "observeSaved notice: ${t.message}")
            }
        }

        viewModelScope.launch {
            try {
                aiRecDao?.observePlayedCount()?.collect { count ->
                    _playedRecCount.value = count
                }
            } catch (_: Throwable) {}
        }

        viewModelScope.launch {
            try {
                aiRecDao?.observeSavedCount()?.collect { count ->
                    _savedRecCount.value = count
                }
            } catch (_: Throwable) {}
        }

        viewModelScope.launch {
            try {
                aiRecDao?.observeDismissedCount()?.collect { count ->
                    _dismissedRecCount.value = count
                }
            } catch (_: Throwable) {}
        }

        // Initialize Telemetry Reasoning Report & schedule periodic Claude + Noon Drop Workers
        AppHolder.context?.let { ctx ->
            _reasoningReport.value = com.tunemind.app.domain.ai.ClaudeReasoningEngine.getLatestReport(ctx)
            com.tunemind.app.domain.ai.ClaudeTelemetryReasoningWorker.schedulePeriodic(ctx)
            com.tunemind.app.domain.ai.ForYouDailyDropWorker.scheduleNextNoonDrop(ctx)
        }
        refreshTelemetryReasoningReport()
        recomputeAdaptiveTheme()
        triggerChronoDiscovery(forceRefresh = false)

        // 60-second periodic watcher: auto-rotates For You AI discovery at 12:00 PM local time and tracks daypart shifts
        viewModelScope.launch {
            var lastObservedSlot = currentTimeSlot
            var lastObservedNoonCycle = com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val currentSlot = currentTimeSlot
                val currentNoonCycle = com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
                if (currentNoonCycle != lastObservedNoonCycle) {
                    lastObservedNoonCycle = currentNoonCycle
                    lastObservedSlot = currentSlot
                    recomputeAdaptiveTheme()
                    triggerChronoDiscovery(forceRefresh = true)
                } else if (currentSlot != lastObservedSlot) {
                    lastObservedSlot = currentSlot
                    recomputeAdaptiveTheme()
                } else {
                    recomputeAdaptiveTheme()
                }
            }
        }

        // Observe Permanent Cross-App Library, Play Logs, TuneMind Playlists & Universal Visuals
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val pDao = tuneMindPlaylistDao ?: return@runCatching
                combine(
                    pDao.observeAllPlaylists(),
                    pDao.observeAllPlaylistTracks()
                ) { playlists, tracks ->
                    val grouped = tracks.groupBy { it.playlistId }
                    playlists.map { pl ->
                        TuneMindPlaylistWithTracks(
                            playlist = pl,
                            tracks = grouped[pl.playlistId].orEmpty()
                        )
                    }
                }.collect {
                    _tuneMindPlaylists.value = it
                }
            }.onFailure { err ->
                android.util.Log.e("TuneMindDB", "Failed observing TuneMind playlists: ${err.message}", err)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                crossAppDao?.observeAllLibraryTracks()?.collect { libTracks ->
                    _crossAppLibraryTracks.value = libTracks
                    _detectedPlayerTabs.value = computeDetectedPlayerTabs(libTracks)
                    visualRepo?.let { vr ->
                        _tasteShiftSummary.value = vr.computeTasteShiftSummary(
                            libraryTracks = libTracks,
                            visualsByKey = _trackVisuals.value,
                            artistsByKey = _artistProfiles.value
                        )
                    }
                    syncUpNextQueueWithSources(forceRebuild = false)
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                crossAppDao?.observePlayLogs(500)?.collect { logs ->
                    _crossAppPlayLogs.value = logs
                }
            }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                visualRepo?.observeTrackVisualsMap()?.debounce(600L)?.collect { visualsMap ->
                    _trackVisuals.value = visualsMap
                    visualRepo?.let { vr ->
                        _tasteShiftSummary.value = vr.computeTasteShiftSummary(
                            libraryTracks = _crossAppLibraryTracks.value,
                            visualsByKey = visualsMap,
                            artistsByKey = _artistProfiles.value
                        )
                    }
                }
            }
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                visualRepo?.observeArtistProfilesMap()?.debounce(600L)?.collect { artistsMap ->
                    _artistProfiles.value = artistsMap
                    visualRepo?.let { vr ->
                        _tasteShiftSummary.value = vr.computeTasteShiftSummary(
                            libraryTracks = _crossAppLibraryTracks.value,
                            visualsByKey = _trackVisuals.value,
                            artistsByKey = artistsMap
                        )
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Stagger heavy network & visual hydration slightly so first frame + taps stay 100% responsive
            kotlinx.coroutines.delay(1_200L)
            runCatching {
                refreshLiveListeningStatsNow()
                val missingArt = _crossAppLibraryTracks.value
                    .filter { it.albumArtUrl.isNullOrBlank() }
                    .take(16)
                    .map { it.title to it.artist }
                if (missingArt.isNotEmpty()) {
                    visualRepo?.enqueueVisualEnrichment(missingArt)
                }
                AppHolder.context?.let { ctx ->
                    com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.syncCrossAppLibraryToSupabase(ctx)
                }
            }
        }

        // Live listening clock: updates active Today/7d/30d/All-Time durations smoothly while UI is visible
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(12_000L)
                val playingAny = _homeState.value.nowPlaying?.isPlaying == true ||
                    com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
                    com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
                if (playingAny && uiForegroundActive) {
                    refreshLiveListeningStatsNow()
                }
            }
        }

        // Unified Authoritative Pipeline: Combine Device MediaSessions + Permanent Cross-App Library & Play Logs
        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val obsFlow = repo?.observeRecent() ?: flowOf(emptyList())
                val crossAppTracksFlow = crossAppDao?.observeAllLibraryTracks() ?: flowOf(emptyList())
                val crossAppLogsFlow = crossAppDao?.observePlayLogs(500) ?: flowOf(emptyList())

                combine(
                    obsFlow,
                    crossAppTracksFlow,
                    crossAppLogsFlow,
                    com.tunemind.app.domain.voice.AgentSettingsStore.mergeDuplicatePlaysEnabled,
                    com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity
                ) { rows, crossTracks, crossLogs, mergeDuplicates, repetitionSensitivity ->
                    listOf(rows, crossTracks, crossLogs, mergeDuplicates, repetitionSensitivity)
                }.debounce(250L).collect { values ->
                    @Suppress("UNCHECKED_CAST")
                    val rows = values[0] as List<PlaybackObservation>
                    @Suppress("UNCHECKED_CAST")
                    val crossTracks = values[1] as List<CrossAppTrackEntity>
                    @Suppress("UNCHECKED_CAST")
                    val crossLogs = values[2] as List<CrossAppPlayLogEntity>
                    val mergeDuplicates = values[3] as Boolean
                    val repetitionSensitivity = values[4] as Float

                    cachedRowsForStats = rows
                    cachedCrossAppTracksForStats = crossTracks
                    cachedCrossAppLogsForStats = crossLogs
                    cachedMergeDuplicatesForStats = mergeDuplicates
                    cachedRepetitionSensitivityForStats = repetitionSensitivity

                    crossAppRecorder?.backfillFromObservationsIfEmpty(rows)

                    val memory: ListeningMemory
                    val coverage: CoverageAssessment
                    val nowPlayingObs = resolveBestNowPlayingForStats(rows)

                    val restoredMap = loadRestoredVaultMapFromPrefs()
                    val activeVaultFreshness = buildActiveVaultFreshnessMap(System.currentTimeMillis())
                    val snapshot: com.tunemind.app.domain.memory.DeduplicatedListeningSnapshot
                    withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        memory = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)
                        coverage = CoverageAssessor.assessFromRawEvents(rows)
                        snapshot = ListeningPlayDeduplicator.buildSnapshot(
                            rawObservations = rows,
                            crossAppTracks = crossTracks,
                            crossAppLogs = crossLogs,
                            nowPlayingTitle = nowPlayingObs?.title,
                            nowPlayingArtist = nowPlayingObs?.artist,
                            nowPlayingSourcePkg = nowPlayingObs?.sourcePackage,
                            isMusicPlayingNow = nowPlayingObs?.isPlaying == true,
                            nowMs = System.currentTimeMillis(),
                            mergeDuplicatePlays = mergeDuplicates,
                            repetitionSensitivity = repetitionSensitivity,
                            nowPlayingPositionMs = nowPlayingObs?.positionMs ?: 0L,
                            nowPlayingDurationMs = nowPlayingObs?.durationMs ?: 0L,
                            nowPlayingUpdatedAtMs = nowPlayingObs?.updatedAtMs ?: 0L,
                            restoredAtByKey = restoredMap,
                            activeVaultFreshnessByKey = activeVaultFreshness
                        )
                    }

                    _unifiedTracks.value = snapshot.unifiedTracks
                    _cleanPlayHistory.value = snapshot.cleanPlayHistory
                    _listeningStats.value = snapshot.stats
                    _playCountByKey.value = snapshot.playCountByTrackKey
                    _playCountByUri.value = snapshot.playCountByUri
                    _playCountByTrackId.value = snapshot.playCountByTrackId
                    reconcileVaultLifecycle(
                        unifiedList = snapshot.unifiedTracks,
                        cleanEvents = snapshot.cleanPlayHistory,
                        syncBackend = true
                    )
                    if (!hasHydratedRemoteVault) {
                        hasHydratedRemoteVault = true
                        hydrateVaultFromBackendIfNeeded()
                    }
                    refreshHearingInsights()

                    // Build authoritative deduplicated TrackMemoryEntry list (accurate play counts everywhere)
                    val recent = snapshot.unifiedTracks
                        .map { ut ->
                            TrackMemoryEntry(
                                entryId = ut.entryId,
                                sourcePackage = ut.primarySourcePackage,
                                providerTrackId = TrackIdentityResolver.normalizeSpotifyUri(ut.spotifyUri),
                                title = ut.title,
                                artist = ut.artist,
                                album = ut.album,
                                distinctAppearanceCount = ut.verifiedPlayCount.coerceAtLeast(0),
                                firstObservedAppearanceAtMs = ut.firstPlayedAtMs,
                                lastObservedAppearanceAtMs = ut.lastPlayedAtMs,
                                supportingObservationIds = emptyList(),
                                identityBasis = if (!ut.spotifyUri.isNullOrBlank()) TrackIdentityBasis.PROVIDER_TRACK_ID else TrackIdentityBasis.METADATA_FINGERPRINT,
                                evidenceQuality = com.tunemind.app.domain.model.MemoryEvidenceQuality.SUPPORTED_IDENTITY,
                                historyMayBeIncomplete = false,
                                appearances = emptyList(),
                                trackId = ut.trackId
                            )
                        }
                        .take(80)

                    val summary = memory.summary
                    _homeState.value = HomeState(
                        identifiableTrackCount = snapshot.unifiedTracks.size,
                        distinctAppearanceCount = snapshot.stats.totalVerifiedPlays,
                        recurringTrackCount = snapshot.unifiedTracks.count { it.verifiedPlayCount >= 2 },
                        recentTracks = recent,
                        nowPlaying = nowPlayingObs,
                        coverage = coverage,
                        isLoading = false,
                    )
                    recomputeAdaptiveTheme()

                    // Universal Profile Engine (works for ANY music player — YouTube Music, Boomplay, Spotify Passive, Audiomack, local MP3)
                    val enrichPairs = buildList {
                        nowPlayingObs?.title?.takeIf { it.isNotBlank() }?.let { activeTitle ->
                            add(activeTitle to (nowPlayingObs.artist ?: ""))
                        }
                        recent.take(15).forEach { entry ->
                            entry.title?.takeIf { it.isNotBlank() }?.let { t ->
                                add(t to (entry.artist ?: ""))
                            }
                        }
                    }
                    if (enrichPairs.isNotEmpty()) {
                        visualRepo?.enqueueVisualEnrichment(enrichPairs)
                    }

                    _historyState.value = HistoryState(
                        tracks = recent,
                        filterMode = _historyState.value.filterMode,
                        isLoading = false,
                    )

                    // Profile data
                    var sigText = "No signal data yet."
                    var dynText = "No dynamics data yet."
                    var resText = "No research data yet."
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
                        observationCount = summary.distinctReconstructedAppearanceCount.coerceAtLeast(rows.size),
                    )

                    evaluateActiveForYouSessionTransition(nowPlayingObs)
                    syncUpNextQueueWithSources(forceRebuild = false)
                    refreshRecommendations()

                    // At most once per 12:00 PM daily cycle, trigger discovery ONLY if Room has no drop yet
                    val observedArtists = recent.mapNotNull { it.artist }.filter { it.isNotBlank() }.distinct()
                    if (observedArtists.isNotEmpty()) {
                        val todayNoonKey = com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
                        if (autoTriggeredNoonCycleKey != todayNoonKey && !_isDiscovering.value) {
                            val hasDropInFlow = _aiDiscoveryCrate.value.any {
                                it.dropDateKey == todayNoonKey && !it.isDismissed
                            }
                            if (hasDropInFlow) {
                                autoTriggeredNoonCycleKey = todayNoonKey
                            } else {
                                val hasDropInRoom = withContext(Dispatchers.IO) {
                                    (aiRecDao?.getByDropDateSnapshot(todayNoonKey) ?: emptyList()).isNotEmpty()
                                }
                                autoTriggeredNoonCycleKey = todayNoonKey
                                if (!hasDropInRoom) {
                                    triggerChronoDiscovery(forceRefresh = false)
                                }
                            }
                        }
                    }
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

    private fun resolveActiveMediaPackage(): String {
        val snapPkg = com.tunemind.app.service.TuneMindListenerService.instance
            ?.getActivePlaybackSnapshot()?.packageName?.takeIf { it.isNotBlank() }
        if (snapPkg != null) return snapPkg
        val nowPkg = _homeState.value.nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() }
        if (nowPkg != null) return nowPkg
        val ctx = AppHolder.context ?: return "tunemind_inapp"
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx)
        val prefId = registry.getPreferredProviderId()
        if (prefId == "tunemind_inapp") return ctx.packageName
        return registry.getProvider(prefId)
            .packageName
            .takeIf { it.isNotBlank() && it != "*" }
            ?: _detectedPlayerTabs.value.firstOrNull()?.packageName
            ?: ctx.packageName
    }

    private fun computeDetectedPlayerTabs(tracks: List<CrossAppTrackEntity>): List<DetectedPlayerTab> {
        if (tracks.isEmpty()) return emptyList()
        data class Acc(
            var trackKeys: MutableSet<String> = mutableSetOf(),
            var totalPlays: Int = 0,
            var totalListenedMs: Long = 0L,
            var lastPlayedAtMs: Long = 0L
        )
        val byPkg = linkedMapOf<String, Acc>()
        for (t in tracks) {
            val pkgs = buildSet {
                t.primarySourcePackage.trim().takeIf { it.isNotBlank() && it != "unknown_player" }?.let { add(it) }
                t.sourcePackagesCsv.split(",").map { it.trim() }.filter { it.isNotBlank() && it != "unknown_player" }.forEach { add(it) }
            }.ifEmpty { setOf(t.primarySourcePackage.ifBlank { "unknown_player" }) }
            for (pkg in pkgs) {
                val acc = byPkg.getOrPut(pkg) { Acc() }
                acc.trackKeys.add(t.canonicalTrackKey)
                if (pkg == t.primarySourcePackage || pkgs.size == 1) {
                    acc.totalPlays += t.playCount.coerceAtLeast(1)
                    acc.totalListenedMs += t.totalListenedMs
                } else {
                    acc.totalPlays += 1
                }
                acc.lastPlayedAtMs = maxOf(acc.lastPlayedAtMs, t.lastPlayedAtMs)
            }
        }
        return byPkg.entries.map { (pkg, acc) ->
            DetectedPlayerTab(
                packageName = pkg,
                displayName = CrossAppPlayRecorder.resolveFriendlyPlayerName(pkg),
                trackCount = acc.trackKeys.size,
                totalPlays = acc.totalPlays,
                totalListenedMs = acc.totalListenedMs,
                lastPlayedAtMs = acc.lastPlayedAtMs
            )
        }.sortedWith(compareByDescending<DetectedPlayerTab> { it.totalPlays }.thenByDescending { it.lastPlayedAtMs })
    }

    private fun dispatchOsMediaKey(context: android.content.Context, keyCode: Int): Boolean {
        return try {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return false
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pauseExternalAndLocalMediaQuietly(context: android.content.Context, targetPkg: String?) {
        runCatching { com.tunemind.app.provider.TuneMindInAppPlayer.pause() }
        runCatching { com.tunemind.app.provider.TuneMindYouTubeEmbedController.pause() }
        runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.pause(targetPkg) ?: false
        }
        dispatchOsMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    private fun scheduleMediaSessionResync(targetPkg: String?) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(400L)
            com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
            publishAuthoritativeNowPlayingNow()
            kotlinx.coroutines.delay(950L)
            com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
            publishAuthoritativeNowPlayingNow()
        }
    }

    fun pausePlayback() {
        val ctx = AppHolder.context
        if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() && isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindYouTubeEmbedController.pause()
            setAgentPlaybackPaused()
            postNowPlayingStatus("Paused TuneMind YouTube Player")
            return
        }
        if (isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindInAppPlayer.pause()
            setAgentPlaybackPaused()
            postNowPlayingStatus("Paused TuneMind Built-In Player")
            return
        }
        val targetPkg = resolveActiveMediaPackage()
        val controllerPaused = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.pause(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else false

        setAgentPlaybackPaused()
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (controllerPaused || keyDispatched) "Paused $appName"
            else "Sent pause signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun resumePlayback() {
        val ctx = AppHolder.context
        if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() && isInAppPlayerCurrent()) {
            val ok = com.tunemind.app.provider.TuneMindYouTubeEmbedController.resume()
            if (ok) {
                setAgentPlaybackResumed()
                postNowPlayingStatus("Resumed TuneMind YouTube Player")
                return
            }
        }
        if (isInAppPlayerCurrent()) {
            val ok = com.tunemind.app.provider.TuneMindInAppPlayer.resume()
            if (ok) {
                setAgentPlaybackResumed()
                postNowPlayingStatus("Resumed TuneMind Built-In Player")
                return
            }
        }
        val targetPkg = resolveActiveMediaPackage()
        val controllerResumed = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.play(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (!controllerResumed && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        } else false

        setAgentPlaybackResumed()
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (controllerResumed || keyDispatched) "Playing on $appName"
            else "Sent play signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun togglePlayback() {
        val currentlyPlaying = if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() && isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
        } else if (isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying()
        } else {
            val snap = com.tunemind.app.service.TuneMindListenerService.instance?.getActivePlaybackSnapshot()
            _homeState.value.nowPlaying?.isPlaying ?: snap?.isPlaying ?: false
        }
        if (currentlyPlaying) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    fun skipNext(respectAutoQueue: Boolean = true) {
        val ctx = AppHolder.context
        val autoQueueActive = ctx != null &&
            com.tunemind.app.domain.voice.AgentSettingsStore.isUpNextAutoOverrideEnabled(ctx)
        if (respectAutoQueue &&
            (autoQueueActive || isInAppPlayerCurrent()) &&
            compileDopamineCapsule().tracks.isNotEmpty()
        ) {
            if (playNextFromQueue(resolveActiveMediaPackage())) return
        }

        val targetPkg = resolveActiveMediaPackage()
        val controllerSkipped = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.skipNext(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (!controllerSkipped && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        } else false

        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (controllerSkipped || keyDispatched) "Skipped to next on $appName"
            else "Sent next-track signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun skipPrev() {
        val ctx = AppHolder.context
        if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() && isInAppPlayerCurrent()) {
            val pos = com.tunemind.app.provider.TuneMindYouTubeEmbedController.positionMsFlow.value
            if (pos > 3_000L) {
                com.tunemind.app.provider.TuneMindYouTubeEmbedController.seekTo(0L)
                publishAuthoritativeNowPlayingNow()
                postNowPlayingStatus("Restarted track")
                return
            }
        }
        if (isInAppPlayerCurrent()) {
            val pos = com.tunemind.app.provider.TuneMindInAppPlayer.positionMsFlow.value
            if (pos > 3_000L) {
                com.tunemind.app.provider.TuneMindInAppPlayer.seekTo(0L)
                publishAuthoritativeNowPlayingNow()
                postNowPlayingStatus("Restarted track")
                return
            }
        }
        val targetPkg = resolveActiveMediaPackage()
        val controllerPrev = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.skipPrev(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (!controllerPrev && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        } else false

        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (controllerPrev || keyDispatched) "Previous track on $appName"
            else "Sent previous-track signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() && isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindYouTubeEmbedController.seekTo(clamped)
            publishAuthoritativeNowPlayingNow()
            return
        }
        if (isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindInAppPlayer.seekTo(clamped)
            publishAuthoritativeNowPlayingNow()
            return
        }
        val targetPkg = resolveActiveMediaPackage()
        runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.seekTo(targetPkg, clamped) ?: false
        }

        val cur = _homeState.value.nowPlaying
        if (cur != null) {
            val now = System.currentTimeMillis()
            val updated = cur.copy(
                positionMs = clamped,
                updatedAtMs = now
            )
            manualLaunchPendingUntilMs = now + 1_500L
            manualActiveTrackOverride = updated
            _homeState.value = _homeState.value.copy(nowPlaying = updated)
        }
        scheduleMediaSessionResync(targetPkg)
    }

    private fun handleSnapshotForUpNextAutoOverride(snap: com.tunemind.app.media.ActivePlaybackSnapshot) {
        val ctx = AppHolder.context ?: return
        if (!com.tunemind.app.domain.voice.AgentSettingsStore.isUpNextAutoOverrideEnabled(ctx)) {
            queueMonitoredTitle = snap.title.trim()
            queueMonitoredArtist = snap.artist.trim()
            queueMonitoredPackage = snap.packageName
            queueMonitoredDurationMs = snap.durationMs
            queueMonitoredMaxPosMs = snap.positionMs
            queueMonitoredStartedAtMs = System.currentTimeMillis()
            return
        }

        val rawTitle = snap.title.trim()
        if (rawTitle.length < 2 || rawTitle.equals("Advertisement", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        val expectedTrack = expectedHandoverTargetTrack
        val matchesExpected = expectedHandoverTargetTitle.isNotBlank() &&
            ListeningPlayDeduplicator.isSameTrack(
                rawTitle, snap.artist, null,
                expectedHandoverTargetTitle, expectedTrack?.artist, expectedTrack?.spotifyUri
            )
        val matchesFinishedSource = lastQueueHandoverSourceTitle.isNotBlank() &&
            ListeningPlayDeduplicator.isSameTrack(
                rawTitle, snap.artist, null,
                lastQueueHandoverSourceTitle, queueMonitoredArtist, null
            )

        if (matchesExpected) {
            queueMonitoredTitle = rawTitle
            queueMonitoredArtist = snap.artist
            queueMonitoredPackage = snap.packageName
            if (snap.durationMs > 0L) queueMonitoredDurationMs = snap.durationMs
            queueMonitoredMaxPosMs = snap.positionMs
            if (queueMonitoredStartedAtMs <= 0L) queueMonitoredStartedAtMs = now
            if (snap.isPlaying) {
                expectedHandoverTargetTitle = ""
                expectedHandoverTargetTrack = null
                expectedHandoverWindowUntilMs = 0L
                expectedHandoverRetryCount = 0
                queueHandoverInFlight = false
            }
            return
        }

        // Intercept unapproved third-party autoplay if external player (e.g. Spotify playlist or YouTube Music) sneaks a different track
        if (expectedTrack != null &&
            now < expectedHandoverWindowUntilMs &&
            !matchesFinishedSource &&
            !com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle) &&
            (now - lastHandoverRetryAtMs) >= 900L &&
            expectedHandoverRetryCount < 5
        ) {
            val nextRetryCount = expectedHandoverRetryCount + 1
            lastHandoverRetryAtMs = now
            queueHandoverInFlight = true
            val pkg = snap.packageName.ifBlank { resolveActiveMediaPackage() }
            pauseExternalAndLocalMediaQuietly(ctx, pkg)
            postNowPlayingStatus("Blocked player autoplay · Playing \"${expectedTrack.title}\"")
            playQueuedTrack(expectedTrack, pkg) { _, _ ->
                queueHandoverInFlight = false
            }
            expectedHandoverRetryCount = nextRetryCount
            return
        }

        if (queueHandoverInFlight || (now - lastQueueHandoverAtMs) < 2_500L) {
            if (com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle)) {
                queueMonitoredTitle = rawTitle
                queueMonitoredArtist = snap.artist
                queueMonitoredPackage = snap.packageName
                if (snap.durationMs > 0L) queueMonitoredDurationMs = snap.durationMs
                queueMonitoredMaxPosMs = snap.positionMs
                queueMonitoredStartedAtMs = now
            }
            return
        }

        val isSameTrack = queueMonitoredTitle.isBlank() ||
            ListeningPlayDeduplicator.isSameTrack(rawTitle, snap.artist, null, queueMonitoredTitle, queueMonitoredArtist, null)

        if (isSameTrack) {
            if (queueMonitoredTitle.isBlank()) {
                queueMonitoredStartedAtMs = now
            }
            queueMonitoredTitle = rawTitle
            queueMonitoredArtist = snap.artist
            queueMonitoredPackage = snap.packageName
            if (snap.durationMs > 0L) queueMonitoredDurationMs = snap.durationMs
            if (snap.positionMs > queueMonitoredMaxPosMs) queueMonitoredMaxPosMs = snap.positionMs

            val elapsedWallMs = if (queueMonitoredStartedAtMs > 0L) (now - queueMonitoredStartedAtMs) else 0L
            if (elapsedWallMs >= 15_000L && lastQueueHandoverSourceTitle.equals(rawTitle, ignoreCase = true)) {
                // Track was freshly replayed later in session: re-arm end-of-song interception
                lastQueueHandoverSourceTitle = ""
            }

            // Trigger A: Song is within final window -> preempt external playlist autoplay & spin TuneMind Up Next
            val leadWindowMs = if (queueMonitoredDurationMs >= 35_000L) 4_200L else 1_800L
            if (snap.isPlaying &&
                queueMonitoredDurationMs >= 15_000L &&
                snap.positionMs >= (queueMonitoredDurationMs - leadWindowMs) &&
                !lastQueueHandoverSourceTitle.equals(rawTitle, ignoreCase = true)
            ) {
                triggerAutoQueueHandover(
                    finishedTitle = rawTitle,
                    targetPkg = snap.packageName.ifBlank { resolveActiveMediaPackage() }
                )
            }
            return
        }

        // Trigger B: External player auto-advanced to an unrequested playlist/radio song -> intercept immediately
        val prevTitle = queueMonitoredTitle
        val prevDur = queueMonitoredDurationMs
        val prevMax = queueMonitoredMaxPosMs
        val elapsedWallMs = if (queueMonitoredStartedAtMs > 0L) (now - queueMonitoredStartedAtMs) else 0L
        val wasTuneMindLaunch = com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle) ||
            (manualActiveTrackOverride?.title?.equals(rawTitle, ignoreCase = true) == true &&
                (now - (manualActiveTrackOverride?.updatedAtMs ?: 0L)) < 12_000L)
        val prevPlayedSubstantially = prevMax >= 8_000L ||
            elapsedWallMs >= 12_000L ||
            (prevDur >= 15_000L && (prevMax >= (prevDur * 35L) / 100L || prevMax >= (prevDur - 15_000L)))

        if (!wasTuneMindLaunch &&
            prevTitle.isNotBlank() &&
            prevPlayedSubstantially &&
            !lastQueueHandoverSourceTitle.equals(prevTitle, ignoreCase = true)
        ) {
            triggerAutoQueueHandover(
                finishedTitle = prevTitle,
                targetPkg = snap.packageName.ifBlank { resolveActiveMediaPackage() }
            )
            return
        }

        queueMonitoredTitle = rawTitle
        queueMonitoredArtist = snap.artist
        queueMonitoredPackage = snap.packageName
        queueMonitoredDurationMs = snap.durationMs
        queueMonitoredMaxPosMs = snap.positionMs
        queueMonitoredStartedAtMs = now
    }

    private fun triggerAutoQueueHandover(finishedTitle: String, targetPkg: String) {
        val ctx = AppHolder.context ?: return
        if (queueHandoverInFlight) return
        val nextTrack = compileDopamineCapsule().tracks.firstOrNull() ?: return
        val now = System.currentTimeMillis()
        queueHandoverInFlight = true
        lastQueueHandoverAtMs = now
        lastQueueHandoverSourceTitle = finishedTitle
        expectedHandoverTargetTitle = nextTrack.title
        expectedHandoverTargetTrack = nextTrack
        expectedHandoverWindowUntilMs = now + 15_000L
        expectedHandoverRetryCount = 0

        pauseExternalAndLocalMediaQuietly(ctx, targetPkg)
        postNowPlayingStatus("Auto-playing next from TuneMind queue: \"${nextTrack.title}\"")
        playQueuedTrack(nextTrack, targetPkg) { _, _ ->
            queueHandoverInFlight = false
        }
    }

    fun rebindListener() {
        val ctx = AppHolder.context ?: return
        com.tunemind.app.MainActivity.forceRebindListener(ctx)
        com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
    }

    private fun loadRestoredVaultMapFromPrefs(): MutableMap<String, Long> {
        cachedRestoredVaultMap?.let { return HashMap(it) }
        val ctx = AppHolder.context ?: return mutableMapOf()
        val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("vault_restored_map", null) ?: run {
            cachedRestoredVaultMap = emptyMap()
            return mutableMapOf()
        }
        return try {
            val obj = org.json.JSONObject(raw)
            val result = mutableMapOf<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optLong(k, 0L)
                if (v > 0L) result[k] = v
            }
            cachedRestoredVaultMap = HashMap(result)
            result
        } catch (_: Exception) {
            cachedRestoredVaultMap = emptyMap()
            mutableMapOf()
        }
    }

    private fun saveRestoredVaultMapToPrefs(map: Map<String, Long>) {
        val ctx = AppHolder.context ?: return
        val trimmed = map.entries
            .sortedByDescending { it.value }
            .take(120)
            .associate { it.key to it.value }
        cachedRestoredVaultMap = trimmed
        val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
        val obj = org.json.JSONObject()
        trimmed.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("vault_restored_map", obj.toString()).apply()
    }

    private fun buildActiveVaultFreshnessMap(nowMs: Long = System.currentTimeMillis()): Map<String, Int> {
        val items = runCatching { _vaultState.value }.getOrNull() ?: return emptyMap()
        if (items.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, Int>()
        for (v in items) {
            val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                quarantinedAtMs = v.quarantinedAtMs,
                totalDays = v.totalDays,
                baseSatietyScore = v.satietyScore,
                nowMs = nowMs
            )
            if (!rec.isGraduated) {
                val canonicalId = v.resolvedCanonicalTrackId()
                val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(v.title, v.artist)
                map[canonicalId] = rec.freshnessPercent
                map[normKey] = rec.freshnessPercent
            }
        }
        return map
    }

    private fun loadVaultFromPrefs(): List<VaultTrack> {
        val ctx = AppHolder.context ?: return defaultSeedVault()
        val prefs = ctx.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("vault_items", null) ?: return defaultSeedVault()
        val restoredMap = loadRestoredVaultMapFromPrefs()
        return try {
            val array = org.json.JSONArray(raw)
            val now = System.currentTimeMillis()
            val list = mutableListOf<VaultTrack>()
            val graduatedNew = mutableListOf<GraduatedVaultSummary>()
            var changed = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val qTime = obj.getLong("quarantinedAtMs")
                val totalDays = obj.getDouble("totalDays").toFloat().coerceIn(0.5f, 14.0f)
                val pkg = obj.optString("sourcePackage", "").ifBlank { "unknown_player" }
                val rawUri = obj.optString("spotifyUri", "").takeIf { it.isNotBlank() }
                val titleStr = obj.getString("title")
                val artistStr = obj.getString("artist")
                val resolvedIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = obj.optString("trackId", "").takeIf { it.isNotBlank() },
                    spotifyUri = rawUri,
                    title = titleStr,
                    artist = artistStr
                )
                val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(titleStr, artistStr)
                val restoredAt = maxOf(restoredMap[resolvedIdentity.trackId] ?: 0L, restoredMap[normKey] ?: 0L)
                if (restoredAt > qTime) {
                    changed = true
                    continue
                }
                val satiety = obj.optInt("satietyScore", 82).coerceIn(40, 99)
                val entryReason = obj.optString("entryReason", "").ifBlank { "Manual Vault Lock" }
                val isAuto = obj.optBoolean("isAutoQuarantined", false)
                val lastPenalty = obj.optLong("lastPenaltyPlayAtMs", 0L)

                val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                    quarantinedAtMs = qTime,
                    totalDays = totalDays,
                    baseSatietyScore = satiety,
                    nowMs = now
                )
                if (rec.isGraduated) {
                    changed = true
                    restoredMap[resolvedIdentity.trackId] = now
                    restoredMap[normKey] = now
                    graduatedNew.add(
                        GraduatedVaultSummary(
                            id = obj.getString("id"),
                            trackId = resolvedIdentity.trackId,
                            title = titleStr,
                            artist = artistStr,
                            spotifyUri = resolvedIdentity.spotifyUri,
                            graduatedAtMs = now,
                            totalRestedDays = totalDays,
                            previousSatietyScore = satiety,
                            entryReason = entryReason
                        )
                    )
                    continue
                }

                list.add(
                    VaultTrack(
                        id = obj.getString("id"),
                        title = titleStr,
                        artist = artistStr,
                        quarantinedAtMs = qTime,
                        totalDays = totalDays,
                        cooldownDaysRemaining = rec.cooldownDaysRemaining,
                        recoveryProgress = rec.recoveryProgress,
                        satietyScore = satiety,
                        sourcePackage = pkg,
                        spotifyUri = resolvedIdentity.spotifyUri,
                        trackId = resolvedIdentity.trackId,
                        entryReason = entryReason,
                        isAutoQuarantined = isAuto,
                        lastPenaltyPlayAtMs = lastPenalty,
                        updatedAtMs = now
                    )
                )
            }
            if (changed) {
                saveRestoredVaultMapToPrefs(restoredMap)
            }
            if (graduatedNew.isNotEmpty()) {
                _recentlyGraduatedVaultTracks.value = (graduatedNew + _recentlyGraduatedVaultTracks.value).take(10)
            }
            if (list.isEmpty()) defaultSeedVault() else list
        } catch (_: Exception) {
            defaultSeedVault()
        }
    }

    private fun defaultSeedVault(): List<VaultTrack> = emptyList()

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
                put("sourcePackage", item.sourcePackage)
                put("entryReason", item.entryReason)
                put("isAutoQuarantined", item.isAutoQuarantined)
                put("lastPenaltyPlayAtMs", item.lastPenaltyPlayAtMs)
                put("updatedAtMs", item.updatedAtMs)
                put(
                    "trackId",
                    item.trackId.ifBlank {
                        TrackIdentityResolver.resolveCanonicalTrackId(
                            spotifyUri = item.spotifyUri,
                            title = item.title,
                            artist = item.artist
                        )
                    }
                )
                TrackIdentityResolver.normalizeSpotifyUri(item.spotifyUri)?.let { put("spotifyUri", it) }
            }
            array.put(obj)
        }
        prefs.edit().putString("vault_items", array.toString()).apply()
    }

    private fun loadManualFreshFromPrefs(): List<ManualFreshTrack> {
        val ctx = AppHolder.context ?: return emptyList()
        val prefs = ctx.getSharedPreferences("tunemind_fresh_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("manual_fresh_items", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<ManualFreshTrack>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val t = obj.optString("title", "").trim()
                if (t.isBlank()) continue
                val a = obj.optString("artist", "Unknown Artist").trim()
                val rawUri = obj.optString("spotifyUri", "").takeIf { it.isNotBlank() }
                val identity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = obj.optString("trackId", "").takeIf { it.isNotBlank() },
                    spotifyUri = rawUri,
                    title = t,
                    artist = a
                )
                list.add(
                    ManualFreshTrack(
                        id = obj.optString("id", "fresh-${identity.trackId}"),
                        title = t,
                        artist = a,
                        spotifyUri = identity.spotifyUri,
                        sourcePackage = obj.optString("sourcePackage", "unknown_player"),
                        trackId = identity.trackId,
                        addedAtMs = obj.optLong("addedAtMs", System.currentTimeMillis()),
                        albumArtUrl = obj.optString("albumArtUrl", "").takeIf { it.isNotBlank() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveManualFreshToPrefs(items: List<ManualFreshTrack>) {
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_fresh_prefs", android.content.Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        for (item in items) {
            array.put(org.json.JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artist", item.artist)
                item.spotifyUri?.let { put("spotifyUri", it) }
                put("sourcePackage", item.sourcePackage)
                put("trackId", item.resolvedCanonicalTrackId())
                put("addedAtMs", item.addedAtMs)
                item.albumArtUrl?.let { put("albumArtUrl", it) }
            })
        }
        prefs.edit().putString("manual_fresh_items", array.toString()).apply()
    }

    private fun loadFreshExcludedKeysFromPrefs(): Set<String> {
        val ctx = AppHolder.context ?: return emptySet()
        val prefs = ctx.getSharedPreferences("tunemind_fresh_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("fresh_excluded_keys", null) ?: return emptySet()
        return try {
            val array = org.json.JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val s = array.optString(i, "").trim()
                if (s.isNotBlank()) set.add(s)
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveFreshExcludedKeysToPrefs(keys: Set<String>) {
        val ctx = AppHolder.context ?: return
        val prefs = ctx.getSharedPreferences("tunemind_fresh_prefs", android.content.Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        keys.take(250).forEach { array.put(it) }
        prefs.edit().putString("fresh_excluded_keys", array.toString()).apply()
    }

    fun addToFresh(
        title: String,
        artist: String,
        sourcePackage: String? = null,
        spotifyUri: String? = null,
        trackId: String? = null,
        albumArtUrl: String? = null
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val cleanArtist = artist.trim().ifBlank { "Your Library" }
        val nowMs = System.currentTimeMillis()
        val identity = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId,
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = cleanArtist
        )
        val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(cleanTitle, cleanArtist)

        // If the song is currently in Resting Vault, restore it back cleanly first
        val vaultMatch = _vaultState.value.firstOrNull { vt ->
            ListeningPlayDeduplicator.isSameTrack(vt.title, vt.artist, vt.spotifyUri, cleanTitle, cleanArtist, spotifyUri) ||
                vt.resolvedCanonicalTrackId() == identity.trackId
        }
        if (vaultMatch != null) {
            val restoredMap = loadRestoredVaultMapFromPrefs()
            restoredMap[vaultMatch.resolvedCanonicalTrackId()] = nowMs
            restoredMap[normKey] = nowMs
            saveRestoredVaultMapToPrefs(restoredMap)
            val remainingVault = _vaultState.value.filter { it.id != vaultMatch.id }
            _vaultState.value = remainingVault
            saveVaultToPrefs(remainingVault)
        } else {
            val restoredMap = loadRestoredVaultMapFromPrefs()
            restoredMap[identity.trackId] = nowMs
            restoredMap[normKey] = nowMs
            saveRestoredVaultMapToPrefs(restoredMap)
        }

        // Remove any previous Fresh exclusion
        val cleanedExcluded = _freshExcludedKeys.value
            .filterNot { it == normKey || it == identity.trackId }
            .toSet()
        _freshExcludedKeys.value = cleanedExcluded
        saveFreshExcludedKeysToPrefs(cleanedExcluded)

        val resolvedPkg = sourcePackage?.takeIf { it.isNotBlank() }
            ?: _homeState.value.nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() }
            ?: "unknown_player"
        val newPin = ManualFreshTrack(
            id = "fresh-${identity.trackId}-$nowMs",
            title = cleanTitle,
            artist = cleanArtist,
            spotifyUri = identity.spotifyUri,
            sourcePackage = resolvedPkg,
            trackId = identity.trackId,
            addedAtMs = nowMs,
            albumArtUrl = albumArtUrl
        )
        val updatedPins = listOf(newPin) + _manualFreshTracks.value.filterNot {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, cleanTitle, cleanArtist, spotifyUri) ||
                it.resolvedCanonicalTrackId() == identity.trackId
        }
        _manualFreshTracks.value = updatedPins
        saveManualFreshToPrefs(updatedPins)
        refreshLiveListeningStatsNow()
        syncUpNextQueueWithSources(forceRebuild = false)
        postNowPlayingStatus("Added \"$cleanTitle\" to Fresh")
        syncVaultToBackendAsync(force = true)
    }

    fun removeFromFresh(
        title: String,
        artist: String,
        spotifyUri: String? = null,
        trackId: String? = null
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val cleanArtist = artist.trim().ifBlank { "Your Library" }
        val identity = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId,
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = cleanArtist
        )
        val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(cleanTitle, cleanArtist)

        val updatedPins = _manualFreshTracks.value.filterNot {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, cleanTitle, cleanArtist, spotifyUri) ||
                it.resolvedCanonicalTrackId() == identity.trackId
        }
        _manualFreshTracks.value = updatedPins
        saveManualFreshToPrefs(updatedPins)

        val updatedExcluded = (_freshExcludedKeys.value + normKey + identity.trackId).toSet()
        _freshExcludedKeys.value = updatedExcluded
        saveFreshExcludedKeysToPrefs(updatedExcluded)

        syncUpNextQueueWithSources(forceRebuild = false)
        postNowPlayingStatus("Removed \"$cleanTitle\" from Fresh")
    }

    fun toggleTrackFreshStatus(
        title: String,
        artist: String,
        sourcePackage: String? = null,
        spotifyUri: String? = null,
        trackId: String? = null,
        albumArtUrl: String? = null
    ) {
        val status = resolveTrackVaultStatus(title, artist, spotifyUri, trackId)
        if (status.isInFresh) {
            removeFromFresh(title, artist, spotifyUri, trackId)
        } else {
            addToFresh(title, artist, sourcePackage, spotifyUri, trackId, albumArtUrl)
        }
    }

    fun resolveTrackVaultStatus(
        title: String?,
        artist: String?,
        spotifyUri: String? = null,
        trackId: String? = null
    ): TrackVaultUiStatus {
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isBlank()) return TrackVaultUiStatus()
        val nowMs = System.currentTimeMillis()
        val canonicalId = TrackIdentityResolver.resolveCanonicalTrackId(
            trackId = trackId?.takeIf { it.isNotBlank() },
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = artist
        )
        val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(cleanTitle, artist)
        val isManualFresh = _manualFreshTracks.value.any { mf ->
            ListeningPlayDeduplicator.isSameTrack(mf.title, mf.artist, mf.spotifyUri, cleanTitle, artist, spotifyUri) ||
                mf.resolvedCanonicalTrackId() == canonicalId
        }
        val excludedSet = _freshExcludedKeys.value
        val isExcludedFresh = normKey in excludedSet || canonicalId in excludedSet

        val vaultMatch = _vaultState.value.firstOrNull { vt ->
            ListeningPlayDeduplicator.isSameTrack(vt.title, vt.artist, vt.spotifyUri, cleanTitle, artist, spotifyUri) ||
                vt.resolvedCanonicalTrackId() == canonicalId
        }
        if (vaultMatch != null) {
            val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                quarantinedAtMs = vaultMatch.quarantinedAtMs,
                totalDays = vaultMatch.totalDays,
                baseSatietyScore = vaultMatch.satietyScore,
                nowMs = nowMs
            )
            val inVaultActive = !rec.isGraduated
            return TrackVaultUiStatus(
                isInVault = inVaultActive,
                vaultId = vaultMatch.id,
                isCoolingDown = inVaultActive,
                freshnessPercent = rec.freshnessPercent,
                recoveryPercent = (rec.recoveryProgress * 100f).toInt().coerceIn(0, 100),
                cooldownRemainingLabel = rec.remainingLabel,
                satietyScore = vaultMatch.satietyScore,
                entryReason = vaultMatch.entryReason,
                isManualFresh = isManualFresh,
                isExcludedFromFresh = isExcludedFresh,
                isInFresh = !inVaultActive && !isExcludedFresh && (isManualFresh || true)
            )
        }

        val statsMatch = _unifiedTracks.value.firstOrNull { ut ->
            ListeningPlayDeduplicator.isSameTrack(ut.title, ut.artist, ut.spotifyUri, cleanTitle, artist, spotifyUri) ||
                ut.trackId == canonicalId
        }
        if (statsMatch != null) {
            val effectiveCooling = if (isManualFresh) false else statsMatch.isCoolingDown
            val inFresh = isManualFresh || (!statsMatch.isCoolingDown && !isExcludedFresh)
            return TrackVaultUiStatus(
                isInVault = false,
                vaultId = null,
                isCoolingDown = effectiveCooling,
                freshnessPercent = if (isManualFresh) maxOf(statsMatch.freshnessPercent, 96) else statsMatch.freshnessPercent,
                recoveryPercent = 0,
                cooldownRemainingLabel = null,
                satietyScore = (100 - statsMatch.freshnessPercent).coerceIn(0, 99),
                entryReason = null,
                isManualFresh = isManualFresh,
                isExcludedFromFresh = isExcludedFresh,
                isInFresh = inFresh
            )
        }

        val inLibraryOrRecentlyKnown = isManualFresh ||
            _crossAppLibraryTracks.value.any {
                it.isLiked && ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, null, cleanTitle, artist, spotifyUri)
            } ||
            _recentlyGraduatedVaultTracks.value.any {
                ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, cleanTitle, artist, spotifyUri)
            }

        return TrackVaultUiStatus(
            isInVault = false,
            isCoolingDown = false,
            freshnessPercent = if (isManualFresh) 99 else 96,
            isManualFresh = isManualFresh,
            isExcludedFromFresh = isExcludedFresh,
            isInFresh = !isExcludedFresh && inLibraryOrRecentlyKnown
        )
    }

    fun toggleTrackVaultStatus(
        title: String,
        artist: String,
        sourcePackage: String? = null,
        spotifyUri: String? = null,
        trackId: String? = null
    ) {
        val status = resolveTrackVaultStatus(title, artist, spotifyUri, trackId)
        if (status.isInVault && !status.vaultId.isNullOrBlank()) {
            restoreFromVault(status.vaultId)
        } else {
            quarantineTrack(
                title = title,
                artist = artist,
                durationDays = null,
                sourcePackage = sourcePackage,
                spotifyUri = spotifyUri,
                trackId = trackId
            )
        }
    }

    fun refreshVaultLifecycleNow(syncWithBackend: Boolean = false) {
        reconcileVaultLifecycle(
            unifiedList = _unifiedTracks.value,
            cleanEvents = _cleanPlayHistory.value,
            syncBackend = syncWithBackend
        )
    }

    private fun reconcileVaultLifecycle(
        unifiedList: List<UnifiedTrackStats>,
        cleanEvents: List<UnifiedPlayEvent>,
        syncBackend: Boolean
    ) {
        val nowMs = System.currentTimeMillis()
        val sensitivity = cachedRepetitionSensitivityForStats.coerceIn(0.2f, 1.0f)
        val restoredMap = loadRestoredVaultMapFromPrefs()
        val eventsByTrackKey = cleanEvents.groupBy { it.trackKey }

        val updatedActive = mutableListOf<VaultTrack>()
        val graduatedNow = mutableListOf<GraduatedVaultSummary>()
        var mutated = false

        val existing = _vaultState.value
        val existingKeys = mutableSetOf<String>()

        for (v in existing) {
            val canonicalId = v.resolvedCanonicalTrackId()
            val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(v.title, v.artist)
            existingKeys.add(normKey)
            existingKeys.add(canonicalId)

            val restoredAt = maxOf(restoredMap[canonicalId] ?: 0L, restoredMap[normKey] ?: 0L)
            if (restoredAt > v.quarantinedAtMs) {
                mutated = true
                continue
            }

            // Premature Replay Penalty: if played again >15m after entering Vault, extend rest by +0.75d once per new play
            var effectiveTotalDays = v.totalDays
            var effectivePenaltyPlayMs = v.lastPenaltyPlayAtMs
            var effectiveReason = v.entryReason
            val matchedStats = unifiedList.firstOrNull { ut ->
                ListeningPlayDeduplicator.isSameTrack(ut.title, ut.artist, ut.spotifyUri, v.title, v.artist, v.spotifyUri) ||
                    ut.trackId == canonicalId
            }
            if (matchedStats != null &&
                matchedStats.lastPlayedAtMs > v.quarantinedAtMs + 15L * 60_000L &&
                matchedStats.lastPlayedAtMs > effectivePenaltyPlayMs
            ) {
                effectiveTotalDays = (v.totalDays + 0.75f).coerceIn(2.0f, 7.0f)
                effectivePenaltyPlayMs = matchedStats.lastPlayedAtMs
                effectiveReason = "Replay during rest (+18h added)"
                mutated = true
            }

            val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                quarantinedAtMs = v.quarantinedAtMs,
                totalDays = effectiveTotalDays,
                baseSatietyScore = v.satietyScore,
                nowMs = nowMs
            )
            if (rec.isGraduated) {
                mutated = true
                restoredMap[canonicalId] = nowMs
                restoredMap[normKey] = nowMs
                graduatedNow.add(
                    GraduatedVaultSummary(
                        id = v.id,
                        trackId = canonicalId,
                        title = v.title,
                        artist = v.artist,
                        spotifyUri = v.spotifyUri,
                        graduatedAtMs = nowMs,
                        totalRestedDays = effectiveTotalDays,
                        previousSatietyScore = v.satietyScore,
                        entryReason = effectiveReason
                    )
                )
            } else {
                if (kotlin.math.abs(rec.recoveryProgress - v.recoveryProgress) >= 0.01f ||
                    rec.cooldownDaysRemaining != v.cooldownDaysRemaining
                ) {
                    mutated = true
                }
                updatedActive.add(
                    v.copy(
                        totalDays = effectiveTotalDays,
                        cooldownDaysRemaining = rec.cooldownDaysRemaining,
                        recoveryProgress = rec.recoveryProgress,
                        lastPenaltyPlayAtMs = effectivePenaltyPlayMs,
                        entryReason = effectiveReason,
                        updatedAtMs = nowMs
                    )
                )
            }
        }

        // Auto-Entry check for tracks exceeding temporal satiety or 24h binge thresholds
        for (ut in unifiedList) {
            val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(ut.title, ut.artist)
            if (normKey in existingKeys || ut.trackId in existingKeys) continue

            val restoredAt = maxOf(restoredMap[ut.trackId] ?: 0L, restoredMap[normKey] ?: 0L)
            val evs = eventsByTrackKey[ut.trackKey].orEmpty()
            val timestamps = if (evs.isNotEmpty()) evs.map { it.playedAtMs } else listOf(ut.lastPlayedAtMs)
            val ratios = evs.map { ev ->
                if (ev.durationMs > 30_000L && ev.listenedMs > 0L) {
                    (ev.listenedMs.toFloat() / ev.durationMs.toFloat()).coerceIn(0.01f, 1.0f)
                } else {
                    1.0f
                }
            }
            val fatigue = com.tunemind.app.domain.vault.RestingVaultEngine.evaluateTrackFatigue(
                playTimestampsMs = timestamps,
                listenedRatios = ratios,
                sensitivity = sensitivity,
                lastRestoredAtMs = restoredAt,
                nowMs = nowMs
            )

            if (fatigue.shouldAutoQuarantine) {
                mutated = true
                existingKeys.add(normKey)
                existingKeys.add(ut.trackId)
                val newItem = VaultTrack(
                    id = "vault-${ut.trackId}-$nowMs",
                    title = ut.title,
                    artist = ut.artist,
                    quarantinedAtMs = nowMs,
                    totalDays = fatigue.recommendedCooldownDays,
                    cooldownDaysRemaining = fatigue.recommendedCooldownDays,
                    recoveryProgress = 0.0f,
                    satietyScore = fatigue.effectiveSatiety.coerceIn(55, 99),
                    sourcePackage = ut.primarySourcePackage.ifBlank { "unknown_player" },
                    spotifyUri = TrackIdentityResolver.normalizeSpotifyUri(ut.spotifyUri),
                    trackId = ut.trackId,
                    entryReason = fatigue.entryReason,
                    isAutoQuarantined = true,
                    updatedAtMs = nowMs
                )
                updatedActive.add(0, newItem)
            }
        }

        if (graduatedNow.isNotEmpty()) {
            _recentlyGraduatedVaultTracks.value = (graduatedNow + _recentlyGraduatedVaultTracks.value).take(10)
            saveRestoredVaultMapToPrefs(restoredMap)
        }

        if (mutated) {
            _vaultState.value = updatedActive
            saveVaultToPrefs(updatedActive)
            syncUpNextQueueWithSources(forceRebuild = false)
            if (syncBackend) {
                syncVaultToBackendAsync(force = graduatedNow.isNotEmpty())
            }
        }
    }

    fun quarantineTrack(
        title: String,
        artist: String,
        durationDays: Float? = null,
        sourcePackage: String? = null,
        spotifyUri: String? = null,
        trackId: String? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val matched = _unifiedTracks.value.firstOrNull {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, spotifyUri)
        }
        val resolvedPkg = sourcePackage?.takeIf { it.isNotBlank() }
            ?: matched?.primarySourcePackage
            ?: _homeState.value.nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() }
            ?: "unknown_player"
        val resolvedIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId ?: matched?.trackId,
            spotifyUri = spotifyUri ?: matched?.spotifyUri,
            title = title,
            artist = artist
        )

        val evs = if (matched != null) {
            _cleanPlayHistory.value.filter { it.trackKey == matched.trackKey }
        } else {
            emptyList()
        }
        val fatigue = com.tunemind.app.domain.vault.RestingVaultEngine.evaluateTrackFatigue(
            playTimestampsMs = evs.map { it.playedAtMs },
            sensitivity = cachedRepetitionSensitivityForStats,
            nowMs = nowMs
        )
        val satietyScore = maxOf(fatigue.effectiveSatiety, if (matched != null) 70 else 78).coerceIn(50, 99)
        val computedDays = durationDays?.coerceIn(0.5f, 14.0f)
            ?: com.tunemind.app.domain.vault.RestingVaultEngine.computeCooldownDurationDays(
                satietyScore = satietyScore,
                recentPlays7d = maxOf(fatigue.recentPlays7d, 2),
                recentPlays24h = fatigue.recentPlays24h,
                sensitivity = cachedRepetitionSensitivityForStats
            )

        val id = "vault-${resolvedIdentity.trackId}-$nowMs"
        val newTrack = VaultTrack(
            id = id,
            title = title.trim(),
            artist = artist.trim(),
            quarantinedAtMs = nowMs,
            totalDays = computedDays,
            cooldownDaysRemaining = computedDays,
            recoveryProgress = 0.0f,
            satietyScore = satietyScore,
            sourcePackage = resolvedPkg,
            spotifyUri = resolvedIdentity.spotifyUri,
            trackId = resolvedIdentity.trackId,
            entryReason = if (fatigue.recentPlays7d >= 2) {
                "Manual Lock · ${fatigue.recentPlays7d} recent plays"
            } else {
                "Manual Vault Lock"
            },
            isAutoQuarantined = false,
            updatedAtMs = nowMs
        )
        val updated = listOf(newTrack) + _vaultState.value.filter {
            !ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, spotifyUri) &&
                it.resolvedCanonicalTrackId() != resolvedIdentity.trackId
        }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        // Remove from Manual Fresh pins if moved into Resting Vault
        val remainingPins = _manualFreshTracks.value.filterNot {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, spotifyUri) ||
                it.resolvedCanonicalTrackId() == resolvedIdentity.trackId
        }
        if (remainingPins.size != _manualFreshTracks.value.size) {
            _manualFreshTracks.value = remainingPins
            saveManualFreshToPrefs(remainingPins)
        }
        refreshLiveListeningStatsNow()
        syncUpNextQueueWithSources(forceRebuild = false)
        refreshRecommendations()
        syncVaultToBackendAsync(force = true)
    }

    fun addToVault(title: String, artist: String, durationDays: Float = 5f) {
        quarantineTrack(title, artist, durationDays)
    }

    fun restoreFromVault(id: String) {
        val nowMs = System.currentTimeMillis()
        val target = _vaultState.value.firstOrNull {
            it.id == id || it.trackId == id || it.resolvedCanonicalTrackId() == id
        }
        val restoredMap = loadRestoredVaultMapFromPrefs()
        if (target != null) {
            val cid = target.resolvedCanonicalTrackId()
            val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(target.title, target.artist)
            restoredMap[cid] = nowMs
            restoredMap[normKey] = nowMs
            saveRestoredVaultMapToPrefs(restoredMap)
            val cleanedExcluded = _freshExcludedKeys.value.filterNot { it == normKey || it == cid }.toSet()
            if (cleanedExcluded.size != _freshExcludedKeys.value.size) {
                _freshExcludedKeys.value = cleanedExcluded
                saveFreshExcludedKeysToPrefs(cleanedExcluded)
            }
        }
        val updated = _vaultState.value.filter {
            it.id != id && it.trackId != id && it.resolvedCanonicalTrackId() != id
        }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        refreshLiveListeningStatsNow()
        syncUpNextQueueWithSources(forceRebuild = false)
        refreshRecommendations()
        syncVaultToBackendAsync(force = true)
    }

    private fun syncVaultToBackendAsync(force: Boolean = false) {
        val ctx = AppHolder.context ?: return
        val nowMs = System.currentTimeMillis()
        if (!force && nowMs - lastBackendVaultSyncMs < 45_000L) return
        lastBackendVaultSyncMs = nowMs

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(ctx).trim().trimEnd('/')
                if (baseUrl.isBlank()) return@launch
                val authSession = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value
                val restoredMap = loadRestoredVaultMapFromPrefs()
                val currentVault = _vaultState.value

                val body = org.json.JSONObject().apply {
                    put("user_id", authSession.userId)
                    put("user_email", authSession.email)
                    put("repetition_sensitivity", cachedRepetitionSensitivityForStats.toDouble())
                    val tracksArr = org.json.JSONArray()
                    for (ut in _unifiedTracks.value.take(80)) {
                        tracksArr.put(org.json.JSONObject().apply {
                            put("title", ut.title)
                            put("artist", ut.artist)
                            put("trackId", ut.trackId)
                            ut.spotifyUri?.let { put("spotifyUri", it) }
                            put("playCount", ut.verifiedPlayCount)
                            put("source", ut.primarySourcePackage)
                            put("lastPlayedAt", ut.lastPlayedAtMs)
                        })
                    }
                    put("tracks", tracksArr)
                    val restoredObj = org.json.JSONObject()
                    restoredMap.forEach { (k, v) -> restoredObj.put(k, v) }
                    put("restored_track_timestamps", restoredObj)

                    val vaultArr = org.json.JSONArray()
                    for (vt in currentVault) {
                        val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                            quarantinedAtMs = vt.quarantinedAtMs,
                            totalDays = vt.totalDays,
                            baseSatietyScore = vt.satietyScore,
                            nowMs = nowMs
                        )
                        vaultArr.put(org.json.JSONObject().apply {
                            put("id", vt.id)
                            put("title", vt.title)
                            put("artist", vt.artist)
                            put("trackId", vt.resolvedCanonicalTrackId())
                            vt.spotifyUri?.let { put("spotifyUri", it) }
                            put("sourcePackage", vt.sourcePackage)
                            put("quarantinedAtMs", vt.quarantinedAtMs)
                            put("totalDays", vt.totalDays.toDouble())
                            put("cooldownDaysRemaining", rec.cooldownDaysRemaining.toDouble())
                            put("recoveryProgress", rec.recoveryProgress.toDouble())
                            put("satietyScore", vt.satietyScore)
                            put("freshnessPercent", rec.freshnessPercent)
                            put("entryReason", vt.entryReason)
                            put("isAutoQuarantined", vt.isAutoQuarantined)
                            put("lastPenaltyPlayAtMs", vt.lastPenaltyPlayAtMs)
                        })
                    }
                    put("vault_tracks", vaultArr)
                }

                val conn = (java.net.URL("$baseUrl/api/v1/intelligence/sync").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                android.util.Log.d("TuneMindVM", "Synced Vault state to backend: HTTP $code (${currentVault.size} resting)")
            } catch (e: Exception) {
                android.util.Log.w("TuneMindVM", "Vault backend sync skipped: ${e.message}")
            }
        }
    }

    private fun hydrateVaultFromBackendIfNeeded() {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(ctx).trim().trimEnd('/')
                if (baseUrl.isBlank()) return@launch
                val authSession = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value
                val uid = java.net.URLEncoder.encode(authSession.userId, "UTF-8")
                val email = java.net.URLEncoder.encode(authSession.email, "UTF-8")
                val url = java.net.URL("$baseUrl/api/v1/intelligence/growth?user_id=$uid&user_email=$email")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@launch
                }
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = org.json.JSONObject(raw)
                val remoteArr = json.optJSONArray("vaultTracks") ?: return@launch
                if (remoteArr.length() == 0) return@launch

                val restoredMap = loadRestoredVaultMapFromPrefs()
                val currentLocal = _vaultState.value.toMutableList()
                val localKeys = currentLocal.flatMap {
                    listOf(
                        it.resolvedCanonicalTrackId(),
                        com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(it.title, it.artist)
                    )
                }.toMutableSet()

                var addedAny = false
                val nowMs = System.currentTimeMillis()
                for (i in 0 until remoteArr.length()) {
                    val obj = remoteArr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val artist = obj.optString("artist", "Unknown Artist").trim()
                    if (title.isBlank()) continue
                    val identity = TrackIdentityResolver.resolveCanonicalIdentity(
                        trackId = obj.optString("trackId", "").takeIf { it.isNotBlank() },
                        spotifyUri = obj.optString("spotifyUri", "").takeIf { it.isNotBlank() },
                        title = title,
                        artist = artist
                    )
                    val normKey = com.tunemind.app.domain.vault.RestingVaultEngine.normalizeTrackKey(title, artist)
                    if (normKey in localKeys || identity.trackId in localKeys) continue

                    val qAt = obj.optLong("quarantinedAtMs", nowMs)
                    val restoredAt = maxOf(restoredMap[identity.trackId] ?: 0L, restoredMap[normKey] ?: 0L)
                    if (restoredAt > qAt) continue

                    val totalDays = obj.optDouble("totalDays", 3.5).toFloat().coerceIn(0.5f, 14.0f)
                    val satiety = obj.optInt("satietyScore", 82).coerceIn(40, 99)
                    val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(qAt, totalDays, satiety, nowMs)
                    if (rec.isGraduated) continue

                    currentLocal.add(
                        VaultTrack(
                            id = obj.optString("id", "vault-${identity.trackId}-$qAt"),
                            title = title,
                            artist = artist,
                            quarantinedAtMs = qAt,
                            totalDays = totalDays,
                            cooldownDaysRemaining = rec.cooldownDaysRemaining,
                            recoveryProgress = rec.recoveryProgress,
                            satietyScore = satiety,
                            sourcePackage = obj.optString("sourcePackage", "unknown_player"),
                            spotifyUri = identity.spotifyUri,
                            trackId = identity.trackId,
                            entryReason = obj.optString("entryReason", "Synced from Cloud Vault"),
                            isAutoQuarantined = obj.optBoolean("isAutoQuarantined", false),
                            lastPenaltyPlayAtMs = obj.optLong("lastPenaltyPlayAtMs", 0L),
                            updatedAtMs = nowMs
                        )
                    )
                    localKeys.add(normKey)
                    localKeys.add(identity.trackId)
                    addedAny = true
                }

                if (addedAny) {
                    _vaultState.value = currentLocal
                    saveVaultToPrefs(currentLocal)
                    refreshLiveListeningStatsNow()
                }
            } catch (e: Exception) {
                android.util.Log.w("TuneMindVM", "Cloud Vault hydration skipped: ${e.message}")
            }
        }
    }


    private var foregroundVolumeObserver: android.database.ContentObserver? = null
    private var foregroundAudioReceiver: android.content.BroadcastReceiver? = null
    private var foregroundDeviceCallback: android.media.AudioDeviceCallback? = null

    private fun registerForegroundHearingMonitor() {
        val ctx = AppHolder.context ?: return
        if (foregroundVolumeObserver != null) return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val audioMgr = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager

        val volObserver = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                refreshHearingInsights()
            }
        }
        foregroundVolumeObserver = volObserver
        runCatching {
            ctx.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                volObserver
            )
        }

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "android.media.VOLUME_CHANGED_ACTION",
                    android.media.AudioManager.ACTION_HEADSET_PLUG,
                    android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        refreshHearingInsights()
                    }
                }
            }
        }
        foregroundAudioReceiver = receiver
        val filter = android.content.IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(android.media.AudioManager.ACTION_HEADSET_PLUG)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                ctx,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioMgr != null) {
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    refreshHearingInsights()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    refreshHearingInsights()
                }
            }
            foregroundDeviceCallback = cb
            runCatching { audioMgr.registerAudioDeviceCallback(cb, mainHandler) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        statusClearJob?.cancel()
        snapshotMonitorListener?.let {
            com.tunemind.app.service.TuneMindListenerService.removePlaybackSnapshotListener(it)
        }
        snapshotMonitorListener = null
        inAppCompletionQueueListener?.let {
            com.tunemind.app.provider.TuneMindInAppPlayer.removeOnTrackCompletedListener(it)
        }
        inAppCompletionQueueListener = null
        val ctx = AppHolder.context ?: return
        foregroundVolumeObserver?.let { obs ->
            runCatching { ctx.contentResolver.unregisterContentObserver(obs) }
        }
        foregroundAudioReceiver?.let { rec ->
            runCatching { ctx.unregisterReceiver(rec) }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val audioMgr = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            foregroundDeviceCallback?.let { cb ->
                runCatching { audioMgr?.unregisterAudioDeviceCallback(cb) }
            }
        }
        foregroundVolumeObserver = null
        foregroundAudioReceiver = null
        foregroundDeviceCallback = null
    }

    fun setSystemMediaVolumePercent(percent: Int, showUi: Boolean = false) {
        val ctx = AppHolder.context ?: return
        com.tunemind.app.domain.audio.AudioDeviceObserver.setMediaVolumePercent(ctx, percent, showUi)
        refreshHearingInsights()
    }

    fun refreshHearingInsights() {
        val ctx = AppHolder.context ?: return
        val snapshot = com.tunemind.app.domain.audio.AudioDeviceObserver.getActiveAudioDeviceSnapshot(ctx)
        val estDb = snapshot.estimatedDb
        val headroom = snapshot.headroomDb
        val todayMs = _listeningStats.value.todayListeningMs.coerceAtLeast(0L)
        val todayMins = (todayMs / 60_000L).toInt().coerceAtLeast(0)
        val todayLabel = if (todayMs in 1_000L until 60_000L) "${todayMs / 1000L}s" else "${todayMins}m"

        // NIOSH 3 dB exchange rule: 8h max at 85 dBA; halves every +3 dB above 85 dBA
        val safeWindowHours = if (estDb <= 72f) {
            24f
        } else {
            (8.0 / Math.pow(2.0, ((estDb - 85f) / 3f).toDouble())).toFloat().coerceIn(0.05f, 24f)
        }
        val elapsedHours = todayMs.toFloat() / 3_600_000f
        val safeHoursLeft = (safeWindowHours - elapsedHours).coerceAtLeast(0f)

        // Real accumulated dose based on today's listened time vs safe NIOSH ceiling at current dBA
        val timeWeightedDose = if (todayMs > 0L) {
            (elapsedHours / safeWindowHours).coerceIn(0.01f, 1.0f)
        } else {
            0f
        }

        val guidance = when {
            estDb >= 92f -> "High volume (${estDb.toInt()} dBA). Lowering to ~70% protects inner-ear hair cells."
            headroom < 0f -> "Above 85 dBA daily threshold (%.1f dB over). Shorten session or step volume down.".format(kotlin.math.abs(headroom))
            headroom < 4f -> "Approaching 85 dBA guide (+%.1f dB margin). Safe for up to %.1fh today.".format(headroom, safeHoursLeft)
            todayMs <= 0L -> "Comfortable level (+%.1f dB headroom). Ready to monitor today's sound dose.".format(headroom)
            else -> "$todayLabel listened today with +%.1f dB margin — comfortable within safe limits.".format(headroom)
        }

        _hearingInsightsState.value = _hearingInsightsState.value.copy(
            activeSnapshot = snapshot,
            estimatedAverageDb = estDb,
            currentHeadroomDb = headroom,
            dailyExposureMinutes = todayMins,
            dailyExposureMs = todayMs,
            soundDoseFraction = timeWeightedDose,
            safeHoursRemaining = safeHoursLeft,
            safetyGuidance = guidance
        )
    }

    fun getFreshHearingInsightsNow(): HearingInsightsState {
        refreshHearingInsights()
        return _hearingInsightsState.value
    }

    fun refreshClaudeState() {
        val ctx = AppHolder.context ?: return
        _isClaudeConfigured.value = com.tunemind.app.domain.ai.ClaudeReasoningEngine.isConfigured(ctx)
    }

    fun requestClaudeSessionReasoning() {
        val ctx = AppHolder.context ?: return
        if (!com.tunemind.app.domain.ai.ClaudeReasoningEngine.isConfigured(ctx)) {
            _claudeSessionInsight.value = null
            return
        }
        viewModelScope.launch {
            _isReasoningLoading.value = true
            val result = com.tunemind.app.domain.ai.ClaudeReasoningEngine.analyzeActiveSession(
                context = ctx,
                recentTracks = _homeState.value.recentTracks,
                nowPlaying = _homeState.value.nowPlaying,
                vaultTracks = _vaultState.value,
                timeSlot = currentTimeSlot.label
            )
            _isReasoningLoading.value = false
            result.onSuccess { insight ->
                _claudeSessionInsight.value = insight
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
            val currentSource = _listeningStats.value.appBreakdown.firstOrNull()?.displayName
                ?: _homeState.value.nowPlaying?.sourcePackage?.let { ListeningPlayDeduplicator.resolveAppDisplayName(it) }
                ?: "No active player"
            val result = com.tunemind.app.domain.ai.ClaudeReasoningEngine.generateTelemetryReasoningReport(
                context = ctx,
                eventCount = rowsCount,
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

    fun testClaudeConnection(onResult: (Boolean, String) -> Unit) {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            val result = com.tunemind.app.domain.ai.ClaudeReasoningEngine.testConnection(ctx)
            result.onSuccess { text ->
                onResult(true, text)
            }.onFailure { err ->
                onResult(false, err.message ?: "Connection test failed.")
            }
        }
    }


    fun reportPlaybackLaunchResult(result: com.tunemind.app.provider.PlaybackLaunchResult) {
        _playbackLaunchFeedback.value = result
    }

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

    fun isAlwaysAskEnabled(context: android.content.Context): Boolean {
        return com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).isAlwaysAskEnabled()
    }

    fun setAlwaysAskEnabled(context: android.content.Context, enabled: Boolean) {
        com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).setAlwaysAskEnabled(enabled)
    }

    fun selectPlayerAndPlay(
        context: android.content.Context,
        item: com.tunemind.app.domain.recommendation.AiRecommendationEntity,
        providerId: String,
        rememberAsDefault: Boolean
    ): com.tunemind.app.provider.PlaybackLaunchResult {
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        registry.selectPlayer(providerId, rememberAsDefault)
        return playAiRecommendation(context, item, providerId)
    }

    fun resetDefaultPlayer(context: android.content.Context) {
        com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).resetDefaultPlayer()
    }


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
        val prefProvider = if (ctx != null) getPreferredProviderId(ctx) else "generic"

        // Local evidence-based baseline
        val baseline = com.tunemind.app.domain.recommendation.RecommendationEngine.generateRecommendations(
            tracks = _historyState.value.tracks,
            vaultTracks = _vaultState.value,
            preferredProviderId = prefProvider
        )
        _recommendations.value = baseline

        // Elevate asynchronously with Claude backend reasoning
        if (ctx != null && com.tunemind.app.domain.ai.ClaudeReasoningEngine.isConfigured(ctx)) {
            viewModelScope.launch {
                val aiRecs = com.tunemind.app.domain.ai.ClaudeReasoningEngine.generateReasonedRecommendations(
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

    fun buildUserDataExportJson(context: android.content.Context): String {
        val authSession = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context).sessionFlow.value
        val profile = _userProfile.value
        val stats = _listeningStats.value
        val root = org.json.JSONObject().apply {
            put("exported_at", java.time.Instant.now().toString())
            put("app", "TuneMind")
            put("account", org.json.JSONObject().apply {
                put("user_id", authSession.userId)
                put("display_name", authSession.displayName.ifBlank { profile.name })
                put("email", authSession.email.ifBlank { profile.email })
                put("provider", authSession.primaryProvider.ifBlank { "GUEST" })
                put("cross_app_library_tracks", _crossAppLibraryTracks.value.size)
                put("detected_players", org.json.JSONArray(_detectedPlayerTabs.value.map { it.displayName }))
                put("archetype", profile.archetype)
            })
            put("listening_summary", org.json.JSONObject().apply {
                put("total_listening_ms", stats.allTimeListeningMs)
                put("unique_tracks", stats.uniqueTrackCount)
                put("unique_artists", stats.uniqueArtistCount)
                put("apps_tracked", stats.appBreakdown.size)
            })
            put("taste_anchors", org.json.JSONArray(_userTasteAnchors.value))
            val historyArr = org.json.JSONArray()
            _historyState.value.tracks.take(100).forEach { track ->
                historyArr.put(
                    org.json.JSONObject().apply {
                        put("track_id", track.canonicalTrackId())
                        TrackIdentityResolver.normalizeSpotifyUri(track.providerTrackId)?.let { put("spotify_uri", it) }
                        put("title", track.title)
                        put("artist", track.artist)
                        put("plays", track.distinctAppearanceCount)
                        put("source_package", track.sourcePackage)
                    }
                )
            }
            put("recent_tracks", historyArr)
            val vaultArr = org.json.JSONArray()
            _vaultState.value.forEach { v ->
                vaultArr.put(
                    org.json.JSONObject().apply {
                        put(
                            "track_id",
                            v.trackId.ifBlank {
                                TrackIdentityResolver.resolveCanonicalTrackId(
                                    spotifyUri = v.spotifyUri,
                                    title = v.title,
                                    artist = v.artist
                                )
                            }
                        )
                        put("title", v.title)
                        put("artist", v.artist)
                        put("recovery_progress", v.recoveryProgress)
                    }
                )
            }
            put("dopamine_vault", vaultArr)
        }
        return root.toString(2)
    }

    suspend fun wipeAllLocalUserData(context: android.content.Context) = withContext(Dispatchers.IO) {
        runCatching { repo?.clear() }
        _vaultState.value = emptyList()
        _userTasteAnchors.value = emptyList()
        runCatching {
            context.getSharedPreferences("tunemind_vault_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("tunemind_user_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        withContext(Dispatchers.Main) {
            _userProfile.value = loadUserProfile()
        }
    }

    /**
     * Triggers Self-Learning AI Discovery tuned to the user's 12:00 PM local-timezone cycle.
     * When [forceRefresh] is true or today's 12:00 PM cycle has not been generated yet,
     * evaluates previous drop outcomes and fetches 2 unheard picks per genre + 3 Global Wildcards.
     */
    fun triggerChronoDiscovery(forceRefresh: Boolean = false) {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            try {
                if (_isDiscovering.value) return@launch

                val todayNoonKey = com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
                val existingInFlow = _aiDiscoveryCrate.value.filter {
                    it.dropDateKey == todayNoonKey && !it.isDismissed
                }
                if (!forceRefresh) {
                    fun repairUnverifiedAsync(pool: List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>) {
                        val unverified = pool.filter { it.previewUrl.isNullOrBlank() && it.spotifyUri.isNullOrBlank() }
                        if (unverified.isEmpty()) return
                        viewModelScope.launch(Dispatchers.IO) {
                            val repaired = unverified.take(8).map { rec ->
                                val resolved = runCatching {
                                    com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                                        context = ctx,
                                        cleanTitle = rec.title,
                                        cleanArtist = rec.artist,
                                        fullQuery = "${rec.title} ${rec.artist}".trim()
                                    )
                                }.getOrNull()
                                if (resolved == null) rec else {
                                    val playable = resolved.audiusStreamUrl?.takeIf { it.isNotBlank() }
                                        ?: resolved.previewUrl?.takeIf { it.isNotBlank() }
                                    val link = resolved.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                                        ?: resolved.deezerUrl?.takeIf { it.isNotBlank() }
                                    rec.copy(
                                        previewUrl = playable ?: rec.previewUrl,
                                        spotifyUri = link ?: rec.spotifyUri
                                    )
                                }
                            }
                            runCatching { aiRecDao?.insertAll(repaired) }
                        }
                    }

                    if (existingInFlow.size >= 10) {
                        autoTriggeredNoonCycleKey = todayNoonKey
                        repairUnverifiedAsync(existingInFlow)
                        return@launch
                    }
                    // Check Room synchronously before hitting backend (Flow may not have emitted on startup yet)
                    val existingInRoom = withContext(Dispatchers.IO) {
                        aiRecDao?.getByDropDateSnapshot(todayNoonKey) ?: emptyList()
                    }
                    if (existingInRoom.size >= 10) {
                        autoTriggeredNoonCycleKey = todayNoonKey
                        repairUnverifiedAsync(existingInRoom)
                        return@launch
                    }
                }

                autoTriggeredNoonCycleKey = todayNoonKey
                _isDiscovering.value = true
                val prefProvider = getPreferredProviderId(ctx)
                val currentPlays = _homeState.value.recentTracks.firstOrNull()?.distinctAppearanceCount?.coerceAtLeast(0) ?: 0
                val satiety = if (currentPlays <= 0) 0 else minOf(95, maxOf(18, currentPlays * 22))
                val authSession = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value

                val (allKnownTitles, playedSignals, dismissedSignals, previousOutcomes) = withContext(Dispatchers.IO) {
                    val knownFromCrate = aiRecDao?.getAllKnownTitles() ?: emptyList()
                    val playedUnifiedTitles = _unifiedTracks.value
                        .filter { it.verifiedPlayCount > 0 }
                        .map { it.title }
                    val historyTitles = _historyState.value.tracks
                        .filter { it.distinctAppearanceCount > 0 }
                        .mapNotNull { it.title }
                    val vaultTitles = _vaultState.value.map { it.title }
                    // Strict Never-Played Rule: exclude only played tracks (playCount > 0), Vault tracks, and past suggestions;
                    // unplayed tracks in Library with playCount == 0 remain eligible.
                    val combinedExclusions = (knownFromCrate + playedUnifiedTitles + historyTitles + vaultTitles).distinct()
                    DataQuad(
                        combinedExclusions,
                        aiRecDao?.getPositiveFeedbackSignals() ?: emptyList(),
                        aiRecDao?.getDismissedSignals() ?: emptyList(),
                        aiRecDao?.getPreviousDropsForEvaluation(todayNoonKey, 30) ?: emptyList()
                    )
                }

                val tasteSum = _tasteShiftSummary.value
                val genreDist = linkedMapOf<String, Int>()
                tasteSum.topGenres.forEach { gw ->
                    if (gw.genre.isNotBlank() && gw.count > 0) {
                        genreDist[gw.genre] = (genreDist[gw.genre] ?: 0) + gw.count
                    }
                }
                val artistGenreMap = linkedMapOf<String, String>()
                _artistProfiles.value.values.forEach { ap ->
                    val genres = ap.genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (ap.artistName.isNotBlank() && genres.isNotEmpty()) {
                        artistGenreMap[ap.artistName.lowercase()] = genres.first()
                        genres.forEach { g -> genreDist[g] = (genreDist[g] ?: 0) + 1 }
                    }
                }
                (tasteSum.risingArtists + tasteSum.consistentArtists).forEach { sa ->
                    if (sa.name.isNotBlank() && sa.genres.isNotEmpty()) {
                        artistGenreMap.putIfAbsent(sa.name.lowercase(), sa.genres.first())
                    }
                }

                val avgVolume = resolveCurrentVolumeRatio(ctx)
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val lateNightActive = com.tunemind.app.domain.voice.AgentSettingsStore.isLateNightComfortEnabled(ctx) &&
                    (hour >= 22 || hour < 6)
                val effectiveTimeLabel = if (lateNightActive) {
                    "${currentTimeSlot.label} (Warm & soft late-night comfort)"
                } else {
                    currentTimeSlot.label
                }

                val newRecs = com.tunemind.app.domain.ai.ClaudeReasoningEngine.generateChronoCrateRecommendations(
                    context = ctx,
                    recentTracks = _historyState.value.tracks,
                    vaultTracks = _vaultState.value,
                    timeSlotKey = currentTimeSlot.name,
                    timeSlotLabel = effectiveTimeLabel,
                    satietyPercent = satiety,
                    preferredProviderId = prefProvider,
                    userTasteAnchors = _userTasteAnchors.value,
                    nowPlayingArtist = _homeState.value.nowPlaying?.artist,
                    excludeTitles = allKnownTitles,
                    playedSignals = playedSignals,
                    dismissedSignals = dismissedSignals,
                    userId = authSession.userId,
                    userEmail = authSession.email,
                    avgVolumeRatio = avgVolume,
                    yesterdayOutcomes = previousOutcomes,
                    forceRefresh = forceRefresh,
                    genreDistribution = genreDist,
                    artistGenres = artistGenreMap,
                    cleanPlayEvents = _cleanPlayHistory.value,
                    onLearningSummaryUpdated = { summary ->
                        _forYouLearningState.value = summary
                    }
                )

                if (newRecs.isNotEmpty()) {
                    val verifiedRecs = withContext(Dispatchers.IO) {
                        newRecs.map { rec ->
                            val resolved = runCatching {
                                com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                                    context = ctx,
                                    cleanTitle = rec.title,
                                    cleanArtist = rec.artist,
                                    fullQuery = "${rec.title} ${rec.artist}".trim()
                                )
                            }.getOrNull()
                            if (resolved == null) rec else {
                                val playable = resolved.audiusStreamUrl?.takeIf { it.isNotBlank() }
                                    ?: resolved.previewUrl?.takeIf { it.isNotBlank() }
                                val link = resolved.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                                    ?: resolved.deezerUrl?.takeIf { it.isNotBlank() }
                                rec.copy(
                                    previewUrl = playable ?: rec.previewUrl,
                                    spotifyUri = link ?: rec.spotifyUri
                                )
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            aiRecDao?.insertAll(verifiedRecs)
                        } catch (t: Throwable) {
                            android.util.Log.e("TuneMindVM", "aiRecDao insertAll error: ${t.message}", t)
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("TuneMindVM", "triggerChronoDiscovery error: ${e.message}", e)
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    private data class DataQuad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun resolveCurrentVolumeRatio(context: android.content.Context): Float? {
        val recentAvg = _hearingInsightsState.value.recentVolumeObs
            .take(10)
            .mapNotNull { obs ->
                if (obs.maximumVolumeIndex > 0) {
                    (obs.volumeIndex.toFloat() / obs.maximumVolumeIndex.toFloat()).coerceIn(0.05f, 1.0f)
                } else null
            }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
        if (recentAvg != null && recentAvg > 0.02f) return recentAvg.coerceIn(0.05f, 1.0f)

        return runCatching {
            val audioMgr = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            val cur = audioMgr?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
            val max = audioMgr?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
            if (max > 0) (cur.toFloat() / max.toFloat()).coerceIn(0.05f, 1.0f) else null
        }.getOrNull()
    }

    fun toggleSaveRecommendation(id: String, isSaved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.updateSaved(id, isSaved)
                val ctx = AppHolder.context ?: return@launch
                val item = _aiDiscoveryCrate.value.firstOrNull { it.id == id } ?: return@launch
                val userId = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value.userId
                val updated = com.tunemind.app.domain.ai.ClaudeReasoningEngine.sendDiscoveryFeedback(
                    context = ctx,
                    userId = userId,
                    item = item,
                    outcome = if (isSaved) "SAVED" else "IGNORED",
                    volumeRatio = resolveCurrentVolumeRatio(ctx)
                )
                if (updated != null) {
                    _forYouLearningState.value = updated
                }
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "toggleSaveRecommendation error: ${t.message}", t)
            }
        }
    }

    private fun evaluateActiveForYouSessionTransition(nowPlayingObs: NowPlayingObservation?) {
        // Passive recognition: even if played directly from Spotify/Boomplay without tapping inside For You,
        // detect if the currently playing track matches a For You recommendation and begin tracking completion vs skip!
        if (activeTrackedForYouSession == null && nowPlayingObs != null && nowPlayingObs.isPlaying && !nowPlayingObs.title.isNullOrBlank()) {
            val matchedRec = _aiDiscoveryCrate.value.firstOrNull { rec ->
                ListeningPlayDeduplicator.isSameTrack(
                    nowPlayingObs.title,
                    nowPlayingObs.artist,
                    null,
                    rec.title,
                    rec.artist,
                    rec.spotifyUri
                )
            }
            if (matchedRec != null) {
                activeTrackedForYouSession = TrackedForYouSession(
                    rec = matchedRec,
                    startedAtMs = System.currentTimeMillis(),
                    matchedNowPlayingAtMs = System.currentTimeMillis(),
                    observedTrackDurationMs = nowPlayingObs.durationMs.takeIf { it > 0L } ?: matchedRec.trackDurationMs
                )
            }
        }

        val session = activeTrackedForYouSession ?: return
        if (session.reportedOutcome) {
            if (nowPlayingObs != null &&
                !ListeningPlayDeduplicator.isSameTrack(
                    nowPlayingObs.title,
                    nowPlayingObs.artist,
                    null,
                    session.rec.title,
                    session.rec.artist,
                    session.rec.spotifyUri
                )
            ) {
                activeTrackedForYouSession = null
            }
            return
        }

        val matches = nowPlayingObs != null &&
            ListeningPlayDeduplicator.isSameTrack(
                nowPlayingObs.title,
                nowPlayingObs.artist,
                null,
                session.rec.title,
                session.rec.artist,
                session.rec.spotifyUri
            )

        if (matches && nowPlayingObs != null) {
            if (session.matchedNowPlayingAtMs == null) {
                session.matchedNowPlayingAtMs = System.currentTimeMillis()
            }
            if (nowPlayingObs.durationMs > 0L) {
                session.observedTrackDurationMs = nowPlayingObs.durationMs
            }
            val elapsedMatchedMs = System.currentTimeMillis() - (session.matchedNowPlayingAtMs ?: session.startedAtMs)
            val effectiveDurMs = (session.observedTrackDurationMs.takeIf { it > 0L } ?: session.rec.trackDurationMs).takeIf { it > 0L } ?: 180_000L
            if (elapsedMatchedMs >= (effectiveDurMs * 0.65).toLong() || elapsedMatchedMs >= 60_000L) {
                finalizeTrackedForYouOutcome(session, outcome = "COMPLETED", listenedMs = elapsedMatchedMs, durationMs = effectiveDurMs)
            } else if (!nowPlayingObs.isPlaying && elapsedMatchedMs in 2_500L..24_999L && elapsedMatchedMs < (effectiveDurMs * 0.25).toLong()) {
                finalizeTrackedForYouOutcome(session, outcome = "SKIPPED", listenedMs = elapsedMatchedMs, durationMs = effectiveDurMs)
            }
            return
        }

        // Track changed to a different song after we observed this recommended track playing
        val matchedAt = session.matchedNowPlayingAtMs
        if (matchedAt != null) {
            val listenedMs = System.currentTimeMillis() - matchedAt
            val effectiveDurMs = (session.observedTrackDurationMs.takeIf { it > 0L } ?: session.rec.trackDurationMs).takeIf { it > 0L } ?: 180_000L
            if (listenedMs >= 30_000L || listenedMs >= (effectiveDurMs * 0.65).toLong()) {
                finalizeTrackedForYouOutcome(session, outcome = "COMPLETED", listenedMs = listenedMs, durationMs = effectiveDurMs)
            } else if (listenedMs in 2_500L..24_999L && listenedMs < (effectiveDurMs * 0.25).toLong()) {
                finalizeTrackedForYouOutcome(session, outcome = "SKIPPED", listenedMs = listenedMs, durationMs = effectiveDurMs)
            }
            activeTrackedForYouSession = null
        }
    }

    private fun finalizeTrackedForYouOutcome(
        session: TrackedForYouSession,
        outcome: String,
        listenedMs: Long,
        durationMs: Long
    ) {
        if (session.reportedOutcome) return
        session.reportedOutcome = true
        val item = session.rec
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = AppHolder.context ?: return@launch
                val volRatio = resolveCurrentVolumeRatio(ctx) ?: 0f
                val isCompletedFlag = if (outcome == "COMPLETED") 1 else 0
                aiRecDao?.recordListeningOutcome(
                    id = item.id,
                    listenedMs = listenedMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    volumeRatio = volRatio,
                    isCompleted = isCompletedFlag
                )
                val userId = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value.userId
                val updated = com.tunemind.app.domain.ai.ClaudeReasoningEngine.sendDiscoveryFeedback(
                    context = ctx,
                    userId = userId,
                    item = item,
                    outcome = outcome,
                    listenedMs = listenedMs,
                    durationMs = durationMs,
                    volumeRatio = volRatio.takeIf { it > 0f }
                )
                if (updated != null) {
                    _forYouLearningState.value = updated
                }
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "finalizeTrackedForYouOutcome error: ${t.message}", t)
            }
        }
    }

    fun markRecommendationPlayed(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = AppHolder.context
                val volRatio = ctx?.let { resolveCurrentVolumeRatio(it) } ?: 0f
                aiRecDao?.markPlayed(id, volRatio)
                if (ctx != null) {
                    val item = _aiDiscoveryCrate.value.firstOrNull { it.id == id } ?: return@launch
                    activeTrackedForYouSession = TrackedForYouSession(
                        rec = item,
                        startedAtMs = System.currentTimeMillis(),
                        observedTrackDurationMs = item.trackDurationMs
                    )
                    val userId = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value.userId
                    val updated = com.tunemind.app.domain.ai.ClaudeReasoningEngine.sendDiscoveryFeedback(
                        context = ctx,
                        userId = userId,
                        item = item,
                        outcome = "PLAYED",
                        listenedMs = 30_000L,
                        durationMs = item.trackDurationMs.takeIf { it > 0L } ?: 180_000L,
                        volumeRatio = volRatio.takeIf { it > 0f }
                    )
                    if (updated != null) {
                        _forYouLearningState.value = updated
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "markRecommendationPlayed error: ${t.message}", t)
            }
        }
    }

    fun dismissAiRecommendation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.dismiss(id)
                val ctx = AppHolder.context ?: return@launch
                val item = _aiDiscoveryCrate.value.firstOrNull { it.id == id } ?: return@launch
                val userId = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(ctx).sessionFlow.value.userId
                val updated = com.tunemind.app.domain.ai.ClaudeReasoningEngine.sendDiscoveryFeedback(
                    context = ctx,
                    userId = userId,
                    item = item,
                    outcome = "DISMISSED",
                    volumeRatio = resolveCurrentVolumeRatio(ctx)
                )
                if (updated != null) {
                    _forYouLearningState.value = updated
                }
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "dismiss error: ${t.message}", t)
            }
        }
    }

    fun restoreDismissedRecommendation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.restoreDismissed(id)
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "restoreDismissed error: ${t.message}", t)
            }
        }
    }

    fun deleteRecommendation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.deleteById(id)
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "deleteRecommendation error: ${t.message}", t)
            }
        }
    }

    fun previewAiRecommendation(
        context: android.content.Context,
        item: com.tunemind.app.domain.recommendation.AiRecommendationEntity
    ): Boolean {
        val url = item.previewUrl?.trim()
        if (!url.isNullOrBlank() && url.startsWith("http")) {
            val started = com.tunemind.app.provider.TuneMindInAppPlayer.playStreamUrl(
                context = context,
                streamUrl = url,
                title = item.title,
                artist = item.artist,
                album = item.album
            )
            if (started) {
                markRecommendationPlayed(item.id)
                return true
            }
        }
        return false
    }

    fun playAiRecommendation(
        context: android.content.Context,
        item: com.tunemind.app.domain.recommendation.AiRecommendationEntity,
        explicitProviderId: String? = null
    ): com.tunemind.app.provider.PlaybackLaunchResult {
        markRecommendationPlayed(item.id)
        val recCategory = when (item.category) {
            "PALATE_CLEANSER" -> com.tunemind.app.domain.recommendation.RecommendationCategory.PALATE_CLEANSER
            "CHRONO_RESTORE" -> com.tunemind.app.domain.recommendation.RecommendationCategory.COOLDOWN_RESTORED
            "SONIC_NOVELTY", "NOVEL_EXPLORATION" -> com.tunemind.app.domain.recommendation.RecommendationCategory.EXPLORATION_DISCOVERY
            else -> com.tunemind.app.domain.recommendation.RecommendationCategory.FAMILIAR_RECEPTIVE
        }
        val resolvedUri = TrackIdentityResolver.normalizeSpotifyUri(item.spotifyUri)
        val canonicalId = item.resolvedCanonicalTrackId()
        val track = com.tunemind.app.domain.recommendation.RecommendedTrack(
            id = item.id,
            title = item.title,
            artist = item.artist,
            album = item.album,
            reason = item.reason,
            confidence = item.confidence,
            category = recCategory,
            suggestedProviderId = explicitProviderId ?: item.suggestedProviderId,
            providerTrackId = resolvedUri,
            observationCountBasis = item.playCount.coerceAtLeast(0),
            trackId = canonicalId,
            spotifyUri = resolvedUri
        )
        return playRecommendation(context, track, explicitProviderId)
    }

    fun clearLibraryActionFeedback() {
        _libraryActionFeedback.value = null
    }

    fun refreshCrossAppIntelligence() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLiveListeningStatsNow()
            val missing = _crossAppLibraryTracks.value
                .filter { it.albumArtUrl.isNullOrBlank() }
                .take(16)
                .map { it.title to it.artist }
            if (missing.isNotEmpty()) {
                visualRepo?.enqueueVisualEnrichment(missing)
            }
            AppHolder.context?.let { ctx ->
                com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.syncCrossAppLibraryToSupabase(ctx)
            }
        }
    }

    fun exportForYouDropAsPlaylist(
        playlistName: String,
        tracks: List<Triple<String, String, String?>>
    ) {
        val inputs = tracks.map { (title, artist, extUri) ->
            com.tunemind.app.domain.execution.PlaylistTrackInput(
                title = title,
                artist = artist,
                spotify_uri = extUri
            )
        }
        createOrAddToTuneMindPlaylistAsync(
            name = playlistName,
            description = "Curated by TuneMind Daily Drop",
            tracks = inputs,
            appendIfExists = true
        ) {
            _libraryActionFeedback.value = "Saved \"$playlistName\" to TuneMind Playlists"
        }
    }

    // --- TUNEMIND-OWNED PLAYLISTS (Local Room DB across all detected players) ---

    fun createOrAddToTuneMindPlaylistAsync(
        name: String,
        description: String = "",
        moodOrAudience: String = "",
        tracks: List<com.tunemind.app.domain.execution.PlaylistTrackInput> = emptyList(),
        appendIfExists: Boolean = true,
        onComplete: ((TuneMindPlaylistEntity?) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pDao = tuneMindPlaylistDao ?: return@launch
                val cleanName = name.trim().ifBlank { "TuneMind Mix" }
                val existing = if (appendIfExists) {
                    pDao.getAllPlaylistsOnce().firstOrNull {
                        it.name.equals(cleanName, ignoreCase = true)
                    }
                } else null

                val now = System.currentTimeMillis()
                val playlistId = existing?.playlistId ?: java.util.UUID.randomUUID().toString()
                val targetPlaylist = existing?.copy(
                    description = description.ifBlank { existing.description },
                    moodOrAudience = moodOrAudience.ifBlank { existing.moodOrAudience },
                    updatedAtMs = now
                ) ?: TuneMindPlaylistEntity(
                    playlistId = playlistId,
                    name = cleanName,
                    description = description.ifBlank { "Created inside TuneMind" },
                    moodOrAudience = moodOrAudience,
                    createdAtMs = now,
                    updatedAtMs = now
                )
                pDao.upsertPlaylist(targetPlaylist)

                val existingTracks = pDao.getTracksForPlaylistOnce(playlistId)
                var nextPos = (existingTracks.maxOfOrNull { it.positionIndex } ?: -1) + 1
                val defaultPlayer = AppHolder.context?.let { ctx ->
                    val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx)
                    val pid = registry.getPreferredProviderId()
                    registry.getProvider(pid).packageName.ifBlank { pid }
                } ?: "tunemind_inapp"

                val newEntities = mutableListOf<TuneMindPlaylistTrackEntity>()
                tracks.forEach { t ->
                    val cleanTitle = t.title.trim()
                    if (cleanTitle.isNotBlank()) {
                        val cleanArtist = t.artist.trim()
                        val canonical = TrackIdentityResolver.resolveCanonicalIdentity(
                            trackId = t.track_id?.takeIf { it.isNotBlank() },
                            spotifyUri = t.spotify_uri?.takeIf { it.isNotBlank() },
                            title = cleanTitle,
                            artist = cleanArtist
                        )
                        val dedupKey = ListeningPlayDeduplicator.canonicalTrackKey(cleanTitle, cleanArtist)
                        val alreadyHas = existingTracks.any {
                            ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) == dedupKey
                        }
                        if (!alreadyHas) {
                            newEntities.add(
                                TuneMindPlaylistTrackEntity(
                                    entryId = "${playlistId}::$dedupKey",
                                    playlistId = playlistId,
                                    title = cleanTitle,
                                    artist = cleanArtist,
                                    externalUri = t.spotify_uri ?: canonical.spotifyUri.orEmpty(),
                                    trackId = canonical.trackId,
                                    sourcePlayer = t.source_player?.takeIf { it.isNotBlank() } ?: defaultPlayer,
                                    positionIndex = nextPos++,
                                    addedAtMs = now
                                )
                            )
                        }
                    }
                }
                if (newEntities.isNotEmpty()) {
                    pDao.upsertPlaylistTracks(newEntities)
                    pDao.touchPlaylist(playlistId, now)
                }
                AppHolder.context?.let { ctx ->
                    com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                        .triggerRelationalTrackCardsSyncAsync(ctx)
                }
                postNowPlayingStatus(
                    if (existing == null) "Created \"$cleanName\" in TuneMind (${newEntities.size} track${if (newEntities.size == 1) "" else "s"})"
                    else "Added ${newEntities.size} song${if (newEntities.size == 1) "" else "s"} to \"$cleanName\""
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete?.invoke(targetPlaylist)
                }
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindDB", "Failed creating/updating TuneMind playlist '$name': ${t.message}", t)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete?.invoke(null)
                }
            }
        }
    }

    fun addCurrentTrackToTuneMindPlaylist(playlistNameOrId: String) {
        val np = _homeState.value.nowPlaying ?: return
        val npTitle = np.title?.trim()
        if (npTitle.isNullOrBlank()) return
        val match = _tuneMindPlaylists.value.firstOrNull {
            it.playlist.playlistId == playlistNameOrId ||
                it.playlist.name.equals(playlistNameOrId.trim(), ignoreCase = true)
        }
        createOrAddToTuneMindPlaylistAsync(
            name = match?.playlist?.name ?: playlistNameOrId,
            tracks = listOf(
                com.tunemind.app.domain.execution.PlaylistTrackInput(
                    title = npTitle,
                    artist = np.artist.orEmpty(),
                    spotify_uri = np.spotifyUri,
                    track_id = np.trackId,
                    source_player = np.sourcePackage
                )
            ),
            appendIfExists = true
        )
    }

    fun renameTuneMindPlaylist(playlistId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            tuneMindPlaylistDao?.renamePlaylist(playlistId, clean)
            AppHolder.context?.let { ctx ->
                com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                    .triggerRelationalTrackCardsSyncAsync(ctx)
            }
        }
    }

    fun deleteTuneMindPlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val pDao = tuneMindPlaylistDao ?: return@launch
            pDao.deleteTracksByPlaylistId(playlistId)
            pDao.deletePlaylistById(playlistId)
            AppHolder.context?.let { ctx ->
                com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                    .triggerRelationalTrackCardsSyncAsync(ctx)
            }
        }
    }

    fun removeTrackFromTuneMindPlaylist(playlistId: String, entryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val pDao = tuneMindPlaylistDao ?: return@launch
            pDao.deleteTrackByEntryId(entryId)
            pDao.touchPlaylist(playlistId)
            AppHolder.context?.let { ctx ->
                com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                    .triggerRelationalTrackCardsSyncAsync(ctx)
            }
        }
    }

    fun playTuneMindPlaylist(
        playlistWithTracks: TuneMindPlaylistWithTracks,
        targetPlayerPkg: String,
        playTrack: (title: String, artist: String, spotifyUri: String?, sourcePkg: String) -> Unit
    ) {
        val items = playlistWithTracks.tracks
        val ctx = AppHolder.context
        val spotifyPlaylistId = com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine
            .extractSpotifyPlaylistId(playlistWithTracks.playlist.playlistId)
        val prefersSpotify = targetPlayerPkg.isBlank() ||
            targetPlayerPkg == com.tunemind.app.provider.SpotifyMusicProvider.SPOTIFY_PACKAGE ||
            targetPlayerPkg == "spotify" ||
            targetPlayerPkg == "tunemind_inapp"

        // 1. If this is ANY Spotify playlist (owned, followed editorial, or personalized mix like Discover Weekly),
        // launch the full Spotify playlist context directly — works even when local track list is empty!
        if (ctx != null && spotifyPlaylistId != null && (prefersSpotify || items.isEmpty())) {
            if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(ctx)) {
                viewModelScope.launch(Dispatchers.IO) {
                    val res = com.tunemind.app.provider.SpotifyPremiumConnectManager
                        .playPlaylistContextSilently(ctx, spotifyPlaylistId)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        if (res is com.tunemind.app.provider.SpotifyPlayResult.Success) {
                            postNowPlayingStatus("Playing Spotify playlist \"${playlistWithTracks.playlist.name}\" on ${res.deviceName}")
                        } else if (items.isNotEmpty()) {
                            items.drop(1).reversed().forEach { tr ->
                                enqueueUpNextTrack(
                                    title = tr.title,
                                    artist = tr.artist,
                                    spotifyUri = tr.externalUri.takeIf { it.isNotBlank() },
                                    reason = "From ${playlistWithTracks.playlist.name}",
                                    freshnessPercent = 96,
                                    playNext = true
                                )
                            }
                            val first = items.first()
                            val chosenPkg = targetPlayerPkg.ifBlank { first.sourcePlayer.ifBlank { "tunemind_inapp" } }
                            playTrack(first.title, first.artist, first.externalUri.takeIf { it.isNotBlank() }, chosenPkg)
                            postNowPlayingStatus("Playing \"${playlistWithTracks.playlist.name}\" (${items.size} tracks)")
                        } else {
                            // No active silent device awake + personalized mix -> open directly in Spotify app
                            com.tunemind.app.provider.SpotifyPremiumConnectManager
                                .openSpotifyPlaylistInApp(ctx, spotifyPlaylistId)
                            postNowPlayingStatus("Opening \"${playlistWithTracks.playlist.name}\" in Spotify — TuneMind will track every song you play")
                        }
                    }
                }
                return
            } else if (items.isEmpty()) {
                // Spotify Free Smart Mode or personalized mix: open directly in Spotify app so full tracks play
                com.tunemind.app.provider.SpotifyPremiumConnectManager
                    .openSpotifyPlaylistInApp(ctx, spotifyPlaylistId)
                postNowPlayingStatus("Opening \"${playlistWithTracks.playlist.name}\" in Spotify — TuneMind will capture every song you play")
                return
            }
        }

        if (items.isEmpty()) {
            postNowPlayingStatus("\"${playlistWithTracks.playlist.name}\" has no songs yet — tap '+ Add Songs' to add tracks.")
            return
        }

        items.drop(1).reversed().forEach { tr ->
            enqueueUpNextTrack(
                title = tr.title,
                artist = tr.artist,
                spotifyUri = tr.externalUri.takeIf { it.isNotBlank() },
                reason = "From ${playlistWithTracks.playlist.name}",
                freshnessPercent = 96,
                playNext = true
            )
        }
        val first = items.first()
        val chosenPkg = targetPlayerPkg.ifBlank { first.sourcePlayer.ifBlank { "tunemind_inapp" } }
        playTrack(first.title, first.artist, first.externalUri.takeIf { it.isNotBlank() }, chosenPkg)
        postNowPlayingStatus("Playing \"${playlistWithTracks.playlist.name}\" (${items.size} tracks)")
    }

    fun startSleepTimerMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 240)
        sleepTimerJob?.cancel()
        val targetEndMs = System.currentTimeMillis() + clamped * 60_000L
        _sleepTimerEndsAtMs.value = targetEndMs
        postNowPlayingStatus("Sleep timer set for $clamped minute${if (clamped == 1) "" else "s"}")
        sleepTimerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(clamped * 60_000L)
            _sleepTimerEndsAtMs.value = null
            pausePlayback()
            postNowPlayingStatus("Sleep timer complete — paused playback softly.")
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerEndsAtMs.value = null
        postNowPlayingStatus("Sleep timer cancelled")
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
    val sourcePackage: String = "unknown_player",
    val spotifyUri: String? = null,
    val trackId: String = "",
    val entryReason: String = "Manual Vault Lock",
    val isAutoQuarantined: Boolean = false,
    val lastPenaltyPlayAtMs: Long = 0L,
    val updatedAtMs: Long = quarantinedAtMs
) {
    fun resolvedCanonicalTrackId(): String = TrackIdentityResolver.resolveCanonicalTrackId(
        trackId = trackId.ifBlank { null },
        spotifyUri = spotifyUri,
        title = title,
        artist = artist
    )
}

data class GraduatedVaultSummary(
    val id: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val graduatedAtMs: Long,
    val totalRestedDays: Float,
    val previousSatietyScore: Int,
    val entryReason: String
)

data class TrackVaultUiStatus(
    val isInVault: Boolean = false,
    val vaultId: String? = null,
    val isCoolingDown: Boolean = false,
    val freshnessPercent: Int = 96,
    val recoveryPercent: Int = 0,
    val cooldownRemainingLabel: String? = null,
    val satietyScore: Int = 0,
    val entryReason: String? = null,
    val isManualFresh: Boolean = false,
    val isExcludedFromFresh: Boolean = false,
    val isInFresh: Boolean = false
)

data class ManualFreshTrack(
    val id: String,
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val sourcePackage: String = "unknown_player",
    val trackId: String = "",
    val addedAtMs: Long = System.currentTimeMillis(),
    val albumArtUrl: String? = null
) {
    fun resolvedCanonicalTrackId(): String = TrackIdentityResolver.resolveCanonicalTrackId(
        trackId = trackId.ifBlank { null },
        spotifyUri = spotifyUri,
        title = title,
        artist = artist
    )
}

data class DopamineCapsuleTrack(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val freshnessPercent: Int = 90,
    val reason: String = "Peak dopamine readiness",
    val trackId: String = "",
    val playCount: Int = 0
) {
    fun resolvedCanonicalTrackId(): String = TrackIdentityResolver.resolveCanonicalTrackId(
        trackId = trackId.ifBlank { null },
        spotifyUri = spotifyUri,
        title = title,
        artist = artist
    )
}

data class DopamineCapsuleState(
    val slotLabel: String = "",
    val headline: String = "Dopamine Capsule",
    val tracks: List<DopamineCapsuleTrack> = emptyList()
)

object AppHolder {
    var context: android.content.Context? = null
}

class TuneMindViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TuneMindViewModel() as T
}
