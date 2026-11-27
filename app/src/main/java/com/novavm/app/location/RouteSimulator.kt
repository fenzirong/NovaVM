package com.novavm.app.location

import com.novavm.app.data.GeoPoint
import com.novavm.app.data.RouteLoopMode
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.VmInstanceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

/**
 * Immutable position snapshot produced while a route is being replayed.
 * [progressFraction] is `0.0..1.0` and already accounts for [loopMode].
 */
data class RouteFix(
    val point: GeoPoint,
    val bearingDegrees: Float = 0f,
    val progressFraction: Float = 0f
)

/**
 * Deterministic, clock-free cursor over a polyline.
 *
 * The whole route playback logic lives here so unit tests can drive it with
 * explicit `deltaMeters` steps and no coroutine timing at all.
 */
data class RouteStepState(
    val segmentIndex: Int = 0,
    val segmentOffsetMeters: Double = 0.0,
    val forward: Boolean = true,
    val traveledMeters: Double = 0.0,
    val finished: Boolean = false
)

/**
 * Pure routes stepper: consumes distance along [GeoPoint] waypoints and
 * supports one-shot, looping and ping-pong playback.
 */
object RouteStepper {

    private const val EPSILON = 1e-9

    /** Length of each leg between consecutive waypoints (empty for < 2 points). */
    fun segmentLengths(waypoints: List<GeoPoint>): List<Double> {
        if (waypoints.size < 2) return emptyList()
        return (0 until waypoints.size - 1).map { index ->
            val from = waypoints[index]
            val to = waypoints[index + 1]
            GeoMath.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        }
    }

    /** Total polyline length in meters (0 for fewer than two waypoints). */
    fun totalLength(waypoints: List<GeoPoint>): Double = segmentLengths(waypoints).sum()

    /**
     * Advances [state] by [deltaMeters] and returns the new cursor plus the
     * interpolated fix. Negative / `NaN` deltas are treated as 0.
     */
    fun advance(
        waypoints: List<GeoPoint>,
        state: RouteStepState,
        deltaMeters: Double,
        loopMode: RouteLoopMode
    ): Pair<RouteStepState, RouteFix> {
        val lengths = segmentLengths(waypoints)
        val total = lengths.sum()
        if (waypoints.size < 2 || total <= EPSILON) {
            val point = waypoints.firstOrNull() ?: GeoPoint(0.0, 0.0)
            val finished = state.copy(finished = true)
            return finished to RouteFix(point, 0f, 1f)
        }

        val lastIndex = lengths.lastIndex
        var index = state.segmentIndex.coerceIn(0, lastIndex)
        var offset = state.segmentOffsetMeters.coerceIn(0.0, lengths[index])
        var forward = state.forward
        var traveled = state.traveledMeters
        var finished = state.finished
        var remaining = if (deltaMeters.isNaN()) 0.0 else deltaMeters.coerceAtLeast(0.0)

        // A finished ONCE route simply stays parked at its final waypoint.
        if (finished) {
            return state to fixAt(waypoints, lengths, index, lengths[index], forward, 1f)
        }

        var guard = 0
        while (remaining > EPSILON && guard < waypoints.size * 64) {
            guard++
            val length = lengths[index]
            if (length <= EPSILON) {
                val hop = nextSegment(index, forward, lastIndex, loopMode)
                if (hop == null) {
                    finished = true
                    break
                }
                index = hop.first
                forward = hop.second
                offset = 0.0
                continue
            }
            val available = (length - offset).coerceAtLeast(0.0)
            val consumed = min(remaining, available)
            offset += consumed
            remaining -= consumed
            traveled += consumed
            if (offset >= length - 1e-6) {
                offset = length
                val hop = nextSegment(index, forward, lastIndex, loopMode)
                if (hop == null) {
                    finished = true
                    break
                }
                index = hop.first
                forward = hop.second
                offset = 0.0
            } else {
                break
            }
        }

        val progress = progressOf(traveled, total, loopMode, finished)
        val next = RouteStepState(
            segmentIndex = index,
            segmentOffsetMeters = offset,
            forward = forward,
            traveledMeters = traveled,
            finished = finished
        )
        val fix = fixAt(waypoints, lengths, index, offset, forward, progress)
        return next to fix
    }

