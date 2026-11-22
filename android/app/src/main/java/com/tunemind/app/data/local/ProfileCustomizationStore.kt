package com.tunemind.app.data.local

import android.content.Context
import android.net.Uri
import com.tunemind.app.data.auth.TuneMindAccountStorage
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import java.io.File

data class UserProfileCustomData(
    val avatarFilePath: String = "",
    val bio: String = "",
    val favoriteGenres: List<String> = emptyList()
)

object ProfileCustomizationStore {
    private const val PREFS_NAME = "tunemind_profile_custom"
    private const val KEY_AVATAR_PATH = "avatar_file_path"
    private const val KEY_BIO = "profile_bio"
    private const val KEY_GENRES = "favorite_genres"

    fun load(context: Context): UserProfileCustomData {
        val prefs = TuneMindAccountStorage.getUserPrefs(context, PREFS_NAME)
        val path = prefs.getString(KEY_AVATAR_PATH, "")?.trim().orEmpty()
        val validPath = if (path.isNotBlank() && File(path).exists()) path else ""
        val bio = prefs.getString(KEY_BIO, "")?.trim().orEmpty()
        val genres = prefs.getString(KEY_GENRES, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return UserProfileCustomData(
            avatarFilePath = validPath,
            bio = bio,
            favoriteGenres = genres
        )
    }

    fun save(context: Context, data: UserProfileCustomData) {
        val prefs = TuneMindAccountStorage.getUserPrefs(context, PREFS_NAME)
        prefs.edit()
            .putString(KEY_AVATAR_PATH, data.avatarFilePath.trim())
            .putString(KEY_BIO, data.bio.trim())
            .putString(KEY_GENRES, data.favoriteGenres.distinct().joinToString(","))
            .apply()
    }

    fun saveAvatarFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            val dir = File(context.filesDir, "profile_avatars").apply { mkdirs() }
            val accountKey = TuneMindAuthSessionManager.getInstance(context)
                .sessionFlow.value.userId
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "default" }
            val dest = File(dir, "avatar_${accountKey}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            if (dest.length() <= 0L) return null
            dest.absolutePath
        }.getOrNull()
    }
}
