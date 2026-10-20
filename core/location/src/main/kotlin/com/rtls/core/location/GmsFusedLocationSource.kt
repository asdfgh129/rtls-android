package com.rtls.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rtls.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Google Play Services FLP source (priority 1 in ADR-007). */
class GmsFusedLocationSource(private val context: Context) : LocationSource {

    override val id: String = ID

    @Volatile
    private var client: com.google.android.gms.location.FusedLocationProviderClient? = null

    @Volatile
    private var callback: LocationCallback? = null

    override fun isAvailable(): Boolean {
        if (!LocationPermissions.hasForegroundLocation(context)) return false
        val status = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (status != ConnectionResult.SUCCESS) return false

        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun start(config: LocationConfig): Flow<LocationSample> = callbackFlow {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        client = fused

        val request = LocationRequest.Builder(config.priority.toPriority(), config.intervalMs)
            .setMinUpdateIntervalMillis(config.fastestIntervalMs)
            .setMinUpdateDistanceMeters(config.minDistanceMeters)
            .setMaxUpdateDelayMillis(config.maxUpdateDelayMs)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location: Location ->
                    location.toLocationSample()?.let { trySend(it) }
                }
            }
        }
        callback = locationCallback

        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            .addOnFailureListener { error ->
                close(error)
            }

        awaitClose { stop() }
    }

    override fun stop() {
        val fused = client
        val locationCallback = callback
        if (fused != null && locationCallback != null) {
            runCatching { fused.removeLocationUpdates(locationCallback) }
        }
        callback = null
    }

    private fun LocationPriority.toPriority(): Int = when (this) {
        LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        LocationPriority.BALANCED_POWER_ACCURACY -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    }

    companion object {
        const val ID = "gms-fused"
    }
}
