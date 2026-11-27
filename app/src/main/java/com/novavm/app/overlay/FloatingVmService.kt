package com.novavm.app.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novavm.app.NovaVmApp
import com.novavm.app.R
import com.novavm.app.data.VmProfile
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.RfbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Foreground service that renders a VM inside a draggable, resizable
 * `TYPE_APPLICATION_OVERLAY` window with a collapse-to-bubble mode.
 */
class FloatingVmService : Service() {

    private lateinit var windowManager: WindowManager
    private var app: NovaVmApp? = null
    private var profile: VmProfile? = null
    private var pipeline: VmDisplayPipeline? = null

    /**
     * Main-thread scope for the start sequence: repository/engine work hops to
     * [Dispatchers.IO] and comes back here to touch views and the notification.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dimmed = false

    /**
     * Set in [onDestroy].
     *
     * The start sequence resumes on a main-thread coroutine after IO hops; if the
     * service is torn down meanwhile the continuation would otherwise add a
     * window (or start a pipeline) onto an already destroyed service, leaving an
     * overlay that nobody can remove.
     */
    @Volatile
    private var destroyed = false

    /** Running bubble edge-snap animator; cancelled whenever the bubble goes away. */
    private var snapAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        app = application as NovaVmApp
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground first: a START_STICKY service that does disk IO
        // (or returns early) before startForeground() is eligible for an
        // "did not call startForeground" kill / ANR.
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("NovaVM", "正在启动悬浮窗…"))

        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // `Settings.canDrawOverlays` can throw on some OEM builds (broken AppOps
        // backend): treat any failure as "not granted" instead of crashing.
        if (!canDrawOverlays()) {
            Toast.makeText(this, "需要悬浮窗权限才能显示虚拟机窗口", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val application = app ?: return START_NOT_STICKY
        val requestedVmId = intent?.getStringExtra(EXTRA_VM_ID)

        scope.launch {
            // VM profile lookup hits the on-disk store; never on the main thread.
            val loaded = withContext(Dispatchers.IO) {
                val vmId = requestedVmId ?: application.vmRepository.loadAll().firstOrNull()?.id
                vmId?.let { application.vmRepository.get(it) }
            }
            if (destroyed) return@launch
            if (loaded == null) {
                Toast.makeText(this@FloatingVmService, "找不到虚拟机", Toast.LENGTH_SHORT).show()
                stopSelf()
                return@launch
            }
            profile = loaded
            application.setActiveVm(loaded.id)
            // Engine start probes QEMU/socket state: keep it off the main thread.
            withContext(Dispatchers.IO) {
                if (application.vmInstanceManager.state(loaded.id)?.isRunning != true) {
                    application.vmInstanceManager.start(loaded.id)
                }
            }
            if (destroyed) return@launch
            ensurePipeline(loaded)
            if (destroyed) {
                pipeline?.stopAsync()
                pipeline = null
                return@launch
            }
            showWindow(loaded)
            notifyStatus(loaded)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        snapAnimator?.cancel()
        snapAnimator = null
        removeWindow()
        removeBubble()
        pipeline?.stopAsync()
        pipeline = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)

    // ------------------------------------------------------------ pipeline

    private fun ensurePipeline(profile: VmProfile) {
        if (destroyed || pipeline != null) return
        val dims = VmDisplayPipeline.fitDimensions(profile.hardware.width, profile.hardware.height)
        val display = VmDisplayPipeline(
            vmId = profile.id,
            vmName = profile.displayName,
            width = dims.first,
            height = dims.second,
            vncPort = profile.vncPort,
            targetFps = profile.hardware.targetFps.coerceIn(24, 60),
            onLog = { line -> app?.vmInstanceManager?.appendConsole(profile.id, line) },
            capabilitiesProvider = { app?.hostCapabilities ?: HostCapabilities.conservative() }
        )
        display.console.setStatus("FLOATING WINDOW", "tap header to drag · mini nav below")
        display.console.setProgress(0.46f)
        display.console.footerHint = "Tap to interact  |  VNC 127.0.0.1:${profile.vncPort}"
        display.console.appendLine("[ OK ] floating display attached (TYPE_APPLICATION_OVERLAY)")
        display.start()
        pipeline = display
    }

    // -------------------------------------------------------------- window

    private fun showWindow(profile: VmProfile) {
        if (destroyed || windowView != null) return
        val root = buildWindowView(profile)
        val params = WindowManager.LayoutParams(
            dp(286),
            dp(460),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        windowParams = params
        windowView = root
        runCatching { windowManager.addView(root, params) }.onFailure {
            Toast.makeText(this, "无法显示悬浮窗：${it.message}", Toast.LENGTH_LONG).show()
            // Both references must be dropped together: a stale params object
            // makes the drag/resize handlers write into a window that no longer
            // exists.
            windowView = null
            windowParams = null
            stopSelf()
        }
    }

    private fun removeWindow() {
        detach(windowView, windowParams)
        windowView = null
        windowParams = null
    }

    /**
     * Remove [view] from the window manager exactly once.
     *
     * `removeView` throws `IllegalArgumentException` when the view was already
     * detached (double teardown, window manager restarted by the system), so the
     * detach state is checked first and the call is still guarded.
     */
    private fun detach(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) return
        if (!view.isAttachedToWindow) return
        runCatching { windowManager.removeView(view) }
            .onFailure { runCatching { windowManager.removeViewImmediate(view) } }
    }

    /** `updateViewLayout` on a detached view throws; every call site goes here. */
    private fun updateLayout(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) return
        if (!view.isAttachedToWindow) return
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun buildWindowView(profile: VmProfile): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xF20D1117.toInt(), 0.55f)
            clipToOutline = true
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            background = roundedBackground(0xF2161B22.toInt(), 0.9f)
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF22C55E.toInt())
            }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))

        val title = TextView(this).apply {
            text = profile.displayName
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 12f
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(headerButton("—", "最小化为气泡") { collapseToBubble() })
        header.addView(headerButton("⤢", "全屏显示") { openFullscreen() })
        header.addView(headerButton("◐", "切换透明度") { toggleOpacity() })
        header.addView(headerButton("✕", "关闭悬浮窗") { stopSelf() })

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        attachDrag(header, isBubble = false)

        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pipeline?.attach(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                pipeline?.attach(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline?.attach(null)
            }
        })
        surface.setOnTouchListener { view, event -> forwardTouch(view, event) }
        root.addView(
            surface,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val miniNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = roundedBackground(0xF20D1117.toInt(), 0.92f)
        }
        miniNav.addView(navButton("返回") { sendKeysym(RfbProtocol.KEYSYM_ESCAPE) })
        miniNav.addView(navButton("主页") { sendKeysym(0xFF50) })
        miniNav.addView(navButton("最近") { sendKeysym(0xFF67) })
        val spacer = View(this)
        miniNav.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val resize = navButton("◢") { toast("拖拽此处调整窗口大小") }
        attachResize(resize)
        miniNav.addView(resize)
        root.addView(miniNav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))

        return root
    }

    private fun headerButton(glyph: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            contentDescription = description
            setTextColor(0xFF9BA9BC.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

    private fun navButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(0xFF38BDF8.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }

    private fun roundedBackground(color: Int, alpha: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            setStroke(dp(1), 0x5538BDF8.toInt())
        }

    // ------------------------------------------------------------ gestures

    private fun attachDrag(view: View, isBubble: Boolean) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var downAt = 0L
        view.setOnTouchListener { _, event ->
            val params = if (isBubble) bubbleParams else windowParams
            if (params == null) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downRawX).toInt()
                    params.y = startY + (event.rawY - downRawY).toInt()
                    updateLayout(if (isBubble) bubbleView else windowView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    val clicked = moved < dp(6) && System.currentTimeMillis() - downAt < 350
                    if (isBubble) {
                        if (clicked) restoreFromBubble() else snapBubbleToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResize(handle: View) {
        var startWidth = 0
        var startHeight = 0
        var downRawX = 0f
        var downRawY = 0f
        handle.setOnTouchListener { _, event ->
            val params = windowParams ?: return@setOnTouchListener false
            val root = windowView ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startHeight = params.height
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startWidth + (event.rawX - downRawX).toInt()).coerceIn(dp(220), dp(720))
                    params.height = (startHeight + (event.rawY - downRawY).toInt()).coerceIn(dp(280), dp(920))
                    updateLayout(root, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun forwardTouch(view: View, event: MotionEvent): Boolean {
        val display = pipeline ?: return false
        if (view.width == 0 || view.height == 0) return false
        // Guest space while RFB is live, console space otherwise.
        val frameWidth = display.displayWidth.coerceAtLeast(1)
        val frameHeight = display.displayHeight.coerceAtLeast(1)
        val scale = minOf(
            view.width.toFloat() / frameWidth,
            view.height.toFloat() / frameHeight
        ).coerceAtLeast(0.01f)
        val offsetX = (view.width - frameWidth * scale) / 2f
        val offsetY = (view.height - frameHeight * scale) / 2f
        val fbX = ((event.x - offsetX) / scale).toInt().coerceIn(0, frameWidth - 1)
        val fbY = ((event.y - offsetY) / scale).toInt().coerceIn(0, frameHeight - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> display.sendPointer(1, fbX, fbY)
            MotionEvent.ACTION_MOVE -> display.sendPointer(1, fbX, fbY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> display.sendPointer(0, fbX, fbY)
            else -> Unit
        }
        return true
    }

    // -------------------------------------------------------------- bubble

    private fun collapseToBubble() {
        if (destroyed) return
        removeWindow()
        if (bubbleView != null) return
        val bubble = TextView(this).apply {
            text = "NV"
            setTextColor(0xFF04121C.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF38BDF8.toInt())
                setStroke(dp(2), 0xCC0D1117.toInt())
            }
            elevation = dp(10).toFloat()
        }
        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
        bubbleParams = params
        bubbleView = bubble
        val added = runCatching {
            windowManager.addView(bubble, params)
            attachDrag(bubble, isBubble = true)
        }.isSuccess
        if (!added) {
            // Keep the two fields consistent: a half-added bubble would make the
            // drag handler believe it owns a live window.
            bubbleView = null
            bubbleParams = null
            toast("无法显示悬浮球")
            return
        }
        toast("已收起为悬浮球，点击恢复窗口")
    }

    private fun restoreFromBubble() {
        removeBubble()
        val loaded = profile ?: return
        showWindow(loaded)
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        snapAnimator = null
        detach(bubbleView, bubbleParams)
        bubbleView = null
        bubbleParams = null
    }

    private fun snapBubbleToEdge() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (params.x + params.width / 2 < screenWidth / 2) dp(6) else screenWidth - params.width - dp(6)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180L
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                // The bubble may be removed mid-animation (restore / destroy).
                updateLayout(bubble, params)
            }
            start()
        }
    }

    // ------------------------------------------------------------- actions

    private fun toggleOpacity() {
        dimmed = !dimmed
        windowView?.alpha = if (dimmed) 0.68f else 1f
        toast(if (dimmed) "窗口半透明" else "窗口不透明")
    }

    private fun openFullscreen() {
        val loaded = profile ?: return
        startActivity(
            Intent(this, VmDisplayActivity::class.java)
                .putExtra(VmDisplayActivity.EXTRA_VM_ID, loaded.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        stopSelf()
    }

    private fun sendKeysym(keysym: Int) {
        val display = pipeline
        if (display != null && display.sendKey(keysym)) return
        display?.appendConsole("[ .. ] nav 0x${keysym.toString(16)} requested; waiting for guest RFB")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------- notification

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_floating),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_floating_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Re-publishes the progress notification once the VM profile is resolved. */
    private fun notifyStatus(profile: VmProfile) {
        if (destroyed) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.floating_notification_title),
                    profile.displayName,
                    profile.id
                )
            )
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        vmId: String? = null
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VmDisplayActivity::class.java)
                .putExtra(VmDisplayActivity.EXTRA_VM_ID, vmId ?: profile?.id.orEmpty()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingVmService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_novavm)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.floating_close), closeIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // -------------------------------------------------------------- helpers

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VM_ID = "com.novavm.app.extra.VM_ID"
        private const val CHANNEL_ID = "novavm_floating"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_CLOSE = "com.novavm.app.action.CLOSE_FLOATING"

        /** Permission-checked entry point used by the UI. */
        fun start(context: Context, vmId: String) {
            val granted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
            if (!granted) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return
            }
            val intent = Intent(context, FloatingVmService::class.java)
                .putExtra(EXTRA_VM_ID, vmId)
            // Android 12+ throws ForegroundServiceStartNotAllowedException when the
            // app is in the background; the UI turns that into a toast instead of a
            // crash of the calling screen.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Toast.makeText(context, "无法启动悬浮窗服务", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
