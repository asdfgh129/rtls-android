package com.rtls.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permission decision tree from ADR-007. */
object LocationPermissions {

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun hasCoarseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun hasForegroundLocation(context: Context): Boolean =
        hasFineLocation(context) || hasCoarseLocation(context)

    fun hasBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasForegroundLocation(context)
        return hasForegroundLocation(context) &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Permissions the app should request together on first run. */
    fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

/**
 * Pure permission snapshot so UI decisions can be unit tested without Android.
 */
data class PermissionState(
    val foregroundGranted: Boolean,
    val notificationsGranted: Boolean,
    val backgroundGranted: Boolean,
) {
    val canTrack: Boolean get() = foregroundGranted

    val shouldOfferBackgroundGuide: Boolean
        get() = foregroundGranted && !backgroundGranted

    val shouldRequestNotifications: Boolean
        get() = foregroundGranted && !notificationsGranted

    companion object {
        fun from(context: Context): PermissionState = PermissionState(
            foregroundGranted = LocationPermissions.hasForegroundLocation(context),
            notificationsGranted = LocationPermissions.hasNotifications(context),
            backgroundGranted = LocationPermissions.hasBackgroundLocation(context),
        )
    }
}
