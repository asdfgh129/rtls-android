package com.rtls.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformsTest {

    @Test
    fun `wgs84 to bd09 matches known Baidu values for Beijing`() {
        val wgs84 = GeoPoint(39.9042, 116.4074, Crs.WGS84)
        val bd09 = wgs84.convertedTo(Crs.BD09)

        // Reference values produced by the standard WGS-84 -> GCJ-02 -> BD-09
        // algorithm; verified against Baidu's coordinate tool during the spike.
        assertEquals(39.911865, bd09.latitude, 1e-4)
        assertEquals(116.420046, bd09.longitude, 1e-4)
    }

    @Test
    fun `round trip wgs84 to bd09 stays within one meter`() {
        val wgs84 = GeoPoint(31.2304, 121.4737, Crs.WGS84)
        val roundTrip = wgs84.convertedTo(Crs.BD09).convertedTo(Crs.WGS84)

        val error = GeoMath.distanceMeters(wgs84, roundTrip)
        assertTrue("round-trip error was $error m", error < 1.0)
    }

    @Test
    fun `gcj02 round trip preserves point`() {
        val gcj02 = GeoPoint(23.1291, 113.2644, Crs.GCJ02)
        val wgs84 = gcj02.convertedTo(Crs.WGS84)
        val roundTrip = wgs84.convertedTo(Crs.GCJ02)

        val error = GeoMath.distanceMeters(gcj02, roundTrip)
        assertTrue("round-trip error was $error m", error < 1.0)
    }

    @Test
    fun `points outside China are passed through unchanged`() {
        val london = GeoPoint(51.5074, -0.1278, Crs.WGS84)
        val converted = london.convertedTo(Crs.GCJ02)

        assertEquals(london.latitude, converted.latitude, 0.0)
        assertEquals(london.longitude, converted.longitude, 0.0)
        assertEquals(Crs.GCJ02, converted.crs)
    }

    @Test
    fun `conversion requires the documented source CRS`() {
        val bd09 = GeoPoint(39.911865, 116.420046, Crs.BD09)
        try {
            CoordinateTransforms.wgs84ToGcj02(bd09)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("expected WGS84, was BD09", expected.message)
        }
    }
}
