package com.tunemind.app.domain.execution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Android [BroadcastReceiver] that listens for the backend's structured Satiety JSON decision
 * (`{"state": "fatigued|focused", "action": "change_genre|skip_track|maintain", "reasoning": "..."}`)
 * or triggers a fresh 50-track evaluation from the Local-First Room Database.
 *
 * When `state == "fatigued"`, this receiver delegates to [SatietyExecutionForegroundService]
 * (and executes [AgenticToolRegistry] via `goAsync()`) so `skip_track`, `play_playlist`, or
 * `change_genre` runs automatically at the OS level without requiring the user to open TuneMind.
 */
class SatietyDecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "SatietyDecisionReceiver received action: $action")

        when (action) {
            ACTION_SATIETY_DECISION -> {
                val decisionJson = intent.getStringExtra(EXTRA_DECISION_JSON)
                if (decisionJson.isNullOrBlank()) {
                    Log.w(TAG, "Received ACTION_SATIETY_DECISION with empty JSON payload.")
                    return
                }

                // 1. Immediate execution on receiver scope so latency is < 50ms even if service is cold
                val pendingResult = goAsync()
                try {
                    val decision = SatietyDecisionPayload.fromJsonString(decisionJson)
                    AgenticToolRegistry.executeDecisionAndTools(context.applicationContext, decision)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse or execute SatietyDecisionPayload in receiver", e)
                } finally {
                    pendingResult.finish()
                }

                // 2. Also notify/start the Foreground Service to keep persistent telemetry active
                val serviceIntent = Intent(context.applicationContext, SatietyExecutionForegroundService::class.java).apply {
                    this.action = SatietyExecutionForegroundService.ACTION_HANDLE_DECISION_JSON
                    putExtra(EXTRA_DECISION_JSON, decisionJson)
                }
                runCatching {
                    ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
                }.onFailure {
                    Log.d(TAG, "Background service start note: ${it.message}")
                }
            }

            ACTION_EVALUATE_ROOM_HISTORY -> {
                val serviceIntent = Intent(context.applicationContext, SatietyExecutionForegroundService::class.java).apply {
                    this.action = SatietyExecutionForegroundService.ACTION_EVALUATE_LAST_50_TRACKS
                }
                runCatching {
                    ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
                }.onFailure {
                    Log.w(TAG, "Could not start SatietyExecutionForegroundService: ${it.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SatietyDecisionReceiver"
        const val ACTION_SATIETY_DECISION = "com.tunemind.app.ACTION_SATIETY_DECISION"
        const val ACTION_EVALUATE_ROOM_HISTORY = "com.tunemind.app.ACTION_EVALUATE_ROOM_HISTORY"
        const val EXTRA_DECISION_JSON = "extra_decision_json"

        /**
         * Helper to broadcast a backend JSON decision across the Android OS so [SatietyDecisionReceiver]
         * and [SatietyExecutionForegroundService] execute it immediately.
         */
        fun broadcastDecisionJson(context: Context, decisionJson: String) {
            val broadcastIntent = Intent(context.applicationContext, SatietyDecisionReceiver::class.java).apply {
                action = ACTION_SATIETY_DECISION
                putExtra(EXTRA_DECISION_JSON, decisionJson)
            }
            context.applicationContext.sendBroadcast(broadcastIntent)
        }
    }
}
