package com.rtls.core.model

/** Location provider values allowed by the RTLS wire protocol (v1). */
enum class LocationProvider(val wireValue: String) {
    GPS("gps"),
    NETWORK("network"),
    FUSED("fused"),
    PASSIVE("passive"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): LocationProvider =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * A single location sample produced by a [LocationSource].
 *
 * The CRS follows the sample through the whole pipeline. Upload code must call
 * [toWgs84] and keep [sourceCrs] for auditing (ADR-006 / ADR-007).
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val crs: Crs,
    val accuracyMeters: Float,
    val provider: LocationProvider,
    val timestampMs: Long,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val isMock: Boolean = false,
    val batteryLevel: Float? = null,
    val sourceCrs: Crs = crs,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be a finite value in [-90, 90], was $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be a finite value in [-180, 180], was $longitude"
        }
        require(accuracyMeters.isFinite() && accuracyMeters >= 0f) {
            "accuracyMeters must be finite and >= 0, was $accuracyMeters"
        }
        require(timestampMs > 0) { "timestampMs must be positive, was $timestampMs" }
        require(speedMps == null || (speedMps.isFinite() && speedMps >= 0f)) {
            "speedMps must be finite and >= 0, was $speedMps"
        }
        require(bearingDegrees == null || (bearingDegrees.isFinite() && bearingDegrees in 0f..360f)) {
            "bearingDegrees must be in [0, 360], was $bearingDegrees"
        }
        require(batteryLevel == null || batteryLevel in 0f..1f) {
            "batteryLevel must be in [0, 1], was $batteryLevel"
        }
    }

    /** The sample as a CRS-tagged point. */
    val point: GeoPoint get() = GeoPoint(latitude, longitude, crs)

    /** Returns a copy normalized to WGS-84 for upload/storage. */
    fun toWgs84(): LocationSample {
        if (crs == Crs.WGS84) return this
        val normalized = point.convertedTo(Crs.WGS84)
        return copy(
            latitude = normalized.latitude,
            longitude = normalized.longitude,
            crs = Crs.WGS84,
            sourceCrs = sourceCrs,
        )
    }

    /**
     * ADR-006: keep inaccurate points in storage, but filter them when drawing
     * tracks. Default threshold is 100 m.
     */
    fun isAccurateEnough(thresholdMeters: Float = DEFAULT_DISPLAY_ACCURACY_METERS): Boolean =
        accuracyMeters <= thresholdMeters

    companion object {
        const val DEFAULT_DISPLAY_ACCURACY_METERS: Float = 100f
    }
}
