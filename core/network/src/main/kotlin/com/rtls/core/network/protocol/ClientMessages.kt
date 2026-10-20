package com.rtls.core.network.protocol

import com.rtls.core.model.Crs
import com.rtls.core.model.LocationSample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

@Serializable
data class SubscribePayload(val targetDeviceId: String)

@Serializable
data class UnsubscribePayload(val targetDeviceId: String)

@Serializable
data class QueryLocationPayload(val targetDeviceId: String)

@Serializable
data class LocationUpdatePayload(
    val clientMsgId: String,
    val crs: String,
    val sourceCrs: String? = null,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val provider: String? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val isMock: Boolean? = null,
    val battery: Double? = null,
)

@Serializable
data class AckPayload(
    val status: String,
    val duplicate: Boolean = false,
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class LocationPushPayload(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val provider: String? = null,
    val sourceCrs: String? = null,
    val deviceTs: Long? = null,
    val serverTs: Long,
    val stale: Boolean = false,
    val online: Boolean = true,
)

@Serializable
data class PresencePayload(
    val deviceId: String,
    val online: Boolean,
    val lastSeenAt: Long? = null,
)

@Serializable
data class SubscriptionRevokedPayload(
    val deviceId: String,
    val reason: String,
)

/** Builds outbound envelopes; IDs are generated per transmission. */
object ClientMessages {

    fun ping(nowMs: Long = System.currentTimeMillis()): Envelope =
        envelope(type = "ping", payload = buildJsonObject {}, ts = nowMs)

    fun subscribe(
        targetDeviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope = envelope(
        type = "subscribe",
        payload = RtlsJson.encodeToJsonElement(SubscribePayload(targetDeviceId)).jsonObject,
        ts = nowMs,
    )

    fun unsubscribe(
        targetDeviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope = envelope(
        type = "unsubscribe",
        payload = RtlsJson.encodeToJsonElement(UnsubscribePayload(targetDeviceId)).jsonObject,
        ts = nowMs,
    )

    fun queryLocation(
        targetDeviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope = envelope(
        type = "query_location",
        payload = RtlsJson.encodeToJsonElement(QueryLocationPayload(targetDeviceId)).jsonObject,
        ts = nowMs,
    )

    /**
     * Encodes a sample for upload. The payload is always WGS-84 and retains the
     * original [Crs] in `sourceCrs` for auditing (ADR-006).
     */
    fun locationUpdate(
        sample: LocationSample,
        clientMsgId: String = UUID.randomUUID().toString(),
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope {
        require(sample.crs == Crs.WGS84) {
            "location_update requires a WGS84 sample, was ${sample.crs}; call toWgs84() first"
        }
        val payload = LocationUpdatePayload(
            clientMsgId = clientMsgId,
            crs = sample.crs.name,
            sourceCrs = sample.sourceCrs.name,
            latitude = sample.latitude,
            longitude = sample.longitude,
            accuracy = sample.accuracyMeters.toDouble(),
            provider = sample.provider.wireValue,
            speed = sample.speedMps?.toDouble(),
            bearing = sample.bearingDegrees?.toDouble(),
            isMock = sample.isMock,
            battery = sample.batteryLevel?.toDouble(),
        )
        return envelope(
            type = "location_update",
            payload = RtlsJson.encodeToJsonElement(payload).jsonObject,
            ts = nowMs,
        )
    }

    /** Same as [locationUpdate] but normalizes non-WGS-84 samples first. */
    fun locationUpdateNormalized(
        sample: LocationSample,
        clientMsgId: String = UUID.randomUUID().toString(),
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope = locationUpdate(sample.toWgs84(), clientMsgId, nowMs)

    private fun envelope(
        type: String,
        payload: JsonObject,
        ts: Long,
    ): Envelope = Envelope(
        v = Protocol.VERSION,
        id = UUID.randomUUID().toString(),
        type = type,
        ts = ts,
        payload = payload,
    )
}
