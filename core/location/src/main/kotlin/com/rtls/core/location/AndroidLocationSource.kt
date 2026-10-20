package com.rtls.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.rtls.core.model.Crs
import com.rtls.core.model.LocationProvider
import com.rtls.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Maps an Android [Location] to the CRS-tagged RTLS model. */
internal fun Location.toLocationSample(): LocationSample? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

    val mappedProvider = when (provider) {
        LocationManager.GPS_PROVIDER -> LocationProvider.GPS
        LocationManager.NETWORK_PROVIDER -> LocationProvider.NETWORK
        LocationManager.PASSIVE_PROVIDER -> LocationProvider.PASSIVE
        else -> LocationProvider.UNKNOWN
    }
    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
    return runCatching {
        LocationSample(
            latitude = latitude,
            longitude = longitude,
            crs = Crs.WGS84,
            accuracyMeters = if (hasAccuracy()) accuracy else 0f,
            provider = mappedProvider,
            timestampMs = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            speedMps = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing.normalizedBearing() else null,
            isMock = mock,
        )
    }.getOrNull()
}

private fun Float.normalizedBearing(): Float = ((this % 360f) + 360f) % 360f

/**
 * Platform fallback that does not require GMS. It uses GPS/network providers
 * from LocationManager and always returns WGS-84 (ADR-007).
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override val id: String = ID

    @Volatile
    private var locationManager: LocationManager? = null

    @Volatile
    private var activeListener: LocationListener? = null

    override fun isAvailable(): Boolean {
        if (!LocationPermissions.hasForegroundLocation(context)) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            val canUseGps = LocationPermissions.hasFineLocation(context)
            val canUseNetwork = LocationPermissions.hasForegroundLocation(context)
            (canUseGps && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) ||
                (canUseNetwork && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun start(config: LocationConfig): Flow<LocationSample> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: error("LocationManager unavailable")
        locationManager = manager

        val canUseGps = LocationPermissions.hasFineLocation(context)
        val providers = buildList {
            if (canUseGps && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            close(IllegalStateException("no location provider enabled"))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                location.toLocationSample()?.let { trySend(it) }
            }
        }
        activeListener = listener
        providers.forEach { provider ->
            manager.requestLocationUpdates(
                provider,
                config.intervalMs,
                config.minDistanceMeters,
                listener,
                Looper.getMainLooper(),
            )
        }

        awaitClose { stop() }
    }

    override fun stop() {
        val manager = locationManager ?: return
        val listener = activeListener ?: return
        runCatching { manager.removeUpdates(listener) }
        activeListener = null
    }

    companion object {
        const val ID = "android-location-manager"
    }
}
