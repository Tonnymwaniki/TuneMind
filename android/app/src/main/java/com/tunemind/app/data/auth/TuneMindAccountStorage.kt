package com.tunemind.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Provides strict per-account local data isolation on Android.
 *
 * Every authenticated Google or Email TuneMind account receives its own deterministic
 * [partitionKey] (`acct_<16hex>`), its own isolated Room SQLite database file
 * (`tunemind_<partitionKey>.db`), and its own isolated SharedPreferences files
 * (`<prefsName>__<partitionKey>`).
 *
 * Switching accounts or logging out into another account NEVER leaks songs, playlists,
 * vault cooldowns, taste anchors, or listening history between users on the same device.
 */
object TuneMindAccountStorage {
    private const val TAG = "TuneMindAcctStorage"
    private const val META_PREFS = "tunemind_account_partition_meta"
    private const val KEY_ACTIVE_PARTITION = "active_partition_key"
    private const val KEY_LEGACY_CLAIMED_BY = "legacy_db_claimed_by"
    private const val LEGACY_DB_NAME = "tunemind.db"
    const val SIGNED_OUT_PARTITION = "signed_out"

    private val USER_SCOPED_PREF_NAMES = listOf(
        "tunemind_user_prefs",
        "tunemind_taste_prefs",
        "tunemind_vault_prefs",
        "tunemind_fresh_prefs",
        "tunemind_system_prefs",
        "tunemind_agent_settings",
        "tunemind_theme_prefs",
        "tunemind_player_prefs",
        "tunemind_agent_memory",
        "tunemind_agent_prefs",
        "tunemind_foryou_prefs",
        "tunemind_for_you_perm_history_v1",
        "tunemind_telemetry_reasoning_prefs",
        "tunemind_llm_tags",
        "tunemind_share_drop_alerts",
        "tunemind_sync_prefs"
    )

    @Volatile
    private var cachedPartitionKey: String? = null

    private fun metaPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    }

    fun resolveIdentitySource(userId: String?, email: String?): String {
        val cleanEmail = email?.trim()?.lowercase().orEmpty()
        if (cleanEmail.contains("@")) return cleanEmail
        val cleanUid = userId?.trim().orEmpty()
        if (cleanUid.isNotBlank() &&
            !cleanUid.equals("anonymous", ignoreCase = true) &&
            !cleanUid.equals("tunemind-default-user", ignoreCase = true) &&
            !cleanUid.equals("null", ignoreCase = true)
        ) {
            return cleanUid.lowercase()
        }
        return ""
    }

    fun computePartitionKey(userId: String?, email: String?): String {
        val source = resolveIdentitySource(userId, email)
        if (source.isBlank()) return SIGNED_OUT_PARTITION
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return "acct_$hex"
    }

    fun resolveActivePartitionKey(context: Context): String {
        cachedPartitionKey?.let { return it }
        val appCtx = context.applicationContext
        val stored = metaPrefs(appCtx).getString(KEY_ACTIVE_PARTITION, null)?.trim()
        val resolved = if (!stored.isNullOrBlank()) stored else SIGNED_OUT_PARTITION
        cachedPartitionKey = resolved
        return resolved
    }

    fun resolveDatabaseName(context: Context): String {
        val key = resolveActivePartitionKey(context)
        return if (key == SIGNED_OUT_PARTITION) {
            "tunemind_signed_out.db"
        } else {
            "tunemind_${key}.db"
        }
    }

    fun activateAccount(context: Context, userId: String?, email: String?): String {
        val appCtx = context.applicationContext
        val key = computePartitionKey(userId, email)
        if (key != SIGNED_OUT_PARTITION) {
            claimLegacyDataIfFirstAccount(appCtx, key)
        }
        cachedPartitionKey = key
        metaPrefs(appCtx).edit().putString(KEY_ACTIVE_PARTITION, key).apply()
        return key
    }

    fun clearActivePartition(context: Context): String {
        val appCtx = context.applicationContext
        cachedPartitionKey = SIGNED_OUT_PARTITION
        metaPrefs(appCtx).edit().putString(KEY_ACTIVE_PARTITION, SIGNED_OUT_PARTITION).apply()
        return SIGNED_OUT_PARTITION
    }

    fun getUserPrefs(context: Context, baseName: String): SharedPreferences {
        val appCtx = context.applicationContext
        val partition = resolveActivePartitionKey(appCtx)
        return appCtx.getSharedPreferences("${baseName}__${partition}", Context.MODE_PRIVATE)
    }

    fun wipeAccountStorage(context: Context, partitionKey: String) {
        if (partitionKey.isBlank() || partitionKey == SIGNED_OUT_PARTITION) return
        val appCtx = context.applicationContext
        val dbName = "tunemind_${partitionKey}.db"
        runCatching {
            appCtx.deleteDatabase(dbName)
            File(appCtx.getDatabasePath(dbName).path + "-wal").delete()
            File(appCtx.getDatabasePath(dbName).path + "-shm").delete()
        }
        for (prefName in USER_SCOPED_PREF_NAMES) {
            runCatching {
                appCtx.getSharedPreferences("${prefName}__${partitionKey}", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
        Log.i(TAG, "Wiped isolated local storage for partition=$partitionKey")
    }

    /**
     * One-time upgrade migration: if an existing user already had tracks in the legacy
     * unpartitioned `tunemind.db` before multi-account isolation existed, copy it into
     * the very first authenticated account's partition so they keep their history while
     * every other account starts completely clean.
     */
    private fun claimLegacyDataIfFirstAccount(appCtx: Context, targetPartition: String) {
        val meta = metaPrefs(appCtx)
        if (!meta.getString(KEY_LEGACY_CLAIMED_BY, null).isNullOrBlank()) return

        synchronized(this) {
            if (!meta.getString(KEY_LEGACY_CLAIMED_BY, null).isNullOrBlank()) return
            val targetDbName = "tunemind_${targetPartition}.db"
            val legacyDbFile = appCtx.getDatabasePath(LEGACY_DB_NAME)
            val targetDbFile = appCtx.getDatabasePath(targetDbName)

            if (legacyDbFile.exists() && !targetDbFile.exists()) {
                runCatching {
                    targetDbFile.parentFile?.mkdirs()
                    legacyDbFile.copyTo(targetDbFile, overwrite = false)
                    listOf("-wal", "-shm").forEach { suffix ->
                        val sidecar = File(legacyDbFile.path + suffix)
                        if (sidecar.exists()) {
                            sidecar.copyTo(File(targetDbFile.path + suffix), overwrite = true)
                        }
                    }
                    Log.i(TAG, "Migrated legacy database into $targetDbName")
                }.onFailure {
                    Log.w(TAG, "Legacy DB migration note: ${it.message}")
                }
            }

            for (prefName in USER_SCOPED_PREF_NAMES) {
                runCatching {
                    val legacy = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val entries = legacy.all
                    if (entries.isNotEmpty()) {
                        val dest = appCtx.getSharedPreferences("${prefName}__${targetPartition}", Context.MODE_PRIVATE)
                        if (dest.all.isEmpty()) {
                            copySharedPrefs(entries, dest)
                        }
                    }
                }
            }

            meta.edit().putString(KEY_LEGACY_CLAIMED_BY, targetPartition).apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copySharedPrefs(entries: Map<String, *>, dest: SharedPreferences) {
        val editor = dest.edit()
        for ((k, v) in entries) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is String -> editor.putString(k, v)
                is Set<*> -> editor.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        editor.apply()
    }
}
