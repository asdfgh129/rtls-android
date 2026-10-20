package com.rtls.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the selected [LocationSource] (ADR-007).
 *
 * Phase 0 keeps samples in [LocationServiceState] only. Phase 1 will forward
 * them to the offline queue and WebSocket client.
 */
class LocationForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sourceProvider by lazy { LocationSourceProvider(applicationContext) }

    private var activeSource: LocationSource? = null
    private var collectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!LocationPermissions.hasForegroundLocation(this)) {
                    LocationServiceState.onError(LocationServiceError.MissingPermission)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                startTracking(
                    useFake = intent.getBooleanExtra(EXTRA_USE_FAKE, false),
                    intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS),
                )
            }

            else -> {
                if (activeSource == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        LocationServiceState.onStopped()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTracking(useFake: Boolean, intervalMs: Long) {
        collectionJob?.cancel()
        activeSource?.stop()
        activeSource = null

        val source = sourceProvider.select(allowFake = useFake && LocationServiceFlags.allowFakeLocation)
        if (source == null) {
            LocationServiceState.onError(LocationServiceError.NoAvailableSource)
            stopSelf()
            return
        }

        activeSource = source
        LocationServiceState.onStarted(source.id)
        val config = LocationConfig(intervalMs = intervalMs)
        collectionJob = scope.launch {
            source.start(config)
                .catch { error ->
                    LocationServiceState.onError(
                        LocationServiceError.SourceFailed(
                            error.message ?: error::class.java.simpleName,
                        ),
                    )
                }
                .collect { sample ->
                    LocationServiceState.onSample(sample)
                }
        }
    }

    private fun stopTracking() {
        collectionJob?.cancel()
        collectionJob = null
        activeSource?.stop()
        activeSource = null
        LocationServiceState.onStopped()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_notification)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(getString(R.string.location_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.location_notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.location_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.rtls.core.location.action.START"
        const val ACTION_STOP = "com.rtls.core.location.action.STOP"
        const val EXTRA_USE_FAKE = "com.rtls.core.location.extra.USE_FAKE"
        const val EXTRA_INTERVAL_MS = "com.rtls.core.location.extra.INTERVAL_MS"
        const val DEFAULT_INTERVAL_MS = 10_000L

        private const val CHANNEL_ID = "rtls_location_sharing"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(
            context: Context,
            useFake: Boolean = false,
            intervalMs: Long = DEFAULT_INTERVAL_MS,
        ): Intent = Intent(context, LocationForegroundService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_USE_FAKE, useFake)
            putExtra(EXTRA_INTERVAL_MS, intervalMs)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}

/**
 * Feature flags injected by the app module at startup. Fake location must stay
 * disabled in release builds.
 */
object LocationServiceFlags {
    @Volatile
    var allowFakeLocation: Boolean = false
}
