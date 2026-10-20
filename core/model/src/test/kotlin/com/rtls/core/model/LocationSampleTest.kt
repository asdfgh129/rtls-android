package com.rtls.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleTest {

    private fun sample(
        latitude: Double = 39.90560334316507,
        longitude: Double = 116.41364225378803,
        crs: Crs = Crs.GCJ02,
        accuracy: Float = 15f,
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        crs = crs,
        accuracyMeters = accuracy,
        provider = LocationProvider.GPS,
        timestampMs = 1_720_000_000_000,
    )

    @Test
    fun `toWgs84 normalizes GCJ02 samples`() {
        val gcj02 = sample()
        val wgs84 = gcj02.toWgs84()

        assertEquals(Crs.WGS84, wgs84.crs)
        assertEquals(39.9042, wgs84.latitude, 1e-3)
        assertEquals(116.4074, wgs84.longitude, 1e-3)
        assertEquals(Crs.GCJ02, gcj02.sourceCrs)
    }

    @Test
    fun `wgs84 sample is returned unchanged`() {
        val wgs84 = sample(latitude = 39.9042, longitude = 116.4074, crs = Crs.WGS84)
        assertEquals(wgs84, wgs84.toWgs84())
    }

    @Test
    fun `accuracy filter keeps stored points but marks bad display points`() {
        assertTrue(sample(accuracy = 100f).isAccurateEnough())
        assertFalse(sample(accuracy = 100.1f).isAccurateEnough())
    }

    @Test
    fun `rejects non finite and out of range values`() {
        val invalid = listOf(
            { sample(latitude = Double.NaN) },
            { sample(longitude = Double.POSITIVE_INFINITY) },
            { sample(accuracy = -1f) },
        )
        invalid.forEach { build ->
            try {
                build()
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `optional fields default to null`() {
        val value = sample()
        assertNull(value.speedMps)
        assertNull(value.bearingDegrees)
        assertNull(value.batteryLevel)
    }
}
