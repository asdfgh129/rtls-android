package com.rtls.core.network.protocol

/** Serializes and validates envelopes against the 4 KB frame limit. */
object EnvelopeCodec {

    fun encode(envelope: Envelope): String {
        require(envelope.v == Protocol.VERSION) {
            "unsupported protocol version ${envelope.v}"
        }
        require(envelope.id.isNotBlank() && envelope.id.length <= 64) {
            "envelope id must be 1..64 characters"
        }
        require(envelope.type.isNotBlank()) { "envelope type must not be blank" }
        val text = RtlsJson.encodeToString(Envelope.serializer(), envelope)
        val size = text.encodeToByteArray().size
        require(size <= Protocol.MAX_FRAME_BYTES) {
            "frame is $size bytes, limit is ${Protocol.MAX_FRAME_BYTES}"
        }
        return text
    }

    fun decode(text: String): Envelope {
        val size = text.encodeToByteArray().size
        require(size <= Protocol.MAX_FRAME_BYTES) {
            "frame is $size bytes, limit is ${Protocol.MAX_FRAME_BYTES}"
        }
        return RtlsJson.decodeFromString(Envelope.serializer(), text)
    }
}
