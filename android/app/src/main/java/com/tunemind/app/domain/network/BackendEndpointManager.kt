package com.tunemind.app.domain.network

import android.content.Context
import com.tunemind.app.BuildConfig

/**
 * Manages TuneMind's backend HTTP and WebSocket endpoints so the Android agent
 * works 100% wirelessly over Cloud HTTPS/WSS, public tunnels, or Wi-Fi LAN without USB.
 */
object BackendEndpointManager {
    private const val PREFS_NAME = "tunemind_network_prefs"
    private const val KEY_CUSTOM_BASE_URL = "custom_backend_base_url"

    fun getCustomBaseUrl(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_BASE_URL, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun setCustomBaseUrl(context: Context, url: String) {
        val cleaned = normalizeHttpBaseUrl(url)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, cleaned).apply()
    }

    fun normalizeHttpBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // If user typed an IP like 192.168.1.15:3000 or 192.168.1.15, default to http://
            trimmed.first().isDigit() || trimmed.startsWith("localhost") -> {
                if (trimmed.contains(":")) "http://$trimmed" else "http://$trimmed:3000"
            }
            // Domain name like tunemind-backend.onrender.com -> https://
            else -> "https://$trimmed"
        }.trimEnd('/').removeSuffix("/ws/stt")
    }

    fun toWebSocketUrl(httpBaseUrl: String): String {
        val normalized = normalizeHttpBaseUrl(httpBaseUrl)
        if (normalized.isEmpty()) return "ws://localhost:3000/ws/stt"
        return when {
            normalized.startsWith("https://", ignoreCase = true) ->
                "wss://" + normalized.substring(8) + "/ws/stt"
            normalized.startsWith("http://", ignoreCase = true) ->
                "ws://" + normalized.substring(7) + "/ws/stt"
            else -> "wss://$normalized/ws/stt"
        }
    }

    const val RENDER_PRODUCTION_URL = "https://tunemind-backend-iudp.onrender.com"

    /**
     * Ordered list of WebSocket endpoints to try:
     * 1. Permanent Render Cloud URL (wss://tunemind-backend-iudp.onrender.com/ws/stt)
     * 2. Custom Online / Wi-Fi URL configured inside the app (if set)
     * 3. BuildConfig.TUNEMIND_BASE_URL
     * 4. USB ADB reverse tunnel fallback (ws://localhost:3000/ws/stt)
     */
    fun getCandidateWebSocketUrls(context: Context): List<String> {
        val candidates = mutableListOf<String>()

        val renderWs = toWebSocketUrl(RENDER_PRODUCTION_URL)
        candidates.add(renderWs)

        val custom = getCustomBaseUrl(context)
        if (custom.isNotBlank()) {
            val customWs = toWebSocketUrl(custom)
            if (!candidates.contains(customWs)) {
                candidates.add(customWs)
            }
        }

        val buildConfigUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
        if (buildConfigUrl.isNotBlank() && !buildConfigUrl.contains("ais-dev-")) {
            val wsUrl = toWebSocketUrl(buildConfigUrl)
            if (!candidates.contains(wsUrl)) {
                candidates.add(wsUrl)
            }
        }

        val usbFallback = "ws://localhost:3000/ws/stt"
        if (!candidates.contains(usbFallback)) {
            candidates.add(usbFallback)
        }

        return candidates
    }

    fun getActiveHttpBaseUrl(context: Context): String {
        val custom = getCustomBaseUrl(context)
        if (custom.isNotBlank() && custom.startsWith("https://") && !custom.contains(".loca.lt")) {
            return custom
        }
        return RENDER_PRODUCTION_URL
    }

    fun getDisplayConnectionLabel(context: Context): String {
        val custom = getCustomBaseUrl(context)
        if (custom.isNotBlank()) {
            val host = custom.removePrefix("https://").removePrefix("http://")
            return if (custom.startsWith("https://")) "ONLINE CLOUD ($host)" else "WI-FI LAN ($host)"
        }
        return "ONLINE CLOUD (tunemind-backend-iudp.onrender.com)"
    }
}
