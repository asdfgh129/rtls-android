package com.rtls.core.model

/** Great-circle helpers used for filtering and UI summaries. */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Distance in meters between two points; inputs may use different CRSs. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val from = a.convertedTo(Crs.WGS84)
        val to = b.convertedTo(Crs.WGS84)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = lat2 - lat1
        val deltaLng = Math.toRadians(to.longitude - from.longitude)
        val haversine = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(deltaLng / 2) * kotlin.math.sin(deltaLng / 2)
        return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(kotlin.math.sqrt(haversine))
    }
}
