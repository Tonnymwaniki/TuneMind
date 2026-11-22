package com.tunemind.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tunemind.app.MainActivity
import com.tunemind.app.R
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.voice.AgentSettingsStore
import java.util.Calendar

/**
 * Periodic worker that posts TuneMind's Sunday Weekly Drop & Monthly Recap notifications
 * prompting listeners to check their viral roast cards and share them to WhatsApp Status
 * and across social platforms.
 */
class WeeklyShareDropWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AgentSettingsStore.ensureLoaded(ctx)

        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val prefs = com.tunemind.app.data.auth.TuneMindAccountStorage.getUserPrefs(ctx, PREFS_DROP_ALERTS)

        // Check if user actually has tracked listening history so notifications feel real & earned
        val recentCount = runCatching {
            val db = TuneMindDatabase.getInstance(ctx)
            db.eventQueueDao().getRecentEvents(15).size +
                db.crossAppLibraryDao().getAllLibraryTracksOnce().size
        }.getOrDefault(0)

        if (recentCount <= 0) {
            return Result.success()
        }

        // 1. Sunday Weekly Drop (fires once every Sunday from 9 AM onward)
        val weekKey = "$year-W$week"
        if (AgentSettingsStore.isWeeklyRecapAlertsEnabled(ctx) &&
            dayOfWeek == Calendar.SUNDAY &&
            hour >= 9 &&
            prefs.getString(KEY_LAST_SUNDAY_WEEK, "") != weekKey
        ) {
            postDropNotification(
                context = ctx,
                notificationId = NOTIF_ID_SUNDAY_DROP,
                title = "Your Sunday Music Roast is Ready",
                body = "7-day completion %, top obsession & cross-app split just dropped. Ready for WhatsApp Status, IG & TikTok!"
            )
            prefs.edit().putString(KEY_LAST_SUNDAY_WEEK, weekKey).apply()
        }

        // 2. Monthly Recap Drop (fires on the 1st of the month from 9 AM onward)
        val monthKey = "$year-M$month"
        if (AgentSettingsStore.isMonthlyRecapAlertsEnabled(ctx) &&
            dayOfMonth == 1 &&
            hour >= 9 &&
            prefs.getString(KEY_LAST_MONTHLY_KEY, "") != monthKey
        ) {
            postDropNotification(
                context = ctx,
                notificationId = NOTIF_ID_MONTHLY_DROP,
                title = "Your Monthly Music DNA Receipt Dropped",
                body = "30 days of plays, skips & obsessions verified across your music apps. Tap to grab your share cards!"
            )
            prefs.edit().putString(KEY_LAST_MONTHLY_KEY, monthKey).apply()
        }

        return Result.success()
    }

    companion object {
        const val CHANNEL_ID_SHARE_DROPS = "tunemind_share_drops"
        const val EXTRA_OPEN_NOTIFICATIONS = "tunemind_open_notifications"
        const val EXTRA_OPEN_SHARE_STUDIO = "tunemind_open_share_studio"

        private const val PREFS_DROP_ALERTS = "tunemind_share_drop_alerts"
        private const val KEY_LAST_SUNDAY_WEEK = "last_sunday_week_key"
        private const val KEY_LAST_MONTHLY_KEY = "last_monthly_drop_key"
        private const val NOTIF_ID_SUNDAY_DROP = 4201
        private const val NOTIF_ID_MONTHLY_DROP = 4202
        private const val NOTIF_ID_TEST_DROP = 4203

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun isSundayToday(): Boolean =
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID_SHARE_DROPS)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID_SHARE_DROPS,
                "Sunday & Monthly Recap Drops",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts you every Sunday and monthly when your viral share cards are ready to post."
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        fun postInstantDropPreviewNotification(
            context: Context,
            isMonthly: Boolean = false
        ): Boolean {
            if (!hasNotificationPermission(context)) return false
            val title = if (isMonthly) {
                "Monthly Music Roast Cards Ready"
            } else {
                "Sunday Drop: Your 7-Day Roast is Live"
            }
            val body = if (isMonthly) {
                "Tap to preview your 30-day Personality, Receipt & Vibe cards and share to WhatsApp, IG, or TikTok."
            } else {
                "Happy Sunday! Your 7-day completion %, skips & top obsession cards are ready for WhatsApp Status & socials."
            }
            postDropNotification(
                context = context,
                notificationId = NOTIF_ID_TEST_DROP,
                title = title,
                body = body
            )
            return true
        }

        fun postDropNotification(
            context: Context,
            notificationId: Int,
            title: String,
            body: String
        ) {
            if (!hasNotificationPermission(context)) return
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
                putExtra(EXTRA_OPEN_SHARE_STUDIO, true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_SHARE_DROPS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            runCatching {
                nm.notify(notificationId, notification)
            }
        }
    }
}
