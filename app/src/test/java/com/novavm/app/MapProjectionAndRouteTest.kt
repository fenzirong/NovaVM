package com.novavm.app

import com.novavm.app.data.GeoPoint
import com.novavm.app.data.RouteLoopMode
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.VmInstanceManager
import com.novavm.app.location.GeoMath
import com.novavm.app.location.MapProjection
import com.novavm.app.location.MapTileSource
import com.novavm.app.location.RouteSimulator
import com.novavm.app.location.RouteStepState
import com.novavm.app.location.RouteStepper
import com.novavm.app.location.VirtualGpsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit coverage for the interactive map math, the geo helpers backing map
 * picking and the deterministic route stepper / route simulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapProjectionAndRouteTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        VmInstanceManager.resetForTests()
    }

    // --------------------------------------------------------- projections

    @Test
    fun `world pixel round trip is stable for every reference city`() {
        val cities = listOf(
            Triple("Beijing", 39.9042, 116.4074),
            Triple("San Francisco", 37.7749, -122.4194),
            Triple("Sydney", -33.8688, 151.2093)
        )
        listOf(3.0, 12.0, 16.0).forEach { zoom ->
            cities.forEach { (name, lat, lon) ->
                val (worldX, worldY) = MapProjection.latLonToWorldPixel(lat, lon, zoom)
                assertTrue("$name worldX must be positive at z$zoom", worldX > 0.0)
                assertTrue("$name worldY must be positive at z$zoom", worldY > 0.0)

                val back = MapProjection.worldPixelToLatLon(worldX, worldY, zoom)
                assertEquals("$name latitude at z$zoom", lat, back.latitude, 1e-8)
                assertEquals("$name longitude at z$zoom", lon, back.longitude, 1e-8)
            }
        }
    }

    @Test
    fun `world pixel projection matches the mercator world size`() {
        // Zoom 0: the whole world is exactly one 256px tile.
        val (x0, y0) = MapProjection.latLonToWorldPixel(0.0, 0.0, 0.0)
        assertEquals(128.0, x0, 1e-9)
        assertEquals(128.0, y0, 1e-9)
        assertEquals(256.0, MapProjection.worldSize(0.0), 1e-9)
        assertEquals(512.0, MapProjection.worldSize(1.0), 1e-9)
        assertEquals(16_777_216.0, MapProjection.worldSize(16.0), 1e-6)

        // Antimeridian corners of the world.
        val westEdge = MapProjection.latLonToWorldPixel(0.0, -180.0, 2.0)
        assertEquals(0.0, westEdge.first, 1e-9)
        // Longitude 180 wraps to -180, so it lands on the west edge again.
        val wrappedEdge = MapProjection.latLonToWorldPixel(0.0, 180.0, 2.0)
        assertEquals(0.0, wrappedEdge.first, 1e-9)
        val almostEast = MapProjection.latLonToWorldPixel(0.0, 179.0, 2.0)
        assertEquals(1024.0 * 359.0 / 360.0, almostEast.first, 1e-9)

        // Latitudes beyond the Mercator cutoff stay finite.
        val clamped = MapProjection.latLonToWorldPixel(89.0, 0.0, 5.0)
        assertTrue(clamped.second.isFinite())
    }

    @Test
    fun `screen projection and inverse agree across zoom levels`() {
        val centerLat = 39.9042
        val centerLon = 116.4074
        val width = 640f
        val height = 480f

        listOf(3.0, 12.0, 16.0).forEach { zoom ->
            val (centerX, centerY) = MapProjection.latLonToScreen(
                centerLat,
                centerLon,
                centerLat,
                centerLon,
                zoom,
                width,
                height
            )
            assertEquals("viewport center x at z$zoom", width / 2f, centerX, 1e-3f)
            assertEquals("viewport center y at z$zoom", height / 2f, centerY, 1e-3f)

            val samples = listOf(
                0f to 0f,
                120f to 90f,
                width / 2f to height / 2f,
                540f to 430f
            )
            samples.forEach { (sx, sy) ->
                val geo = MapProjection.screenToLatLon(
                    sx,
                    sy,
                    centerLat,
                    centerLon,
                    zoom,
                    width,
                    height
                )
                val (backX, backY) = MapProjection.latLonToScreen(
                    geo.latitude,
                    geo.longitude,
                    centerLat,
                    centerLon,
                    zoom,
                    width,
                    height
                )
                assertEquals("x round trip at z$zoom", sx, backX, 0.02f)
                assertEquals("y round trip at z$zoom", sy, backY, 0.02f)
            }
        }
    }

    @Test
    fun `screen projection respects viewport offsets and zoom direction`() {
        val centerLat = 37.7749
        val centerLon = -122.4194
        val width = 1080f
        val height = 640f

        val northOfCenter = MapProjection.latLonToScreen(
            centerLat + 0.01,
            centerLon,
            centerLat,
            centerLon,
            15.0,
            width,
            height
        )
        assertTrue("north must be above the center", northOfCenter.second < height / 2f)

        val eastOfCenter = MapProjection.latLonToScreen(
            centerLat,
            centerLon + 0.01,
            centerLat,
            centerLon,
            15.0,
            width,
            height
        )
        assertTrue("east must be right of the center", eastOfCenter.first > width / 2f)

        // Zooming in pushes the same offset further away in pixels.
        val near = MapProjection.latLonToScreen(
            centerLat,
            centerLon + 0.002,
            centerLat,
            centerLon,
            12.0,
            width,
            height
        )
        val far = MapProjection.latLonToScreen(
            centerLat,
            centerLon + 0.002,
            centerLat,
            centerLon,
            16.0,
            width,
            height
        )
        assertTrue("higher zoom must amplify offsets", far.first > near.first)
    }

    @Test
    fun `tile indices and tile urls follow the slippy tile scheme`() {
        assertEquals(0, MapProjection.tileIndexForWorldPixel(0.0))
        assertEquals(0, MapProjection.tileIndexForWorldPixel(255.99))
        assertEquals(1, MapProjection.tileIndexForWorldPixel(256.0))
        assertEquals(1, MapProjection.tileIndexForWorldPixel(511.9))
        assertEquals(4, MapProjection.tileIndexForWorldPixel(1024.0))

        val (worldX, _) = MapProjection.latLonToWorldPixel(39.9042, 116.4074, 12.0)
        assertEquals(
            (worldX / MapProjection.TILE_SIZE).toInt(),
            MapProjection.tileIndexForWorldPixel(worldX)
        )

        assertEquals(256, MapProjection.tileCount(8))
        assertEquals(7, MapProjection.maxTileIndex(3))
        assertEquals(7, MapProjection.wrapTileX(-1, 3))
        assertEquals(0, MapProjection.wrapTileX(8, 3))
        assertEquals(3, MapProjection.wrapTileX(11, 3))
        assertEquals(0, MapProjection.clampTileY(-5, 3))
        assertEquals(7, MapProjection.clampTileY(42, 3))
    }

    @Test
    fun `tile sources expose the documented url templates`() {
        assertEquals(
            "https://tile.openstreetmap.org/12/1024/2048.png",
            MapTileSource.OSM.tileUrl(x = 1024, y = 2048, z = 12)
        )
        val amap = MapTileSource.AMAP.tileUrl(x = 3, y = 4, z = 5)
        assertTrue(amap!!.startsWith("https://webrd01.is.autonavi.com/appmaptile?"))
        assertTrue(amap.contains("lang=zh_cn"))
        assertTrue(amap.contains("x=3&y=4&z=5"))
        assertNull(MapTileSource.OFFLINE_GRID.tileUrl(x = 0, y = 0, z = 0))
        assertFalse(MapTileSource.OFFLINE_GRID.requiresNetwork)
        assertTrue(MapTileSource.OSM.requiresNetwork)
        assertTrue(MapTileSource.AMAP.requiresNetwork)
        assertEquals("高德地图", MapTileSource.AMAP.label)
        assertEquals("离线网格", MapTileSource.OFFLINE_GRID.label)
    }

    // ------------------------------------------------------------- GeoMath

    @Test
    fun `bearing degrees resolves the four cardinal directions`() {
        assertEquals(0.0, GeoMath.bearingDegrees(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, GeoMath.bearingDegrees(0.0, 0.0, -1.0, 0.0), 1e-6)
        assertEquals(270.0, GeoMath.bearingDegrees(0.0, 0.0, 0.0, -1.0), 1e-6)

        // North-east at the equator is 45 degrees up to great-circle curvature.
        assertEquals(45.0, GeoMath.bearingDegrees(0.0, 0.0, 1.0, 1.0), 0.01)

        // Longitudes are compared across the antimeridian, not by raw sign.
        assertEquals(90.0, GeoMath.bearingDegrees(0.0, 179.0, 0.0, -179.0), 1e-4)

        // Real world pair: Beijing -> Shanghai points south-east.
        val beijingToShanghai = GeoMath.bearingDegrees(39.9042, 116.4074, 31.2397, 121.4998)
        assertTrue("BE->SH bearing was $beijingToShanghai", beijingToShanghai in 140.0..180.0)

        // Coincident points degenerate to a stable zero bearing.
        assertEquals(0.0, GeoMath.bearingDegrees(39.9042, 116.4074, 39.9042, 116.4074), 0.0)

        // Every bearing is normalized into 0..360.
        listOf(
            GeoMath.bearingDegrees(0.0, 0.0, -10.0, -10.0),
            GeoMath.bearingDegrees(51.5007, -0.1246, 48.8566, 2.3522)
        ).forEach { value ->
            assertTrue("bearing $value out of range", value >= 0.0 && value < 360.0)
        }
    }

    @Test
    fun `interpolate position walks great circles and clamps fractions`() {
        val from = GeoPoint(0.0, 0.0, altitude = 10.0, label = "A")
        val to = GeoPoint(10.0, 0.0, altitude = 110.0, label = "B")

        val middle = GeoMath.interpolatePosition(from, to, 0.5)
        assertEquals(5.0, middle.latitude, 1e-6)
        assertEquals(0.0, middle.longitude, 1e-9)
        assertEquals(60.0, middle.altitude, 1e-9)
        assertEquals("A", middle.label)

        assertEquals(from.latitude, GeoMath.interpolatePosition(from, to, 0.0).latitude, 0.0)
        assertEquals(to.latitude, GeoMath.interpolatePosition(from, to, 1.0).latitude, 0.0)
        assertEquals(
            "clamped below",
            from.latitude,
            GeoMath.interpolatePosition(from, to, -2.0).latitude,
            0.0
        )
        assertEquals(
            "clamped above",
            to.latitude,
            GeoMath.interpolatePosition(from, to, 3.0).latitude,
            0.0
        )

        // The interpolated point really lies on the route between both ends.
        val legMeters = GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val firstHalf = GeoMath.distanceMeters(from.latitude, from.longitude, middle.latitude, middle.longitude)
        assertEquals(legMeters / 2.0, firstHalf, 1.0)
    }

    @Test
    fun `total route distance sums every leg`() {
        assertEquals(0.0, GeoMath.totalRouteDistanceMeters(emptyList()), 0.0)
        assertEquals(0.0, GeoMath.totalRouteDistanceMeters(listOf(GeoPoint(1.0, 1.0))), 0.0)

        val start = GeoPoint(0.0, 0.0)
        val firstLeg = GeoMath.stepPosition(0.0, 0.0, 90.0, 1_000.0)
        val secondLeg = GeoMath.stepPosition(firstLeg.latitude, firstLeg.longitude, 0.0, 500.0)

        val oneLeg = GeoMath.totalRouteDistanceMeters(listOf(start, firstLeg))
        assertEquals(1_000.0, oneLeg, 1.0)

        val twoLegs = GeoMath.totalRouteDistanceMeters(listOf(start, firstLeg, secondLeg))
        assertEquals(1_500.0, twoLegs, 2.0)
    }

    @Test
    fun `sample loop route is a closed hexagon around the center`() {
        val centerLat = 39.9042
        val centerLon = 116.4074
        val radius = 300.0

        val route = GeoMath.generateSampleLoopRoute(centerLat, centerLon, radius)

        assertEquals(6, route.size)
        assertEquals(6, route.map { it.latitude to it.longitude }.toSet().size)
        route.forEachIndexed { index, point ->
            val fromCenter = GeoMath.distanceMeters(
                centerLat,
                centerLon,
                point.latitude,
                point.longitude
            )
            assertEquals("point ${index + 1} must sit on the radius", radius, fromCenter, 0.5)
            assertTrue("point ${index + 1} must carry a label", point.label.isNotBlank())
            assertTrue(point.latitude in -90.0..90.0)
            assertTrue(point.longitude in -180.0..180.0)
        }

        // A hexagon of chord length R yields five open legs, and the LOOP mode
        // closes the shape with a sixth leg back to the first waypoint.
        assertEquals(5 * radius, GeoMath.totalRouteDistanceMeters(route), 2.0)
        val closedRoute = route + route.first()
        assertEquals("closed hexagon perimeter", 6 * radius, GeoMath.totalRouteDistanceMeters(closedRoute), 2.0)

        // The default radius keeps the loop local and the call is deterministic.
        val defaultRoute = GeoMath.generateSampleLoopRoute(centerLat, centerLon)
        assertEquals(6, defaultRoute.size)
        assertEquals(route.map { it.latitude }, GeoMath.generateSampleLoopRoute(centerLat, centerLon).map { it.latitude })
        assertEquals(300.0, GeoMath.totalRouteDistanceMeters(defaultRoute) / 5.0, 1.0)
    }

    // -------------------------------------------------------- RouteStepper

    /** Two 0.001 degree legs along the equator, ~111.3 m each. */
    private fun testRoute(): List<GeoPoint> = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 0.001),
        GeoPoint(0.0, 0.002)
    )

    @Test
    fun `segment lengths match the summed route distance`() {
        val route = testRoute()
        val lengths = RouteStepper.segmentLengths(route)
        assertEquals(2, lengths.size)
        assertEquals(111.19, lengths[0], 0.5)
        assertEquals(lengths[0], lengths[1], 1e-6)
        assertEquals(lengths.sum(), RouteStepper.totalLength(route), 1e-9)
        assertTrue(RouteStepper.segmentLengths(emptyList()).isEmpty())
        assertTrue(RouteStepper.segmentLengths(listOf(GeoPoint(0.0, 0.0))).isEmpty())
    }

    @Test
    fun `once mode stops at the final waypoint`() {
        val route = testRoute()
        val total = RouteStepper.totalLength(route)

        // Half of the first leg: still moving, heading east.
        val (firstState, firstFix) = RouteStepper.advance(
            route,
            RouteStepState(),
            total / 4.0,
            RouteLoopMode.ONCE
        )
        assertFalse(firstState.finished)
        assertEquals(0, firstState.segmentIndex)
        assertEquals(90.0f, firstFix.bearingDegrees, 0.5f)
        assertEquals(0.25f, firstFix.progressFraction, 0.01f)

        // Overshooting the end parks the cursor on the last waypoint.
        val (endState, endFix) = RouteStepper.advance(
            route,
            firstState,
            total,
            RouteLoopMode.ONCE
        )
        assertTrue(endState.finished)
        assertEquals(route.last().latitude, endFix.point.latitude, 1e-9)
        assertEquals(route.last().longitude, endFix.point.longitude, 1e-9)
        assertEquals(1f, endFix.progressFraction, 1e-6f)
        assertEquals(total, endState.traveledMeters, 1.0)

        // A finished ONCE route never moves again.
        val (stillFinished, stillFix) = RouteStepper.advance(
            route,
            endState,
            1_000.0,
            RouteLoopMode.ONCE
        )
        assertTrue(stillFinished.finished)
        assertEquals(route.last().longitude, stillFix.point.longitude, 1e-9)
    }

    @Test
    fun `loop mode wraps seamlessly from the last waypoint to the first`() {
        val route = testRoute()
        val total = RouteStepper.totalLength(route)

        val (wrapped, fix) = RouteStepper.advance(
            route,
            RouteStepState(),
            total + 5.0,
            RouteLoopMode.LOOP
        )
        assertFalse("LOOP must never finish", wrapped.finished)
        assertTrue("LOOP must keep travelling forward", wrapped.forward)
        assertEquals(0, wrapped.segmentIndex)
        assertEquals(5.0, wrapped.segmentOffsetMeters, 1e-6)
        assertEquals(total + 5.0, wrapped.traveledMeters, 1e-6)
        assertEquals(90.0f, fix.bearingDegrees, 0.5f)
        assertTrue("wrapped fix must sit near the start", fix.point.longitude in 0.0..0.001)
        assertTrue(fix.progressFraction in 0f..0.1f)

        // One full extra lap resumes exactly at the route start.
        val (lap, lapFix) = RouteStepper.advance(
            route,
            RouteStepState(),
            total * 2.0,
            RouteLoopMode.LOOP
        )
        assertEquals(0, lap.segmentIndex)
        assertEquals(0.0, lap.segmentOffsetMeters, 1e-6)
        assertEquals(route.first().longitude, lapFix.point.longitude, 1e-9)
    }

    @Test
    fun `ping pong reverses direction and heading at both ends`() {
        val route = testRoute()
        val total = RouteStepper.totalLength(route)

        // Reaching the far end flips the cursor without leaving the route.
        val (reversed, reversedFix) = RouteStepper.advance(
            route,
            RouteStepState(),
            total,
            RouteLoopMode.PING_PONG
        )
        assertFalse(reversed.finished)
        assertFalse("PING_PONG must reverse at the end", reversed.forward)
        assertEquals(1, reversed.segmentIndex)
        assertEquals(0.0, reversed.segmentOffsetMeters, 1e-9)
        assertEquals(route.last().longitude, reversedFix.point.longitude, 1e-9)
        assertEquals(270.0f, reversedFix.bearingDegrees, 0.5f)
        assertEquals(1f, reversedFix.progressFraction, 0.001f)

        // Walking back west keeps the reverse heading and reduces progress.
        val (walkBack, walkBackFix) = RouteStepper.advance(
            route,
            reversed,
            10.0,
            RouteLoopMode.PING_PONG
        )
        assertTrue(walkBackFix.point.longitude < route.last().longitude)
        assertTrue(walkBackFix.point.longitude > route[1].longitude)
        assertEquals(270.0f, walkBackFix.bearingDegrees, 0.5f)
        assertTrue(walkBackFix.progressFraction < 1f)

        // Returning all the way back reverses again at the first waypoint.
        val (backHome, homeFix) = RouteStepper.advance(
            route,
            walkBack,
            total - 10.0,
            RouteLoopMode.PING_PONG
        )
        assertTrue("must head out again", backHome.forward)
        assertEquals(0, backHome.segmentIndex)
        assertEquals(0.0, backHome.segmentOffsetMeters, 1e-6)
        assertEquals(route.first().longitude, homeFix.point.longitude, 1e-6)
        assertEquals(90.0f, homeFix.bearingDegrees, 0.5f)

        // Progress seesaws between 0 and 1 instead of growing past 1.
        assertTrue(homeFix.progressFraction in 0f..1f)
    }

    @Test
    fun `route stepper tolerates degenerate input`() {
        val (emptyState, emptyFix) = RouteStepper.advance(
            emptyList(),
            RouteStepState(),
            10.0,
            RouteLoopMode.LOOP
        )
        assertTrue(emptyState.finished)
        assertEquals(0.0, emptyFix.point.latitude, 0.0)

        val single = listOf(GeoPoint(12.0, 34.0))
        val (singleState, singleFix) = RouteStepper.advance(
            single,
            RouteStepState(),
            10.0,
            RouteLoopMode.LOOP
        )
        assertTrue(singleState.finished)
        assertEquals(12.0, singleFix.point.latitude, 0.0)

        // Duplicate waypoints (zero-length legs) must not hang the stepper.
        val duplicate = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        val (dupState, dupFix) = RouteStepper.advance(
            duplicate,
            RouteStepState(),
            5.0,
            RouteLoopMode.LOOP
        )
        assertTrue(dupFix.point.longitude in 0.0..0.001)
        assertTrue(dupState.traveledMeters in 0.0..5.0)

        // A zero delta never moves nor finishes anything.
        val (idle, idleFix) = RouteStepper.advance(
            testRoute(),
            RouteStepState(),
            0.0,
            RouteLoopMode.ONCE
        )
        assertFalse(idle.finished)
        assertEquals(0.0, idleFix.point.longitude, 1e-9)
        assertEquals(0.0, idle.segmentOffsetMeters, 1e-9)

        // currentFix reports the cursor without consuming distance.
        val cursor = RouteStepState(segmentIndex = 0, segmentOffsetMeters = 50.0, traveledMeters = 50.0)
        val current = RouteStepper.currentFix(testRoute(), cursor, RouteLoopMode.LOOP)
        assertEquals(50.0f, current.progressFraction * RouteStepper.totalLength(testRoute()).toFloat(), 1.0f)
        assertTrue(current.point.longitude > 0.0)
    }

    @Test
    fun `playback progress follows the loop mode`() {
        val total = 1_000.0
        assertEquals(0.5f, RouteStepper.progressOf(500.0, total, RouteLoopMode.ONCE), 1e-6f)
        assertEquals(1f, RouteStepper.progressOf(2_000.0, total, RouteLoopMode.ONCE), 1e-6f)
        assertEquals(0.25f, RouteStepper.progressOf(1_250.0, total, RouteLoopMode.LOOP), 1e-6f)
        assertEquals(0.8f, RouteStepper.progressOf(800.0, total, RouteLoopMode.PING_PONG), 1e-6f)
        assertEquals(0.8f, RouteStepper.progressOf(1_200.0, total, RouteLoopMode.PING_PONG), 1e-6f)
        assertEquals(0.2f, RouteStepper.progressOf(1_800.0, total, RouteLoopMode.PING_PONG), 1e-6f)
        assertEquals(0f, RouteStepper.progressOf(100.0, 0.0, RouteLoopMode.LOOP), 0f)
    }

    // ------------------------------------------------------ model / engine

    @Test
    fun `virtual location json without loop mode keeps backward compatibility`() {
        val legacyJson = """
            {
              "enabled": true,
              "presetName": "北京 · 天安门",
              "latitude": 39.9042,
              "longitude": 116.4074,
              "altitude": 43.5,
              "accuracyMeters": 5.0,
              "speedMps": 1.4,
              "bearingDegrees": 0.0,
              "mode": "STATIC",
              "jitterEnabled": true,
              "routeWaypoints": []
            }
        """.trimIndent()

        val decoded = VirtualGpsEngine.json.decodeFromString<VirtualLocationConfig>(legacyJson)
        assertEquals(RouteLoopMode.LOOP, decoded.loopMode)
        assertEquals(39.9042, decoded.latitude, 0.0)
        assertTrue(decoded.enabled)

        val encoded = VirtualGpsEngine.json.encodeToString(VirtualLocationConfig.serializer(), decoded)
        assertTrue("loopMode must be serialized: $encoded", encoded.contains("\"loopMode\""))
        assertTrue(encoded.contains("LOOP"))

        val withPingPong = decoded.copy(
            loopMode = RouteLoopMode.PING_PONG,
            routeWaypoints = listOf(GeoPoint(39.9042, 116.4074), GeoPoint(39.9050, 116.4080))
        )
        val roundTripped = VirtualGpsEngine.json.decodeFromString<VirtualLocationConfig>(
            VirtualGpsEngine.json.encodeToString(VirtualLocationConfig.serializer(), withPingPong)
        )
        assertEquals(withPingPong, roundTripped)
        assertEquals(RouteLoopMode.PING_PONG, roundTripped.loopMode)
    }

    @Test
    fun `route simulator moves the persisted fix once per tick`() = runTest {
        val root = temp.newFolder("simulator")
        val repository = VmRepository(root)
        val manager = VmInstanceManager(
            repository = repository,
            capabilitiesProvider = { HostCapabilities.conservative() },
            virtualGps = VirtualGpsEngine(clock = { 0L })
        )
        val route = GeoMath.generateSampleLoopRoute(39.9042, 116.4074, 200.0)
        val created = manager.createProfile(
            name = "Route VM",
            virtualLocation = VirtualLocationConfig(
                enabled = true,
                latitude = 39.9042,
                longitude = 116.4074,
                speedMps = 2.0f,
                jitterEnabled = false,
                mode = VirtualLocationMode.ROUTE_WALK,
                routeWaypoints = route,
                loopMode = RouteLoopMode.LOOP
            )
        )
        val simulator = RouteSimulator(
            repository = repository,
            instanceManager = manager,
            scope = backgroundScope,
            tickMillis = 1_000L
        )

        assertTrue(simulator.start(created.id))
        runCurrent()
        assertTrue(simulator.playbackStates.value.getValue(created.id).isPlaying)

        advanceTimeBy(1_050L)
        runCurrent()
        val playback = simulator.playbackStates.value.getValue(created.id)
        assertEquals(2.0, playback.traveledMeters, 0.01)
        assertEquals(RouteLoopMode.LOOP, playback.loopMode)
        assertTrue(playback.totalMeters > 0.0)

        val moved = repository.get(created.id)!!.virtualLocation
        assertEquals(VirtualLocationMode.ROUTE_WALK, moved.mode)
        assertEquals(2.0f, moved.speedMps, 1e-6f)
        val travelled = GeoMath.distanceMeters(
            route.first().latitude,
            route.first().longitude,
            moved.latitude,
            moved.longitude
        )
        assertEquals("the fix must advance by speed x 1s", 2.0, travelled, 0.05)
        assertTrue(GeoMath.distanceMeters(39.9042, 116.4074, moved.latitude, moved.longitude) > 0.0)

        assertTrue(simulator.pause(created.id))
        assertFalse(simulator.playbackStates.value.getValue(created.id).isPlaying)

        assertTrue(simulator.stopAndReset(created.id))
        val reset = simulator.playbackStates.value.getValue(created.id)
        assertEquals(0.0, reset.traveledMeters, 1e-9)
        assertEquals(0f, reset.progressFraction, 1e-6f)
        assertFalse(reset.isPlaying)

        simulator.shutdown()
    }

    @Test
    fun `route simulator rejects routes with fewer than two waypoints`() = runTest {
        val root = temp.newFolder("simulator-empty")
        val repository = VmRepository(root)
        val manager = VmInstanceManager(
            repository = repository,
            capabilitiesProvider = { HostCapabilities.conservative() },
            virtualGps = VirtualGpsEngine(clock = { 0L })
        )
        val created = manager.createProfile(name = "No Route")
        val simulator = RouteSimulator(
            repository = repository,
            instanceManager = manager,
            scope = backgroundScope,
            tickMillis = 1_000L
        )

        assertFalse(simulator.start(created.id))
        assertNull(simulator.playbackStates.value[created.id])
        assertFalse(simulator.pause(created.id))
        simulator.shutdown()
    }

    @Test
    fun `route playback progress text is localized and complete`() {
        val state = com.novavm.app.location.RoutePlaybackState(
            vmId = "vm-1",
            isPlaying = true,
            currentPoint = GeoPoint(39.9042, 116.4074),
            bearingDegrees = 128.4f,
            speedMps = 1.4f,
            traveledMeters = 120.0,
            totalMeters = 480.0,
            progressFraction = 0.25f
        )
        assertEquals("已行进 120m / 总长 480m · 航向 128°", RouteSimulator.progressText(state))
        assertEquals(360.0, state.remainingMeters, 1e-6)
        assertTrue(state.hasRoute)
    }
}
