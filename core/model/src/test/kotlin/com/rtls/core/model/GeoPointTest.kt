package com.rtls.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoPointTest {

    @Test
    fun `carries its CRS and rejects invalid coordinates`() {
        val point = GeoPoint(39.9042, 116.4074, Crs.WGS84)
        assertEquals(Crs.WGS84, point.crs)

        assertThrows(IllegalArgumentException::class.java) {
            GeoPoint(91.0, 116.0, Crs.WGS84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeoPoint(39.0, Double.NaN, Crs.WGS84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeoPoint(39.0, 181.0, Crs.BD09)
        }
    }

    @Test
    fun `same numbers with different CRS are not equal`() {
        assertNotEquals(
            GeoPoint(39.9042, 116.4074, Crs.WGS84),
            GeoPoint(39.9042, 116.4074, Crs.BD09),
        )
    }

    @Test
    fun `convertedTo returns same instance when CRS already matches`() {
        val point = GeoPoint(39.9042, 116.4074, Crs.GCJ02)
        assertEquals(point, point.convertedTo(Crs.GCJ02))
    }
}
