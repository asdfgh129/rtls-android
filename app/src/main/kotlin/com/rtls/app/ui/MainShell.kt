package com.rtls.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtls.app.AppContainer
import com.rtls.app.R
import com.rtls.app.ui.components.StatusDot
import com.rtls.app.ui.screens.FriendsScreen
import com.rtls.app.ui.screens.MapScreen
import com.rtls.app.ui.screens.SettingsScreen
import com.rtls.core.location.LocationForegroundService
import com.rtls.core.location.LocationServiceError
import com.rtls.core.location.LocationServiceState
import com.rtls.core.location.PermissionState
import com.rtls.core.location.TrackingStatus
import kotlinx.coroutines.launch

private enum class Tab(
    val labelRes: Int,
    val icon: ImageVector,
) {
    MAP(R.string.nav_map, Icons.Filled.Place),
    FRIENDS(R.string.nav_friends, Icons.Filled.Person),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    container: AppContainer,
    permissions: PermissionState,
    onRequestPermissions: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by LocationServiceState.status.collectAsStateWithLifecycle()
    val sample by LocationServiceState.sample.collectAsStateWithLifecycle()
    val sourceId by LocationServiceState.sourceId.collectAsStateWithLifecycle()
    val serviceError by LocationServiceState.error.collectAsStateWithLifecycle()
    val useFake by container.preferences.useFakeLocation.collectAsStateWithLifecycle(initialValue = false)

    var selectedTab by rememberSaveable { mutableStateOf(Tab.MAP) }

    val errorText = when (val error = serviceError) {
        LocationServiceError.MissingPermission -> stringResource(R.string.error_location_permission)
        LocationServiceError.NoAvailableSource -> stringResource(R.string.error_no_location_source)
        is LocationServiceError.SourceFailed -> error.message
        null -> null
    }

    val startTracking: (Boolean) -> Unit = { useFakeLocation ->
        if (permissions.foregroundGranted) {
            val intent = LocationForegroundService.startIntent(
                context,
                useFake = useFakeLocation,
            )
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                LocationServiceState.onError(
                    LocationServiceError.SourceFailed(
                        error.message ?: context.getString(R.string.error_service_start),
                    ),
                )
            }
        }
    }
    val stopTracking = {
        context.stopService(Intent(context, LocationForegroundService::class.java))
        LocationServiceState.onStopped()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "RTLS",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(12.dp))
                        StatusPill(status)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                Tab.MAP -> MapScreen(
                    sample = sample,
                    status = status,
                    sourceId = sourceId,
                    useFake = useFake,
                    allowFakeToggle = com.rtls.app.BuildConfig.ALLOW_FAKE_LOCATION,
                    errorText = errorText,
                    permissions = permissions,
                    onToggleFake = { enabled ->
                        scope.launch {
                            container.preferences.setUseFakeLocation(enabled)
                            if (LocationServiceState.status.value == TrackingStatus.RUNNING) {
                                startTracking(enabled)
                            }
                        }
                    },
                    onStart = { startTracking(useFake) },
                    onStop = stopTracking,
                    onRequestNotifications = onRequestNotifications,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Tab.FRIENDS -> FriendsScreen(modifier = Modifier.padding(top = 4.dp))

                Tab.SETTINGS -> SettingsScreen(
                    permissions = permissions,
                    onRequestPermissions = onRequestPermissions,
                    onRequestNotifications = onRequestNotifications,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: TrackingStatus) {
    val (label, color) = when (status) {
        TrackingStatus.RUNNING -> "共享中" to MaterialTheme.colorScheme.primary
        TrackingStatus.ERROR -> "异常" to MaterialTheme.colorScheme.error
        TrackingStatus.STOPPED -> "未共享" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = color, pulsing = status == TrackingStatus.RUNNING)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}
