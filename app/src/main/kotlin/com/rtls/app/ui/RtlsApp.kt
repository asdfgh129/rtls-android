package com.rtls.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rtls.app.AppContainer
import com.rtls.core.location.LocationPermissions
import com.rtls.core.location.PermissionState

@Composable
fun RtlsApp(container: AppContainer) {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(PermissionState.from(context)) }
    var hasRequested by rememberSaveable { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasRequested = true
        permissions = PermissionState.from(context)
    }
    val requestNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        permissions = PermissionState.from(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissions = PermissionState.from(context)
    }

    if (!permissions.canTrack) {
        PermissionOnboarding(
            showSettingsHint = hasRequested,
            onRequest = {
                requestPermissions.launch(LocationPermissions.runtimePermissions())
            },
            onOpenSettings = { context.openAppSettings() },
        )
        return
    }

    MainShell(
        container = container,
        permissions = permissions,
        onRequestPermissions = {
            requestPermissions.launch(LocationPermissions.runtimePermissions())
        },
        onRequestNotifications = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenSettings = { context.openAppSettings() },
    )
}
