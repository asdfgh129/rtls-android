package com.rtls.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {

    @Test
    fun `follows protocol sequence without jitter`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.0)
        val delays = (1..8).map { backoff.nextDelayMs() }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `jitter stays within twenty percent`() {
        val backoff = ReconnectBackoff(random = Random(42))
        repeat(200) {
            val delay = backoff.nextDelayMs()
            val base = ReconnectBackoff.DEFAULT_DELAYS_MS[
                it.coerceAtMost(ReconnectBackoff.DEFAULT_DELAYS_MS.lastIndex),
            ]
            assertTrue("delay $delay outside jitter for base $base", delay in (base * 0.8).toLong()..(base * 1.2).toLong())
        }
    }

    @Test
    fun `reset starts from the first delay again`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.0)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(2, backoff.currentAttempt)

        backoff.reset()
        assertEquals(0, backoff.currentAttempt)
        assertEquals(1_000L, backoff.nextDelayMs())
    }
}
