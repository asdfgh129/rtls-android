package com.rtls.core.location

import android.content.Context

/**
 * Runtime selection order from ADR-007:
 * GMS FLP → LocationManager fallback → explicit error. Fake is debug/test only.
 */
class LocationSourceProvider(
    context: Context,
    private val sources: List<LocationSource> = listOf(
        GmsFusedLocationSource(context),
        AndroidLocationSource(context),
        FakeLocationSource(),
    ),
) {

    /** Returns the selected source, or null when the device cannot locate. */
    fun select(allowFake: Boolean): LocationSource? {
        val fake = sources.firstOrNull { it.id == LocationSource.FAKE_ID }
        if (allowFake && fake != null) return fake
        return sources.firstOrNull {
            it.id != LocationSource.FAKE_ID && it.isAvailable()
        }
    }

    fun available(allowFake: Boolean): List<LocationSource> =
        sources.filter {
            (allowFake || it.id != LocationSource.FAKE_ID) && it.isAvailable()
        }
}
