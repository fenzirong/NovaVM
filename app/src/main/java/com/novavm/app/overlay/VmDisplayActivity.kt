package com.novavm.app.overlay

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.novavm.app.MainActivity
import com.novavm.app.NovaVmApp
import com.novavm.app.data.VmProfile
import com.novavm.app.engine.RfbProtocol
import com.novavm.app.location.GeoMath
import com.novavm.app.location.RouteSimulator
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.theme.NovaPalette
import com.novavm.app.ui.theme.NovaVmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fullscreen guest display with a toggleable HUD and Picture-in-Picture.
 *
 * The framebuffer comes from [VmDisplayPipeline]: the live RFB guest when QEMU
 * is up, otherwise the interactive boot/status console. Touch and key events are
 * translated into guest framebuffer coordinates and forwarded over RFB.
 */
class VmDisplayActivity : ComponentActivity() {

    private var pipeline: VmDisplayPipeline? = null
    private var profile: VmProfile? = null
    private var app: NovaVmApp? = null

    private var isInPip by mutableStateOf(false)
    private var hudVisible by mutableStateOf(true)
    private var measuredFps by mutableStateOf(0)
    private var sourceLabel by mutableStateOf("启动控制台")
    private var backendLabel by mutableStateOf("QEMU")
    private var paused by mutableStateOf(false)
    private var keepAliveActive by mutableStateOf(false)
    private var landscapeLocked by mutableStateOf(false)

    // 「键盘」HUD 开关：虚拟键盘条展开后，实体键不再被拦截，交给输入法处理。
    private var keyboardVisible by mutableStateOf(false)

    // Floating GPS joystick state, mirrored into Compose so nudges repaint live.
    private var gpsPadVisible by mutableStateOf(false)
    private var gpsLatitude by mutableStateOf(0.0)
    private var gpsLongitude by mutableStateOf(0.0)

