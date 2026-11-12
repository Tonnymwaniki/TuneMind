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
    val activeSnapshot: com.tunemind.app.domain.audio.AudioDeviceSnapshot? = null,
    val recentVolumeObs: List<VolumeObservationEntity> = emptyList(),
    val estimatedAverageDb: Float = 68f,
    val currentHeadroomDb: Float = 17f,
    val dailyExposureMinutes: Int = 45,
    val soundDoseFraction: Float = 0.32f,
    val safeHoursRemaining: Float = 16f,
    val safetyGuidance: String = "Listening exposure within safe acoustic threshold (<80 dB)."
)

data class UserProfileState(
    val name: String = "Tonny",
    val email: String = "tonny@tunemind.neuro",
    val tier: String = "Acoustic Intelligence Tier 1",
    val archetype: String = "Late-Night Deep Explorer",
    val archetypeDescription: String = "High affinity for low-fatigue timbres, nocturnal groove retention, and circadian recovery intervals."
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
        triggerChronoDiscovery(forceRefresh = true)
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
            name = prefs.getString("user_name", "Tonny") ?: "Tonny",
            email = prefs.getString("user_email", "tonny@tunemind.neuro") ?: "tonny@tunemind.neuro",
            tier = prefs.getString("user_tier", "Acoustic Intelligence Tier 1") ?: "Acoustic Intelligence Tier 1",
            archetype = prefs.getString("user_archetype", "Late-Night Deep Explorer") ?: "Late-Night Deep Explorer",
            archetypeDescription = prefs.getString("user_archetype_desc", "High affinity for low-fatigue timbres, nocturnal groove retention, and circadian recovery intervals.")
                ?: "High affinity for low-fatigue timbres, nocturnal groove retention, and circadian recovery intervals."
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
            enableMoodAdaptation = true
        )
        updateThemePreferences(updated)
    }

    fun setAtmospherePaletteById(paletteId: String) {
        val clean = paletteId.lowercase().trim()
        if (clean == "auto" || clean == "time") {
            val updated = _themePreferences.value.copy(
                behavior = com.tunemind.app.ui.theme.ThemeBehavior.AUTO_TIME_OF_DAY,
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
            enableMoodAdaptation = true
        )
        updateThemePreferences(updated)
    }

    fun syncAtmosphereToTrack(title: String, artist: String) {
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
    }

    @Volatile
    private var manualActiveTrackOverride: NowPlayingObservation? = null

    fun setAgentActiveTrack(
        title: String,
        artist: String,
        sourcePackage: String = "com.spotify.music"
    ) {
        val nowObs = NowPlayingObservation(
            title = title.trim(),
            artist = artist.trim().ifBlank { "Autonomous Studio Flow" },
            album = null,
            sourcePackage = sourcePackage,
            isPlaying = true,
            playbackStateCode = 3,
            positionMs = 0L,
            durationMs = 210_000L,
            speed = 1.0f,
            updatedAtMs = System.currentTimeMillis()
        )
        manualActiveTrackOverride = nowObs
        _homeState.value = _homeState.value.copy(nowPlaying = nowObs)
        syncAtmosphereToTrack(title, artist)
    }

    fun setAgentPlaybackPaused() {
        val current = _homeState.value.nowPlaying
        val pausedObs = (current ?: NowPlayingObservation(title = "Studio Standby", artist = "Paused")).copy(
            isPlaying = false,
            playbackStateCode = 2,
            updatedAtMs = System.currentTimeMillis()
        )
        manualActiveTrackOverride = pausedObs
        _homeState.value = _homeState.value.copy(nowPlaying = pausedObs)
        recomputeAdaptiveTheme()
    }

    fun setAgentPlaybackResumed() {
        val current = _homeState.value.nowPlaying
        if (current != null) {
            val resumedObs = current.copy(
                isPlaying = true,
                playbackStateCode = 3,
                updatedAtMs = System.currentTimeMillis()
            )
            manualActiveTrackOverride = resumedObs
            _homeState.value = _homeState.value.copy(nowPlaying = resumedObs)
            recomputeAdaptiveTheme()
        }
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

    fun compileDopamineCapsule(): DopamineCapsuleState {
        val slot = currentTimeSlot
        val recents = _historyState.value.tracks.ifEmpty { _homeState.value.recentTracks }
        val vault = _vaultState.value
        val curTitle = _homeState.value.nowPlaying?.title?.lowercase()?.trim()

        val capsuleTracks = mutableListOf<DopamineCapsuleTrack>()

        // 1. Include any Cooldown Vault track that has recovered >= 50%
        vault.filter { it.recoveryProgress >= 0.50f }.take(1).forEach { vt ->
            capsuleTracks.add(
                DopamineCapsuleTrack(
                    title = vt.title,
                    artist = vt.artist,
                    spotifyUri = null,
                    freshnessPercent = (vt.recoveryProgress * 100).toInt().coerceIn(70, 98),
                    reason = "Rested in Cooldown Vault • Ready for high-dopamine comeback"
                )
            )
        }

        // 2. Fill up to 3 tracks with highest-freshness (lowest play count) real library tracks
        val freshPool = recents
            .filter { !it.title.isNullOrBlank() && it.title.lowercase().trim() != curTitle }
            .sortedBy { it.distinctAppearanceCount }

        for (entry in freshPool) {
            if (capsuleTracks.size >= 3) break
            val tTitle = entry.title?.trim() ?: continue
            if (capsuleTracks.any { it.title.equals(tTitle, ignoreCase = true) }) continue
            val plays = maxOf(1, entry.distinctAppearanceCount)
            val freshness = (100 - minOf(88, plays * 16)).coerceIn(72, 96)
            capsuleTracks.add(
                DopamineCapsuleTrack(
                    title = tTitle,
                    artist = entry.artist?.trim()?.ifBlank { "Your Library" } ?: "Your Library",
                    spotifyUri = entry.providerTrackId?.takeIf { it.startsWith("spotify:track:") },
                    freshnessPercent = freshness,
                    reason = "Low receptor habituation ($plays plays) • Calibrated for ${slot.label}"
                )
            )
        }

        return DopamineCapsuleState(
            slotLabel = slot.label.uppercase(),
            headline = "${slot.label} Dopamine Capsule",
            tracks = capsuleTracks
        )
    }

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

        // Observe audio devices and volume
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

        refreshHearingInsights()

        refreshReadiness()
        refreshGeminiState()
        requestGeminiSessionReasoning()

        // Observe AI Recommendation Crate from Room DB
        viewModelScope.launch {
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

        // Initialize Telemetry Reasoning Report & schedule periodic Worker
        AppHolder.context?.let { ctx ->
            _reasoningReport.value = com.tunemind.app.domain.ai.GeminiReasoningEngine.getLatestReport(ctx)
            com.tunemind.app.domain.ai.GeminiTelemetryReasoningWorker.schedulePeriodic(ctx)
        }
        refreshTelemetryReasoningReport()
        recomputeAdaptiveTheme()
        triggerChronoDiscovery(forceRefresh = false)

        // 60-second periodic time-period transition watcher & auto-changing chrono discovery
        viewModelScope.launch {
            var lastObservedSlot = currentTimeSlot
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val currentSlot = currentTimeSlot
                if (currentSlot != lastObservedSlot) {
                    lastObservedSlot = currentSlot
                    recomputeAdaptiveTheme()
                    triggerChronoDiscovery(forceRefresh = true)
                } else {
                    recomputeAdaptiveTheme()
                }
            }
        }

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
                val isBrowserOrWeb = { pkg: String?, title: String?, artist: String? ->
                    val p = (pkg ?: "").lowercase()
                    val t = (title ?: "").lowercase()
                    val a = (artist ?: "").lowercase()
                    p.contains("chrome") || p.contains("browser") || p.contains("chromium") ||
                    p.contains("webview") || p.contains("firefox") || p.contains("opera") ||
                    p.contains("edge") || p.contains("brave") || p.contains("searchbox") ||
                    t.startsWith("http") || t.contains(".com") || t.contains("chrome") ||
                    a.startsWith("http") || a.contains(".com") || a.contains("chrome")
                }

                // Merge reconstructed memory.tracks WITH all raw observed tracks in Room so no played song is ever dropped!
                val combinedTrackMap = linkedMapOf<String, TrackMemoryEntry>()
                for (t in memory.tracks.filter { !it.title.isNullOrBlank() && !isBrowserOrWeb(it.sourcePackage, it.title, it.artist) }.sortedByDescending { it.lastObservedAppearanceAtMs }) {
                    val key = "${t.title?.trim()?.lowercase()}|${t.artist?.trim()?.lowercase() ?: ""}"
                    combinedTrackMap[key] = t
                }
                val rawGrouped = rows
                    .filter { !it.trackTitle.isNullOrBlank() && !isBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist) }
                    .groupBy { "${it.trackTitle?.trim()?.lowercase()}|${it.trackArtist?.trim()?.lowercase() ?: ""}" }
                for ((key, group) in rawGrouped) {
                    val latest = group.maxByOrNull { it.eventTimeMs } ?: group.first()
                    val rawTrackId = group.mapNotNull { it.trackId?.takeIf(String::isNotBlank) }.firstOrNull()
                    val existing = combinedTrackMap[key]
                    if (existing == null) {
                        combinedTrackMap[key] = TrackMemoryEntry(
                            entryId = "raw:$key",
                            sourcePackage = latest.sourcePackage ?: "com.spotify.music",
                            providerTrackId = rawTrackId,
                            title = latest.trackTitle?.trim(),
                            artist = latest.trackArtist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist",
                            album = latest.trackAlbum?.trim(),
                            distinctAppearanceCount = group.size,
                            firstObservedAppearanceAtMs = group.minOfOrNull { it.eventTimeMs },
                            lastObservedAppearanceAtMs = latest.eventTimeMs,
                            supportingObservationIds = group.map { it.eventId }.take(10),
                            identityBasis = if (rawTrackId != null) TrackIdentityBasis.PROVIDER_TRACK_ID else TrackIdentityBasis.METADATA_FINGERPRINT,
                            evidenceQuality = com.tunemind.app.domain.model.MemoryEvidenceQuality.SUPPORTED_IDENTITY,
                            historyMayBeIncomplete = false,
                            appearances = emptyList()
                        )
                    } else if (existing.providerTrackId.isNullOrBlank() && !rawTrackId.isNullOrBlank()) {
                        combinedTrackMap[key] = existing.copy(providerTrackId = rawTrackId)
                    }
                }

                val recent = combinedTrackMap.values
                    .sortedByDescending { it.lastObservedAppearanceAtMs ?: 0L }
                    .take(60)

                val latestStateObs = rows.firstOrNull { 
                    it.playbackStateCode != null && !isBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
                }
                val latestTrackMeta = rows.firstOrNull { 
                    !it.trackTitle.isNullOrBlank() && !isBrowserOrWeb(it.sourcePackage, it.trackTitle, it.trackArtist)
                }
                val latestObsTimeMs = maxOf(latestStateObs?.eventTimeMs ?: 0L, latestTrackMeta?.eventTimeMs ?: 0L)
                val isRecentObservation = (System.currentTimeMillis() - latestObsTimeMs) < 12 * 60 * 1000L
                val isPlayingNow = (latestStateObs?.playbackStateCode == 3) && isRecentObservation

                val overrideObs = manualActiveTrackOverride
                val useAgentOverride = overrideObs != null && overrideObs.updatedAtMs > latestObsTimeMs

                val nowPlayingObs = when {
                    useAgentOverride -> overrideObs
                    (latestTrackMeta != null || latestStateObs != null) && isRecentObservation -> {
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
                            updatedAtMs = latestObsTimeMs
                        )
                    }
                    overrideObs != null -> overrideObs
                    else -> null
                }

                _homeState.value = HomeState(
                    identifiableTrackCount = maxOf(summary.identifiableTrackCount, recent.size),
                    distinctAppearanceCount = summary.distinctReconstructedAppearanceCount,
                    recurringTrackCount = summary.recurringTrackIdentityCount,
                    recentTracks = recent,
                    nowPlaying = nowPlayingObs,
                    coverage = coverage,
                    isLoading = false,
                )
                recomputeAdaptiveTheme()

                _historyState.value = HistoryState(
                    tracks = recent,
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

                // If real tracks are observed and the crate was uncalibrated or empty, auto-calibrate
                val observedArtists = memory.tracks.mapNotNull { it.artist }.filter { it.isNotBlank() }.distinct()
                if (observedArtists.isNotEmpty()) {
                    val currentCrate = _aiDiscoveryCrate.value
                    val needsCalibration = currentCrate.isEmpty() || currentCrate.any {
                        it.telemetryBasisSummary.contains("Emerging") || it.telemetryBasisSummary.contains("Daypart:")
                    }
                    if (needsCalibration && !_isDiscovering.value) {
                        triggerChronoDiscovery(forceRefresh = true)
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


    fun refreshHearingInsights() {
        val ctx = AppHolder.context ?: return
        val snapshot = com.tunemind.app.domain.audio.AudioDeviceObserver.getActiveAudioDeviceSnapshot(ctx)
        val estDb = snapshot.estimatedDb
        val headroom = snapshot.headroomDb

        val safeHours = if (estDb <= 70f) 24f else {
            (8f / Math.pow(2.0, ((estDb - 85f) / 3f).toDouble())).toFloat().coerceIn(0.1f, 24f)
        }

        val doseFraction = ((estDb / 85f) * 0.44f).coerceIn(0.1f, 1.0f)
        val guidance = when {
            headroom >= 12f -> "Pristine acoustic headroom (+%.1f dB). Safe for all-day listening.".format(headroom)
            headroom >= 4f -> "Optimal safety headroom (+%.1f dB). Stereocilia fatigue within healthy bounds.".format(headroom)
            headroom >= 0f -> "Approaching 85 dBA threshold (+%.1f dB headroom). Max 8h daily recommended.".format(headroom)
            else -> "Headroom exceeded (%.1f dB). High auditory fatigue risk; lower volume.".format(headroom)
        }

        _hearingInsightsState.value = _hearingInsightsState.value.copy(
            activeSnapshot = snapshot,
            estimatedAverageDb = estDb,
            currentHeadroomDb = headroom,
            soundDoseFraction = doseFraction,
            safeHoursRemaining = safeHours,
            safetyGuidance = guidance
        )
    }

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

    /**
     * Triggers Chrono-Receptive AI Discovery for current daypart & telemetry growth.
     * When [forceRefresh] is true, generates a fresh Neuro-Pulse even if crate has recommendations.
     */
    fun triggerChronoDiscovery(forceRefresh: Boolean = false) {
        val ctx = AppHolder.context ?: return
        viewModelScope.launch {
            try {
                if (_isDiscovering.value) return@launch

                val existingForSlot = _aiDiscoveryCrate.value.filter { it.timeSlotKey == currentTimeSlot.name && !it.isDismissed }
                if (!forceRefresh && existingForSlot.isNotEmpty()) {
                    return@launch
                }

                _isDiscovering.value = true
                val prefProvider = getPreferredProviderId(ctx)
                val currentPlays = _homeState.value.recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
                val satiety = minOf(95, maxOf(18, currentPlays * 22))

                val (allKnownTitles, playedSignals, dismissedSignals) = withContext(Dispatchers.IO) {
                    Triple(
                        aiRecDao?.getAllKnownTitles() ?: emptyList(),
                        aiRecDao?.getPositiveFeedbackSignals() ?: emptyList(),
                        aiRecDao?.getDismissedSignals() ?: emptyList()
                    )
                }

                val newRecs = com.tunemind.app.domain.ai.GeminiReasoningEngine.generateChronoCrateRecommendations(
                    context = ctx,
                    recentTracks = _historyState.value.tracks,
                    vaultTracks = _vaultState.value,
                    timeSlotKey = currentTimeSlot.name,
                    timeSlotLabel = currentTimeSlot.label,
                    satietyPercent = satiety,
                    preferredProviderId = prefProvider,
                    userTasteAnchors = _userTasteAnchors.value,
                    nowPlayingArtist = _homeState.value.nowPlaying?.artist,
                    excludeTitles = allKnownTitles,
                    playedSignals = playedSignals,
                    dismissedSignals = dismissedSignals
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

    fun toggleSaveRecommendation(id: String, isSaved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.updateSaved(id, isSaved)
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "toggleSaveRecommendation error: ${t.message}", t)
            }
        }
    }

    fun markRecommendationPlayed(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.markPlayed(id)
            } catch (t: Throwable) {
                android.util.Log.e("TuneMindVM", "markRecommendationPlayed error: ${t.message}", t)
            }
        }
    }

    fun dismissAiRecommendation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiRecDao?.dismiss(id)
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

    fun playAiRecommendation(
        context: android.content.Context,
        item: com.tunemind.app.domain.recommendation.AiRecommendationEntity,
        explicitProviderId: String? = null
    ): com.tunemind.app.provider.PlaybackLaunchResult {
        markRecommendationPlayed(item.id)
        val recCategory = when (item.category) {
            "PALATE_CLEANSER" -> com.tunemind.app.domain.recommendation.RecommendationCategory.PALATE_CLEANSER
            "CHRONO_RESTORE" -> com.tunemind.app.domain.recommendation.RecommendationCategory.COOLDOWN_RESTORED
            "SONIC_NOVELTY" -> com.tunemind.app.domain.recommendation.RecommendationCategory.EXPLORATION_DISCOVERY
            else -> com.tunemind.app.domain.recommendation.RecommendationCategory.FAMILIAR_RECEPTIVE
        }
        val track = com.tunemind.app.domain.recommendation.RecommendedTrack(
            id = item.id,
            title = item.title,
            artist = item.artist,
            album = item.album,
            reason = item.reason,
            confidence = item.confidence,
            category = recCategory,
            suggestedProviderId = explicitProviderId ?: item.suggestedProviderId
        )
        return playRecommendation(context, track, explicitProviderId)
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

data class DopamineCapsuleTrack(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val freshnessPercent: Int = 90,
    val reason: String = "Peak dopamine readiness"
)

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
