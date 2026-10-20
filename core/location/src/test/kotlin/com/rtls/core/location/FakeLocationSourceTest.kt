package com.rtls.core.location

import com.rtls.core.model.Crs
import com.rtls.core.model.LocationProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLocationSourceTest {

    @Test
    fun `emits deterministic WGS84 mock samples`() = runTest {
        val source = FakeLocationSource(clock = { 1_720_000_000_000 })
        val samples = source.start(LocationConfig(intervalMs = 1_000))
            .take(3)
            .toList()

        assertEquals(3, samples.size)
        samples.forEach { sample ->
            assertEquals(Crs.WGS84, sample.crs)
            assertEquals(LocationProvider.FUSED, sample.provider)
            assertTrue(sample.isMock)
            assertEquals(8f, sample.accuracyMeters, 0f)
        }
        assertEquals(39.9042, samples.first().latitude, 1e-9)
        assertEquals(116.4074, samples.first().longitude, 1e-9)
        assertNotEquals(samples[0].longitude, samples[2].longitude)
    }
}
