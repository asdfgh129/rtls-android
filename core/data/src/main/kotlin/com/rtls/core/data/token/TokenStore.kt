package com.rtls.core.data.token

/** Opaque token pair returned by /v1/auth/register or /v1/auth/refresh. */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMs: Long,
    val refreshExpiresAtMs: Long,
) {
    fun isAccessExpired(nowMs: Long, skewMs: Long = DEFAULT_SKEW_MS): Boolean =
        accessExpiresAtMs - skewMs <= nowMs

    companion object {
        const val DEFAULT_SKEW_MS = 30_000L
    }
}

interface TokenStore {
    fun load(): TokenSet?
    fun save(tokens: TokenSet)
    fun clear()
}
