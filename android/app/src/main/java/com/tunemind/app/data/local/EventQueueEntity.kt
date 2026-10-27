package com.tunemind.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local v1 observation row. Sequence orders this database only; it is not event time. */
@Entity(
    tableName = "event_queue",
    indices = [Index(value = ["event_id"], unique = true)],
)
data class EventQueueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Long = 0,

    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    /** Retained for compatibility with the old queue design; this milestone never syncs. */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "event_time_ms")
    val eventTimeMs: Long,

    @ColumnInfo(name = "saved_at_ms")
    val savedAtMs: Long,
)
