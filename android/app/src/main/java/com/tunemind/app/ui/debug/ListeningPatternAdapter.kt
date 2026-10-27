package com.tunemind.app.ui.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tunemind.app.R
import com.tunemind.app.domain.model.AppearanceGapStatus
import com.tunemind.app.domain.model.TrackRecurrencePattern
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ListeningPatternAdapter : RecyclerView.Adapter<ListeningPatternAdapter.Holder>() {
    private val items = mutableListOf<TrackRecurrencePattern>()
    private var zoneId: ZoneId = ZoneId.systemDefault()

    fun submit(values: List<TrackRecurrencePattern>, zoneId: ZoneId) {
        this.zoneId = zoneId
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_listening_pattern, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], zoneId)
    override fun getItemCount() = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tv_pattern_title)
        private val details: TextView = view.findViewById(R.id.tv_pattern_details)

        fun bind(item: TrackRecurrencePattern, zoneId: ZoneId) {
            title.text = item.title ?: "Track metadata unavailable"
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.getDefault()).withZone(zoneId)
            details.text = buildList {
                item.artist?.let { add(it) }
                add("${item.distinctAppearanceCount} distinct reconstructed track appearances")
                add("Source: ${item.sourcePackage ?: "Unavailable"} · identity: ${item.identityBasis}")
                add("Evidence: ${item.evidenceQuality}")
                item.firstObservedAppearanceAtMs?.let { add("First observed appearance: ${formatter.format(Instant.ofEpochMilli(it))}") }
                item.lastObservedAppearanceAtMs?.let { add("Last observed appearance: ${formatter.format(Instant.ofEpochMilli(it))}") }
                item.intervals.forEach { gap ->
                    val value = if (gap.status == AppearanceGapStatus.VALID_OBSERVED_GAP) {
                        formatGap(requireNotNull(gap.gapMs))
                    } else {
                        "Unavailable (${gap.status.name.lowercase().replace('_', ' ')})"
                    }
                    add("Gap between observed appearances: $value")
                }
                add("Appearance IDs: ${item.appearanceIds.joinToString()}")
                add("Supporting raw observation IDs: ${item.supportingObservationIds.joinToString().ifBlank { "Unavailable" }}")
                add("These are observed appearance gaps, not gaps between complete plays.")
            }.joinToString("\n")
        }

        private fun formatGap(ms: Long): String {
            val totalMinutes = ms / 60_000L
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return buildList {
                if (days > 0) add("${days}d")
                if (hours > 0) add("${hours}h")
                add("${minutes}m")
            }.joinToString(" ")
        }
    }
}
