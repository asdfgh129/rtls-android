package com.rtls.core.map

import com.rtls.core.model.Crs
import com.rtls.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class MapContractsTest {

    @Test
    fun `wgs84 points are converted before reaching the map layer`() {
        val wgs84 = GeoPoint(39.9042, 116.4074, Crs.WGS84)
        val bd09 = MapCoordinates.fromWgs84(wgs84)

        assertEquals(Crs.BD09, bd09.crs)
        assertEquals(39.911865, bd09.latitude, 1e-4)
        assertEquals(116.420046, bd09.longitude, 1e-4)
    }

    @Test
    fun `markers reject non BD09 points`() {
        try {
            MapMarker(id = "self", point = GeoPoint(39.9042, 116.4074, Crs.WGS84))
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `camera zoom is bounded`() {
        val center = GeoPoint(39.911865, 116.420046, Crs.BD09)
        MapCamera(center = center, zoom = MapCamera.DEFAULT_ZOOM)
        try {
            MapCamera(center = center, zoom = 30f)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
