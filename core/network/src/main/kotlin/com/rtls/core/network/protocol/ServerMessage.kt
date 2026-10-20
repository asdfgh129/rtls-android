package com.rtls.core.network.protocol

import kotlinx.serialization.json.decodeFromJsonElement

/** A parsed server message. Unknown types are preserved for forward compatibility. */
sealed interface ServerMessage {
    val envelope: Envelope

    data class Pong(override val envelope: Envelope) : ServerMessage
    data class Ack(
        override val envelope: Envelope,
        val payload: AckPayload,
    ) : ServerMessage
    data class Error(
        override val envelope: Envelope,
        val payload: ErrorPayload,
    ) : ServerMessage
    data class LocationPush(
        override val envelope: Envelope,
        val payload: LocationPushPayload,
    ) : ServerMessage
    data class Presence(
        override val envelope: Envelope,
        val payload: PresencePayload,
    ) : ServerMessage
    data class SubscriptionRevoked(
        override val envelope: Envelope,
        val payload: SubscriptionRevokedPayload,
    ) : ServerMessage
    data class Unknown(
        override val envelope: Envelope,
    ) : ServerMessage
    data class UnsupportedVersion(
        override val envelope: Envelope,
    ) : ServerMessage
}

object ServerMessageParser {

    fun parse(text: String): ServerMessage {
        val envelope = EnvelopeCodec.decode(text)
        if (envelope.v != Protocol.VERSION) {
            return ServerMessage.UnsupportedVersion(envelope)
        }
        return when (envelope.type) {
            "pong" -> ServerMessage.Pong(envelope)
            "ack" -> ServerMessage.Ack(envelope, RtlsJson.decodeFromJsonElement(envelope.payload))
            "error" -> ServerMessage.Error(envelope, RtlsJson.decodeFromJsonElement(envelope.payload))
            "location_push" -> ServerMessage.LocationPush(
                envelope,
                RtlsJson.decodeFromJsonElement(envelope.payload),
            )
            "presence" -> ServerMessage.Presence(
                envelope,
                RtlsJson.decodeFromJsonElement(envelope.payload),
            )
            "subscription_revoked" -> ServerMessage.SubscriptionRevoked(
                envelope,
                RtlsJson.decodeFromJsonElement(envelope.payload),
            )
            else -> ServerMessage.Unknown(envelope)
        }
    }
}
