package com.novavm.app.location

import com.novavm.app.data.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Slippy-tile providers supported by [com.novavm.app.ui.components.InteractiveGeoMap]. */
enum class MapTileSource(val label: String) {
    /** AutoNavi (高德) raster tiles, Chinese labels. */
    AMAP("高德地图"),

    /** OpenStreetMap standard raster tiles. */
    OSM("OSM"),

    /** No network access at all: a dark tactical lat/lon grid is rendered. */
    OFFLINE_GRID("离线网格");

    /** `null` for [OFFLINE_GRID]: that source never performs a network fetch. */
    fun tileUrl(x: Int, y: Int, z: Int): String? = when (this) {
        AMAP -> "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8" +
            "&x=$x&y=$y&z=$z"
        OSM -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        OFFLINE_GRID -> null
    }

    /** Only the offline grid is usable without a network connection. */
    val requiresNetwork: Boolean get() = this != OFFLINE_GRID
}

/**
 * Pure Kotlin Web Mercator (EPSG:3857) projection used by the interactive map.
 *
 * Every function is side-effect free and free of Android types, so the whole
 * widget math is exercisable from plain JVM unit tests.
 */
object MapProjection {

    /** Edge length of one raster tile in pixels. */
    const val TILE_SIZE: Double = 256.0

    /** Lowest zoom the UI allows (world view). */
    const val MIN_ZOOM: Double = 3.0

    /** Highest zoom the UI allows (~building level). */
    const val MAX_ZOOM: Double = 18.0

    /** Latitude cutoff where the Mercator projection becomes infinite. */
    const val MAX_MERCATOR_LAT: Double = 85.05112878

    /** Width of the whole world in pixels at [zoom]. */
    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom.coerceIn(0.0, 22.0))

    /** Number of tiles along one axis at [zoom] (`2^zoom`). */
    fun tileCount(zoom: Int): Int = 1 shl zoom.coerceIn(0, 22)

    fun clampLat(lat: Double): Double = when {
        lat.isNaN() -> 0.0
        lat > MAX_MERCATOR_LAT -> MAX_MERCATOR_LAT
        lat < -MAX_MERCATOR_LAT -> -MAX_MERCATOR_LAT
        else -> lat
    }

    /** Normalizes longitude into `-180.0..180.0`. */
    fun normalizeLon(lon: Double): Double {
        if (lon.isNaN() || lon.isInfinite()) return 0.0
        var wrapped = (lon + 180.0) % 360.0
        if (wrapped < 0.0) wrapped += 360.0
        return wrapped - 180.0
    }

    /** Projects a WGS-84 coordinate to absolute world pixels at [zoom]. */
    fun latLonToWorldPixel(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
        val size = worldSize(zoom)
        val latitude = clampLat(lat)
        val x = (normalizeLon(lon) + 180.0) / 360.0 * size
        val latitudeRad = Math.toRadians(latitude)
        val y = (1.0 - ln(tan(latitudeRad) + 1.0 / cos(latitudeRad)) / PI) / 2.0 * size
        return x to y
    }

    /** Inverse of [latLonToWorldPixel]. */
    fun worldPixelToLatLon(worldX: Double, worldY: Double, zoom: Double): GeoPoint {
        val size = worldSize(zoom)
        val lon = worldX / size * 360.0 - 180.0
        val n = PI - 2.0 * PI * worldY / size
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(
            latitude = clampLat(lat),
            longitude = normalizeLon(lon)
        )
    }

    /**
     * Screen position of a coordinate for a camera centered on
     * ([centerLat], [centerLon]). The result is in viewport pixels with the
     * origin at the top-left corner.
     */
    fun latLonToScreen(
        lat: Double,
        lon: Double,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        viewportWidth: Float,
        viewportHeight: Float
    ): Pair<Float, Float> {
        val size = worldSize(zoom)
        val (pointX, pointY) = latLonToWorldPixel(lat, lon, zoom)
        val (centerX, centerY) = latLonToWorldPixel(centerLat, centerLon, zoom)
        var deltaX = pointX - centerX
        // Shortest path across the antimeridian.
        if (deltaX > size / 2.0) deltaX -= size
        if (deltaX < -size / 2.0) deltaX += size
        val screenX = viewportWidth / 2.0 + deltaX
        val screenY = viewportHeight / 2.0 + (pointY - centerY)
        return screenX.toFloat() to screenY.toFloat()
    }

    /** Inverse of [latLonToScreen]: viewport pixels to a WGS-84 coordinate. */
    fun screenToLatLon(
        screenX: Float,
        screenY: Float,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        viewportWidth: Float,
        viewportHeight: Float
    ): GeoPoint {
        val (centerX, centerY) = latLonToWorldPixel(centerLat, centerLon, zoom)
        val worldX = centerX + (screenX - viewportWidth / 2.0)
        val worldY = centerY + (screenY - viewportHeight / 2.0)
        return worldPixelToLatLon(worldX, worldY, zoom)
    }

    /** Tile index (`x` or `y`) that contains [worldCoord]. */
    fun tileIndexForWorldPixel(worldCoord: Double): Int = floor(worldCoord / TILE_SIZE).toInt()

    /** Tile `x` wrapped into `0 until 2^zoom`. */
    fun wrapTileX(index: Int, zoom: Int): Int {
        val count = tileCount(zoom)
        return ((index % count) + count) % count
    }

    /** Tile `y` clamped into `0 until 2^zoom` (no vertical wrap exists). */
    fun clampTileY(index: Int, zoom: Int): Int {
        val count = tileCount(zoom)
        return index.coerceIn(0, count - 1)
    }

    /** Largest tile index on one axis at [zoom]. */
    fun maxTileIndex(zoom: Int): Int = tileCount(zoom) - 1

    /**
     * Zoom honouring [MIN_ZOOM]..[MAX_ZOOM]; also used by the pinch gesture.
     * A `NaN` request falls back to [MIN_ZOOM].
     */
    fun clampZoom(zoom: Double): Double = when {
        zoom.isNaN() -> MIN_ZOOM
        zoom < MIN_ZOOM -> MIN_ZOOM
        zoom > MAX_ZOOM -> MAX_ZOOM
        else -> zoom
    }
}
