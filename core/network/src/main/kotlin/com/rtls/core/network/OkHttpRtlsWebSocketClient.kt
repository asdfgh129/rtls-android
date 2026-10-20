package com.rtls.core.network

import com.rtls.core.network.protocol.ClientMessages
import com.rtls.core.network.protocol.CloseCodes
import com.rtls.core.network.protocol.Envelope
import com.rtls.core.network.protocol.EnvelopeCodec
import com.rtls.core.network.protocol.Protocol
import com.rtls.core.network.protocol.ServerMessage
import com.rtls.core.network.protocol.ServerMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp implementation of [RtlsWebSocketClient].
 *
 * - JWT travels only in the Authorization header (never a query parameter).
 * - Application-level ping every 30 s; a 90 s inbound silence closes 4408.
 * - Reconnect backoff 1s→60s with ±20% jitter, re-subscribing known targets.
 * - 4401 emits [ConnectionEvent.AuthExpired] instead of retrying; the app layer
 *   must refresh the token and call [connect] again (protocol §10.2).
 */
class OkHttpRtlsWebSocketClient(
    private val wsUrl: String,
    private val okHttpClient: OkHttpClient = defaultClient(),
    private val heartbeatIntervalMs: Long = Protocol.HEARTBEAT_INTERVAL_MS,
    private val serverTimeoutMs: Long = Protocol.SERVER_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) : RtlsWebSocketClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicInteger(0)
    private val subscriptions = Collections.synchronizedSet(LinkedHashSet<String>())

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ServerMessage> = _incoming.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var accessToken: String? = null
    private var manualClose = false
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var lastInboundMs: Long = 0

    init {
        require(wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")) {
            "wsUrl must use ws:// or wss://, was $wsUrl"
        }
    }

    override fun connect(accessToken: String) {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        manualClose = false
        this.accessToken = accessToken
        reconnectJob?.cancel()
        backoff.reset()
        webSocket?.cancel()
        webSocket = null
        openSocket()
    }

    override fun disconnect(code: Int, reason: String) {
        manualClose = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.close(code, reason)
        _state.value = ConnectionState.CLOSED
        _events.tryEmit(ConnectionEvent.Disconnected(code, reason, willRetry = false))
    }

    override fun send(envelope: Envelope): Boolean {
        val socket = webSocket ?: return false
        return try {
            socket.send(EnvelopeCodec.encode(envelope))
        } catch (_: IllegalArgumentException) {
            _events.tryEmit(ConnectionEvent.ProtocolError("refusing to send oversized/invalid frame"))
            false
        }
    }

    override fun subscribe(targetDeviceId: String) {
        require(targetDeviceId.isNotBlank()) { "targetDeviceId must not be blank" }
        synchronized(subscriptions) {
            check(
                subscriptions.contains(targetDeviceId) ||
                    subscriptions.size < Protocol.MAX_SUBSCRIPTIONS_PER_CONNECTION,
            ) { "subscription limit reached" }
            subscriptions.add(targetDeviceId)
        }
        if (_state.value == ConnectionState.CONNECTED) {
            send(ClientMessages.subscribe(targetDeviceId, clock()))
        }
    }

    override fun unsubscribe(targetDeviceId: String) {
        subscriptions.remove(targetDeviceId)
        if (_state.value == ConnectionState.CONNECTED) {
            send(ClientMessages.unsubscribe(targetDeviceId, clock()))
        }
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun openSocket() {
        val token = accessToken ?: return
        val currentGeneration = generation.incrementAndGet()
        _state.value = if (backoff.currentAttempt > 0) {
            ConnectionState.RECONNECTING
        } else {
            ConnectionState.CONNECTING
        }
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = okHttpClient.newWebSocket(request, Listener(currentGeneration))
    }

    private fun startHeartbeat(currentGeneration: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && currentGeneration == generation.get() &&
                _state.value == ConnectionState.CONNECTED
            ) {
                delay(heartbeatIntervalMs)
                if (clock() - lastInboundMs > serverTimeoutMs) {
                    _events.tryEmit(ConnectionEvent.HeartbeatTimeout)
                    webSocket?.close(CloseCodes.HEARTBEAT_TIMEOUT, "heartbeat timeout")
                    return@launch
                }
                if (!send(ClientMessages.ping(clock()))) return@launch
            }
        }
    }

    private fun scheduleReconnect(
        currentGeneration: Int,
        code: Int,
        reason: String,
    ) {
        if (manualClose || accessToken == null) return
        _state.value = ConnectionState.RECONNECTING
        _events.tryEmit(ConnectionEvent.Disconnected(code, reason, willRetry = true))
        val delayMs = backoff.nextDelayMs()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (manualClose || currentGeneration != generation.get()) return@launch
            openSocket()
        }
    }

    @Synchronized
    private fun handleDisconnected(
        currentGeneration: Int,
        code: Int,
        reason: String,
    ) {
        if (currentGeneration != generation.get()) return
        if (webSocket == null) return
        webSocket = null
        heartbeatJob?.cancel()

        when {
            manualClose -> Unit
            code == CloseCodes.NORMAL -> {
                _state.value = ConnectionState.CLOSED
                _events.tryEmit(ConnectionEvent.Disconnected(code, reason, willRetry = false))
            }
            code == CloseCodes.UNAUTHORIZED -> {
                _state.value = ConnectionState.CLOSED
                _events.tryEmit(ConnectionEvent.AuthExpired)
                _events.tryEmit(ConnectionEvent.Disconnected(code, reason, willRetry = false))
            }
            code == CloseCodes.FORBIDDEN -> {
                _state.value = ConnectionState.CLOSED
                _events.tryEmit(ConnectionEvent.Disconnected(code, reason, willRetry = false))
            }
            else -> scheduleReconnect(currentGeneration, code, reason)
        }
    }

    private inner class Listener(
        private val currentGeneration: Int,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (currentGeneration != generation.get()) {
                webSocket.close(CloseCodes.NORMAL, "stale connection")
                return
            }
            lastInboundMs = clock()
            _state.value = ConnectionState.CONNECTED
            _events.tryEmit(ConnectionEvent.Connected)
            startHeartbeat(currentGeneration)

            // The protocol resets backoff only after 30 s of stability.
            scope.launch {
                delay(STABLE_CONNECTION_MS)
                if (currentGeneration == generation.get() &&
                    _state.value == ConnectionState.CONNECTED
                ) {
                    backoff.reset()
                }
            }

            synchronized(subscriptions) {
                subscriptions.toList().forEach { target ->
                    send(ClientMessages.subscribe(target, clock()))
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (currentGeneration != generation.get()) return
            lastInboundMs = clock()
            val message = try {
                ServerMessageParser.parse(text)
            } catch (error: Exception) {
                _events.tryEmit(ConnectionEvent.ProtocolError(error.message ?: "malformed frame"))
                return
            }
            _incoming.tryEmit(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnected(currentGeneration, code, reason)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            if (currentGeneration != generation.get()) return
            _events.tryEmit(
                ConnectionEvent.Failure(
                    message = throwable.message ?: throwable::class.java.simpleName,
                    willRetry = !manualClose,
                ),
            )
            val httpStatus = response?.code
            if (httpStatus == 401) {
                handleDisconnected(currentGeneration, CloseCodes.UNAUTHORIZED, "http 401")
            } else {
                handleDisconnected(
                    currentGeneration,
                    CloseCodes.INTERNAL_ERROR,
                    throwable.message ?: "transport failure",
                )
            }
        }
    }

    companion object {
        private const val STABLE_CONNECTION_MS = 30_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
