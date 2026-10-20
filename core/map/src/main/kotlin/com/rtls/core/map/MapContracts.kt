package com.rtls.core.map

import com.rtls.core.model.Crs
import com.rtls.core.model.GeoPoint

/** Map-layer marker. Only BD-09 points are accepted (ADR-006). */
data class MapMarker(
    val id: String,
    val point: GeoPoint,
    val label: String? = null,
    val stale: Boolean = false,
    val online: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "marker id must not be blank" }
        require(point.crs == Crs.BD09) { "map markers require BD09, was ${point.crs}" }
    }
}

data class MapPolyline(
    val id: String,
    val points: List<GeoPoint>,
) {
    init {
        require(points.all { it.crs == Crs.BD09 }) { "map polylines require BD09 points" }
    }
}

data class MapCamera(
    val center: GeoPoint,
    val zoom: Float = DEFAULT_ZOOM,
) {
    init {
        require(center.crs == Crs.BD09) { "map camera requires BD09, was ${center.crs}" }
        require(zoom in MIN_ZOOM..MAX_ZOOM) { "zoom must be in [$MIN_ZOOM, $MAX_ZOOM]" }
    }

    companion object {
        const val MIN_ZOOM = 3f
        const val MAX_ZOOM = 21f
        const val DEFAULT_ZOOM = 16f
    }
}

data class MapRenderState(
    val camera: MapCamera?,
    val markers: List<MapMarker> = emptyList(),
    val polylines: List<MapPolyline> = emptyList(),
)

/**
 * Rendering contract implemented by the Baidu adapter in Phase 1. Keeping this
 * interface SDK-free lets the app and tests avoid a hard Baidu dependency.
 */
interface RtlsMapController {
    fun render(state: MapRenderState)
    fun clear()
}

object MapCoordinates {
    fun fromWgs84(point: GeoPoint): GeoPoint = point.convertedTo(Crs.BD09)
}
