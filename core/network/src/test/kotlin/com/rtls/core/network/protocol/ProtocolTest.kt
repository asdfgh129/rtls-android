package com.rtls.core.network.protocol

import com.rtls.core.model.Crs
import com.rtls.core.model.LocationProvider
import com.rtls.core.model.LocationSample
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `ping envelope uses protocol version and empty payload`() {
        val envelope = ClientMessages.ping(nowMs = 1_720_000_000_000)
        assertEquals(1, envelope.v)
        assertEquals("ping", envelope.type)
        assertEquals(1_720_000_000_000, envelope.ts)
        assertTrue(envelope.payload.isEmpty())
    }

    @Test
    fun `location update requires WGS84 and keeps source CRS`() {
        val sample = LocationSample(
            latitude = 39.9042,
            longitude = 116.4074,
            crs = Crs.WGS84,
            accuracyMeters = 12.5f,
            provider = LocationProvider.FUSED,
            timestampMs = 1_720_000_000_000,
        )
        val envelope = ClientMessages.locationUpdate(sample, clientMsgId = "client-1")
        assertEquals("location_update", envelope.type)

        val payload = RtlsJson.decodeFromJsonElement(
            LocationUpdatePayload.serializer(),
            envelope.payload,
        )
        assertEquals("client-1", payload.clientMsgId)
        assertEquals("WGS84", payload.crs)
        assertEquals("WGS84", payload.sourceCrs)
        assertEquals("fused", payload.provider)
        assertEquals(12.5, payload.accuracy!!, 0.0)
    }

    @Test
    fun `non WGS84 sample is rejected by locationUpdate but normalized variant works`() {
        val gcj02 = LocationSample(
            latitude = 39.90560334316507,
            longitude = 116.41364225378803,
            crs = Crs.GCJ02,
            accuracyMeters = 15f,
            provider = LocationProvider.GPS,
            timestampMs = 1_720_000_000_000,
        )
        try {
            ClientMessages.locationUpdate(gcj02)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val normalized = ClientMessages.locationUpdateNormalized(gcj02, clientMsgId = "client-2")
        val payload = RtlsJson.decodeFromJsonElement(
            LocationUpdatePayload.serializer(),
            normalized.payload,
        )
        assertEquals("WGS84", payload.crs)
        assertEquals("GCJ02", payload.sourceCrs)
    }

    @Test
    fun `server parser handles ack and unknown types`() {
        val ack = ServerMessageParser.parse(
            """{"v":1,"id":"srv-1","replyTo":"c-1","type":"ack","ts":1720000000100,"payload":{"status":"ok","duplicate":false}}""",
        )
        assertTrue(ack is ServerMessage.Ack)
        assertEquals("ok", (ack as ServerMessage.Ack).payload.status)
        assertEquals("c-1", ack.envelope.replyTo)

        val unknown = ServerMessageParser.parse(
            """{"v":1,"id":"srv-2","type":"future_message","ts":1720000000100,"payload":{"anything":true}}""",
        )
        assertTrue(unknown is ServerMessage.Unknown)
    }

    @Test
    fun `server parser rejects unsupported protocol version as a value`() {
        val message = ServerMessageParser.parse(
            """{"v":2,"id":"srv-3","type":"ack","ts":1720000000100,"payload":{"status":"ok"}}""",
        )
        assertTrue(message is ServerMessage.UnsupportedVersion)
    }

    @Test
    fun `envelope codec enforces the 4 KB frame limit`() {
        val huge = Envelope(
            id = "id-1",
            type = "location_update",
            ts = 1_720_000_000_000,
            payload = buildJsonObject { put("blob", "x".repeat(Protocol.MAX_FRAME_BYTES)) },
        )
        try {
            EnvelopeCodec.encode(huge)
            throw AssertionError("expected frame limit rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("limit"))
        }
    }

    @Test
    fun `error payload decodes known and unknown codes`() {
        val message = ServerMessageParser.parse(
            """{"v":1,"id":"srv-4","type":"error","ts":1,"payload":{"code":"FORBIDDEN","message":"no"}}""",
        )
        val error = (message as ServerMessage.Error).payload
        assertEquals(ErrorCode.FORBIDDEN, ErrorCode.fromWire(error.code))
        assertEquals(null, ErrorCode.fromWire("SOMETHING_NEW"))
    }

    @Test
    fun `location push payload keeps server timestamp ordering fields`() {
        val message = ServerMessageParser.parse(
            """{"v":1,"id":"srv-5","type":"location_push","ts":2,"payload":{"deviceId":"d-1","latitude":39.9,"longitude":116.4,"serverTs":1720000000300,"stale":false,"online":true}}""",
        )
        val push = (message as ServerMessage.LocationPush).payload
        assertEquals("d-1", push.deviceId)
        assertEquals(1_720_000_000_300, push.serverTs)
        assertFalse(push.stale)
        assertNotNull(message.envelope.id)
    }
}
