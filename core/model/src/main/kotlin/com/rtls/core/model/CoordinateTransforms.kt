package com.rtls.core.model

/**
 * WGS-84 / GCJ-02 / BD-09 conversions.
 *
 * WGS-84 -> GCJ-02 uses the public "evil transform" approximation. GCJ-02 ->
 * WGS-84 is refined iteratively to keep round-trip error well below 1 m in
 * China. BD-09 conversion follows the standard Baidu algorithm.
 *
 * ADR-006 requires validating these values against Baidu's official tool with
 * known points before release (see Docs/spikes).
 */
object CoordinateTransforms {

    private const val PI = Math.PI
    private const val SEMI_MAJOR_AXIS = 6378245.0
    private const val ECC_SQUARED = 0.00669342162296594323
    private const val BD_FACTOR = 0.0065
    private const val BD_OFFSET = 0.006

    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.WGS84) { "expected WGS84, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.GCJ02)
        }

        val (deltaLat, deltaLng) = gcjDelta(point.latitude, point.longitude)
        return GeoPoint(
            latitude = point.latitude + deltaLat,
            longitude = point.longitude + deltaLng,
            crs = Crs.GCJ02,
        )
    }

    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.GCJ02) { "expected GCJ02, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.WGS84)
        }

        var latitude = point.latitude
        var longitude = point.longitude
        repeat(3) {
            val (deltaLat, deltaLng) = gcjDelta(latitude, longitude)
            latitude = point.latitude - deltaLat
            longitude = point.longitude - deltaLng
        }
        return geo(latitude, longitude, Crs.WGS84)
    }

    fun gcj02ToBd09(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.GCJ02) { "expected GCJ02, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.BD09)
        }
        val latitude = point.latitude
        val longitude = point.longitude
        val radius = kotlin.math.sqrt(longitude * longitude + latitude * latitude) +
            0.00002 * kotlin.math.sin(latitude * PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(latitude, longitude) +
            0.000003 * kotlin.math.cos(longitude * PI * 3000.0 / 180.0)
        return geo(
            latitude = radius * kotlin.math.sin(theta) + BD_OFFSET,
            longitude = radius * kotlin.math.cos(theta) + BD_FACTOR,
            crs = Crs.BD09,
        )
    }

    fun bd09ToGcj02(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.BD09) { "expected BD09, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.GCJ02)
        }
        val x = point.longitude - BD_FACTOR
        val y = point.latitude - BD_OFFSET
        val radius = kotlin.math.sqrt(x * x + y * y) -
            0.00002 * kotlin.math.sin(y * PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(y, x) -
            0.000003 * kotlin.math.cos(x * PI * 3000.0 / 180.0)
        return geo(
            latitude = radius * kotlin.math.sin(theta),
            longitude = radius * kotlin.math.cos(theta),
            crs = Crs.GCJ02,
        )
    }

    fun wgs84ToBd09(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.WGS84) { "expected WGS84, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.BD09)
        }
        return gcj02ToBd09(wgs84ToGcj02(point))
    }

    fun bd09ToWgs84(point: GeoPoint): GeoPoint {
        require(point.crs == Crs.BD09) { "expected BD09, was ${point.crs}" }
        if (outsideChina(point.latitude, point.longitude)) {
            return geo(point.latitude, point.longitude, Crs.WGS84)
        }
        return gcj02ToWgs84(bd09ToGcj02(point))
    }

    fun wgs84From(point: GeoPoint): GeoPoint = when (point.crs) {
        Crs.WGS84 -> point
        Crs.GCJ02 -> gcj02ToWgs84(point)
        Crs.BD09 -> bd09ToWgs84(point)
    }

    fun gcj02From(point: GeoPoint): GeoPoint = when (point.crs) {
        Crs.WGS84 -> wgs84ToGcj02(point)
        Crs.GCJ02 -> point
        Crs.BD09 -> bd09ToGcj02(point)
    }

    fun bd09From(point: GeoPoint): GeoPoint = when (point.crs) {
        Crs.WGS84 -> wgs84ToBd09(point)
        Crs.GCJ02 -> gcj02ToBd09(point)
        Crs.BD09 -> point
    }

    internal fun outsideChina(latitude: Double, longitude: Double): Boolean =
        longitude < 72.004 || longitude > 137.8347 ||
            latitude < 0.8293 || latitude > 55.8271

    private fun gcjDelta(latitude: Double, longitude: Double): Pair<Double, Double> {
        val x = longitude - 105.0
        val y = latitude - 35.0
        var deltaLat = transformLatitude(x, y)
        var deltaLng = transformLongitude(x, y)
        val radLat = latitude / 180.0 * PI
        var magic = kotlin.math.sin(radLat)
        magic = 1 - ECC_SQUARED * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        deltaLat = (deltaLat * 180.0) /
            ((SEMI_MAJOR_AXIS * (1 - ECC_SQUARED)) / (magic * sqrtMagic) * PI)
        deltaLng = (deltaLng * 180.0) /
            (SEMI_MAJOR_AXIS / sqrtMagic * kotlin.math.cos(radLat) * PI)
        return deltaLat to deltaLng
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * PI) + 20.0 * kotlin.math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(y * PI) + 40.0 * kotlin.math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * kotlin.math.sin(y / 12.0 * PI) + 320.0 * kotlin.math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * PI) + 20.0 * kotlin.math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(x * PI) + 40.0 * kotlin.math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * kotlin.math.sin(x / 12.0 * PI) + 300.0 * kotlin.math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun geo(latitude: Double, longitude: Double, crs: Crs): GeoPoint =
        GeoPoint(
            latitude = latitude.coerceIn(-90.0, 90.0),
            longitude = longitude.coerceIn(-180.0, 180.0),
            crs = crs,
        )
}
