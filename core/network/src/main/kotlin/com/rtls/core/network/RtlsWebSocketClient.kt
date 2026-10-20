package com.rtls.core.network

import com.rtls.core.network.protocol.CloseCodes
import com.rtls.core.network.protocol.Envelope
import com.rtls.core.network.protocol.ServerMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSED,
}

sealed interface ConnectionEvent {
    data object Connected : ConnectionEvent

    data class Disconnected(
        val code: Int,
        val reason: String,
        val willRetry: Boolean,
    ) : ConnectionEvent

    data object AuthExpired : ConnectionEvent

    data object HeartbeatTimeout : ConnectionEvent

    data class Failure(
        val message: String,
        val willRetry: Boolean,
    ) : ConnectionEvent

    data class ProtocolError(val message: String) : ConnectionEvent
}

/**
 * Transport contract for the RTLS WebSocket gateway.
 *
 * Implementations own application-level heartbeats, backoff and subscription
 * replay. They do not own offline persistence or token refresh; those live in
 * `:core:data` and the app layer (protocol §10).
 */
interface RtlsWebSocketClient {

    val state: StateFlow<ConnectionState>

    val events: SharedFlow<ConnectionEvent>

    val incoming: SharedFlow<ServerMessage>

    fun connect(accessToken: String)

    fun disconnect(
        code: Int = CloseCodes.NORMAL,
        reason: String = "client_disconnect",
    )

    fun send(envelope: Envelope): Boolean

    /** Subscribes now when connected and remembers the target for reconnects. */
    fun subscribe(targetDeviceId: String)

    fun unsubscribe(targetDeviceId: String)

    /** Releases coroutines. The client cannot be reused afterwards. */
    fun close()
}
