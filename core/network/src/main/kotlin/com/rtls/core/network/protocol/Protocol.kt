package com.rtls.core.network.protocol

/** Protocol constants from Docs/specs/protocol.md v1. */
object Protocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 4096
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val SERVER_TIMEOUT_MS = 90_000L
    const val MAX_SUBSCRIPTIONS_PER_CONNECTION = 100
}

enum class ErrorCode(val wireValue: String) {
    AUTH_REQUIRED("AUTH_REQUIRED"),
    AUTH_INVALID("AUTH_INVALID"),
    FORBIDDEN("FORBIDDEN"),
    NOT_FOUND("NOT_FOUND"),
    NO_LOCATION("NO_LOCATION"),
    INVALID_PAYLOAD("INVALID_PAYLOAD"),
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION"),
    RATE_LIMITED("RATE_LIMITED"),
    SUBSCRIPTION_LIMIT("SUBSCRIPTION_LIMIT"),
    CONFLICT("CONFLICT"),
    ALREADY_FRIENDS("ALREADY_FRIENDS"),
    INVITE_EXPIRED("INVITE_EXPIRED"),
    INVITE_USED("INVITE_USED"),
    INTERNAL("INTERNAL"),
    ;

    companion object {
        fun fromWire(value: String?): ErrorCode? = entries.firstOrNull { it.wireValue == value }
    }
}

object CloseCodes {
    const val NORMAL = 1000
    const val INTERNAL_ERROR = 1011
    const val PROTOCOL_ERROR = 4400
    const val UNAUTHORIZED = 4401
    const val FORBIDDEN = 4403
    const val HEARTBEAT_TIMEOUT = 4408
    const val RATE_LIMITED = 4429
    const val SERVER_ERROR = 4500
}
