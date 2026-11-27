package com.novavm.app

import com.novavm.app.overlay.KeepAliveService
import com.novavm.app.overlay.VmScreenshotHelper
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：覆盖「息屏挂机保活 + 截图命名」这两个 helper 的可测部分。
 *
 * 只断言不依赖 Android 运行时 / 文件系统的纯逻辑：
 *  * [VmScreenshotHelper.buildScreenshotFileName] 的清洗规则与时间戳格式；
 *  * [KeepAliveService] 的 companion 常量与初始 [KeepAliveService.isRunning] 状态。
 *
 * 时间戳统一用 `1_700_000_000_000L`（= 2023-11-14T22:13:20Z），
 * 期望文件名因此完全确定，不受本机时区影响。
 */
class VmosFeatureHelpersTest {

    private companion object {
        /** 2023-11-14T22:13:20Z */
        const val FIXED_TS = 1_700_000_000_000L
        const val FIXED_STAMP = "20231114_221320"

        /** 2025-01-01T00:00:00Z，用于验证不同时间戳不产生同名文件。 */
        const val NEW_YEAR_TS = 1_735_689_600_000L
        const val NEW_YEAR_STAMP = "20250101_000000"
    }

    // --------------------------------------------------------- 文件名清洗

    @Test
    fun `spaces become single underscores`() {
        assertEquals(
            "NovaVM_Pixel_7_Pro_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("Pixel 7 Pro", FIXED_TS)
        )
        // 连续空白折叠成一个下划线，不会留下 `Pixel__7`。
        assertEquals(
            "NovaVM_Pixel_7_Pro_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("Pixel   7\tPro", FIXED_TS)
        )
    }

    @Test
    fun `chinese characters are preserved verbatim`() {
        assertEquals(
            "NovaVM_我的虚拟机_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("我的虚拟机", FIXED_TS)
        )
        // 中英混排 + 全角空格同样安全。
        assertEquals(
            "NovaVM_工作机_Android_14_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("工作机\u3000Android 14", FIXED_TS)
        )
    }

    @Test
    fun `path separators never leak into the file name`() {
        assertEquals(
            "NovaVM_a_b_c_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("a/b\\c", FIXED_TS)
        )
        // 只由分隔符组成时片段会被清空，回落到 `vm`。
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("///", FIXED_TS)
        )
    }

    @Test
    fun `windows reserved and control characters are replaced`() {
        assertEquals(
            "NovaVM_a_b_c_d_e_f_g_h_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("a<b>c:d\"e|f?g*h", FIXED_TS)
        )
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("<>:\"|?*\u0000\u001F", FIXED_TS)
        )
    }

    @Test
    fun `blank and dot only names fall back to the default segment`() {
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("", FIXED_TS)
        )
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("   ", FIXED_TS)
        )
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("...", FIXED_TS)
        )
        assertEquals(
            "NovaVM_vm_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("___", FIXED_TS)
        )
        assertEquals("vm", VmScreenshotHelper.sanitizeNameSegment(""))
    }

    @Test
    fun `leading and trailing separators are trimmed`() {
        assertEquals(
            "NovaVM_hidden_$FIXED_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("  ..hidden..  ", FIXED_TS)
        )
        assertEquals("hidden", VmScreenshotHelper.sanitizeNameSegment("__hidden__"))
        assertEquals("hidden", VmScreenshotHelper.sanitizeNameSegment("..hidden.."))
    }

    @Test
    fun `long names are clipped to the maximum slug length`() {
        val longName = "x".repeat(80)
        val fileName = VmScreenshotHelper.buildScreenshotFileName(longName, FIXED_TS)
        val slug = VmScreenshotHelper.sanitizeNameSegment(longName)

        assertEquals(VmScreenshotHelper.MAX_NAME_LENGTH, slug.length)
        assertEquals(
            "NovaVM_${"x".repeat(VmScreenshotHelper.MAX_NAME_LENGTH)}_$FIXED_STAMP.png",
            fileName
        )
        // 截断后不能以 `_` 或 `.` 收尾，避免拼出双下划线。
        assertFalse(slug.endsWith("_"))
        assertFalse(slug.endsWith("."))
    }

    @Test
    fun `file name keeps the required prefix extension and timestamp shape`() {
        val fileName = VmScreenshotHelper.buildScreenshotFileName("Pixel 7 Pro", FIXED_TS)
        assertTrue(fileName.startsWith("${VmScreenshotHelper.FILE_PREFIX}_"))
        assertTrue(fileName.endsWith(".png"))
        assertTrue(
            "时间戳应为 yyyyMMdd_HHmmss: $fileName",
            Regex("""^NovaVM_.+_\d{8}_\d{6}\.png$""").matches(fileName)
        )
        // 冒号/斜杠是文件系统禁区，名称里绝不能出现。
        assertFalse(fileName.contains(":"))
        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains("\\"))
    }

    @Test
    fun `timestamps are rendered in fixed utc and are deterministic`() {
        assertEquals(
            "NovaVM_vm_$NEW_YEAR_STAMP.png",
            VmScreenshotHelper.buildScreenshotFileName("", NEW_YEAR_TS)
        )
        assertNotEquals(
            VmScreenshotHelper.buildScreenshotFileName("vm", FIXED_TS),
            VmScreenshotHelper.buildScreenshotFileName("vm", NEW_YEAR_TS)
        )
        // 同一毫秒重复截图 -> 同名（调用方如需并存应自行追加序号）。
        assertEquals(
            VmScreenshotHelper.buildScreenshotFileName("vm", FIXED_TS),
            VmScreenshotHelper.buildScreenshotFileName("vm", FIXED_TS)
        )
    }

    // --------------------------------------------- KeepAliveService 初始状态

    @Test
    fun `keep alive service starts in the not running state`() {
        assertTrue(KeepAliveService.isRunning is StateFlow<*>)
        assertFalse("服务未被启动前 isRunning 必须为 false", KeepAliveService.isRunning.value)
    }

    @Test
    fun `keep alive constants match the notification contract`() {
        assertEquals("novavm_keepalive", KeepAliveService.CHANNEL_ID)
        assertEquals("NovaVM:KeepAlive", KeepAliveService.WAKE_LOCK_TAG)
        assertEquals("NovaVM 息屏挂机运行中", KeepAliveService.NOTIFICATION_TITLE)
        assertTrue(KeepAliveService.NOTIFICATION_TITLE.contains("息屏挂机"))
        assertTrue(KeepAliveService.WAKE_LOCK_TIMEOUT_MS > 0L)
        assertEquals(
            "com.novavm.app.extra.RUNNING_VM_COUNT",
            KeepAliveService.EXTRA_RUNNING_COUNT
        )
    }
}
