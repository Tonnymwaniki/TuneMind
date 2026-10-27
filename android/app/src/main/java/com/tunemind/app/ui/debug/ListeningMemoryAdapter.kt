package com.tunemind.app.ui.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tunemind.app.R
import com.tunemind.app.domain.model.TrackMemoryEntry

class ListeningMemoryAdapter(
    private val onEntrySelected: (TrackMemoryEntry) -> Unit,
) : RecyclerView.Adapter<ListeningMemoryAdapter.Holder>() {
    private val items = mutableListOf<TrackMemoryEntry>()
    fun submit(values: List<TrackMemoryEntry>) { items.clear(); items.addAll(values); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_listening_memory, parent, false), onEntrySelected)
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    class Holder(view: View, private val onEntrySelected: (TrackMemoryEntry) -> Unit) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tv_memory_track_title)
        private val details: TextView = view.findViewById(R.id.tv_memory_track_details)
        private var bound: TrackMemoryEntry? = null
        init { view.setOnClickListener { bound?.let(onEntrySelected) } }
        fun bind(item: TrackMemoryEntry) {
            bound = item
            title.text = item.title ?: "Unidentified track appearance"
            details.text = buildList {
                item.artist?.let { add(it) }
                add("${item.distinctAppearanceCount} reconstructed appearance${if (item.distinctAppearanceCount == 1) "" else "s"}")
                add("Source: ${item.sourcePackage ?: "Unavailable"} · ${item.identityBasis}")
                if (item.historyMayBeIncomplete) add("History may be incomplete")
            }.joinToString("\n")
        }
    }
}
