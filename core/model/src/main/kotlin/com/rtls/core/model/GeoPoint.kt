package com.rtls.core.model

/**
 * Coordinate reference system used across RTLS.
 *
 * The canonical storage/upload CRS is [WGS84]. Conversion to [BD09] happens only
 * in the map rendering layer (ADR-006).
 */
enum class Crs {
    WGS84,
    GCJ02,
    BD09,
}

/**
 * A coordinate that always carries its reference system. Bare lat/lng pairs are
 * forbidden in RTLS code (ADR-006).
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val crs: Crs,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be a finite value in [-90, 90], was $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be a finite value in [-180, 180], was $longitude"
        }
    }

    /** Returns this point converted to [target], converting only when necessary. */
    fun convertedTo(target: Crs): GeoPoint = when (target) {
        crs -> this
        Crs.WGS84 -> CoordinateTransforms.wgs84From(this)
        Crs.GCJ02 -> CoordinateTransforms.gcj02From(this)
        Crs.BD09 -> CoordinateTransforms.bd09From(this)
    }

    override fun toString(): String =
        "GeoPoint(lat=%.6f, lng=%.6f, crs=%s)".format(latitude, longitude, crs)
}
