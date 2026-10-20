package com.rtls.core.network.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared JSON configuration for the RTLS wire protocol. */
val RtlsJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

/**
 * Common envelope for both directions (protocol §3). Server-originated messages
 * may carry [replyTo].
 */
@kotlinx.serialization.Serializable
data class Envelope(
    val v: Int = Protocol.VERSION,
    val id: String,
    val type: String,
    val ts: Long,
    val payload: JsonObject,
    val replyTo: String? = null,
)
