package com.novavm.app.overlay

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import com.novavm.app.engine.BootConsoleRenderer
import com.novavm.app.engine.BootProgression
import com.novavm.app.engine.BootStage
import com.novavm.app.engine.BootStageRunner
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.NativeEngine
import com.novavm.app.engine.RfbDisplayClient
import com.novavm.app.engine.advanceProgress
import com.novavm.app.engine.applyTo
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared rendering pipeline for both the fullscreen activity and the floating
 * window service.
 *
 * It owns a direct RGBA [ByteBuffer] that is painted by [BootConsoleRenderer]
 * (default, always available) or filled by a background [RfbDisplayClient] pump
 * once a QEMU guest exposes VNC on `127.0.0.1:<vncPort>`. Frames are pushed to
 * any attached [Surface] through [NativeEngine.blitFramebuffer], falling back to
 * a Canvas blit when the native library is unavailable.
 *
 * A daemon boot thread walks the structured [BootProgression] (Stage 1 -> Stage
 * 5) so the console never stays parked at `STAGE 1 / HOST PROBE`.
 */
class VmDisplayPipeline(
    val vmId: String,
    val vmName: String,
    val width: Int,
    val height: Int,
    val vncPort: Int,
    val targetFps: Int = 30,
    private val onLog: (String) -> Unit = {},
    private val capabilitiesProvider: () -> HostCapabilities = { HostCapabilities.conservative() }
) : Closeable {

    enum class Source { CONSOLE, RFB }

    private val frameIntervalMs = (1000L / targetFps.coerceIn(5, 120))
    private val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val rfbBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val canvasBitmap: Bitmap by lazy {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    private val canvasPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    val console: BootConsoleRenderer = BootConsoleRenderer(width, height)

    @Volatile var source: Source = Source.CONSOLE
        private set
    @Volatile var measuredFps: Int = 0
        private set
    @Volatile var attachedSurface: Surface? = null
        private set
    @Volatile var rfbConnected: Boolean = false
        private set
    @Volatile var rfbServerLabel: String = ""
        private set

    @Volatile private var running = false
    @Volatile private var rfbDirty = false
    @Volatile private var rfb: RfbDisplayClient? = null
    private var renderThread: Thread? = null
    private var rfbThread: Thread? = null
    private var bootThread: Thread? = null
    private var lastConsoleInputMs = 0L

    fun start() {
        if (running) return
        running = true
        synchronized(console) {
            console.setFooterHint("Tap to interact  |  VNC $HOST:$vncPort")
            console.setBootStage(BootStage.STAGE_1_HOST_PROBE, "host probe in progress")
            console.setStatus("BOOTING", "host probe in progress")
            console.appendLine("[ .. ] display pipeline starting for $vmName")
        }
        startRenderThread()
        startRfbPump()
        startBootSequence()
    }

    fun attach(surface: Surface?) {
        attachedSurface = surface
    }

    fun appendConsole(line: String) {
        synchronized(console) { console.appendLine(line) }
    }

    fun setConsoleStatus(status: String, detail: String) {
        synchronized(console) {
            console.setStatus(status, detail)
        }
    }

    /** Returns true when the pointer event was delivered to a live RFB guest. */
    fun sendPointer(buttonMask: Int, x: Int, y: Int): Boolean {
        val client = rfb
        if (client != null && client.isConnected) {
            return runCatching {
                val targetX = x.coerceIn(0, (client.serverWidth - 1).coerceAtLeast(0))
                val targetY = y.coerceIn(0, (client.serverHeight - 1).coerceAtLeast(0))
                client.sendPointerEvent(buttonMask, targetX, targetY)
                true
            }.getOrDefault(false)
        }
        // Console interaction: acknowledge taps so the fallback display feels live.
        if (buttonMask == 1) {
            val now = SystemClock.uptimeMillis()
            if (now - lastConsoleInputMs > 900L) {
                lastConsoleInputMs = now
                synchronized(console) {
                    console.appendLine("[ .. ] pointer ($x, $y): no RFB guest attached")
                    console.setStatus("INPUT ACTIVE", "touch captured at ($x, $y)")
                    // Progress only ever moves forward: once the console is
                    // READY (1.0f) a stray tap can never rewind it.
                    console.advanceProgress(0.04f)
                }
            }
        }
        return false
    }

    fun sendKey(keysym: Int): Boolean {
        val client = rfb ?: return false
        if (!client.isConnected) return false
        return runCatching {
            client.sendKeyPress(keysym)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------- threads

    private fun startRenderThread() {
        renderThread = Thread({ renderLoop() }, "NovaVM-Display").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
            start()
        }
    }

    private fun renderLoop() {
        var frames = 0
        var lastConsoleRenderMs = 0L
        var windowStart = SystemClock.uptimeMillis()
        while (running) {
            val frameStart = SystemClock.uptimeMillis()
            val surface = attachedSurface
            if (surface == null || !surface.isValid) {
                sleepQuietly(frameIntervalMs)
                continue
            }
            val liveRfb = rfb
            if (liveRfb != null && liveRfb.isConnected) {
                if (rfbDirty) {
                    rfbBuffer.position(0)
                    buffer.clear()
                    buffer.put(rfbBuffer)
                    buffer.position(0)
                    rfbDirty = false
                }
                source = Source.RFB
            } else {
                source = Source.CONSOLE
                // The fallback console is painted at a steady 30fps; the buffer
                // is still blitted at the requested frame rate. Progress and the
                // boot stage are owned by the boot sequence thread, never by the
                // painter (that stale `0.32f + …` bump used to rewind the bar).
                if (frameStart - lastConsoleRenderMs >= CONSOLE_FRAME_MS) {
                    lastConsoleRenderMs = frameStart
                    synchronized(console) { console.render(buffer, frameStart) }
                }
            }

            val attached = attachedSurface
            if (attached != null && attached.isValid) {
                blit(attached)
            }

            frames++
            val now = SystemClock.uptimeMillis()
            if (now - windowStart >= 1000L) {
                measuredFps = ((frames * 1000L) / (now - windowStart).coerceAtLeast(1L)).toInt()
                frames = 0
                windowStart = now
            }

            val spent = SystemClock.uptimeMillis() - frameStart
            val sleep = (frameIntervalMs - spent).coerceAtLeast(1L)
            runCatching { Thread.sleep(sleep) }
        }
    }

    private fun blit(surface: Surface) {
        buffer.position(0)
        if (NativeEngine.blitFramebuffer(surface, buffer, width, height)) {
            buffer.position(0)
            return
        }
        buffer.position(0)
        runCatching {
            canvasBitmap.copyPixelsFromBuffer(buffer)
            buffer.position(0)
            val canvas = surface.lockCanvas(null) ?: return
            try {
                canvas.drawBitmap(
                    canvasBitmap,
                    null,
                    Rect(0, 0, canvas.width, canvas.height),
                    canvasPaint
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure {
            onLog("canvas blit failed: ${it.message}")
        }
    }

    private fun startRfbPump() {
        rfbThread = Thread({ rfbLoop() }, "NovaVM-Rfb").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /**
     * Daemon boot-progression thread: walks Stage 1 -> Stage 5 without ever
     * blocking the caller of [start]. It is cancelled by [stop]/[close] through
     * the `running` flag plus a thread interrupt, and it upgrades straight to
     * [BootStage.STAGE_5_RFB_LIVE] when a guest is already connected.
     */
    private fun startBootSequence() {
        val capabilities = runCatching { capabilitiesProvider() }
            .getOrElse { HostCapabilities.conservative() }
        val progression = BootProgression(
            vncPort = vncPort,
            qemuBinaryPresent = capabilities.qemuBinaryPresent,
            hostSummary = capabilities.summary(),
            vmName = vmName,
            displayWidth = width,
            displayHeight = height
        )
        val runner = BootStageRunner(
            progression = progression,
            apply = { step ->
                // Check-and-apply in one lock: a live RFB guest outranks the
                // synthetic boot text, so a later stage step can never demote
                // STAGE 5 / RFB LIVE back to an earlier label.
                synchronized(console) {
                    if (console.currentBootStage != BootStage.STAGE_5_RFB_LIVE) {
                        step.applyTo(console)
                    }
                }
                onLog(
                    "boot ${step.stage.label} · ${(step.stage.defaultProgress * 100).toInt()}% · ${step.status}"
                )
            },
            sleep = { millis -> interruptibleSleep(millis) },
            cancelled = { !running },
            rfbServerLabel = {
                if (rfbConnected) {
                    rfbServerLabel.ifBlank { "RFB $HOST:$vncPort" }
                } else {
                    null
                }
            }
        )
        bootThread = Thread({ runner.run() }, "NovaVM-Boot").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /** Interruptible sleep that never swallows a cancellation interrupt. */
    private fun interruptibleSleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun rfbLoop() {
        while (running) {
            val client = rfb
            if (client == null) {
                if (!tryConnect()) {
                    sleepQuietly(4_000L)
                }
                continue
            }
            try {
                client.requestUpdate(incremental = rfbDirty)
                client.readUpdate()
                synchronized(rfbBuffer) {
                    rfbBuffer.position(0)
                    client.framebuffer.position(0)
                    rfbBuffer.put(client.framebuffer)
                    rfbBuffer.position(0)
                }
                rfbDirty = true
            } catch (error: Throwable) {
                onLog("RFB link lost: ${error.message ?: error.javaClass.simpleName}")
                rfbConnected = false
                rfbServerLabel = ""
                runCatching { client.close() }
                rfb = null
                // Keep the boot stage/progress untouched: the console falls back
                // to its last known state instead of rewinding to Stage 1.
                synchronized(console) {
                    console.setStatus("RFB LOST", "guest link dropped; console fallback")
                    console.appendLine("[FAIL] RFB link lost: ${error.message ?: error.javaClass.simpleName}")
                }
                sleepQuietly(3_000L)
            }
        }
    }

    private fun tryConnect(): Boolean {
        val probe = RfbDisplayClient(
            host = HOST,
            port = vncPort,
            width = width,
            height = height,
            connectTimeoutMs = 900,
            readTimeoutMs = 3_000
        )
        return try {
            probe.connect()
            rfb = probe
            rfbConnected = true
            rfbServerLabel = probe.serverName.ifBlank { "RFB ${probe.serverWidth}x${probe.serverHeight}" }
            val label = rfbServerLabel
            synchronized(console) {
                console.setBootStage(BootStage.STAGE_5_RFB_LIVE, "Connected to $label")
                console.setStatus("RFB LIVE", "Connected to $label")
                console.appendLine("[ OK ] RFB attached at $HOST:$vncPort")
            }
            onLog("attached to guest framebuffer at $HOST:$vncPort")
            rfbDirty = true
            true
        } catch (error: Throwable) {
            runCatching { probe.close() }
            false
        }
    }

    private fun sleepQuietly(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    /** Stops every daemon (boot sequence, render, RFB) and releases the surface. */
    fun stop() {
        running = false
        runCatching { rfb?.close() }
        rfb = null
        rfbConnected = false

        val boot = bootThread
        bootThread = null
        boot?.interrupt()
        runCatching { boot?.join(BOOT_JOIN_MS) }

        runCatching { renderThread?.join(600) }
        runCatching { rfbThread?.interrupt() }
        renderThread = null
        rfbThread = null
        attachedSurface = null
    }

    override fun close() {
        stop()
    }

    companion object {
        const val HOST = "127.0.0.1"
        private const val CONSOLE_FRAME_MS = 33L
        private const val BOOT_JOIN_MS = 800L

        /**
         * Keeps software console rendering smooth: scales any guest resolution
         * down to roughly [maxPixels] while preserving the aspect ratio.
         */
        fun fitDimensions(
            width: Int,
            height: Int,
            maxPixels: Int = 640 * 1024
        ): Pair<Int, Int> {
            val w = width.coerceIn(320, 4096)
            val h = height.coerceIn(320, 4096)
            val pixels = w.toLong() * h.toLong()
            if (pixels <= maxPixels) return w to h
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
            val scaledW = (w * scale).toInt().coerceIn(320, w)
            val scaledH = (h * scale).toInt().coerceIn(320, h)
            return scaledW to scaledH
        }
    }
}