    /** Fix for the cursor without consuming any distance (used by pause/resume). */
    fun currentFix(
        waypoints: List<GeoPoint>,
        state: RouteStepState,
        loopMode: RouteLoopMode
    ): RouteFix {
        val lengths = segmentLengths(waypoints)
        val total = lengths.sum()
        if (waypoints.size < 2 || total <= EPSILON) {
            val point = waypoints.firstOrNull() ?: GeoPoint(0.0, 0.0)
            return RouteFix(point, 0f, if (state.finished) 1f else 0f)
        }
        val lastIndex = lengths.lastIndex
        val index = state.segmentIndex.coerceIn(0, lastIndex)
        val offset = if (state.finished) {
            lengths[index]
        } else {
            state.segmentOffsetMeters.coerceIn(0.0, lengths[index])
        }
        val progress = progressOf(state.traveledMeters, total, loopMode, state.finished)
        return fixAt(waypoints, lengths, index, offset, state.forward, progress)
    }

    /** `0.0..1.0` completion of the whole route for the given loop mode. */
    fun progressOf(
        traveledMeters: Double,
        totalMeters: Double,
        loopMode: RouteLoopMode,
        finished: Boolean = false
    ): Float {
        if (totalMeters <= EPSILON) return if (finished) 1f else 0f
        val raw = when (loopMode) {
            RouteLoopMode.ONCE -> (traveledMeters / totalMeters).coerceIn(0.0, 1.0)
            RouteLoopMode.LOOP -> (traveledMeters % totalMeters) / totalMeters
            RouteLoopMode.PING_PONG -> {
                val cycle = traveledMeters % (2.0 * totalMeters)
                if (cycle <= totalMeters) {
                    cycle / totalMeters
                } else {
                    (2.0 * totalMeters - cycle) / totalMeters
                }
            }
        }
        return raw.toFloat().coerceIn(0f, 1f)
    }

    private fun nextSegment(
        index: Int,
        forward: Boolean,
        lastIndex: Int,
        loopMode: RouteLoopMode
    ): Pair<Int, Boolean>? = if (forward) {
        when {
            index < lastIndex -> (index + 1) to true
            else -> when (loopMode) {
                RouteLoopMode.ONCE -> null
                RouteLoopMode.LOOP -> 0 to true
                RouteLoopMode.PING_PONG -> index to false
            }
        }
    } else {
        when {
            index > 0 -> (index - 1) to false
            else -> when (loopMode) {
                RouteLoopMode.ONCE -> null
                RouteLoopMode.LOOP -> lastIndex to true
                RouteLoopMode.PING_PONG -> index to true
            }
        }
    }

    private fun fixAt(
        waypoints: List<GeoPoint>,
        lengths: List<Double>,
        index: Int,
        offsetMeters: Double,
        forward: Boolean,
        progress: Float
    ): RouteFix {
        val safeIndex = index.coerceIn(0, lengths.lastIndex.coerceAtLeast(0))
        val start = if (forward) waypoints[safeIndex] else waypoints[safeIndex + 1]
        val end = if (forward) waypoints[safeIndex + 1] else waypoints[safeIndex]
        val length = lengths.getOrElse(safeIndex) { 0.0 }
        val fraction = if (length <= EPSILON) {
            0.0
        } else {
            (offsetMeters / length).coerceIn(0.0, 1.0)
        }
        val point = if (length <= EPSILON) start else GeoMath.interpolatePosition(start, end, fraction)
        val bearing = GeoMath.bearingDegrees(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude
        ).toFloat()
        return RouteFix(point, bearing, progress)
    }
}

/** Playback snapshot surfaced to the Compose UI and the display HUD. */
data class RoutePlaybackState(
    val vmId: String,
    val isPlaying: Boolean = false,
    val currentPoint: GeoPoint? = null,
    val bearingDegrees: Float = 0f,
    val speedMps: Float = 1.4f,
    val traveledMeters: Double = 0.0,
    val totalMeters: Double = 0.0,
    val progressFraction: Float = 0f,
    val activeSegmentIndex: Int = 0,
    val loopMode: RouteLoopMode = RouteLoopMode.LOOP
) {
    val hasRoute: Boolean get() = totalMeters > 0.0

    val remainingMeters: Double
        get() = (totalMeters * (1.0 - progressFraction.toDouble())).coerceAtLeast(0.0)
}

