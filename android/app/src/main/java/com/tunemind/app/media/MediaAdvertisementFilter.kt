package com.tunemind.app.media

import android.media.MediaMetadata

/**
 * Excludes media explicitly marked as an advertisement by its media session.
 *
 * AndroidX MediaMetadataCompat defines ADVERTISEMENT as a numeric flag. The
 * framework MediaMetadata can carry that key even though it has no public
 * named constant for it. We intentionally do not infer ads from title/artist
 * text because that can discard legitimate tracks.
 */
object MediaAdvertisementFilter {
    private const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

    /** True/false only when explicitly reported; null means no reliable marker. */
    fun advertisementMarker(metadata: MediaMetadata?): Boolean? {
        if (metadata == null || !metadata.containsKey(METADATA_KEY_ADVERTISEMENT)) return null
        return runCatching { metadata.getLong(METADATA_KEY_ADVERTISEMENT) != 0L }.getOrNull()
    }
}

/** Missing metadata preserves a previously known ad state until an explicit non-ad marker. */
class AdvertisementFilterState {
    private var explicitlyMarkedAdvertisement = false

    fun shouldSuppress(metadata: MediaMetadata?): Boolean {
        when (MediaAdvertisementFilter.advertisementMarker(metadata)) {
            true -> explicitlyMarkedAdvertisement = true
            false -> explicitlyMarkedAdvertisement = false
            null -> Unit
        }
        return explicitlyMarkedAdvertisement
    }
}
