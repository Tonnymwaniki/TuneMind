package com.tunemind.app.ui.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tunemind.app.R
import com.tunemind.app.domain.model.PlaybackObservation
import java.text.DateFormat
import java.util.Date

class PlaybackObservationAdapter : RecyclerView.Adapter<PlaybackObservationAdapter.ViewHolder>() {
    private val items = mutableListOf<PlaybackObservation>()

    fun submit(values: List<PlaybackObservation>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_playback_observation, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tv_event_title)
        private val details: TextView = itemView.findViewById(R.id.tv_event_details)

        fun bind(item: PlaybackObservation) {
            title.text = listOfNotNull(item.trackTitle, item.trackArtist).joinToString(" — ")
                .ifBlank { "Track metadata unavailable" }
            val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            val eventTime = formatter.format(Date(item.eventTimeMs))
            val saveTime = item.savedAtMs?.let { formatter.format(Date(it)) } ?: "Unavailable"
            details.text = buildList {
                add(item.eventType.name)
                add("Event: $eventTime")
                add("Saved: $saveTime")
                add("Source: ${item.sourcePackage ?: "Unavailable"}")
                add("Playback state: ${item.playbackStateCode?.toString() ?: "Unavailable"}")
                add("Position: ${item.positionMs?.let { "$it ms" } ?: "Unavailable"}")
                add("Speed: ${item.playbackSpeed?.let { "$it×" } ?: "Unavailable"}")
                add("Position update (elapsed realtime): ${item.positionUpdateElapsedRealtimeMs?.let { "$it ms" } ?: "Unavailable"}")
                item.trackId?.let { add("Track ID: $it") }
                item.durationMs?.let { add("Duration: $it ms") }
                add("Album: ${item.trackAlbum ?: "Unavailable"}")
            }.joinToString("\n")
        }
    }
}
