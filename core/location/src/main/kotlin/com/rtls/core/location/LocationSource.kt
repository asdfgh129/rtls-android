package com.rtls.core.location

import com.rtls.core.model.Crs
import com.rtls.core.model.GeoPoint
import com.rtls.core.model.LocationProvider
import com.rtls.core.model.LocationSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

/** Power/interval policy passed to every [LocationSource] (ADR-007). */
enum class LocationPriority {
    HIGH_ACCURACY,
    BALANCED_POWER_ACCURACY,
    LOW_POWER,
}

data class LocationConfig(
    val intervalMs: Long = 10_000,
    val fastestIntervalMs: Long = minOf(5_000L, intervalMs),
    val minDistanceMeters: Float = 10f,
    val maxUpdateDelayMs: Long = 30_000,
    val priority: LocationPriority = LocationPriority.BALANCED_POWER_ACCURACY,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(fastestIntervalMs in 1..intervalMs) {
            "fastestIntervalMs must be in [1, intervalMs]"
        }
        require(minDistanceMeters >= 0f) { "minDistanceMeters must be >= 0" }
        require(maxUpdateDelayMs >= intervalMs) {
            "maxUpdateDelayMs must be >= intervalMs"
        }
    }
}

/**
 * Location source abstraction from ADR-007 so GMS/Baidu/Amap can be swapped
 * without changing the foreground service or upload pipeline.
 */
interface LocationSource {
    val id: String

    fun isAvailable(): Boolean

    /** Cold flow of samples; cancellation must release the underlying provider. */
    fun start(config: LocationConfig): Flow<LocationSample>

    /** Best-effort synchronous release for service shutdown. */
    fun stop()

    companion object {
        const val FAKE_ID = "fake"
    }
}

/**
 * Deterministic walking source for emulators, tests and debug builds.
 * Coordinates are WGS-84 and flagged as mock so the server can detect them.
 */
class FakeLocationSource(
    private val origin: GeoPoint = GeoPoint(39.9042, 116.4074, Crs.WGS84),
    private val stepMeters: Double = 12.0,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocationSource {

    override val id: String = LocationSource.FAKE_ID

    override fun isAvailable(): Boolean = true

    override fun start(config: LocationConfig): Flow<LocationSample> = flow {
        var latitude = origin.latitude
        var longitude = origin.longitude
        while (coroutineContext.isActive) {
            emit(
                LocationSample(
                    latitude = latitude,
                    longitude = longitude,
                    crs = Crs.WGS84,
                    accuracyMeters = 8f,
                    provider = LocationProvider.FUSED,
                    timestampMs = clock(),
                    speedMps = (stepMeters / (config.intervalMs / 1000.0)).toFloat(),
                    bearingDegrees = 90f,
                    isMock = true,
                    batteryLevel = 0.8f,
                ),
            )
            longitude += stepMeters / (111_320.0 * cos(Math.toRadians(latitude)))
            delay(config.intervalMs)
        }
    }

    override fun stop() = Unit
}
