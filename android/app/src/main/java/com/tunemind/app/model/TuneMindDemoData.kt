package com.tunemind.app.model

data class TrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val albumCoverUrl: String,
    val genre: String,
    val bpm: Int,
    val durationSeconds: Int,
    val playCount: Int,
    val satietyScore: Int, // 0 - 100
    val fatigueCategory: String, // "Critical Loop", "Vocal Fatigue", "Preemptive", "Healthy Staple"
    val recentSkips: Int = 0,
    val sourceApp: String = "Spotify",
    val cooldownActive: Boolean = false,
    val recoveryProgress: Float = 0f, // 0.0 - 1.0
    val cooldownDaysRemaining: Float = 0f
)

data class AnchorWeight(
    val name: String,
    val percentage: Int,
    val driftDelta: String,
    val description: String
)

data class CircadianWindow(
    val title: String,
    val timeRange: String,
    val receptivityPercent: Int,
    val descriptor: String,
    val isFatigueProne: Boolean = false
)

data class DailyStudyTile(
    val dayNumber: Int,
    val dayLabel: String,
    val status: String, // "COMPLETED", "IN_PROGRESS", "SCHEDULED"
    val minutesTracked: Int,
    val skipsAnalyzed: Int,
    val entropyScore: Float
)

object TuneMindDemoData {
    val currentUserProfile = mapOf(
        "name" to "Tonny",
        "email" to "tonny@tunemind.neuro",
        "tier" to "Acoustic Intelligence Tier 1 • Active",
        "monitoredHours" to "87.4h",
        "syncedApps" to "3 Apps",
        "confidence" to "91%"
    )

    val currentNowPlaying = TrackItem(
        id = "np-001",
        title = "Midnight Thoughts",
        artist = "Luna Rivers",
        albumCoverUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=200",
        genre = "Lo-Fi Atmosphere",
        bpm = 84,
        durationSeconds = 216,
        playCount = 9,
        satietyScore = 58,
        fatigueCategory = "Vocal Fatigue",
        recentSkips = 3,
        sourceApp = "Spotify"
    )

    val highSatietyTracks = listOf(
        TrackItem(
            id = "trk-1",
            title = "Stay With Me",
            artist = "The Neon Hearts",
            albumCoverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=200",
            genre = "Synth Pop",
            bpm = 128,
            durationSeconds = 204,
            playCount = 14,
            satietyScore = 84,
            fatigueCategory = "Critical Loop",
            recentSkips = 4,
            cooldownActive = true,
            recoveryProgress = 0.42f,
            cooldownDaysRemaining = 3.5f
        ),
        TrackItem(
            id = "trk-2",
            title = "Hyperion Drive",
            artist = "Solar Pulse",
            albumCoverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=200",
            genre = "Cyber Electro",
            bpm = 132,
            durationSeconds = 230,
            playCount = 16,
            satietyScore = 76,
            fatigueCategory = "Loop Decay",
            recentSkips = 2,
            cooldownActive = true,
            recoveryProgress = 0.20f,
            cooldownDaysRemaining = 4.0f
        ),
        TrackItem(
            id = "trk-3",
            title = "Velvet Horizons",
            artist = "Kaelen",
            albumCoverUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=200",
            genre = "Neo-Soul",
            bpm = 92,
            durationSeconds = 188,
            playCount = 7,
            satietyScore = 62,
            fatigueCategory = "Preemptive",
            recentSkips = 1,
            cooldownActive = true,
            recoveryProgress = 0.80f,
            cooldownDaysRemaining = 1.0f
        )
    )

    val candidateCooldownTracks = listOf(
        TrackItem(
            id = "cand-1",
            title = "Neon Horizon",
            artist = "Vektor Drift",
            albumCoverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200",
            genre = "Synthwave",
            bpm = 128,
            durationSeconds = 240,
            playCount = 19,
            satietyScore = 78,
            fatigueCategory = "Critical Loop",
            recentSkips = 3,
            sourceApp = "Spotify"
        ),
        TrackItem(
            id = "cand-2",
            title = "Ghost in the Machine",
            artist = "Komorebi",
            albumCoverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=200",
            genre = "Lo-Fi Beats",
            bpm = 82,
            durationSeconds = 175,
            playCount = 14,
            satietyScore = 64,
            fatigueCategory = "Vocal Fatigue",
            recentSkips = 2,
            sourceApp = "YT Music"
        ),
        TrackItem(
            id = "cand-3",
            title = "Solar Flare",
            artist = "Aura Void",
            albumCoverUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=200",
            genre = "Ambient Chill",
            bpm = 110,
            durationSeconds = 210,
            playCount = 8,
            satietyScore = 45,
            fatigueCategory = "Healthy Staple",
            recentSkips = 0,
            sourceApp = "Spotify"
        )
    )

    val palateCleansers = listOf(
        TrackItem(
            id = "pc-1",
            title = "Electric Dreams",
            artist = "The Wanderers",
            albumCoverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=200",
            genre = "Synth Pop • High Energy",
            bpm = 128,
            durationSeconds = 194,
            playCount = 11,
            satietyScore = 18,
            fatigueCategory = "Matches groove + novelty",
            sourceApp = "Spotify"
        ),
        TrackItem(
            id = "pc-2",
            title = "Better Days",
            artist = "Juno Vale",
            albumCoverUrl = "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=200",
            genre = "Indie Pop • Warm Vocals",
            bpm = 126,
            durationSeconds = 205,
            playCount = 5,
            satietyScore = 22,
            fatigueCategory = "Matches vocal anchor",
            sourceApp = "Spotify"
        ),
        TrackItem(
            id = "pc-3",
            title = "Lost in Motion",
            artist = "Solar State",
            albumCoverUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=200",
            genre = "Electronic • Dynamic Build",
            bpm = 124,
            durationSeconds = 230,
            playCount = 3,
            satietyScore = 15,
            fatigueCategory = "Matches structure anchor",
            sourceApp = "Spotify"
        )
    )

    val anchorWeights = listOf(
        AnchorWeight("Groove & Rhythm Anchor", 38, "+6%", "Rhythm, bass, tempo lock"),
        AnchorWeight("Vocal & Melody Anchor", 34, "-7% reset", "Vocals, harmony, linguistic load"),
        AnchorWeight("Dynamic & Structure Anchor", 28, "+1% stable", "Build-ups, drops, frequency balance")
    )

    val circadianWindows = listOf(
        CircadianWindow("Morning Window (08:00 – 12:00)", "Sweet Spot: 10:30 AM – 1:00 PM", 92, "High Harmonic Tolerance", false),
        CircadianWindow("Midday Drive (13:00 – 18:00)", "41% Habitual Loops Zone", 68, "Groove & Dynamic Tolerance", false),
        CircadianWindow("Late Night (> 22:00)", "Low BPM Ambient Advised", 24, "Treble & Vocal Clashing Alert", true)
    )

    val studyDays = listOf(
        DailyStudyTile(1, "Mon", "COMPLETED", 142, 6, 0.88f),
        DailyStudyTile(2, "Tue", "COMPLETED", 95, 3, 0.72f),
        DailyStudyTile(3, "Wed", "COMPLETED", 180, 8, 0.94f),
        DailyStudyTile(4, "Thu", "COMPLETED", 110, 4, 0.81f),
        DailyStudyTile(5, "Fri", "IN_PROGRESS", 74, 2, 0.65f),
        DailyStudyTile(6, "Sat", "SCHEDULED", 0, 0, 0.0f),
        DailyStudyTile(7, "Sun", "SCHEDULED", 0, 0, 0.0f)
    )
}