    // Route playback HUD state, fed by the process-wide RouteSimulator.
    private var routeAvailable by mutableStateOf(false)
    private var routePlaying by mutableStateOf(false)
    private var routeProgress by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as NovaVmApp
        app = application
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // The profile lookup hits the on-disk store (`vmRepository.loadAll/get`):
        // running it inline blocked onCreate long enough for an ANR on cold start.
        val requestedId = intent.getStringExtra(EXTRA_VM_ID)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val vmId = requestedId ?: application.vmRepository.loadAll().firstOrNull()?.id
                vmId?.let { application.vmRepository.get(it) }
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                Toast.makeText(this@VmDisplayActivity, "找不到虚拟机", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            bindProfile(loaded)
        }
    }

    /** Applies the loaded profile: display pipeline, HUD state, observers, content. */
    private fun bindProfile(loaded: VmProfile) {
        val application = application as NovaVmApp
        profile = loaded
        application.setActiveVm(loaded.id)
        gpsLatitude = loaded.virtualLocation.latitude
        gpsLongitude = loaded.virtualLocation.longitude
        gpsPadVisible = loaded.virtualLocation.enabled
        routeAvailable = loaded.virtualLocation.routeWaypoints.size >= 2
        keepAliveActive = KeepAliveService.isRunning.value
        // 「默认横屏锁定」 already saved on the profile wins over the manifest default.
        if (loaded.keepAlive.landscapeLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            landscapeLocked = true
        }

        val runtime = application.vmInstanceManager.state(loaded.id)
        if (runtime?.isRunning != true) {
            // Engine boot probes QEMU/sockets: never on the main thread.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { application.vmInstanceManager.start(loaded.id) }
            }
        }
        backendLabel = application.vmInstanceManager.state(loaded.id)?.backendName ?: loaded.backendType.name

        val dims = VmDisplayPipeline.fitDimensions(loaded.hardware.width, loaded.hardware.height)
        val display = VmDisplayPipeline(
            vmId = loaded.id,
            vmName = loaded.displayName,
            width = dims.first,
            height = dims.second,
            vncPort = loaded.vncPort,
            targetFps = loaded.hardware.targetFps.coerceIn(24, 60),
            onLog = { line ->
                application.vmInstanceManager.appendConsole(loaded.id, line)
            },
            capabilitiesProvider = { application.hostCapabilities }
        )
        display.console.appendLine("[ OK ] display attached: ${loaded.hardware.width}x${loaded.hardware.height} @ ${loaded.hardware.targetFps}fps")
        display.console.appendLine("[ .. ] RFB ${VmDisplayPipeline.HOST}:${loaded.vncPort} / ${loaded.architecture.name} ${loaded.backendType.name}")
        // Bitmap console font is ASCII-only, so the footer hint stays Latin.
        display.console.footerHint = "Tap to interact  |  VNC ${VmDisplayPipeline.HOST}:${loaded.vncPort}"
        display.console.setProgress(0.4f)
        pipeline = display
        display.start()

        // A zero/odd guest aspect ratio makes PictureInPictureParams.Builder.build()
        // throw IllegalArgumentException, which would take the screen down with it.
        runCatching { setPictureInPictureParams(pipParams(loaded)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val current = pipeline
                    if (current != null) {
                        measuredFps = current.measuredFps
                        sourceLabel = if (current.source == VmDisplayPipeline.Source.RFB) "客户机 RFB" else "启动控制台"
                        backendLabel = application.vmInstanceManager.state(loaded.id)?.backendName
                            ?: loaded.backendType.name
                        paused = application.vmInstanceManager.state(loaded.id)?.status ==
                            com.novavm.app.data.VmStatus.PAUSED
                    }
                    delay(700)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                application.routeSimulator.playbackStates.collect { states ->
                    val playback = states[loaded.id]
                    routePlaying = playback?.isPlaying == true
                    routeProgress = playback?.takeIf { it.hasRoute }
                        ?.let { RouteSimulator.progressText(it) }
                        .orEmpty()
                }
            }
        }

        lifecycleScope.launch {
            // Polls the on-disk store; it must not keep hammering disk while the
            // display is stopped (background / PiP), hence the RESUMED gate.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching { application.vmRepository.get(loaded.id) }.getOrNull()
                    }
                    routeAvailable = (fresh?.virtualLocation?.routeWaypoints?.size ?: 0) >= 2
                    if (fresh != null) {
                        gpsLatitude = fresh.virtualLocation.latitude
                        gpsLongitude = fresh.virtualLocation.longitude
                    }
                    delay(2_000)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isInPip) return
                if (hudVisible) hudVisible = false else finish()
            }
        })

        setContent {
            NovaVmTheme {
                VmDisplayScreen(
                    pipeline = display,
                    profile = loaded,
                    backend = backendLabel,
                    source = sourceLabel,
                    fps = measuredFps,
                    paused = paused,
                    isInPip = isInPip,
                    hudVisible = hudVisible,
                    gpsPadVisible = gpsPadVisible,
                    gpsLatitude = gpsLatitude,
                    gpsLongitude = gpsLongitude,
                    routeAvailable = routeAvailable,
                    routePlaying = routePlaying,
                    routeProgress = routeProgress,
                    keyboardVisible = keyboardVisible,
                    onToggleHud = { hudVisible = !hudVisible },
                    onToggleKeyboard = { keyboardVisible = !keyboardVisible },
                    onCloseKeyboard = { keyboardVisible = false },
                    onSendKey = { keysym -> sendKeysym(keysym) },
                    onSendText = { text -> sendText(text) },
                    onSendCtrlAltDel = { sendCtrlAltDel() },
                    onToggleGpsPad = { gpsPadVisible = !gpsPadVisible },
                    onGpsNudge = { bearing -> nudgeGps(bearing) },
                    onToggleRoute = { toggleRouteSimulation() },
                    onPauseResume = { togglePause() },
                    onSnapshot = { takeSnapshot() },
                    onScreenshot = { captureScreenshot() },
                    onRotate = { toggleRotation() },
                    onToggleKeepAlive = { toggleKeepAlive() },
                    keepAliveActive = keepAliveActive,
                    landscapeLocked = landscapeLocked,
                    onNavBack = { sendKeysym(RfbProtocol.KEYSYM_ESCAPE) },
                    onNavHome = { consoleInput("home") },
                    onNavRecents = { consoleInput("recents") },
                    onVolumeUp = { adjustVolume(true) },
                    onVolumeDown = { adjustVolume(false) },
                    onPip = { enterPip() },
                    onFloating = { switchToFloating() },
                    onTransfer = { openTransfer() },
                    onPower = { powerOff() }
                )
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        hudVisible = !isInPictureInPictureMode
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    override fun onDestroy() {
        // Non-blocking teardown: the pipeline detaches the surface immediately
        // and joins its daemons on its own thread.
        pipeline?.stopAsync()
        pipeline = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun pipParams(profile: VmProfile): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(profile.hardware.width, profile.hardware.height))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }
        return builder.build()
    }

    private fun enterPip() {
        val loaded = profile ?: return
        runCatching {
            enterPictureInPictureMode(pipParams(loaded))
        }.onFailure {
            Toast.makeText(this, "无法进入画中画模式", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------- actions

    private fun togglePause() {
        val loaded = profile ?: return
        val manager = app?.vmInstanceManager ?: return
        val runtime = manager.state(loaded.id) ?: return
        if (runtime.status == com.novavm.app.data.VmStatus.PAUSED) {
            manager.resume(loaded.id)
            pipeline?.setConsoleStatus("RUNNING", "guest resumed")
        } else {
            manager.pause(loaded.id)
            pipeline?.setConsoleStatus("PAUSED", "guest paused via QMP")
        }
    }

    private fun takeSnapshot() {
        val loaded = profile ?: return
        val manager = app?.vmInstanceManager ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        val snapshot = manager.takeSnapshot(loaded.id, "Snapshot $stamp")
        Toast.makeText(
            this,
            if (snapshot != null) "快照已创建" else "无法创建快照",
            Toast.LENGTH_SHORT
        ).show()
        pipeline?.appendConsole(
            if (snapshot != null) "[ OK ] snapshot ${snapshot.name}" else "[FAIL] snapshot failed"
        )
    }

    /**
     * 「截屏」：把当前帧缓冲（客户机 RFB 或启动控制台）编码成 PNG 存进相册。
     *
     * 帧拷贝 + PNG 编码 + MediaStore 写入都是重活（1080p 帧 8 MiB），全部在
     * 主线程做会直接卡住 UI 乃至 ANR，所以整体搬到后台，只有 Toast / 控制台
     * 回显回到主线程。
     */
    private fun captureScreenshot() {
        val display = pipeline ?: return
        val vmName = profile?.displayName ?: "NovaVM"
        lifecycleScope.launch {
            val frame = withContext(Dispatchers.Default) {
                runCatching { display.captureCurrentFrameRgba() }.getOrNull()
            }
            if (frame == null) {
                Toast.makeText(this@VmDisplayActivity, "当前没有可截取的画面", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (bytes, frameWidth, frameHeight) = frame
            val result = withContext(Dispatchers.IO) {
                VmScreenshotHelper.captureToGalleryOrAppDir(
                    context = this@VmDisplayActivity,
                    vmName = vmName,
                    rgbaBytes = bytes,
                    width = frameWidth,
                    height = frameHeight
                )
            }
            if (isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = { saved ->
                    Toast.makeText(this@VmDisplayActivity, "截图已保存：$saved", Toast.LENGTH_LONG).show()
                    display.appendConsole("[ OK ] screenshot saved ($frameWidth x $frameHeight)")
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@VmDisplayActivity,
                        "截图失败：${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    /** 「旋转」：在横屏与竖屏之间切换 HUD 方向。 */
    private fun toggleRotation() {
        val next = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestedOrientation = next
        landscapeLocked = next == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Persist the choice: otherwise the next recreation re-applies
        // profile.keepAlive.landscapeLocked and silently undoes this toggle.
        val loaded = profile
        val application = app
        if (loaded != null && application != null &&
            loaded.keepAlive.landscapeLocked != landscapeLocked
        ) {
            val updated = loaded.copy(
                keepAlive = loaded.keepAlive.copy(landscapeLocked = landscapeLocked)
            )
            profile = updated
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { application.vmRepository.update(updated) }
            }
        }
        Toast.makeText(
            this,
            if (landscapeLocked) "已切换为横屏" else "已切换为竖屏",
            Toast.LENGTH_SHORT
        ).show()
        pipeline?.appendConsole(
            "[ .. ] orientation -> ${if (landscapeLocked) "landscape" else "portrait"}"
        )
    }

    /** 「挂机」：开关 [KeepAliveService]，让虚拟机在息屏后继续运行。 */
    private fun toggleKeepAlive() {
        val running = KeepAliveService.isRunning.value
        if (running) {
            KeepAliveService.stop(this)
            keepAliveActive = false
            Toast.makeText(this, "已停止息屏挂机", Toast.LENGTH_SHORT).show()
            pipeline?.appendConsole("[ .. ] screen-off keep-alive stopped")
        } else {
            KeepAliveService.start(this, 1)
            keepAliveActive = true
            Toast.makeText(this, "已开启息屏挂机", Toast.LENGTH_SHORT).show()
            pipeline?.appendConsole("[ OK ] screen-off keep-alive started")
        }
    }

    private fun sendKeysym(keysym: Int) {
        val display = pipeline ?: return
        if (display.sendKey(keysym)) return
        display.appendConsole("[ .. ] keysym 0x${keysym.toString(16)} queued (console mode)")
    }

    private fun consoleInput(label: String) {
        val display = pipeline ?: return
        if (display.rfbConnected) {
            val keysym = when (label) {
                "home" -> 0xFF50
                else -> 0xFF67
            }
            display.sendKey(keysym)
        } else {
            display.appendConsole("[ .. ] $label requested; awaiting guest framebuffer")
        }
    }

    private fun adjustVolume(up: Boolean) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun switchToFloating() {
        val loaded = profile ?: return
        FloatingVmService.start(this, loaded.id)
        finish()
    }

    private fun openTransfer() {
        val loaded = profile ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "transfer")
                .putExtra(MainActivity.EXTRA_VM_ID, loaded.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun powerOff() {
        val loaded = profile ?: return
        val manager = app?.vmInstanceManager
        pipeline?.appendConsole("[ OK ] power off requested")
        if (manager == null) {
            finishAndRemoveTask()
            return
        }
        // Shutting the guest down can take seconds: keep the main thread free
        // and only leave the task once the engine acknowledged the stop.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { manager.stop(loaded.id) }
            withContext(Dispatchers.Main) { finishAndRemoveTask() }
        }
    }

    /**
     * Moves the mock GPS fix by one joystick step and re-syncs the guest NMEA
     * files, so the VM screen reflects the new position without leaving the HUD.
     */
    private fun nudgeGps(bearingDegrees: Double) {
        val loaded = profile ?: return
        val application = app ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val current = runCatching { application.vmRepository.get(loaded.id) }
                .getOrNull() ?: return@launch
            val gps = current.virtualLocation
            val point = GeoMath.stepPosition(
                gps.latitude,
                gps.longitude,
                bearingDegrees,
                GPS_STEP_METERS
            )
            val updated = current.copy(
                virtualLocation = gps.copy(
                    enabled = true,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    bearingDegrees = bearingDegrees.toFloat()
                )
            )
            runCatching { application.vmRepository.update(updated) }
            runCatching {
                application.rootXposedConfiguratorFor(loaded.id).writeAll(
                    updated.rootXposed,
                    updated.deviceSpoof,
                    updated.hardware,
                    updated.name,
                    updated.virtualLocation,
                    updated.vmosBundle()
                )
            }
            application.vmInstanceManager.syncVirtualLocation(loaded.id, "display-joystick")
            withContext(Dispatchers.Main) {
                profile = updated
                gpsLatitude = point.latitude
                gpsLongitude = point.longitude
                pipeline?.appendConsole(
                    String.format(
                        Locale.US,
                        "[GPS] joystick b%.0f +%.0fm -> %.6f, %.6f",
                        bearingDegrees,
                        GPS_STEP_METERS,
                        point.latitude,
                        point.longitude
                    )
                )
            }
        }
    }

    /**
     * Starts or pauses route movement straight from the display HUD, so the
     * guest keeps walking along the planned waypoints while the VM screen is
     * being watched fullscreen.
     */
    private fun toggleRouteSimulation() {
        val loaded = profile ?: return
        val application = app ?: return
        val playing = application.routeSimulator.playbackState(loaded.id)?.isPlaying == true
        val changed = if (playing) {
            application.routeSimulator.pause(loaded.id)
        } else {
            application.routeSimulator.start(loaded.id)
        }
        if (!changed) {
            Toast.makeText(this, "请先在虚拟机详情中规划至少两个途经点", Toast.LENGTH_SHORT).show()
            return
        }
        routePlaying = !playing
        pipeline?.appendConsole(
            if (playing) "[GPS] route paused" else "[GPS] route playback started"
        )
        Toast.makeText(
            this,
            if (playing) "路线播放已暂停" else "路线播放已开始",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------------------------------------------------------------- input

    /**
     * 把虚拟键盘条的「发送」文本逐字符注入客户机。
     *
     * 每个 keysym 都是一次 RFB KeyEvent 写操作，1080p 下几十个字符也足够卡住
     * 主线程，所以整段序列放到 IO 线程；失败计数回到主线程给一次提示。
     */
    private fun sendText(text: String) {
        val display = pipeline ?: return
        val keysyms = HardwareInputMapper.textToKeysyms(text)
        if (keysyms.isEmpty()) {
            Toast.makeText(this, "没有可发送的字符", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val delivered = withContext(Dispatchers.IO) {
                keysyms.count { keysym -> display.sendKey(keysym) }
            }
            if (isFinishing || isDestroyed) return@launch
            if (delivered == 0) {
                Toast.makeText(this@VmDisplayActivity, "客户机尚未连接，字符未送达", Toast.LENGTH_SHORT).show()
            }
            display.appendConsole("[ .. ] text injected: $delivered/${keysyms.size} keysyms")
        }
    }

    /** 经典 Ctrl+Alt+Del：修饰键与 DEL 依次按下并释放。 */
    private fun sendCtrlAltDel() {
        val display = pipeline ?: return
        val chord = intArrayOf(
            HardwareInputMapper.KEYSYM_CTRL_L,
            HardwareInputMapper.KEYSYM_ALT_L,
            HardwareInputMapper.KEYSYM_DELETE
        )
        lifecycleScope.launch(Dispatchers.IO) {
            chord.forEach { keysym -> display.sendKey(keysym) }
        }
        display.appendConsole("[ .. ] Ctrl+Alt+Del sent")
    }

    /**
     * 实体 / 蓝牙键盘透传。
     *
     * 虚拟键盘条的输入框打开时一律走默认分发，否则用户无法在输入框里打字；
     * 音量键与返回键也始终交给系统，保证任何情况下都能退出或调节音量。
     *
     * `ComponentActivity.dispatchKeyEvent` 被标记为 `@RestrictTo`，但拦截按键
     * 事件正是全屏客户机画面必须的能力，这里显式压制该检查。
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyboardVisible || event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> Unit
        }
        val display = pipeline ?: return super.dispatchKeyEvent(event)
        val keysym = HardwareInputMapper.androidKeyCodeToKeysym(event.keyCode, event.unicodeChar)
            ?: return super.dispatchKeyEvent(event)
        return if (display.sendKey(keysym)) true else super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = mapKeysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        val display = pipeline ?: return super.onKeyDown(keyCode, event)
        return if (display.sendKey(keysym)) true else super.onKeyDown(keyCode, event)
    }

    private fun mapKeysym(keyCode: Int, event: KeyEvent): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> RfbProtocol.KEYSYM_ENTER
        KeyEvent.KEYCODE_DEL -> RfbProtocol.KEYSYM_BACKSPACE
        KeyEvent.KEYCODE_TAB -> RfbProtocol.KEYSYM_TAB
        KeyEvent.KEYCODE_ESCAPE -> RfbProtocol.KEYSYM_ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> RfbProtocol.KEYSYM_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RfbProtocol.KEYSYM_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RfbProtocol.KEYSYM_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RfbProtocol.KEYSYM_RIGHT
        KeyEvent.KEYCODE_BACK -> null
        else -> event.unicodeChar.takeIf { it != 0 }
    }

    companion object {
        const val EXTRA_VM_ID = "com.novavm.app.extra.VM_ID"

        /** One D-Pad tap distance on the display HUD joystick. */
        const val GPS_STEP_METERS = 50.0
    }
}

// --------------------------------------------------------------------- UI

@Composable
private fun VmDisplayScreen(
    pipeline: VmDisplayPipeline,
    profile: VmProfile,
    backend: String,
    source: String,
    fps: Int,
    paused: Boolean,
    isInPip: Boolean,
    hudVisible: Boolean,
    gpsPadVisible: Boolean,
    gpsLatitude: Double,
    gpsLongitude: Double,
    routeAvailable: Boolean,
    routePlaying: Boolean,
    routeProgress: String,
    keyboardVisible: Boolean,
    onToggleHud: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onCloseKeyboard: () -> Unit,
    onSendKey: (Int) -> Unit,
    onSendText: (String) -> Unit,
    onSendCtrlAltDel: () -> Unit,
    onToggleGpsPad: () -> Unit,
    onGpsNudge: (Double) -> Unit,
    onToggleRoute: () -> Unit,
    onPauseResume: () -> Unit,
    onSnapshot: () -> Unit,
    onScreenshot: () -> Unit,
    onRotate: () -> Unit,
    onToggleKeepAlive: () -> Unit,
    keepAliveActive: Boolean,
    landscapeLocked: Boolean,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPip: () -> Unit,
    onFloating: () -> Unit,
    onTransfer: () -> Unit,
    onPower: () -> Unit
) {
    val resolution = "${profile.hardware.width}x${profile.hardware.height}"
    val density = LocalDensity.current
    var pillOffset by androidx.compose.runtime.remember {
        mutableStateOf(with(density) { Offset(16.dp.toPx(), 168.dp.toPx()) })
    }
    val configuration = LocalConfiguration.current
    val maxX = with(density) {
        (configuration.screenWidthDp.dp.toPx() - 64.dp.toPx()).coerceAtLeast(0f)
    }
    val maxY = with(density) {
        (configuration.screenHeightDp.dp.toPx() - 150.dp.toPx()).coerceAtLeast(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val surfaceView = SurfaceView(context)
                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            pipeline.attach(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            pipeline.attach(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            pipeline.attach(null)
                        }
                    })
                    surfaceView.setOnTouchListener { view, event ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        handlePipelineTouch(pipeline, view as android.view.View, event)
                    }
                    addView(
                        surfaceView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isInPip) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(NovaPalette.Background.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = profile.displayName,
                    color = NovaPalette.TextPrimary.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else if (hudVisible) {
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                HudTopBar(
                    name = profile.displayName,
                    backend = backend,
                    resolution = resolution,
                    fps = fps,
                    source = source,
                    paused = paused,
                    onPauseResume = onPauseResume,
                    onSnapshot = onSnapshot
                )
            }
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                HudBottomBar(
                    onNavBack = onNavBack,
                    onNavHome = onNavHome,
                    onNavRecents = onNavRecents,
                    onVolumeUp = onVolumeUp,
                    onVolumeDown = onVolumeDown,
                    onPip = onPip,
                    onFloating = onFloating,
                    onTransfer = onTransfer,
                    onPower = onPower,
                    gpsPadVisible = gpsPadVisible,
                    onToggleGpsPad = onToggleGpsPad,
                    onScreenshot = onScreenshot,
                    onRotate = onRotate,
                    onToggleKeepAlive = onToggleKeepAlive,
                    keepAliveActive = keepAliveActive,
                    landscapeLocked = landscapeLocked,
                    keyboardVisible = keyboardVisible,
                    onToggleKeyboard = onToggleKeyboard
                )
            }
        }

        if (gpsPadVisible && !isInPip) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                GpsQuickJoystick(
                    latitude = gpsLatitude,
                    longitude = gpsLongitude,
                    onNudge = onGpsNudge,
                    routeAvailable = routeAvailable,
                    routePlaying = routePlaying,
                    routeProgress = routeProgress,
                    onToggleRoute = onToggleRoute
                )
            }
        }

        if (keyboardVisible && !isInPip) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 78.dp)
            ) {
                VirtualKeyboardBar(
                    onSendKey = onSendKey,
                    onSendText = onSendText,
                    onSendCtrlAltDel = onSendCtrlAltDel,
                    onClose = onCloseKeyboard
                )
            }
        }

        if (!isInPip) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(pillOffset.x.roundToInt(), pillOffset.y.roundToInt())
                    }
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NovaPalette.SurfaceElevated.copy(alpha = 0.92f))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            pillOffset = Offset(
                                (pillOffset.x + dragAmount.x).coerceIn(0f, maxX.coerceAtLeast(0f)),
                                (pillOffset.y + dragAmount.y).coerceIn(0f, maxY.coerceAtLeast(0f))
                            )
                        }
                    }
                    .clickable { onToggleHud() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hudVisible) Icons.Default.KeyboardArrowDown else Icons.Default.Menu,
                    contentDescription = "控制面板",
                    tint = NovaPalette.Cyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun HudTopBar(
    name: String,
    backend: String,
    resolution: String,
    fps: Int,
    source: String,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onSnapshot: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaPalette.Background.copy(alpha = 0.9f))
            .statusBarsPadding()
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (paused) NovaPalette.Amber else NovaPalette.Emerald)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = NovaPalette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp)
            ) {
                NovaTag(backend, NovaPalette.Cyan)
                Text(
                    text = "$resolution · ${fps}fps · $source",
                    color = NovaPalette.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = onPauseResume) {
            Icon(
                imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (paused) "继续" else "暂停",
                tint = NovaPalette.TextPrimary
            )
        }
        IconButton(onClick = onSnapshot) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "快照",
                tint = NovaPalette.TextPrimary
            )
        }
    }
}

