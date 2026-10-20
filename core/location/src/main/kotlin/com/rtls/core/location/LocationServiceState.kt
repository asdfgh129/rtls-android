package com.rtls.core.location

import com.rtls.core.model.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingStatus {
    STOPPED,
    RUNNING,
    ERROR,
}

sealed interface LocationServiceError {
    data object MissingPermission : LocationServiceError
    data object NoAvailableSource : LocationServiceError
    data class SourceFailed(val message: String) : LocationServiceError
}

/**
 * Process-wide observable state for the foreground service so the Compose layer
 * can render status without binding to the service.
 */
object LocationServiceState {

    private val _status = MutableStateFlow(TrackingStatus.STOPPED)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val _sample = MutableStateFlow<LocationSample?>(null)
    val sample: StateFlow<LocationSample?> = _sample.asStateFlow()

    private val _sourceId = MutableStateFlow<String?>(null)
    val sourceId: StateFlow<String?> = _sourceId.asStateFlow()

    private val _error = MutableStateFlow<LocationServiceError?>(null)
    val error: StateFlow<LocationServiceError?> = _error.asStateFlow()

    fun onStarted(sourceId: String) {
        _sourceId.value = sourceId
        _error.value = null
        _status.value = TrackingStatus.RUNNING
    }

    fun onSample(sample: LocationSample) {
        _sample.value = sample
        _error.value = null
        _status.value = TrackingStatus.RUNNING
    }

    fun onError(error: LocationServiceError) {
        _error.value = error
        _status.value = TrackingStatus.ERROR
    }

    fun onStopped() {
        _sourceId.value = null
        _status.value = TrackingStatus.STOPPED
    }

    fun clearError() {
        _error.value = null
    }
}