/**
 * Process-wide route playback engine.
 *
 * Ticks once per second while a VM is playing, moves the mock fix along the
 * profile's `routeWaypoints`, persists it and re-syncs the guest NMEA stream so
 * `novavm_gps.nmea` and the VM console stay live. Registered in
 * [com.novavm.app.NovaVmApp] so playback keeps running while the user switches
 * between the detail screen, fullscreen display and floating window.
 */
class RouteSimulator(
    private val repository: VmRepository,
    private val instanceManager: VmInstanceManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickMillis: Long = DEFAULT_TICK_MS,
    private val random: Random = Random.Default
) {

    private val lock = Any()
    private val steps = LinkedHashMap<String, RouteStepState>()
    private val jobs = LinkedHashMap<String, Job>()

    private val _playbackStates = MutableStateFlow<Map<String, RoutePlaybackState>>(emptyMap())
    val playbackStates: StateFlow<Map<String, RoutePlaybackState>> = _playbackStates.asStateFlow()

    fun playbackState(vmId: String): RoutePlaybackState? = _playbackStates.value[vmId]

    /** Starts (or resumes) playback for [vmId]. `false` when the route is unusable. */
    fun start(vmId: String): Boolean {
        val profile = repository.get(vmId) ?: return false
        val config = profile.virtualLocation
        val waypoints = config.routeWaypoints
        val total = GeoMath.totalRouteDistanceMeters(waypoints)
        if (waypoints.size < 2 || total <= 0.0) return false

        synchronized(lock) {
            jobs.remove(vmId)?.cancel()
            // A cursor left `finished` by a previous ONCE run would park the route
            // at its last waypoint forever: restarting must rewind it.
            val existing = steps[vmId]
            val step = if (existing == null || existing.finished) {
                RouteStepState().also { steps[vmId] = it }
            } else {
                existing
            }
            val fix = RouteStepper.currentFix(waypoints, step, config.loopMode)
            publish(playbackOf(profile, step, fix, isPlaying = true))
            jobs[vmId] = scope.launch { tick(vmId) }
        }
        return true
    }

    /** Stops ticking but keeps the cursor so [start] resumes from the same point. */
    fun pause(vmId: String): Boolean {
        val profile = repository.get(vmId) ?: return false
        val config = profile.virtualLocation
        val waypoints = config.routeWaypoints
        if (waypoints.size < 2 || GeoMath.totalRouteDistanceMeters(waypoints) <= 0.0) return false
        synchronized(lock) {
            // A missing job means playback already stopped (finished ONCE route, or
            // a ticker that removed itself). Publishing the paused snapshot is
            // still correct and keeps callers from mis-reporting "no route".
            jobs.remove(vmId)?.cancel()
            val step = steps.getOrPut(vmId) { RouteStepState() }
            val fix = RouteStepper.currentFix(waypoints, step, config.loopMode)
            publish(playbackOf(profile, step, fix, isPlaying = false))
        }
        return true
    }

    /** Stops ticking and rewinds the cursor to the first waypoint. */
    fun stopAndReset(vmId: String): Boolean {
        val profile = repository.get(vmId) ?: return false
        synchronized(lock) {
            jobs.remove(vmId)?.cancel()
            val step = RouteStepState()
            steps[vmId] = step
            val fix = RouteStepper.currentFix(
                profile.virtualLocation.routeWaypoints,
                step,
                profile.virtualLocation.loopMode
            )
            publish(playbackOf(profile, step, fix, isPlaying = false))
        }
        return true
    }

    /** Drops cached state (VM deleted / tests). */
    fun forget(vmId: String) {
        synchronized(lock) {
            jobs.remove(vmId)?.cancel()
            steps.remove(vmId)
            _playbackStates.value = _playbackStates.value - vmId
        }
    }

    /** Cancels every ticker (application shutdown / tests). */
    fun shutdown() {
        synchronized(lock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }

    // ----------------------------------------------------------------- tick

    /**
     * Playback ticker.
     *
     * The ticker unregisters itself in `finally`, but only if it is still the
     * *current* job for [vmId]: an older ticker finishing after a restart must not
     * delete the freshly registered job, otherwise `pause()` / `stopAndReset()`
     * find nothing to cancel and the tick loop keeps running forever.
     */
    private suspend fun tick(vmId: String) {
        val self = currentCoroutineContext()[Job]
        try {
            while (currentCoroutineContext().isActive) {
                delay(tickMillis)
                val keepRunning = try {
                    advanceOnce(vmId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // A single failed step (disk/engine hiccup) must not kill the
                    // ticker, but it must not swallow coroutine cancellation either.
                    false
                }
                if (!keepRunning) break
            }
        } finally {
            synchronized(lock) {
                if (self != null && jobs[vmId] === self) jobs.remove(vmId)
            }
        }
    }

    /**
     * One simulation step. Returns `true` while playback should continue.
     * Package-visible for unit tests that drive the engine without delays.
     */
    internal fun advanceOnce(vmId: String): Boolean {
        val profile = repository.get(vmId) ?: return false
        val config = profile.virtualLocation
        val waypoints = config.routeWaypoints
        val total = GeoMath.totalRouteDistanceMeters(waypoints)
        if (waypoints.size < 2 || total <= 0.0) return false

        val delta = config.speedMps.toDouble() * (tickMillis / 1000.0)
        val previous = synchronized(lock) { steps[vmId] ?: RouteStepState() }
        val (nextStep, rawFix) = RouteStepper.advance(waypoints, previous, delta, config.loopMode)
        val fix = if (config.jitterEnabled) jitter(rawFix) else rawFix

        synchronized(lock) { steps[vmId] = nextStep }

        val updatedConfig: VirtualLocationConfig = config.copy(
            enabled = true,
            latitude = fix.point.latitude,
            longitude = fix.point.longitude,
            bearingDegrees = fix.bearingDegrees,
            mode = VirtualLocationMode.ROUTE_WALK
        )
        runCatching { repository.update(profile.copy(virtualLocation = updatedConfig)) }
        runCatching { instanceManager.syncVirtualLocation(vmId, "route") }

        val state = playbackOf(
            profile.copy(virtualLocation = updatedConfig),
            nextStep,
            fix,
            isPlaying = !nextStep.finished
        )
        publish(state)
        return !nextStep.finished
    }

    private fun jitter(fix: RouteFix): RouteFix {
        val moved = GeoMath.applyJitter(
            fix.point.latitude,
            fix.point.longitude,
            MAX_JITTER_METERS,
            random
        )
        return fix.copy(
            point = fix.point.copy(latitude = moved.latitude, longitude = moved.longitude)
        )
    }

    private fun playbackOf(
        profile: VmProfile,
        step: RouteStepState,
        fix: RouteFix,
        isPlaying: Boolean
    ): RoutePlaybackState {
        val config = profile.virtualLocation
        return RoutePlaybackState(
            vmId = profile.id,
            isPlaying = isPlaying,
            currentPoint = fix.point,
            bearingDegrees = fix.bearingDegrees,
            speedMps = config.speedMps,
            traveledMeters = step.traveledMeters,
            totalMeters = GeoMath.totalRouteDistanceMeters(config.routeWaypoints),
            progressFraction = fix.progressFraction,
            activeSegmentIndex = step.segmentIndex,
            loopMode = config.loopMode
        )
    }

    private fun publish(state: RoutePlaybackState) {
        synchronized(lock) {
            _playbackStates.value = _playbackStates.value + (state.vmId to state)
        }
    }

    companion object {
        /** 1 Hz playback tick, as required by the UI spec. */
        const val DEFAULT_TICK_MS = 1_000L

        /** Micro drift applied on top of the exact route fix when jitter is on. */
        const val MAX_JITTER_METERS = 0.8

        fun progressText(state: RoutePlaybackState): String = String.format(
            Locale.US,
            "已行进 %.0fm / 总长 %.0fm · 航向 %.0f°",
            state.traveledMeters,
            state.totalMeters,
            state.bearingDegrees
        )
    }
}
