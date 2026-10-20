package com.rtls.core.network

import kotlin.random.Random

/**
 * Exponential backoff with jitter from protocol §10.2:
 * 1s, 2s, 4s, 8s, 16s, 30s, 60s, each ±20%.
 */
class ReconnectBackoff(
    private val baseDelaysMs: List<Long> = DEFAULT_DELAYS_MS,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    init {
        require(baseDelaysMs.isNotEmpty()) { "baseDelaysMs must not be empty" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0, 1]" }
    }

    fun nextDelayMs(): Long {
        val base = baseDelaysMs[attempt.coerceAtMost(baseDelaysMs.lastIndex)]
        attempt++
        if (jitterRatio == 0.0) return base
        val jitter = base * jitterRatio
        return (base + random.nextDouble(-jitter, jitter)).toLong().coerceAtLeast(0L)
    }

    fun reset() {
        attempt = 0
    }

    val currentAttempt: Int get() = attempt

    companion object {
        val DEFAULT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 60_000L)
    }
}
