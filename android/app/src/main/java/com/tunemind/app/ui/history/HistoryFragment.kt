package com.tunemind.app.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.tunemind.app.R
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {
    private var currentTracks = listOf<TrackMemoryEntry>()
    private lateinit var adapter: HistoryAdapter
    private var filterMode = "all"

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_history, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repo = EventBufferRepository(TuneMindDatabase.getInstance(requireContext()).eventQueueDao())
        val rv = view.findViewById<RecyclerView>(R.id.rv_tracks)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)

        adapter = HistoryAdapter { track ->
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
            AlertDialog.Builder(requireContext())
                .setTitle(track.title ?: "Unknown")
                .setMessage(buildString {
                    appendLine("Artist: ${track.artist ?: "Unavailable"}")
                    appendLine("Appearances: ${track.distinctAppearanceCount}")
                    track.firstObservedAppearanceAtMs?.let { appendLine("First: ${fmt.format(java.util.Date(it))}") }
                    track.lastObservedAppearanceAtMs?.let { appendLine("Last: ${fmt.format(java.util.Date(it))}") }
                    if (track.appearances.isNotEmpty()) {
                        appendLine()
                        track.appearances.sortedBy { it.firstObservedAtMs }.forEachIndexed { i, a ->
                            appendLine("  ${i+1}. ${fmt.format(java.util.Date(a.firstObservedAtMs))}")
                        }
                    }
                    appendLine("\nRecurrence signal, dynamics, and coverage available in Music Profile.")
                })
                .setPositiveButton("Close", null)
                .show()
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<ChipGroup>(R.id.chip_group).setOnCheckedChangeListener { _, checkedId ->
            filterMode = when (checkedId) {
                R.id.chip_identified -> "identified"
                R.id.chip_recurring -> "recurring"
                else -> "all"
            }
            applyFilter()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.observeRecent().collect { rows ->
                    currentTracks = withContext(Dispatchers.Default) {
                        val truncated = rows.size >= EventBufferRepository.MAX_OBSERVATIONS
                        val episodes = ListeningSessionReconstructor().reconstruct(rows, retainedWindowMayBeTruncated = truncated)
                        ListeningMemoryBuilder().build(rows, episodes, retainedHistoryMayBeIncomplete = truncated)
                    }.tracks.sortedByDescending { it.lastObservedAppearanceAtMs }
                    applyFilter()
                } }
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (filterMode) {
            "identified" -> currentTracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT }
            "recurring" -> currentTracks.filter { it.identityBasis != TrackIdentityBasis.INSUFFICIENT && it.distinctAppearanceCount >= 2 }
            else -> currentTracks
        }
        adapter.submit(filtered)
        view?.findViewById<TextView>(R.id.tv_empty)?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
}

class HistoryAdapter(private val onClick: (TrackMemoryEntry) -> Unit) : RecyclerView.Adapter<HistoryAdapter.VH>() {
    private val items = mutableListOf<TrackMemoryEntry>()
    fun submit(list: List<TrackMemoryEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_2, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.t1.text = item.title ?: "Unknown track"
        h.t2.text = "${item.artist ?: "Unknown artist"} · ${item.distinctAppearanceCount} appearances"
        h.itemView.setOnClickListener { onClick(item) }
    }
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val t1 = itemView.findViewById<TextView>(android.R.id.text1)!!
        val t2 = itemView.findViewById<TextView>(android.R.id.text2)!!
    }
}
