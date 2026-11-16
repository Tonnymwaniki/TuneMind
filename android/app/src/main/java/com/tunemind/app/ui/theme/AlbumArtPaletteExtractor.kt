package com.tunemind.app.ui.theme

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts a vivid dark-UI-readable accent hex (#RRGGBB) directly from album cover artwork
 * using Coil + AndroidX Palette. Runs 100% on-device with zero AI token cost.
 */
object AlbumArtPaletteExtractor {
    private val hexCache = ConcurrentHashMap<String, String>()

    suspend fun extractDominantAccentHex(
        context: Context,
        imageUrl: String?
    ): String? {
        val cleanUrl = imageUrl?.trim()?.takeIf { it.startsWith("http") } ?: return null
        hexCache[cleanUrl]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(context.applicationContext)
                val request = ImageRequest.Builder(context.applicationContext)
                    .data(cleanUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()

                val result = loader.execute(request) as? SuccessResult ?: return@withContext null
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                val palette = Palette.from(bitmap).maximumColorCount(12).generate()

                val rawRgb = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: return@withContext null

                val tunedRgb = ensureReadableDarkUiAccent(rawRgb)
                val hex = String.format("#%06X", 0xFFFFFF and tunedRgb)
                hexCache[cleanUrl] = hex
                hex
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun ensureReadableDarkUiAccent(rgb: Int): Int {
        val hsl = FloatArray(3)
        AndroidColor.RGBToHSV(
            AndroidColor.red(rgb),
            AndroidColor.green(rgb),
            AndroidColor.blue(rgb),
            hsl
        )
        // Lift dark swatches so text, icons, and glow borders remain luminous on obsidian backgrounds
        if (hsl[2] < 0.62f) {
            hsl[2] = 0.72f
        }
        if (hsl[1] in 0.05f..0.42f) {
            hsl[1] = 0.58f
        }
        return AndroidColor.HSVToColor(hsl)
    }
}
