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
import com.tunemind.app.domain.moment.MusicalMomentEntity
import com.tunemind.app.domain.temporal.TimeSlot
import com.tunemind.app.domain.temporal.TimePatternEngine
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.memory.UnifiedTrackStats
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyLiveTrackState
import com.tunemind.app.data.ingestion.SpotifyOAuthRepository
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.data.ingestion.SpotifyPodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    private val momentDao by lazy { db?.musicalMomentDao() }
    private val audioDao by lazy { db?.audioDeviceDao() }
    private val aiRecDao by lazy { db?.aiRecommendationDao() }

    private val _musicalMomentsState = MutableStateFlow(MusicalMomentsState())
    val musicalMomentsState: StateFlow<MusicalMomentsState> = _musicalMomentsState.asStateFlow()

    private val _hearingInsightsState = MutableStateFlow(HearingInsightsState())
    val hearingInsightsState: StateFlow<HearingInsightsState> = _hearingInsightsState.asStateFlow()

    // Authoritative Deduplicated Listening & Cross-App Stats
    private val spotifyRepo by lazy {
        AppHolder.context?.let { SpotifyOAuthRepository.getInstance(it) }
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

    // Spotify Library (Liked Songs, Playlists, Podcasts, Live State)
    private val _spotifyLikedTracks = MutableStateFlow<List<SpotifyLikedTrackEntity>>(emptyList())
    val spotifyLikedTracks: StateFlow<List<SpotifyLikedTrackEntity>> = _spotifyLikedTracks.asStateFlow()

    private val _spotifyPlaylists = MutableStateFlow<List<SpotifyPlaylistEntity>>(emptyList())
    val spotifyPlaylists: StateFlow<List<SpotifyPlaylistEntity>> = _spotifyPlaylists.asStateFlow()

    private val _spotifyPodcasts = MutableStateFlow<List<SpotifyPodcastEntity>>(emptyList())
    val spotifyPodcasts: StateFlow<List<SpotifyPodcastEntity>> = _spotifyPodcasts.asStateFlow()

    private val _spotifyLiveState = MutableStateFlow<SpotifyLiveTrackState?>(null)
    val spotifyLiveState: StateFlow<SpotifyLiveTrackState?> = _spotifyLiveState.asStateFlow()

    private val _isLibrarySyncing = MutableStateFlow(false)
    val isLibrarySyncing: StateFlow<Boolean> = _isLibrarySyncing.asStateFlow()

    private val _needsLibraryScopeUpgrade = MutableStateFlow(false)
    val needsLibraryScopeUpgrade: StateFlow<Boolean> = _needsLibraryScopeUpgrade.asStateFlow()

    private val visualRepo by lazy {
        AppHolder.context?.let { com.tunemind.app.data.ingestion.SpotifyVisualArtworkRepository.getInstance(it) }
    }
    private val _spotifyTrackVisuals = MutableStateFlow<Map<String, com.tunemind.app.data.ingestion.ResolvedTrackVisuals>>(emptyMap())
    val spotifyTrackVisuals: StateFlow<Map<String, com.tunemind.app.data.ingestion.ResolvedTrackVisuals>> = _spotifyTrackVisuals.asStateFlow()

    private val _spotifyArtistProfiles = MutableStateFlow<Map<String, com.tunemind.app.data.ingestion.ArtistProfileCacheEntity>>(emptyMap())
    val spotifyArtistProfiles: StateFlow<Map<String, com.tunemind.app.data.ingestion.ArtistProfileCacheEntity>> = _spotifyArtistProfiles.asStateFlow()

    private val _spotifyUserProfile = MutableStateFlow<com.tunemind.app.data.ingestion.SpotifyUserProfileSnapshot?>(null)
    val spotifyUserProfile: StateFlow<com.tunemind.app.data.ingestion.SpotifyUserProfileSnapshot?> = _spotifyUserProfile.asStateFlow()

    private val _tasteShiftSummary = MutableStateFlow(com.tunemind.app.data.ingestion.SpotifyTasteShiftSummary())
    val tasteShiftSummary: StateFlow<com.tunemind.app.data.ingestion.SpotifyTasteShiftSummary> = _tasteShiftSummary.asStateFlow()

    private val _spotifyNewReleases = MutableStateFlow<List<com.tunemind.app.data.ingestion.SpotifyNewReleaseItem>>(emptyList())
    val spotifyNewReleases: StateFlow<List<com.tunemind.app.data.ingestion.SpotifyNewReleaseItem>> = _spotifyNewReleases.asStateFlow()

    private val _spotifyDevices = MutableStateFlow<List<com.tunemind.app.data.ingestion.SpotifyDeviceItem>>(emptyList())
    val spotifyDevices: StateFlow<List<com.tunemind.app.data.ingestion.SpotifyDeviceItem>> = _spotifyDevices.asStateFlow()

    private val _spotifyActionFeedback = MutableStateFlow<String?>(null)
    val spotifyActionFeedback: StateFlow<String?> = _spotifyActionFeedback.asStateFlow()

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
    private var expectedHandoverTargetTitle: String = ""
    @Volatile
    private var expectedHandoverTargetTrack: DopamineCapsuleTrack? = null
    @Volatile
    private var expectedHandoverWindowUntilMs: Long = 0L
    @Volatile
    private var expectedHandoverRetryCount: Int = 0
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
    private var cachedSpotifyHistoryForStats: List<com.tunemind.app.data.ingestion.SpotifyListeningHistoryEntity> = emptyList()
    @Volatile
    private var cachedMergeDuplicatesForStats: Boolean = true
    @Volatile
    private var cachedRepetitionSensitivityForStats: Float = 0.75f
    @Volatile
    private var liveStatsRecomputeJob: kotlinx.coroutines.Job? = null

    fun setUiForegroundActive(active: Boolean) {
        uiForegroundActive = active
        if (active) {
            viewModelScope.launch {
                runCatching {
                    com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
                    publishAuthoritativeNowPlayingNow()
                    spotifyRepo?.refreshCurrentlyPlayingOnly()
                    publishAuthoritativeNowPlayingNow()
                }
            }
        }
    }

    private fun isInAppPlayerCurrent(): Boolean {
        val ctxPkg = AppHolder.context?.packageName
        val nowPkg = _homeState.value.nowPlaying?.sourcePackage
        return com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
            (com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack() &&
                (nowPkg == null || nowPkg == ctxPkg))
    }

    private fun resolveBestNowPlayingForStats(
        rows: List<PlaybackObservation> = cachedRowsForStats,
        liveSpotify: SpotifyLiveTrackState? = _spotifyLiveState.value
    ): NowPlayingObservation? {
        val nowMs = System.currentTimeMillis()
        val appPkg = AppHolder.context?.packageName ?: "com.tunemind.app"

        val inAppActive = com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()
        val inAppTitle = com.tunemind.app.provider.TuneMindInAppPlayer.currentTitleFlow.value?.takeIf { it.isNotBlank() }
        val inAppObs = if (inAppActive && inAppTitle != null) {
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
            val resolvedDeviceUri = TrackIdentityResolver.normalizeSpotifyUri(
                latestTrackMeta?.providerTrackId ?: latestStateObs?.providerTrackId
            )
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
                    spotifyUri = resolvedDeviceUri,
                    title = resolvedDeviceTitle,
                    artist = resolvedDeviceArtist
                ),
                spotifyUri = resolvedDeviceUri
            )
        } else null

        val freshLiveSpotify = liveSpotify != null && (nowMs - liveSpotify.updatedAtMs) < 12 * 60 * 1000L
        val spotifyObs = if (freshLiveSpotify && liveSpotify != null && liveSpotify.title.isNotBlank()) {
            val spUri = TrackIdentityResolver.normalizeSpotifyUri(liveSpotify.spotifyUri)
            NowPlayingObservation(
                title = liveSpotify.title,
                artist = liveSpotify.artistOrShow,
                album = liveSpotify.albumOrSubtitle.ifBlank { null },
                sourcePackage = "com.spotify.music",
                isPlaying = liveSpotify.isPlaying && (nowMs - liveSpotify.updatedAtMs) < 90_000L,
                playbackStateCode = if (liveSpotify.isPlaying && (nowMs - liveSpotify.updatedAtMs) < 90_000L) 3 else 2,
                positionMs = liveSpotify.progressMs,
                durationMs = liveSpotify.durationMs,
                speed = 1.0f,
                updatedAtMs = liveSpotify.updatedAtMs,
                trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                    trackId = liveSpotify.trackId,
                    spotifyUri = spUri,
                    title = liveSpotify.title,
                    artist = liveSpotify.artistOrShow
                ),
                spotifyUri = spUri
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
            val spotifyAlreadySwitched = !pendingTitle.isNullOrBlank() &&
                spotifyObs != null &&
                spotifyObs.isPlaying &&
                ListeningPlayDeduplicator.isSameTrack(
                    spotifyObs.title, spotifyObs.artist, null,
                    pendingTitle, null, null
                )
            if (!liveAlreadySwitched && !spotifyAlreadySwitched) {
                return overrideObs
            }
        }

        // 1. Any actively playing real source wins immediately
        if (inAppObs != null && inAppObs.isPlaying) return inAppObs
        if (liveSnapObs != null && liveSnapObs.isPlaying) return liveSnapObs
        if (spotifyObs != null && spotifyObs.isPlaying &&
            (liveSnapObs == null || liveSnapObs.sourcePackage?.contains("spotify", ignoreCase = true) == true)
        ) {
            return spotifyObs
        }
        if (deviceObsCandidate != null && deviceObsCandidate.isPlaying &&
            (liveSnapObs == null || liveSnapObs.sourcePackage == deviceObsCandidate.sourcePackage)
        ) {
            return deviceObsCandidate
        }

        // 2. When paused/stopped: pick newest non-playing source
        val candidates = listOfNotNull(
            liveSnapObs,
            inAppObs,
            deviceObsCandidate,
            spotifyObs
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
                }
                if (incomingSnap.isPlaying && !incomingSnap.packageName.contains("spotify", ignoreCase = true)) {
                    spotifyRepo?.markSpotifyLivePausedExternally()
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

        val best = resolveBestNowPlayingForStats(cachedRowsForStats, _spotifyLiveState.value) ?: return
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
                val spMatch = best.sourcePackage?.contains("spotify", ignoreCase = true) == true &&
                    ListeningPlayDeduplicator.isSameTrack(
                        best.title, best.artist, null,
                        _spotifyLiveState.value?.title, _spotifyLiveState.value?.artistOrShow, _spotifyLiveState.value?.spotifyUri
                    )
                visualRepo?.requestTrackVisuals(
                    title = best.title,
                    artist = best.artist,
                    spotifyUri = if (spMatch) _spotifyLiveState.value?.spotifyUri else null,
                    fallbackAlbumArtUrl = if (spMatch) _spotifyLiveState.value?.albumArtUrl else null
                )
            }
        }
    }

    fun refreshLiveListeningStatsNow() {
        val rows = cachedRowsForStats
        val spHistory = cachedSpotifyHistoryForStats
        if (rows.isEmpty() && spHistory.isEmpty() && _homeState.value.nowPlaying == null) return
        if (liveStatsRecomputeJob?.isActive == true) return
        liveStatsRecomputeJob = viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val np = resolveBestNowPlayingForStats(rows, _spotifyLiveState.value)
            val snapshot = withContext(Dispatchers.Default) {
                ListeningPlayDeduplicator.buildSnapshot(
                    rawObservations = rows,
                    spotifyHistory = spHistory,
                    nowPlayingTitle = np?.title,
                    nowPlayingArtist = np?.artist,
                    nowPlayingSourcePkg = np?.sourcePackage,
                    isMusicPlayingNow = np?.isPlaying == true,
                    nowMs = nowMs,
                    mergeDuplicatePlays = cachedMergeDuplicatesForStats,
                    repetitionSensitivity = cachedRepetitionSensitivityForStats,
                    nowPlayingPositionMs = np?.positionMs ?: 0L,
                    nowPlayingDurationMs = np?.durationMs ?: 0L,
                    nowPlayingUpdatedAtMs = np?.updatedAtMs ?: 0L
                )
            }
            _unifiedTracks.value = snapshot.unifiedTracks
            _cleanPlayHistory.value = snapshot.cleanPlayHistory
            _listeningStats.value = snapshot.stats
            _playCountByKey.value = snapshot.playCountByTrackKey
            _playCountByUri.value = snapshot.playCountByUri
            _playCountByTrackId.value = snapshot.playCountByTrackId
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
        val key = ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
        _playCountByKey.value[key]?.let { return it }
        val cleanTitle = ListeningPlayDeduplicator.normalizeText(title)
        if (cleanTitle.isNotBlank()) {
            val fuzzy = _unifiedTracks.value.firstOrNull {
                (canonical.trackId.isNotBlank() && it.trackId == canonical.trackId) ||
                    ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, canonical.spotifyUri)
            }
            if (fuzzy != null) return fuzzy.verifiedPlayCount
        }
        return 0
    }

    fun isTrackCurrentlyPlaying(title: String?, artist: String?, spotifyUri: String? = null): Boolean {
        if (title.isNullOrBlank()) return false
        val nowPlaying = _homeState.value.nowPlaying ?: return false
        if (!nowPlaying.isPlaying) return false
        val matchedSpotifyUri = _spotifyLiveState.value?.takeIf { sp ->
            nowPlaying.sourcePackage?.contains("spotify", ignoreCase = true) == true &&
                ListeningPlayDeduplicator.isSameTrack(
                    nowPlaying.title,
                    nowPlaying.artist,
                    null,
                    sp.title,
                    sp.artistOrShow,
                    sp.spotifyUri
                )
        }?.spotifyUri
        return ListeningPlayDeduplicator.isSameTrack(
            nowPlaying.title,
            nowPlaying.artist,
            matchedSpotifyUri,
            title,
            artist,
            spotifyUri
        )
    }

    private val _loadingPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingPlaylistIds: StateFlow<Set<String>> = _loadingPlaylistIds.asStateFlow()

    private val _loadedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val loadedPlaylistIds: StateFlow<Set<String>> = _loadedPlaylistIds.asStateFlow()

    private val _playlistTrackErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val playlistTrackErrors: StateFlow<Map<String, String>> = _playlistTrackErrors.asStateFlow()

    fun refreshSpotifyLibrary(forcePlaylistTracks: Boolean = true) {
        val repoInstance = spotifyRepo ?: return
        viewModelScope.launch {
            if (_isLibrarySyncing.value) return@launch
            _isLibrarySyncing.value = true
            try {
                repoInstance.syncRecentlyPlayedToLocalRoom()
                repoInstance.syncFullSpotifyLibraryToLocalRoom(forcePlaylistTracks = forcePlaylistTracks)
            } finally {
                _isLibrarySyncing.value = false
            }
        }
    }

    fun loadPlaylistTracksIfNeeded(playlistId: String, forceRefresh: Boolean = false) {
        val repoInstance = spotifyRepo ?: return
        if (_loadingPlaylistIds.value.contains(playlistId)) return
        if (!forceRefresh && _loadedPlaylistIds.value.contains(playlistId)) {
            val cached = _spotifyPlaylists.value.firstOrNull { it.playlistId == playlistId }?.decodeTracks().orEmpty()
            if (cached.isNotEmpty()) return
        }
        viewModelScope.launch {
            _loadingPlaylistIds.value = _loadingPlaylistIds.value + playlistId
            _playlistTrackErrors.value = _playlistTrackErrors.value - playlistId
            try {
                when (val res = repoInstance.loadPlaylistTracksDetailed(playlistId)) {
                    is com.tunemind.app.data.ingestion.PlaylistTracksLoadResult.Success -> {
                        _loadedPlaylistIds.value = _loadedPlaylistIds.value + playlistId
                    }
                    is com.tunemind.app.data.ingestion.PlaylistTracksLoadResult.Error -> {
                        _loadedPlaylistIds.value = _loadedPlaylistIds.value + playlistId
                        _playlistTrackErrors.value = _playlistTrackErrors.value + (playlistId to res.message)
                    }
                }
            } catch (e: Exception) {
                _loadedPlaylistIds.value = _loadedPlaylistIds.value + playlistId
                _playlistTrackErrors.value = _playlistTrackErrors.value + (playlistId to "Could not load playlist songs. Tap Retry.")
            } finally {
                _loadingPlaylistIds.value = _loadingPlaylistIds.value - playlistId
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
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallbackIntent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }
    }

    // Cooldown Vault Persistence
    private val _vaultState = MutableStateFlow<List<VaultTrack>>(loadVaultFromPrefs())
    val vaultState: StateFlow<List<VaultTrack>> = _vaultState.asStateFlow()

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
            val artUrl = explicitArtworkUrl?.takeIf { it.isNotBlank() }
                ?: visualRepo?.getCachedTrackVisuals(title, artist, _spotifyLiveState.value?.spotifyUri)?.albumArtUrl
                ?: _spotifyLiveState.value?.albumArtUrl
            if (!artUrl.isNullOrBlank()) {
                val hex = com.tunemind.app.ui.theme.AlbumArtPaletteExtractor.extractDominantAccentHex(ctx, artUrl)
                if (!hex.isNullOrBlank()) {
                    setCustomThemeAccentHex(hex, com.tunemind.app.ui.theme.ThemeBehavior.FIXED)
                }
            } else {
                visualRepo?.requestTrackVisuals(title, artist)
            }
        }
    }

    @Volatile
    private var manualActiveTrackOverride: NowPlayingObservation? = null

    fun setAgentActiveTrack(
        title: String,
        artist: String,
        sourcePackage: String = "com.spotify.music",
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
            if (enabled) "Auto Queue active · TuneMind will block Spotify autoplay and spin Up Next"
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

    private fun buildFreshQueueCandidates(): List<DopamineCapsuleTrack> {
        val slot = currentTimeSlot
        val vault = _vaultState.value
        val coolingKeys = vault
            .filter { it.recoveryProgress < 0.50f }
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

        val rawCandidates = mutableListOf<DopamineCapsuleTrack>()
        val seenKeys = mutableSetOf<String>()

        fun addCandidateIfEligible(track: DopamineCapsuleTrack) {
            val cleanTitle = track.title.trim()
            if (cleanTitle.length < 2) return
            val key = ListeningPlayDeduplicator.canonicalTrackKey(cleanTitle, track.artist)
            val normTitle = ListeningPlayDeduplicator.normalizeText(cleanTitle)
            if (key == activeKey || (activeCleanTitle.isNotBlank() && normTitle == activeCleanTitle)) return
            if (key in coolingKeys) return
            if (!seenKeys.add(key)) return
            rawCandidates.add(track)
        }

        // 1. Cooldown Vault comebacks ready for high-dopamine return (>= 50% recovered)
        vault.filter { it.recoveryProgress >= 0.50f }.forEach { vt ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(vt.spotifyUri)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = vt.title,
                    artist = vt.artist,
                    spotifyUri = cleanUri,
                    freshnessPercent = (vt.recoveryProgress * 100).toInt().coerceIn(72, 98),
                    reason = "Rested in Cooldown Vault • Ready for high-dopamine comeback",
                    trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                        trackId = vt.trackId.ifBlank { null },
                        spotifyUri = cleanUri,
                        title = vt.title,
                        artist = vt.artist
                    )
                )
            )
        }

        // 2. Fresh For You AI Discovery picks (unplayed / non-dismissed)
        _aiDiscoveryCrate.value
            .filter { !it.isDismissed && !it.wasPlayed }
            .forEach { rec ->
                val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
                addCandidateIfEligible(
                    DopamineCapsuleTrack(
                        title = rec.title,
                        artist = rec.artist,
                        spotifyUri = cleanUri,
                        freshnessPercent = (rec.confidence * 100f).toInt().coerceIn(82, 97),
                        reason = rec.reason.takeIf { it.isNotBlank() } ?: "AI Discovery pick for ${slot.label}",
                        trackId = rec.resolvedCanonicalTrackId()
                    )
                )
            }

        // 3. Low-habituation Unified / Recent tracks
        val recents = _historyState.value.tracks.ifEmpty { _homeState.value.recentTracks }
        recents
            .filter { !it.title.isNullOrBlank() }
            .sortedWith(
                compareBy<TrackMemoryEntry> { it.distinctAppearanceCount }
                    .thenBy { it.lastObservedAppearanceAtMs }
            )
            .forEach { entry ->
                val tTitle = entry.title?.trim() ?: return@forEach
                val tArtist = entry.artist?.trim()?.ifBlank { "Your Library" } ?: "Your Library"
                val plays = entry.distinctAppearanceCount.coerceAtLeast(0)
                val freshness = (100 - minOf(84, plays * 14)).coerceIn(70, 96)
                val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(entry.providerTrackId)
                val playLabel = if (plays > 0) "Low receptor habituation ($plays ${if (plays == 1) "play" else "plays"})" else "Fresh track"
                addCandidateIfEligible(
                    DopamineCapsuleTrack(
                        title = tTitle,
                        artist = tArtist,
                        spotifyUri = cleanUri,
                        freshnessPercent = freshness,
                        reason = "$playLabel • Calibrated for ${slot.label}",
                        trackId = entry.canonicalTrackId()
                    )
                )
            }

        // 4. Saved AI recommendations & Spotify Liked Songs for deep non-repeating variety
        _savedAiRecommendations.value.forEach { rec ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = rec.title,
                    artist = rec.artist,
                    spotifyUri = cleanUri,
                    freshnessPercent = 88,
                    reason = "Saved For You • Calibrated for ${slot.label}",
                    trackId = rec.resolvedCanonicalTrackId()
                )
            )
        }

        _spotifyLikedTracks.value.forEach { liked ->
            val cleanUri = TrackIdentityResolver.normalizeSpotifyUri(liked.spotifyUri)
            addCandidateIfEligible(
                DopamineCapsuleTrack(
                    title = liked.trackTitle,
                    artist = liked.artistName.ifBlank { "Spotify Library" },
                    spotifyUri = cleanUri,
                    freshnessPercent = 84,
                    reason = "From your Spotify Liked Songs • Fresh rotation",
                    trackId = TrackIdentityResolver.resolveCanonicalTrackId(
                        trackId = liked.trackId,
                        spotifyUri = cleanUri,
                        title = liked.trackTitle,
                        artist = liked.artistName
                    )
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

        val coolingKeys = _vaultState.value
            .filter { it.recoveryProgress < 0.50f }
            .map { ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist) }
            .toSet()

        val activeNormTitle = ListeningPlayDeduplicator.normalizeText(activeTitle)
        val existingFiltered = if (forceRebuild) {
            emptyList()
        } else {
            _upNextQueueState.value.tracks.filter { item ->
                val key = ListeningPlayDeduplicator.canonicalTrackKey(item.title, item.artist)
                val itemNormTitle = ListeningPlayDeduplicator.normalizeText(item.title)
                val matchesActive = activeNormTitle.isNotBlank() && itemNormTitle == activeNormTitle
                !matchesActive && !isConsumedKey(key) && key !in coolingKeys
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
        if (ctx != null && head != null && head.spotifyUri.isNullOrBlank()) {
            val headKey = ListeningPlayDeduplicator.canonicalTrackKey(head.title, head.artist)
            if (headKey != preResolvedQueueKey) {
                preResolvedQueueKey = headKey
                viewModelScope.launch(Dispatchers.IO) {
                    val uri = runCatching {
                        com.tunemind.app.provider.SpotifyTrackUriResolver.resolveTrackUriBlocking(
                            context = ctx,
                            cleanTitle = head.title,
                            cleanArtist = head.artist,
                            fullQuery = "${head.title} ${head.artist}".trim()
                        )
                    }.getOrNull()
                    if (!uri.isNullOrBlank() && uri.startsWith("spotify:track:")) {
                        val curList = _upNextQueueState.value.tracks
                        var patchedAny = false
                        val patched = curList.map { item ->
                            if (ListeningPlayDeduplicator.canonicalTrackKey(item.title, item.artist) == headKey &&
                                item.spotifyUri.isNullOrBlank()
                            ) {
                                patchedAny = true
                                item.copy(spotifyUri = uri)
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
            syncUpNextQueueWithSources(forceRebuild = true)
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
        _upNextQueueState.value = _upNextQueueState.value.copy(tracks = current.take(5))
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

    fun playQueuedTrack(
        track: DopamineCapsuleTrack,
        targetPlayer: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val ctx = AppHolder.context ?: return
        val resolvedPkg = targetPlayer?.takeIf { it.isNotBlank() }
            ?: resolveActiveMediaPackage()
            ?: "com.spotify.music"

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
        postNowPlayingStatus("Playing \"${track.title}\" from Up Next…")

        viewModelScope.launch(Dispatchers.IO) {
            var ok = false
            var msg = "Launched \"${track.title}\""
            try {
                // Ensure current Spotify/external song stops before launching queued track
                pauseExternalAndLocalMediaQuietly(ctx, resolvedPkg)
                if (resolvedPkg.contains("spotify", ignoreCase = true) &&
                    !com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected()
                ) {
                    runCatching { visualRepo?.controlSpotifyWebPlayback("PAUSE") }
                }
                val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx)
                val matchedProvider = registry.getAllProviders().firstOrNull {
                    it.packageName.equals(resolvedPkg, ignoreCase = true)
                }
                val providerId = matchedProvider?.providerId ?: "pkg_$resolvedPkg"
                val displayName = matchedProvider?.displayName
                    ?: ListeningPlayDeduplicator.resolveAppDisplayName(resolvedPkg)

                val recTrack = com.tunemind.app.domain.recommendation.RecommendedTrack(
                    id = resolvedIdentity.trackId,
                    title = track.title,
                    artist = track.artist,
                    reason = track.reason,
                    confidence = (track.freshnessPercent / 100f).coerceIn(0.5f, 0.99f),
                    category = com.tunemind.app.domain.recommendation.RecommendationCategory.FAMILIAR_RECEPTIVE,
                    suggestedProviderId = providerId,
                    providerTrackId = resolvedIdentity.spotifyUri,
                    observationCountBasis = resolvePlayCount(track.title, track.artist, resolvedIdentity.spotifyUri, resolvedIdentity.trackId),
                    trackId = resolvedIdentity.trackId,
                    spotifyUri = resolvedIdentity.spotifyUri
                )
                val result = com.tunemind.app.provider.MusicPlayerAutoLauncher.launchWithAutoPlay(
                    context = ctx,
                    providerId = providerId,
                    displayName = displayName,
                    packageName = resolvedPkg,
                    track = recTrack,
                    query = "${track.title} ${track.artist}".trim(),
                    deepLinkUri = track.spotifyUri
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
                    targetPkg = pkg.ifBlank { resolveActiveMediaPackage() ?: "com.spotify.music" }
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
        viewModelScope.launch {
            while (true) {
                val ctx = AppHolder.context
                val overrideOn = ctx != null && com.tunemind.app.domain.voice.AgentSettingsStore.isUpNextAutoOverrideEnabled(ctx)
                val isPlayingNow = _homeState.value.nowPlaying?.isPlaying == true
                kotlinx.coroutines.delay(
                    when {
                        overrideOn && isPlayingNow -> 400L
                        uiForegroundActive -> 900L
                        else -> 1800L
                    }
                )
                if (ctx == null) continue
                val listener = com.tunemind.app.service.TuneMindListenerService.instance
                if (uiForegroundActive || overrideOn) {
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
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(4_500L)
                if (!uiForegroundActive) continue
                if (spotifyRepo?.isConnectedFlow?.value == true) {
                    runCatching { spotifyRepo?.refreshCurrentlyPlayingOnly() }
                    publishAuthoritativeNowPlayingNow()
                }
            }
        }

        // Observe AI Recommendation Crate from Room DB
        viewModelScope.launch {
            try {
                aiRecDao?.observeAll()?.collect { crate ->
                    _aiDiscoveryCrate.value = crate
                    syncUpNextQueueWithSources(forceRebuild = false)
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

        // Initialize Telemetry Reasoning Report & schedule periodic Claude Worker
        AppHolder.context?.let { ctx ->
            _reasoningReport.value = com.tunemind.app.domain.ai.ClaudeReasoningEngine.getLatestReport(ctx)
            com.tunemind.app.domain.ai.ClaudeTelemetryReasoningWorker.schedulePeriodic(ctx)
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

        // Observe Spotify Library (Liked Songs, Playlists, Podcasts, Live Playback State)
        viewModelScope.launch {
            runCatching {
                spotifyRepo?.observeLikedTracks(150)?.collect {
                    _spotifyLikedTracks.value = it
                    syncUpNextQueueWithSources(forceRebuild = false)
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                spotifyRepo?.observePlaylists(60)?.collect { _spotifyPlaylists.value = it }
            }
        }
        viewModelScope.launch {
            runCatching {
                spotifyRepo?.observePodcasts(100)?.collect { _spotifyPodcasts.value = it }
            }
        }
        viewModelScope.launch {
            runCatching {
                spotifyRepo?.currentlyPlayingLiveState?.collect { _spotifyLiveState.value = it }
            }
        }
        viewModelScope.launch {
            runCatching {
                spotifyRepo?.needsLibraryScopeUpgrade?.collect { _needsLibraryScopeUpgrade.value = it }
            }
        }

        // Observe Spotify Visuals, Profile, Taste Shifts, New Releases & Playback Devices
        viewModelScope.launch {
            runCatching {
                visualRepo?.trackVisualsFlow?.collect { visualsMap ->
                    _spotifyTrackVisuals.value = visualsMap
                    val recentPairs = _unifiedTracks.value.take(30).map { it.title to it.artist }
                    if (recentPairs.isNotEmpty()) {
                        visualRepo?.refreshUniversalTasteIntelligenceFromResolvedTracks(recentPairs)
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching { visualRepo?.artistProfilesFlow?.collect { _spotifyArtistProfiles.value = it } }
        }
        viewModelScope.launch {
            runCatching { visualRepo?.userProfileFlow?.collect { _spotifyUserProfile.value = it } }
        }
        viewModelScope.launch {
            runCatching { visualRepo?.tasteShiftSummaryFlow?.collect { _tasteShiftSummary.value = it } }
        }
        viewModelScope.launch {
            runCatching { visualRepo?.newReleasesFlow?.collect { _spotifyNewReleases.value = it } }
        }
        viewModelScope.launch {
            runCatching { visualRepo?.playbackDevicesFlow?.collect { _spotifyDevices.value = it } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (spotifyRepo?.isConnectedFlow?.value == true) {
                    visualRepo?.syncUserProfileAndTasteIntelligence(force = false)
                    visualRepo?.syncFollowedNewReleases(force = false)
                }
            }
        }

        // Purge any legacy CURRENTLY_PLAYING polling noise on startup
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db?.spotifyListeningHistoryDao()?.deletePollingDuplicates() }
        }

        // Live listening clock: updates active Today/7d/30d/All-Time durations every 5s while playback is running
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                val playingAny = _homeState.value.nowPlaying?.isPlaying == true ||
                    com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
                    _spotifyLiveState.value?.isPlaying == true
                if (playingAny) {
                    refreshLiveListeningStatsNow()
                }
            }
        }

        // Unified Authoritative Pipeline: Combine Device MediaSessions + Verified Spotify History + Live State
        viewModelScope.launch {
            try {
                val obsFlow = repo?.observeRecent() ?: flowOf(emptyList())
                val spotifyHistoryFlow = db?.spotifyListeningHistoryDao()?.observeRecentHistory(10_000) ?: flowOf(emptyList())

                combine(
                    obsFlow,
                    spotifyHistoryFlow,
                    _spotifyLiveState,
                    com.tunemind.app.domain.voice.AgentSettingsStore.mergeDuplicatePlaysEnabled,
                    com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity
                ) { rows, spotifyHistory, liveSpotify, mergeDuplicates, repetitionSensitivity ->
                    listOf(rows, spotifyHistory, liveSpotify, mergeDuplicates, repetitionSensitivity)
                }.collect { values ->
                    @Suppress("UNCHECKED_CAST")
                    val rows = values[0] as List<PlaybackObservation>
                    @Suppress("UNCHECKED_CAST")
                    val spotifyHistory = values[1] as List<com.tunemind.app.data.ingestion.SpotifyListeningHistoryEntity>
                    val liveSpotify = values[2] as com.tunemind.app.data.ingestion.SpotifyLiveTrackState?
                    val mergeDuplicates = values[3] as Boolean
                    val repetitionSensitivity = values[4] as Float

                    cachedRowsForStats = rows
                    cachedSpotifyHistoryForStats = spotifyHistory
                    cachedMergeDuplicatesForStats = mergeDuplicates
                    cachedRepetitionSensitivityForStats = repetitionSensitivity

                    val memory: ListeningMemory
                    val coverage: CoverageAssessment
                    val nowPlayingObs = resolveBestNowPlayingForStats(rows, liveSpotify)

                    val snapshot: com.tunemind.app.domain.memory.DeduplicatedListeningSnapshot
                    withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        memory = ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)
                        coverage = CoverageAssessor.assessFromRawEvents(rows)
                        snapshot = ListeningPlayDeduplicator.buildSnapshot(
                            rawObservations = rows,
                            spotifyHistory = spotifyHistory,
                            nowPlayingTitle = nowPlayingObs?.title,
                            nowPlayingArtist = nowPlayingObs?.artist,
                            nowPlayingSourcePkg = nowPlayingObs?.sourcePackage,
                            isMusicPlayingNow = nowPlayingObs?.isPlaying == true,
                            nowMs = System.currentTimeMillis(),
                            mergeDuplicatePlays = mergeDuplicates,
                            repetitionSensitivity = repetitionSensitivity,
                            nowPlayingPositionMs = nowPlayingObs?.positionMs ?: 0L,
                            nowPlayingDurationMs = nowPlayingObs?.durationMs ?: 0L,
                            nowPlayingUpdatedAtMs = nowPlayingObs?.updatedAtMs ?: 0L
                        )
                    }

                    _unifiedTracks.value = snapshot.unifiedTracks
                    _cleanPlayHistory.value = snapshot.cleanPlayHistory
                    _listeningStats.value = snapshot.stats
                    _playCountByKey.value = snapshot.playCountByTrackKey
                    _playCountByUri.value = snapshot.playCountByUri
                    _playCountByTrackId.value = snapshot.playCountByTrackId
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

                    // Universal Profile Engine (works for ANY music player — YouTube Music, Boomplay, Apple Music, local MP3, Spotify)
                    nowPlayingObs?.title?.takeIf { it.isNotBlank() }?.let { activeTitle ->
                        val spMatch = nowPlayingObs.sourcePackage?.contains("spotify", ignoreCase = true) == true &&
                            ListeningPlayDeduplicator.isSameTrack(
                                activeTitle, nowPlayingObs.artist, null,
                                liveSpotify?.title, liveSpotify?.artistOrShow, liveSpotify?.spotifyUri
                            )
                        visualRepo?.requestTrackVisuals(
                            title = activeTitle,
                            artist = nowPlayingObs.artist,
                            spotifyUri = if (spMatch) liveSpotify?.spotifyUri else null,
                            fallbackAlbumArtUrl = if (spMatch) liveSpotify?.albumArtUrl else null
                        )
                    }
                    recent.take(15).forEach { entry ->
                        val t = entry.title?.takeIf { it.isNotBlank() } ?: return@forEach
                        visualRepo?.requestTrackVisuals(t, entry.artist, entry.providerTrackId)
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

    private fun resolveActiveMediaPackage(): String? {
        val snapPkg = com.tunemind.app.service.TuneMindListenerService.instance
            ?.getActivePlaybackSnapshot()?.packageName?.takeIf { it.isNotBlank() }
        if (snapPkg != null) return snapPkg
        val nowPkg = _homeState.value.nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() }
        if (nowPkg != null) return nowPkg
        val ctx = AppHolder.context ?: return "com.spotify.music"
        val prefId = com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx).getPreferredProviderId()
        return com.tunemind.app.provider.MusicProviderRegistry.getInstance(ctx)
            .getProvider(prefId)
            .packageName
            .takeIf { it.isNotBlank() && it != "*" } ?: "com.spotify.music"
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
        runCatching {
            if (com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected()) {
                com.tunemind.app.provider.SpotifyAppRemoteManager.pause()
            }
        }
        val controllerPaused = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.pause(targetPkg) ?: false
        }.getOrDefault(false)
        if (!controllerPaused) {
            dispatchOsMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            // Also send explicit pause key if external controller was Spotify or third-party app that ignores one path
            dispatchOsMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun scheduleMediaSessionResync(targetPkg: String?) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(400L)
            com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
            publishAuthoritativeNowPlayingNow()
            if (targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)) {
                runCatching { spotifyRepo?.refreshCurrentlyPlayingOnly() }
            }
            kotlinx.coroutines.delay(950L)
            com.tunemind.app.service.TuneMindListenerService.instance?.observerOrNull()?.refreshSessions()
            publishAuthoritativeNowPlayingNow()
        }
    }

    fun pausePlayback() {
        val ctx = AppHolder.context
        if (isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindInAppPlayer.pause()
            setAgentPlaybackPaused()
            postNowPlayingStatus("Paused TuneMind Built-In Player")
            return
        }
        val targetPkg = resolveActiveMediaPackage()
        val isSpotify = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val appRemotePaused = if (isSpotify) {
            runCatching { com.tunemind.app.provider.SpotifyAppRemoteManager.pause() }.getOrDefault(false)
        } else {
            false
        }
        val controllerPaused = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.pause(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else false

        if (isSpotify && !appRemotePaused) {
            viewModelScope.launch(Dispatchers.IO) {
                visualRepo?.controlSpotifyWebPlayback("PAUSE")
            }
        }

        setAgentPlaybackPaused()
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (appRemotePaused || controllerPaused || keyDispatched) "Paused $appName"
            else "Sent pause signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun resumePlayback() {
        val ctx = AppHolder.context
        if (isInAppPlayerCurrent()) {
            val ok = com.tunemind.app.provider.TuneMindInAppPlayer.resume()
            if (ok) {
                setAgentPlaybackResumed()
                postNowPlayingStatus("Resumed TuneMind Built-In Player")
                return
            }
        }
        val targetPkg = resolveActiveMediaPackage()
        val isSpotify = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val appRemoteResumed = if (isSpotify) {
            runCatching { com.tunemind.app.provider.SpotifyAppRemoteManager.resume() }.getOrDefault(false)
        } else {
            false
        }
        val controllerResumed = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.play(targetPkg) ?: false
        }.getOrDefault(false)
        val keyDispatched = if (!appRemoteResumed && !controllerResumed && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        } else false

        if (isSpotify && !appRemoteResumed) {
            viewModelScope.launch(Dispatchers.IO) {
                visualRepo?.controlSpotifyWebPlayback("PLAY")
            }
        }

        setAgentPlaybackResumed()
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (appRemoteResumed || controllerResumed || keyDispatched) "Playing on $appName"
            else "Sent play signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun togglePlayback() {
        val currentlyPlaying = if (isInAppPlayerCurrent()) {
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
        val isSpotify = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val appRemoteSkipped = if (isSpotify) {
            runCatching { com.tunemind.app.provider.SpotifyAppRemoteManager.skipNext() }.getOrDefault(false)
        } else {
            false
        }
        val controllerSkipped = if (!appRemoteSkipped) {
            runCatching {
                com.tunemind.app.service.TuneMindListenerService.instance?.skipNext(targetPkg) ?: false
            }.getOrDefault(false)
        } else false
        val keyDispatched = if (!appRemoteSkipped && !controllerSkipped && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        } else false

        if (isSpotify && !appRemoteSkipped && !controllerSkipped) {
            viewModelScope.launch(Dispatchers.IO) {
                visualRepo?.controlSpotifyWebPlayback("NEXT")
            }
        }
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (appRemoteSkipped || controllerSkipped || keyDispatched) "Skipped to next on $appName"
            else "Sent next-track signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun skipPrev() {
        val ctx = AppHolder.context
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
        val isSpotify = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val appRemotePrev = if (isSpotify) {
            runCatching { com.tunemind.app.provider.SpotifyAppRemoteManager.skipPrev() }.getOrDefault(false)
        } else {
            false
        }
        val controllerPrev = if (!appRemotePrev) {
            runCatching {
                com.tunemind.app.service.TuneMindListenerService.instance?.skipPrev(targetPkg) ?: false
            }.getOrDefault(false)
        } else false
        val keyDispatched = if (!appRemotePrev && !controllerPrev && ctx != null) {
            dispatchOsMediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        } else false

        if (isSpotify && !appRemotePrev && !controllerPrev) {
            viewModelScope.launch(Dispatchers.IO) {
                visualRepo?.controlSpotifyWebPlayback("PREV")
            }
        }
        val appName = ListeningPlayDeduplicator.resolveAppDisplayName(targetPkg)
        postNowPlayingStatus(
            if (appRemotePrev || controllerPrev || keyDispatched) "Previous track on $appName"
            else "Sent previous-track signal to $appName"
        )
        scheduleMediaSessionResync(targetPkg)
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        if (isInAppPlayerCurrent()) {
            com.tunemind.app.provider.TuneMindInAppPlayer.seekTo(clamped)
            publishAuthoritativeNowPlayingNow()
            return
        }
        val targetPkg = resolveActiveMediaPackage()
        val isSpotify = targetPkg == null || targetPkg.contains("spotify", ignoreCase = true)
        val appRemoteSeeked = if (isSpotify && com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected()) {
            runCatching { com.tunemind.app.provider.SpotifyAppRemoteManager.seekTo(clamped) }.getOrDefault(false)
        } else false
        val controllerSeeked = if (!appRemoteSeeked) {
            runCatching {
                com.tunemind.app.service.TuneMindListenerService.instance?.seekTo(targetPkg, clamped) ?: false
            }.getOrDefault(false)
        } else false

        if (isSpotify && !appRemoteSeeked && !controllerSeeked) {
            viewModelScope.launch(Dispatchers.IO) {
                visualRepo?.seekSpotifyWebPlayback(clamped)
            }
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
            if (snap.isPlaying) {
                expectedHandoverTargetTitle = ""
                expectedHandoverTargetTrack = null
                expectedHandoverWindowUntilMs = 0L
                expectedHandoverRetryCount = 0
            }
            return
        }

        // Intercept unapproved third-party autoplay if external player sneaked past Pause during TuneMind handover
        if (expectedTrack != null &&
            now < expectedHandoverWindowUntilMs &&
            !queueHandoverInFlight &&
            !matchesFinishedSource &&
            !com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle) &&
            expectedHandoverRetryCount < 2
        ) {
            val nextRetryCount = expectedHandoverRetryCount + 1
            queueHandoverInFlight = true
            val pkg = snap.packageName.ifBlank { resolveActiveMediaPackage() ?: "com.spotify.music" }
            pauseExternalAndLocalMediaQuietly(ctx, pkg)
            if (pkg.contains("spotify", ignoreCase = true)) {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { visualRepo?.controlSpotifyWebPlayback("PAUSE") }
                }
            }
            postNowPlayingStatus("Intercepted player autoplay · Spinning \"${expectedTrack.title}\"")
            playQueuedTrack(expectedTrack, pkg) { _, _ ->
                queueHandoverInFlight = false
            }
            expectedHandoverRetryCount = nextRetryCount
            return
        }

        if (queueHandoverInFlight || (now - lastQueueHandoverAtMs) < 6_500L) {
            if (com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle)) {
                queueMonitoredTitle = rawTitle
                queueMonitoredArtist = snap.artist
                queueMonitoredPackage = snap.packageName
                if (snap.durationMs > 0L) queueMonitoredDurationMs = snap.durationMs
                queueMonitoredMaxPosMs = snap.positionMs
            }
            return
        }

        val isSameTrack = queueMonitoredTitle.isBlank() ||
            ListeningPlayDeduplicator.isSameTrack(rawTitle, snap.artist, null, queueMonitoredTitle, queueMonitoredArtist, null)

        if (isSameTrack) {
            queueMonitoredTitle = rawTitle
            queueMonitoredArtist = snap.artist
            queueMonitoredPackage = snap.packageName
            if (snap.durationMs > 0L) queueMonitoredDurationMs = snap.durationMs
            if (snap.positionMs > queueMonitoredMaxPosMs) queueMonitoredMaxPosMs = snap.positionMs

            // Trigger A: Song is within final window -> immediately stop player autoplay & spin TuneMind Up Next
            val leadWindowMs = if (queueMonitoredDurationMs >= 45_000L) 3_200L else 1_800L
            if (snap.isPlaying &&
                queueMonitoredDurationMs >= 15_000L &&
                snap.positionMs >= (queueMonitoredDurationMs - leadWindowMs) &&
                !lastQueueHandoverSourceTitle.equals(rawTitle, ignoreCase = true)
            ) {
                triggerAutoQueueHandover(
                    finishedTitle = rawTitle,
                    targetPkg = snap.packageName.ifBlank { resolveActiveMediaPackage() ?: "com.spotify.music" }
                )
            }
            return
        }

        // Trigger B: External player auto-advanced to an unrequested song after substantial play -> intercept immediately
        val prevTitle = queueMonitoredTitle
        val prevDur = queueMonitoredDurationMs
        val prevMax = queueMonitoredMaxPosMs
        val wasTuneMindLaunch = com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle) ||
            (manualActiveTrackOverride?.title?.equals(rawTitle, ignoreCase = true) == true &&
                (now - (manualActiveTrackOverride?.updatedAtMs ?: 0L)) < 12_000L)
        val prevPlayedSubstantially = prevMax >= 12_000L || (prevDur >= 15_000L && prevMax >= (prevDur * 45L) / 100L)

        if (!wasTuneMindLaunch &&
            prevTitle.isNotBlank() &&
            prevPlayedSubstantially &&
            !lastQueueHandoverSourceTitle.equals(prevTitle, ignoreCase = true)
        ) {
            triggerAutoQueueHandover(
                finishedTitle = prevTitle,
                targetPkg = snap.packageName.ifBlank { resolveActiveMediaPackage() ?: "com.spotify.music" }
            )
            return
        }

        queueMonitoredTitle = rawTitle
        queueMonitoredArtist = snap.artist
        queueMonitoredPackage = snap.packageName
        queueMonitoredDurationMs = snap.durationMs
        queueMonitoredMaxPosMs = snap.positionMs
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
        if (targetPkg.contains("spotify", ignoreCase = true)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { visualRepo?.controlSpotifyWebPlayback("PAUSE") }
            }
        }
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
                val pkg = obj.optString("sourcePackage", "").ifBlank { "com.spotify.music" }
                val rawUri = obj.optString("spotifyUri", "").takeIf { it.isNotBlank() }
                val titleStr = obj.getString("title")
                val artistStr = obj.getString("artist")
                val resolvedIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = obj.optString("trackId", "").takeIf { it.isNotBlank() },
                    spotifyUri = rawUri,
                    title = titleStr,
                    artist = artistStr
                )
                list.add(
                    VaultTrack(
                        id = obj.getString("id"),
                        title = titleStr,
                        artist = artistStr,
                        quarantinedAtMs = qTime,
                        totalDays = totalDays,
                        cooldownDaysRemaining = (remaining * 10).toInt() / 10f,
                        recoveryProgress = progress,
                        satietyScore = obj.optInt("satietyScore", 85),
                        sourcePackage = pkg,
                        spotifyUri = resolvedIdentity.spotifyUri,
                        trackId = resolvedIdentity.trackId
                    )
                )
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

    fun quarantineTrack(
        title: String,
        artist: String,
        durationDays: Float = 5f,
        sourcePackage: String? = null,
        spotifyUri: String? = null,
        trackId: String? = null
    ) {
        val matched = _unifiedTracks.value.firstOrNull {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, spotifyUri)
        }
        val resolvedPkg = sourcePackage?.takeIf { it.isNotBlank() }
            ?: matched?.primarySourcePackage
            ?: _homeState.value.nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() }
            ?: "com.spotify.music"
        val resolvedIdentity = TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId ?: matched?.trackId,
            spotifyUri = spotifyUri ?: matched?.spotifyUri,
            title = title,
            artist = artist
        )

        val id = "vault-${System.currentTimeMillis()}"
        val newTrack = VaultTrack(
            id = id,
            title = title,
            artist = artist,
            quarantinedAtMs = System.currentTimeMillis(),
            totalDays = durationDays,
            cooldownDaysRemaining = durationDays,
            recoveryProgress = 0.05f,
            satietyScore = 85,
            sourcePackage = resolvedPkg,
            spotifyUri = resolvedIdentity.spotifyUri,
            trackId = resolvedIdentity.trackId
        )
        val updated = listOf(newTrack) + _vaultState.value.filter { !it.title.equals(title, ignoreCase = true) }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        syncUpNextQueueWithSources(forceRebuild = false)
        refreshRecommendations()
    }

    fun addToVault(title: String, artist: String, durationDays: Float = 5f) {
        quarantineTrack(title, artist, durationDays)
    }

    fun restoreFromVault(id: String) {
        val updated = _vaultState.value.filter { it.id != id && it.trackId != id }
        _vaultState.value = updated
        saveVaultToPrefs(updated)
        syncUpNextQueueWithSources(forceRebuild = false)
        refreshRecommendations()
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

    fun configureAndSeekMomentPlayback(startMs: Long, endMs: Long, loopEnabled: Boolean) {
        com.tunemind.app.provider.TuneMindInAppPlayer.setClipLoop(startMs, endMs, loopEnabled)
        viewModelScope.launch {
            kotlinx.coroutines.delay(900L)
            if (com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()) {
                com.tunemind.app.provider.TuneMindInAppPlayer.seekTo(startMs)
            } else if (com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected()) {
                com.tunemind.app.provider.SpotifyAppRemoteManager.seekTo(startMs)
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
        customName: String? = null,
        spotifyUri: String? = null
    ) {
        val duration = maxOf(5_000L, endMs - startMs)
        val resolvedUri = spotifyUri?.takeIf { it.isNotBlank() } ?: _unifiedTracks.value.firstOrNull {
            ListeningPlayDeduplicator.isSameTrack(it.title, it.artist, it.spotifyUri, title, artist, null)
        }?.spotifyUri
        viewModelScope.launch(Dispatchers.IO) {
            try {
                momentDao?.insertOrUpdate(
                    MusicalMomentEntity(
                        momentId = java.util.UUID.randomUUID().toString(),
                        trackTitle = title,
                        trackArtist = artist,
                        trackAlbum = album,
                        sourcePackage = sourcePkg,
                        providerTrackId = resolvedUri,
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
                put("spotify_data_connected", authSession.isSpotifyConnected)
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
                    if (existingInFlow.isNotEmpty()) {
                        autoTriggeredNoonCycleKey = todayNoonKey
                        return@launch
                    }
                    // Check Room synchronously before hitting backend (Flow may not have emitted on startup yet)
                    val existingInRoom = withContext(Dispatchers.IO) {
                        aiRecDao?.getByDropDateSnapshot(todayNoonKey) ?: emptyList()
                    }
                    if (existingInRoom.isNotEmpty()) {
                        autoTriggeredNoonCycleKey = todayNoonKey
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
                    val likedTitles = _spotifyLikedTracks.value.map { it.trackTitle }
                    val historyTitles = _historyState.value.tracks.mapNotNull { it.title }
                    val vaultTitles = _vaultState.value.map { it.title }
                    val combinedExclusions = if (forceRefresh) {
                        (likedTitles + historyTitles + vaultTitles).distinct()
                    } else {
                        (knownFromCrate + likedTitles + historyTitles + vaultTitles).distinct()
                    }
                    DataQuad(
                        combinedExclusions,
                        aiRecDao?.getPositiveFeedbackSignals() ?: emptyList(),
                        aiRecDao?.getDismissedSignals() ?: emptyList(),
                        aiRecDao?.getPreviousDropsForEvaluation(todayNoonKey, 24) ?: emptyList()
                    )
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
                    onLearningSummaryUpdated = { summary ->
                        _forYouLearningState.value = summary
                    }
                )

                if (newRecs.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            aiRecDao?.insertAll(newRecs)
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
        val session = activeTrackedForYouSession ?: return
        if (session.reportedOutcome) return

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

    fun clearSpotifyActionFeedback() {
        _spotifyActionFeedback.value = null
    }

    fun refreshSpotifyIntelligence(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            visualRepo?.syncUserProfileAndTasteIntelligence(force = force)
            visualRepo?.syncFollowedNewReleases(force = force)
        }
    }

    fun addTrackToSpotifyQueue(title: String, artist: String, spotifyUri: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = visualRepo?.addToSpotifyQueue(title, artist, spotifyUri) ?: return@launch
            _spotifyActionFeedback.value = res.message
        }
    }

    fun saveTrackToSpotifyLiked(title: String, artist: String, spotifyUri: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = visualRepo?.saveTrackToSpotifyLiked(title, artist, spotifyUri) ?: return@launch
            _spotifyActionFeedback.value = res.message
        }
    }

    fun exportForYouDropAsPlaylist(
        playlistName: String,
        tracks: List<Triple<String, String, String?>>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _spotifyActionFeedback.value = "Creating Spotify playlist…"
            val res = visualRepo?.exportDropAsSpotifyPlaylist(
                playlistName = playlistName,
                description = "Curated by TuneMind Daily Drop",
                tracks = tracks
            ) ?: return@launch
            _spotifyActionFeedback.value = res.message
        }
    }

    fun refreshSpotifyDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            visualRepo?.refreshPlaybackDevices()
        }
    }

    fun transferPlaybackToDevice(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = visualRepo?.transferPlaybackToDevice(deviceId, startPlaying = true) ?: return@launch
            _spotifyActionFeedback.value = res.message
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
    val sourcePackage: String = "com.spotify.music",
    val spotifyUri: String? = null,
    val trackId: String = ""
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
    val trackId: String = ""
) {
    fun resolvedCanonicalTrackId(): String = TrackIdentityResolver.resolveCanonicalTrackId(
        trackId = trackId.ifBlank { null },
        spotifyUri = spotifyUri,
        title = title,
        artist = artist
    )
}

data class DopamineCapsuleState(
    val slotLabel: String = "MORNING FOCUS",
    val headline: String = "Morning Dopamine Capsule",
    val tracks: List<DopamineCapsuleTrack> = emptyList()
)

object AppHolder {
    var context: android.content.Context? = null
}

class TuneMindViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TuneMindViewModel() as T
}
