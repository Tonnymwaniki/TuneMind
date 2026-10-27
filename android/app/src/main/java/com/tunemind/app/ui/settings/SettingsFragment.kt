package com.tunemind.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.tunemind.app.R
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.sync.SyncScheduler
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repo = EventBufferRepository(TuneMindDatabase.getInstance(requireContext()).eventQueueDao())
        val tvNotification = view.findViewById<TextView>(R.id.tv_notification)
        val btnGrant = view.findViewById<MaterialButton>(R.id.btn_grant)
        val tvSync = view.findViewById<TextView>(R.id.tv_sync)
        val btnSync = view.findViewById<MaterialButton>(R.id.btn_sync)
        val tvData = view.findViewById<TextView>(R.id.tv_data)
        val btnDebug = view.findViewById<MaterialButton>(R.id.btn_debug)

        btnGrant.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btnSync.setOnClickListener { SyncScheduler.schedule(requireContext()) }
        btnDebug.setOnClickListener { startActivity(Intent(requireContext(), com.tunemind.app.ui.debug.DebugActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.observeCount().collect { count ->
                    tvData.text = "$count stored observations (max ${EventBufferRepository.MAX_OBSERVATIONS})"
                } }
            }
        }
        updateStatus(tvNotification, btnGrant, tvSync)
    }

    override fun onResume() {
        super.onResume()
        view?.let { updateStatus(
            it.findViewById(R.id.tv_notification),
            it.findViewById(R.id.btn_grant),
            it.findViewById(R.id.tv_sync),
        ) }
    }

    private fun updateStatus(tvNotification: TextView, btnGrant: MaterialButton, tvSync: TextView) {
        val hasAccess = NotificationAccess.isEnabled(requireContext())
        val connected = TuneMindListenerService.isConnected
        tvNotification.text = if (hasAccess) "Granted" else "Not granted"
        tvNotification.setTextColor(if (hasAccess) resources.getColor(R.color.tunemind_green, null) else resources.getColor(R.color.tunemind_red, null))
        btnGrant.visibility = if (hasAccess) View.GONE else View.VISIBLE
        tvSync.text = if (connected) "Listener connected" else "Listener not connected"
    }
}
