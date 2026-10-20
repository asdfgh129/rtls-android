package com.rtls.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_queue",
    indices = [Index(value = ["enqueued_at_ms"]), Index(value = ["timestamp_ms"])],
)
data class QueuedLocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_msg_id")
    val clientMsgId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String?,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "source_crs")
    val sourceCrs: String,
    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: Float,
    val provider: String,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,
    @ColumnInfo(name = "speed_mps")
    val speedMps: Float?,
    @ColumnInfo(name = "bearing_degrees")
    val bearingDegrees: Float?,
    @ColumnInfo(name = "is_mock")
    val isMock: Boolean,
    @ColumnInfo(name = "battery_level")
    val batteryLevel: Float?,
    @ColumnInfo(name = "enqueued_at_ms")
    val enqueuedAtMs: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
)
