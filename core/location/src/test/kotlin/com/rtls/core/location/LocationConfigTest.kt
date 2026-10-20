package com.rtls.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationConfigTest {

    @Test
    fun `default configuration follows ADR-007 battery policy`() {
        val config = LocationConfig()
        assertEquals(10_000L, config.intervalMs)
        assertEquals(10f, config.minDistanceMeters, 0f)
        assertEquals(LocationPriority.BALANCED_POWER_ACCURACY, config.priority)
    }

    @Test
    fun `rejects intervals that would drain the battery`() {
        try {
            LocationConfig(intervalMs = 0)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            LocationConfig(intervalMs = 5_000, fastestIntervalMs = 10_000)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `permission state exposes onboarding decisions`() {
        val denied = PermissionState(
            foregroundGranted = false,
            notificationsGranted = false,
            backgroundGranted = false,
        )
        assertFalse(denied.canTrack)
        assertFalse(denied.shouldOfferBackgroundGuide)

        val foregroundOnly = PermissionState(
            foregroundGranted = true,
            notificationsGranted = false,
            backgroundGranted = false,
        )
        assertTrue(foregroundOnly.canTrack)
        assertTrue(foregroundOnly.shouldRequestNotifications)
        assertTrue(foregroundOnly.shouldOfferBackgroundGuide)

        val complete = PermissionState(
            foregroundGranted = true,
            notificationsGranted = true,
            backgroundGranted = true,
        )
        assertFalse(complete.shouldRequestNotifications)
        assertFalse(complete.shouldOfferBackgroundGuide)
    }
}