@Composable
private fun HudBottomBar(
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPip: () -> Unit,
    onFloating: () -> Unit,
    onTransfer: () -> Unit,
    onPower: () -> Unit,
    gpsPadVisible: Boolean,
    onToggleGpsPad: () -> Unit,
    onScreenshot: () -> Unit,
    onRotate: () -> Unit,
    onToggleKeepAlive: () -> Unit,
    keepAliveActive: Boolean,
    landscapeLocked: Boolean,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaPalette.Background.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudNavItem(Icons.AutoMirrored.Filled.ArrowBack, "返回", NovaPalette.TextPrimary, onNavBack)
        HudNavItem(Icons.Default.Home, "主页", NovaPalette.TextPrimary, onNavHome)
        HudNavItem(Icons.Default.Apps, "最近", NovaPalette.TextPrimary, onNavRecents)
        HudNavItem(Icons.AutoMirrored.Filled.VolumeUp, "音量+", NovaPalette.TextSecondary, onVolumeUp)
        HudNavItem(Icons.AutoMirrored.Filled.VolumeDown, "音量-", NovaPalette.TextSecondary, onVolumeDown)
        HudNavItem(Icons.Default.PictureInPictureAlt, "画中画", NovaPalette.Cyan, onPip)
        HudNavItem(Icons.AutoMirrored.Filled.OpenInNew, "悬浮窗", NovaPalette.Violet, onFloating)
        HudNavItem(
            icon = Icons.Default.MyLocation,
            label = "定位摇杆",
            tint = if (gpsPadVisible) NovaPalette.Amber else NovaPalette.TextSecondary,
            onClick = onToggleGpsPad
        )
        HudNavItem(Icons.Default.SwapHoriz, "互传", NovaPalette.Emerald, onTransfer)
        HudNavItem(Icons.Default.PhotoCamera, "截屏", NovaPalette.Cyan, onScreenshot)
        HudNavItem(
            icon = Icons.Default.Keyboard,
            label = "键盘",
            tint = if (keyboardVisible) NovaPalette.Amber else NovaPalette.TextSecondary,
            onClick = onToggleKeyboard
        )
        HudNavItem(
            icon = Icons.Default.ScreenRotation,
            label = "旋转",
            tint = if (landscapeLocked) NovaPalette.Amber else NovaPalette.TextSecondary,
            onClick = onRotate
        )
        HudNavItem(
            icon = Icons.Default.Bedtime,
            label = "挂机",
            tint = if (keepAliveActive) NovaPalette.Emerald else NovaPalette.TextSecondary,
            onClick = onToggleKeepAlive
        )
        HudNavItem(Icons.Default.PowerSettingsNew, "关机", NovaPalette.Coral, onPower)
    }
}

