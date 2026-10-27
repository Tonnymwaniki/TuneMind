package com.tunemind.app.ui.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tunemind.app.R
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import java.text.DateFormat
import java.util.Date

class ReconstructedEpisodeAdapter : RecyclerView.Adapter<ReconstructedEpisodeAdapter.Holder>() {
    private val items = mutableListOf<ReconstructedPlaybackEpisode>()
    fun submit(values: List<ReconstructedPlaybackEpisode>) { items.clear(); items.addAll(values); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_reconstructed_episode, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tv_episode_title)
        private val details: TextView = view.findViewById(R.id.tv_episode_details)
        fun bind(item: ReconstructedPlaybackEpisode) {
            title.text = "${item.sourcePackage ?: "Source unavailable"} · ${item.status}"
            val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            details.text = buildList {
                add("First observed: ${formatter.format(Date(item.firstObservedAtMs))} (${item.startBoundary})")
                add("Last observed: ${formatter.format(Date(item.lastObservedAtMs))} (${item.endBoundary})")
                add("Estimated active time: ${item.estimatedActivePlaybackMs?.let { "$it ms" } ?: "Unavailable"} (callback-bounded estimate)")
                if (item.hasObservationGap) add("Observation gap: active time across the gap was not assumed")
                if (item.hasTimestampOrderingAmbiguity) add("Timestamp / duplicate-ID evidence is ambiguous")
                add("Raw evidence IDs: ${item.rawObservationIds.joinToString()}")
                if (item.trackAppearances.isEmpty()) add("Track metadata unavailable")
                item.trackAppearances.forEachIndexed { index, track ->
                    val metadata = listOfNotNull(track.title, track.artist, track.album).joinToString(" — ").ifBlank { "Track metadata unavailable" }
                    add("Candidate track appearance ${index + 1}: $metadata")
                    add("  Title: ${track.title ?: "Unavailable"}; Artist: ${track.artist ?: "Unavailable"}; Album: ${track.album ?: "Unavailable"}; Track ID: ${track.trackId ?: "Unavailable"}")
                    add("  Identity: ${track.identityBasis}; evidence: ${track.evidenceStatus}; observed ${track.firstObservedAtMs}–${track.lastObservedAtMs}")
                    add("  Duration reported by source: ${track.durationMs?.let { "$it ms" } ?: "Unavailable"}")
                    add("  Raw evidence IDs: ${track.rawObservationIds.joinToString()}")
                }
            }.joinToString("\n")
        }
    }
}
