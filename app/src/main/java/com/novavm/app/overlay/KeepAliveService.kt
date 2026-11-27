package com.novavm.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novavm.app.MainActivity
import com.novavm.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「息屏挂机」保活前台服务。
 *
 * 手机屏幕关闭后系统会逐步冻结进程（Doze / App Standby），虚拟机随之挂起。
 * 该服务做两件事：
 *
 *  1. 用前台通知把自己钉在 `IMPORTANCE_LOW` 的常驻通知栏上，避免被回收；
 *  2. 持有一个 [PowerManager.PARTIAL_WAKE_LOCK]（tag `NovaVM:KeepAlive`），
 *     屏幕熄灭后 CPU 仍可调度 QEMU / RFB 线程。
 *
 * 与 `FloatingVmService` 保持同一套通知约定：`novavm_` 前缀的通道 id、
 * `ic_stat_novavm` 小图标、`setOngoing(true) + setSilent(true)`、
 * `FOREGROUND_SERVICE_IMMEDIATE`，并且 `startForeground()` 是
 * [onStartCommand] 的第一条实质语句（Android 12+ / 14+ 的硬性要求）。
 *
 * 通知文案用中文字面量而非 `R.string.*`：本次改动不触碰 `res/values`，
 * 避免引用尚不存在的资源导致 `aapt`/`R` 解析失败。
 *
 * 集成方还需在 `AndroidManifest.xml` 声明
 * `<service android:name=".overlay.KeepAliveService" android:exported="false"
 *          android:foregroundServiceType="specialUse" />`，
 * `FOREGROUND_SERVICE_SPECIAL_USE` 与 `WAKE_LOCK` 权限本工程已有。
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var runningCount: Int = 0

    /** 主线程 Handler：只用于周期性续期 wake lock，不做任何 IO。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * wake lock 续期任务。
     *
     * `acquire(timeout)` 带超时是为了「永不泄漏」：即使进程被强杀、`onDestroy`
     * 没来得及执行，系统也会在超时后自动释放。代价是长挂机需要定期续期，
     * 所以每 [WAKE_LOCK_TIMEOUT_MS] / 2 重新获取一次。
     */
    private val reArmWakeLock = object : Runnable {
        override fun run() {
            acquireWakeLock()
            mainHandler.postDelayed(this, WAKE_LOCK_TIMEOUT_MS / 2)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第一件事就进前台：startForegroundService 之后迟迟不调用
        // startForeground()，Android 12+ 会抛 ForegroundServiceDidNotStartInTime，
        // Android 14+ 还会把服务连同通知一起拆掉。任何 IO 都排在后面。
        ensureNotificationChannel()
        runningCount = (intent?.getIntExtra(EXTRA_RUNNING_COUNT, runningCount) ?: runningCount)
            .coerceAtLeast(0)
        startForeground(NOTIFICATION_ID, buildNotification(runningCount))
        markRunning(true)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        mainHandler.removeCallbacks(reArmWakeLock)
        mainHandler.postDelayed(reArmWakeLock, WAKE_LOCK_TIMEOUT_MS / 2)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 从最近任务划掉时保持存活（挂机场景的预期行为）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // START_STICKY 会拉起进程；这里只补一次续期，不重启任何 Activity。
        acquireWakeLock()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reArmWakeLock)
        releaseWakeLock()
        markRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // 非引用计数：重复 acquire/release 不会互相抵消，也不会因计数错乱而泄漏。
        lock.setReferenceCounted(false)
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            .onSuccess { wakeLock = lock }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    // -------------------------------------------------------- notification

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_TITLE,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Stop 动作由本服务自己处理，无需额外组件。
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (count > 0) {
            "正在保活 $count 台虚拟机 · 息屏后继续运行"
        } else {
            "等待虚拟机启动 · 息屏后自动保活"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_novavm)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, ACTION_STOP_LABEL, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ------------------------------------------------------------- helpers

    private fun markRunning(value: Boolean) {
        runningState.value = value
    }

    companion object {
        /** 通道名固定，供设置页 / 调试面板反查。 */
        const val CHANNEL_ID = "novavm_keepalive"

        /** 通道展示名（中文字面量，不依赖 strings.xml）。 */
        const val CHANNEL_TITLE = "NovaVM 息屏挂机"

        const val CHANNEL_DESCRIPTION = "屏幕关闭后保持虚拟机继续运行"

        /** 通知标题，需求指定为「NovaVM 息屏挂机运行中」。 */
        const val NOTIFICATION_TITLE = "NovaVM 息屏挂机运行中"

        const val ACTION_STOP_LABEL = "停止"

        /** wake lock tag：`dumpsys power` 里可直接看到 `NovaVM:KeepAlive`。 */
        const val WAKE_LOCK_TAG = "NovaVM:KeepAlive"

        const val EXTRA_RUNNING_COUNT = "com.novavm.app.extra.RUNNING_VM_COUNT"

        /** 单次 wake lock 持有上限；续期任务按一半周期重新获取。 */
        const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L

        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP = "com.novavm.app.action.STOP_KEEPALIVE"

        private val runningState = MutableStateFlow(false)

        /** 保活服务是否在跑；Compose / ViewModel 可直接 collect 这个 StateFlow。 */
        val isRunning: StateFlow<Boolean> = runningState.asStateFlow()

        /**
         * 启动（或刷新）保活服务。
         *
         * 重复调用是安全的：服务已在运行时只把通知里的虚拟机台数刷新成
         * [runningCount]，不会重复创建通道或 wake lock。
         */
        fun start(context: Context, runningCount: Int) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_RUNNING_COUNT, runningCount.coerceAtLeast(0))
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 停止保活（虚拟机随后按系统策略正常回收）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            runningState.value = false
        }

        /**
         * 申请「忽略电池优化」的系统弹窗 Intent。
         *
         * 返回 `null` 表示无需申请：系统版本低于 Android 6.0，或本应用已在
         * 白名单内。调用方拿到非空 Intent 后自行 `startActivity`。
         */
        fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            val powerManager = context.getSystemService(PowerManager::class.java) ?: return null
            val alreadyIgnoring = runCatching {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
            if (alreadyIgnoring) return null
            return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