@Composable
private fun GpsQuickJoystick(
    latitude: Double,
    longitude: Double,
    onNudge: (Double) -> Unit,
    routeAvailable: Boolean,
    routePlaying: Boolean,
    routeProgress: String,
    onToggleRoute: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(end = 10.dp, bottom = 92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NovaPalette.SurfaceElevated.copy(alpha = 0.94f))
            .border(1.dp, NovaPalette.Amber.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GPS 摇杆",
            color = NovaPalette.Amber,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = String.format(Locale.US, "%.5f, %.5f", latitude, longitude),
            color = NovaPalette.TextSecondary,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            GpsPadKey("↖") { onNudge(315.0) }
            GpsPadKey("↑") { onNudge(0.0) }
            GpsPadKey("↗") { onNudge(45.0) }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GpsPadKey("←") { onNudge(270.0) }
            Text(
                text = "50m",
                color = NovaPalette.Cyan,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 5.dp)
            )
            GpsPadKey("→") { onNudge(90.0) }
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            GpsPadKey("↙") { onNudge(225.0) }
            GpsPadKey("↓") { onNudge(180.0) }
            GpsPadKey("↘") { onNudge(135.0) }
        }
        if (routeAvailable) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (routePlaying) {
                            NovaPalette.Amber.copy(alpha = 0.22f)
                        } else {
                            NovaPalette.Cyan.copy(alpha = 0.18f)
                        }
                    )
                    .border(
                        1.dp,
                        if (routePlaying) NovaPalette.Amber.copy(alpha = 0.55f)
                        else NovaPalette.Cyan.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onToggleRoute)
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (routePlaying) "路线暂停" else "路线播放",
                    color = if (routePlaying) NovaPalette.Amber else NovaPalette.Cyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (routeProgress.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = routeProgress,
                    color = NovaPalette.TextSecondary,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 9.sp,
                    maxLines = 2,
                    modifier = Modifier.width(120.dp)
                )
            }
        }
    }
}

