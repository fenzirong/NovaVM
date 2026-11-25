package com.novavm.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.GeoPoint
import com.novavm.app.location.MapProjection
import com.novavm.app.location.MapTileSource
import com.novavm.app.ui.theme.NovaPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a tap on the map does. */
enum class MapInteractionMode(val label: String) {
    /** Tap teleports the mock fix to the tapped coordinate. */
    PICK("选点定位"),

    /** Tap appends a route waypoint at the tapped coordinate. */
    ROUTE("规划路径")
}

/** A single cached slippy tile identity. */
internal data class MapTileKey(
    val source: MapTileSource,
    val z: Int,
    val x: Int,
    val y: Int
) {
    val cacheKey: String get() = "${source.name}/$z/$x/$y"

    fun url(): String? = source.tileUrl(x, y, z)
}

/** A tile that is currently visible, with its un-wrapped world tile index. */
internal data class VisibleTile(
    val key: MapTileKey,
    val tileX: Int,
    val tileY: Int,
    val level: Int
)

/**
 * In-memory raster tile cache. Bounded so long panning sessions cannot grow the
 * heap: ~96 tiles of 256x256 px are roughly 25 MB in the worst case.
 */
internal object MapTileCache {

    private const val MAX_TILES = 96

    val bitmaps: LruCache<String, ImageBitmap> = LruCache(MAX_TILES)

    /** Downloads + decodes one tile; `null` on any failure (grid fallback). */
    suspend fun load(source: MapTileSource, key: MapTileKey): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val url = key.url() ?: return@withContext null
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NovaVM/0.1")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
}

/** Camera of the interactive map; mutable so gestures never recompose the tree. */
internal class MapCameraState(
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Double
) {
    var latitude by mutableStateOf(initialLatitude)
    var longitude by mutableStateOf(initialLongitude)
    var zoom by mutableStateOf(initialZoom)

    fun recenter(lat: Double, lon: Double, minimumZoom: Double = 14.0) {
        latitude = lat
        longitude = lon
        if (zoom < minimumZoom) zoom = minimumZoom
    }
}

/**
 * Interactive slippy-tile map: Web Mercator projection, LRU bitmap cache and a
 * dark tactical lat/lon grid fallback whenever tiles are missing or disabled.
 */
