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
import com.novavm.app.engine.RfbProtocol
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

    private var windowView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dimmed = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        app = application as NovaVmApp
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限才能显示虚拟机窗口", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val application = app ?: return START_NOT_STICKY
        val vmId = intent?.getStringExtra(EXTRA_VM_ID)
            ?: application.vmRepository.loadAll().firstOrNull()?.id
        val loaded = vmId?.let { application.vmRepository.get(it) }
        if (loaded == null) {
            Toast.makeText(this, "找不到虚拟机", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        profile = loaded
        application.setActiveVm(loaded.id)
        if (application.vmInstanceManager.state(loaded.id)?.isRunning != true) {
            application.vmInstanceManager.start(loaded.id)
        }

        startForeground(NOTIFICATION_ID, buildNotification(loaded))
        ensurePipeline(loaded)
        showWindow(loaded)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeWindow()
        removeBubble()
        pipeline?.close()
        pipeline = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------ pipeline

    private fun ensurePipeline(profile: VmProfile) {
        if (pipeline != null) return
        val dims = VmDisplayPipeline.fitDimensions(profile.hardware.width, profile.hardware.height)
        val display = VmDisplayPipeline(
            vmId = profile.id,
            vmName = profile.displayName,
            width = dims.first,
            height = dims.second,
            vncPort = profile.vncPort,
            targetFps = profile.hardware.targetFps.coerceIn(24, 60),
            onLog = { line -> app?.vmInstanceManager?.appendConsole(profile.id, line) }
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
        if (windowView != null) return
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
            windowView = null
            stopSelf()
        }
    }

    private fun removeWindow() {
        windowView?.let { runCatching { windowManager.removeView(it) } }
        windowView = null
        windowParams = null
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
                    val target = if (isBubble) bubbleView else windowView
                    runCatching { target?.let { windowManager.updateViewLayout(it, params) } }
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
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun forwardTouch(view: View, event: MotionEvent): Boolean {
        val display = pipeline ?: return false
        if (view.width == 0 || view.height == 0) return false
        val scale = minOf(
            view.width.toFloat() / display.width,
            view.height.toFloat() / display.height
        ).coerceAtLeast(0.01f)
        val offsetX = (view.width - display.width * scale) / 2f
        val offsetY = (view.height - display.height * scale) / 2f
        val fbX = ((event.x - offsetX) / scale).toInt().coerceIn(0, display.width - 1)
        val fbY = ((event.y - offsetY) / scale).toInt().coerceIn(0, display.height - 1)
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
        runCatching {
            windowManager.addView(bubble, params)
            attachDrag(bubble, isBubble = true)
        }.onFailure { bubbleView = null }
        toast("已收起为悬浮球，点击恢复窗口")
    }

    private fun restoreFromBubble() {
        removeBubble()
        val loaded = profile ?: return
        showWindow(loaded)
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    private fun snapBubbleToEdge() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (params.x + params.width / 2 < screenWidth / 2) dp(6) else screenWidth - params.width - dp(6)
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180L
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubble, params) }
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

    private fun ensureChannel() {
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

    private fun buildNotification(profile: VmProfile): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VmDisplayActivity::class.java)
                .putExtra(VmDisplayActivity.EXTRA_VM_ID, profile.id),
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
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(profile.displayName)
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
            if (!Settings.canDrawOverlays(context)) {
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
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