@Composable
private fun GpsPadKey(arrow: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NovaPalette.SurfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = arrow, color = NovaPalette.Cyan, fontSize = 13.sp)
    }
}

@Composable
private fun HudNavItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = tint, fontSize = 8.sp)
    }
}

/** Touch translation shared by the SurfaceView listener (outside Compose scope). */
private fun handlePipelineTouch(
    pipeline: VmDisplayPipeline,
    view: android.view.View,
    event: MotionEvent
): Boolean {
    if (view.width == 0 || view.height == 0) return false
    // Guest space while RFB is live (oversized guests keep their own geometry),
    // console space otherwise.
    val frameWidth = pipeline.displayWidth.coerceAtLeast(1)
    val frameHeight = pipeline.displayHeight.coerceAtLeast(1)
    val scale = min(
        view.width.toFloat() / frameWidth,
        view.height.toFloat() / frameHeight
    ).coerceAtLeast(0.01f)
    val offsetX = (view.width - frameWidth * scale) / 2f
    val offsetY = (view.height - frameHeight * scale) / 2f
    val fbX = ((event.x - offsetX) / scale).roundToInt().coerceIn(0, frameWidth - 1)
    val fbY = ((event.y - offsetY) / scale).roundToInt().coerceIn(0, frameHeight - 1)
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> pipeline.sendPointer(1, fbX, fbY)
        MotionEvent.ACTION_MOVE -> pipeline.sendPointer(1, fbX, fbY)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pipeline.sendPointer(0, fbX, fbY)
        else -> Unit
    }
    return true
}