@Composable
fun InteractiveGeoMap(
    modifier: Modifier = Modifier,
    resetKey: String,
    centerLat: Double,
    centerLon: Double,
    accuracyMeters: Float = 5.0f,
    routeWaypoints: List<GeoPoint> = emptyList(),
    currentPoint: GeoPoint? = null,
    bearingDegrees: Float = 0.0f,
    interactionMode: MapInteractionMode = MapInteractionMode.PICK,
    tileSource: MapTileSource = MapTileSource.AMAP,
    onInteractionModeChange: (MapInteractionMode) -> Unit = {},
    onTileSourceChange: (MapTileSource) -> Unit = {},
    onPickLocation: (GeoPoint) -> Unit = {},
    onAddWaypoint: (GeoPoint) -> Unit = {},
    mapHeight: Dp = 320.dp
) {
    val camera = remember(resetKey) {
        MapCameraState(
            initialLatitude = centerLat,
            initialLongitude = centerLon,
            initialZoom = 15.0
        )
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val tileBitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    val textMeasurer = rememberTextMeasurer()

    val visibleTiles = remember(
        camera.latitude,
        camera.longitude,
        camera.zoom,
        viewport.width,
        viewport.height,
        tileSource
    ) {
        computeVisibleTiles(
            camera.latitude,
            camera.longitude,
            camera.zoom,
            viewport.width,
            viewport.height,
            tileSource
        )
    }

    LaunchedEffect(visibleTiles, tileSource) {
        if (!tileSource.requiresNetwork) return@LaunchedEffect
        coroutineScope {
            visibleTiles.take(32).forEach { tile ->
                if (tileBitmaps.containsKey(tile.key.cacheKey)) return@forEach
                launch {
                    val cached = MapTileCache.bitmaps.get(tile.key.cacheKey)
                    if (cached != null) {
                        tileBitmaps[tile.key.cacheKey] = cached
                        return@launch
                    }
                    val loaded = MapTileCache.load(tileSource, tile.key) ?: return@launch
                    MapTileCache.bitmaps.put(tile.key.cacheKey, loaded)
                    tileBitmaps[tile.key.cacheKey] = loaded
                }
            }
        }
        // Drop bitmaps of tiles that scrolled far away; the LRU cache keeps
        // them hot without pinning unbounded heap memory per composition.
        if (tileBitmaps.size > 64) {
            val visibleKeys = visibleTiles.map { it.key.cacheKey }.toSet()
            tileBitmaps.keys.toList().forEach { key ->
                if (key !in visibleKeys) tileBitmaps.remove(key)
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "gps-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gps-pulse-alpha"
    )

    val position = currentPoint ?: GeoPoint(centerLat, centerLon)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(NovaPalette.Background)
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .onSizeChanged { viewport = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectTapGestures
                        val picked = MapProjection.screenToLatLon(
                            offset.x,
                            offset.y,
                            camera.latitude,
                            camera.longitude,
                            camera.zoom,
                            width,
                            height
                        )
                        when (interactionMode) {
                            MapInteractionMode.PICK -> onPickLocation(picked)
                            MapInteractionMode.ROUTE -> onAddWaypoint(picked)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectTransformGestures
                        var latitude = camera.latitude
                        var longitude = camera.longitude
                        var zoom = camera.zoom

                        if (pan.x != 0f || pan.y != 0f) {
                            val moved = MapProjection.screenToLatLon(
                                width / 2f - pan.x,
                                height / 2f - pan.y,
                                latitude,
                                longitude,
                                zoom,
                                width,
                                height
                            )
                            latitude = moved.latitude
                            longitude = moved.longitude
                        }
                        if (gestureZoom != 1f && gestureZoom > 0f) {
                            val anchor = MapProjection.screenToLatLon(
                                centroid.x,
                                centroid.y,
                                latitude,
                                longitude,
                                zoom,
                                width,
                                height
                            )
                            val nextZoom = MapProjection.clampZoom(zoom * gestureZoom)
                            val (anchorX, anchorY) = MapProjection.latLonToScreen(
                                anchor.latitude,
                                anchor.longitude,
                                latitude,
                                longitude,
                                nextZoom,
                                width,
                                height
                            )
                            val shifted = MapProjection.screenToLatLon(
                                width / 2f + (anchorX - centroid.x),
                                height / 2f + (anchorY - centroid.y),
                                latitude,
                                longitude,
                                nextZoom,
                                width,
                                height
                            )
                            latitude = shifted.latitude
                            longitude = shifted.longitude
                            zoom = nextZoom
                        }
                        camera.latitude = MapProjection.clampLat(latitude)
                        camera.longitude = MapProjection.normalizeLon(longitude)
                        camera.zoom = MapProjection.clampZoom(zoom)
                    }
                }
        ) {
            drawRect(color = NovaPalette.Background)

            val width = size.width
            val height = size.height
            val zoom = camera.zoom
            val level = zoom.toInt().coerceIn(3, 18)
            val tileScale = 2.0.pow(zoom - level)
            val tilePx = (MapProjection.TILE_SIZE * tileScale).toFloat()
            val (centerX, centerY) = MapProjection.latLonToWorldPixel(
                camera.latitude,
                camera.longitude,
                zoom
            )
            val originX = width / 2.0 - centerX
            val originY = height / 2.0 - centerY

            var drawnTiles = 0
            visibleTiles.forEach { tile ->
                val image = tileBitmaps[tile.key.cacheKey] ?: return@forEach
                val left = (originX + tile.tileX * MapProjection.TILE_SIZE * tileScale).toFloat()
                val top = (originY + tile.tileY * MapProjection.TILE_SIZE * tileScale).toFloat()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(
                        tilePx.roundToInt().coerceAtLeast(1),
                        tilePx.roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.Low
                )
                drawnTiles++
            }

            val needsGrid = tileSource == MapTileSource.OFFLINE_GRID ||
                drawnTiles < visibleTiles.size
            if (needsGrid) {
                drawTacticalGrid(
                    camera = camera,
                    zoom = zoom,
                    textMeasurer = textMeasurer,
                    alpha = if (drawnTiles == 0) 1f else 0.45f
                )
            }

            // Route polyline with a soft cyan glow, arrowheads and badges.
            val routeScreen = routeWaypoints.map { point ->
                val (x, y) = MapProjection.latLonToScreen(
                    point.latitude,
                    point.longitude,
                    camera.latitude,
                    camera.longitude,
                    zoom,
                    width,
                    height
                )
                Offset(x, y)
            }
            if (routeScreen.size >= 2) {
                val path = Path().apply {
                    routeScreen.forEachIndexed { index, offset ->
                        if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
                    }
                }
                drawPath(
                    path = path,
                    color = NovaPalette.Cyan.copy(alpha = 0.20f),
                    style = Stroke(width = 9f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = NovaPalette.Cyan,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
                for (index in 0 until routeScreen.size - 1) {
                    drawDirectionArrow(routeScreen[index], routeScreen[index + 1])
                }
            }
            routeScreen.forEachIndexed { index, offset ->
                drawWaypointBadge(
                    textMeasurer = textMeasurer,
                    center = offset,
                    label = "${index + 1}"
                )
            }

            // Live fix: accuracy halo, heading cone and a pulsing pin.
            val (markerX, markerY) = MapProjection.latLonToScreen(
                position.latitude,
                position.longitude,
                camera.latitude,
                camera.longitude,
                zoom,
                width,
                height
            )
            if (markerX > -80f && markerX < width + 80f && markerY > -80f && markerY < height + 80f) {
                val marker = Offset(markerX, markerY)
                val metersPerPixel = 156_543.03392 * cos(Math.toRadians(position.latitude)) /
                    2.0.pow(zoom)
                val haloRadius = (accuracyMeters / metersPerPixel.coerceAtLeast(1e-6))
                    .toFloat()
                    .coerceIn(10f, 70f)
                drawCircle(
                    color = NovaPalette.Cyan.copy(alpha = 0.10f),
                    radius = haloRadius,
                    center = marker
                )
                drawCircle(
                    color = NovaPalette.Cyan.copy(alpha = 0.28f),
                    radius = haloRadius,
                    center = marker,
                    style = Stroke(width = 1.2f)
                )
                drawArc(
                    color = NovaPalette.Cyan.copy(alpha = 0.22f),
                    startAngle = bearingDegrees - 26f,
                    sweepAngle = 52f,
                    useCenter = true,
                    topLeft = Offset(marker.x - 30f, marker.y - 30f),
                    size = Size(60f, 60f)
                )
                val pulseAlpha = 0.10f + 0.26f * pulse.value
                drawCircle(
                    color = NovaPalette.Cyan.copy(alpha = pulseAlpha),
                    radius = 10f + 8f * pulse.value,
                    center = marker
                )
                drawCircle(color = NovaPalette.Cyan, radius = 5.5f, center = marker)
                drawCircle(
                    color = NovaPalette.Background,
                    radius = 2.2f,
                    center = marker
                )
            }
        }

        // --------------------------------------------------------- overlays
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(NovaPalette.Surface.copy(alpha = 0.88f))
                .border(1.dp, NovaPalette.Border.copy(alpha = 0.8f), RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MapInteractionMode.entries.forEach { mode ->
                MapPill(
                    label = mode.label,
                    selected = interactionMode == mode,
                    accent = if (mode == MapInteractionMode.PICK) NovaPalette.Cyan else NovaPalette.Violet
                ) { onInteractionModeChange(mode) }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(NovaPalette.Surface.copy(alpha = 0.88f))
                .border(1.dp, NovaPalette.Border.copy(alpha = 0.8f), RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MapTileSource.entries.forEach { source ->
                MapPill(
                    label = source.label,
                    selected = tileSource == source,
                    accent = NovaPalette.Amber
                ) { onTileSourceChange(source) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MapSquareButton("+") {
                camera.zoom = MapProjection.clampZoom(camera.zoom + 1.0)
            }
            MapSquareButton("-") {
                camera.zoom = MapProjection.clampZoom(camera.zoom - 1.0)
            }
            MapSquareButton("定位") {
                camera.recenter(centerLat, centerLon)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NovaPalette.Background.copy(alpha = 0.78f))
                .border(1.dp, NovaPalette.Cyan.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 5.dp)
        ) {
            Text(
                text = "纬度 ${formatLatLon(camera.latitude)} / 经度 ${formatLatLon(camera.longitude)}",
                color = NovaPalette.Cyan,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "缩放级别 ${String.format(Locale.US, "%.1f", camera.zoom)} · ${tileSource.label}",
                color = NovaPalette.TextMuted,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ------------------------------------------------------------------ tiles

/** Visible tile list for the current camera and viewport. */
internal fun computeVisibleTiles(
    latitude: Double,
    longitude: Double,
    zoom: Double,
    widthPx: Int,
    heightPx: Int,
    source: MapTileSource
): List<VisibleTile> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val level = zoom.toInt().coerceIn(3, 18)
    val scale = 2.0.pow(zoom - level)
    val (centerX, centerY) = MapProjection.latLonToWorldPixel(latitude, longitude, level.toDouble())
    val halfWidth = (widthPx / 2.0) / scale
    val halfHeight = (heightPx / 2.0) / scale
    val minX = floor((centerX - halfWidth) / MapProjection.TILE_SIZE).toInt()
    val maxX = floor((centerX + halfWidth) / MapProjection.TILE_SIZE).toInt()
    val minY = floor((centerY - halfHeight) / MapProjection.TILE_SIZE).toInt()
    val maxY = floor((centerY + halfHeight) / MapProjection.TILE_SIZE).toInt()

    val tiles = ArrayList<VisibleTile>((maxX - minX + 1) * (maxY - minY + 1))
    for (tileY in minY..maxY) {
        for (tileX in minX..maxX) {
            val clampedY = MapProjection.clampTileY(tileY, level)
            if (clampedY != tileY) continue
            tiles += VisibleTile(
                key = MapTileKey(
                    source = source,
                    z = level,
                    x = MapProjection.wrapTileX(tileX, level),
                    y = clampedY
                ),
                tileX = tileX,
                tileY = tileY,
                level = level
            )
        }
    }
    return tiles
}

// --------------------------------------------------------------- drawing

/** Crisp dark tactical grid with lat/lon coordinate labels. */
private fun DrawScope.drawTacticalGrid(
    camera: MapCameraState,
    zoom: Double,
    textMeasurer: TextMeasurer,
    alpha: Float
) {
    val step = 88f
    val minor = NovaPalette.Cyan.copy(alpha = 0.06f * alpha)
    val major = NovaPalette.Cyan.copy(alpha = 0.14f * alpha)
    val labelStyle = TextStyle(
        color = NovaPalette.TextMuted.copy(alpha = 0.9f * alpha),
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace
    )
    var index = 0
    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = if (index % 4 == 0) major else minor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
        if (index % 2 == 0 && index > 0) {
            val lon = MapProjection.screenToLatLon(
                x,
                size.height / 2f,
                camera.latitude,
                camera.longitude,
                zoom,
                size.width,
                size.height
            ).longitude
            drawText(
                textMeasurer = textMeasurer,
                text = formatLatLon(lon),
                topLeft = Offset(x + 2f, size.height - 13f),
                style = labelStyle
            )
        }
        x += step
        index++
    }
    index = 0
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = if (index % 4 == 0) major else minor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
        if (index % 2 == 0 && index > 0) {
            val lat = MapProjection.screenToLatLon(
                size.width / 2f,
                y,
                camera.latitude,
                camera.longitude,
                zoom,
                size.width,
                size.height
            ).latitude
            drawText(
                textMeasurer = textMeasurer,
                text = formatLatLon(lat),
                topLeft = Offset(3f, y + 1f),
                style = labelStyle
            )
        }
        y += step
        index++
    }
}

private fun DrawScope.drawDirectionArrow(from: Offset, to: Offset) {
    val deltaX = to.x - from.x
    val deltaY = to.y - from.y
    val length = kotlin.math.hypot(deltaX, deltaY)
    if (length < 34f) return
    val angle = atan2(deltaY, deltaX)
    val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
    val arrow = 5.5f
    val tip = Offset(mid.x + cos(angle) * arrow, mid.y + sin(angle) * arrow)
    val left = Offset(
        mid.x + cos(angle + 2.6f) * arrow,
        mid.y + sin(angle + 2.6f) * arrow
    )
    val right = Offset(
        mid.x + cos(angle - 2.6f) * arrow,
        mid.y + sin(angle - 2.6f) * arrow
    )
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path = path, color = NovaPalette.Cyan.copy(alpha = 0.95f))
}

private fun DrawScope.drawWaypointBadge(
    textMeasurer: TextMeasurer,
    center: Offset,
    label: String
) {
    drawCircle(color = NovaPalette.SurfaceElevated.copy(alpha = 0.96f), radius = 8.5f, center = center)
    drawCircle(
        color = NovaPalette.Cyan,
        radius = 8.5f,
        center = center,
        style = Stroke(width = 1.4f)
    )
    val layout = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = NovaPalette.Cyan,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - layout.size.height / 2f
        )
    )
}

// --------------------------------------------------------------- controls

@Composable
private fun MapPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) Color(0xFF04121C) else NovaPalette.TextSecondary,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun MapSquareButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(NovaPalette.SurfaceElevated.copy(alpha = 0.94f))
            .border(1.dp, NovaPalette.Cyan.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = NovaPalette.Cyan,
            fontSize = if (label.length > 1) 8.sp else 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------- helpers

private fun formatLatLon(value: Double): String =
    String.format(Locale.US, "%.5f", value)
