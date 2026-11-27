package com.novavm.app

import com.novavm.app.data.DownloadStatus
import com.novavm.app.data.RomDownloadState
import com.novavm.app.data.RomDownloader
import com.novavm.app.data.VmArchitecture
import com.novavm.app.engine.RuntimeSetupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 运行时安装器 + 镜像下载器的纯 JVM 测试（不触网）。
 */
class RuntimeAndDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------------------------------------------------------------- 样本

    /** 合法 ELF 头：0x7F 'E' 'L' 'F' + 填充。 */
    private val elfBytes: ByteArray
        get() = byteArrayOf(
            0x7F,
            'E'.code.toByte(),
            'L'.code.toByte(),
            'F'.code.toByte()
        ) + ByteArray(60) { index -> index.toByte() }

    /** 明显不是 ELF 的内容（PE 的 MZ 头）。 */
    private val garbageBytes: ByteArray
        get() = byteArrayOf(0x4D, 0x5A, 0x00, 0x00) + ByteArray(32)

    private val firmwareBytes: ByteArray
        get() = ByteArray(128) { 0x5A.toByte() }

    private fun manager(): RuntimeSetupManager =
        RuntimeSetupManager(tempFolder.newFolder("novavm-root"))

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    // -------------------------------------------------- isValidElfBinary

    @Test
    fun `isValidElfBinary accepts a file starting with the elf magic`() {
        val file = tempFolder.newFile("qemu-system-aarch64")
        file.writeBytes(elfBytes)

        assertTrue(manager().isValidElfBinary(file))
    }

    @Test
    fun `isValidElfBinary rejects garbage truncated and missing files`() {
        val m = manager()
        val garbage = File(tempFolder.root, "garbage.bin").apply { writeBytes(garbageBytes) }
        val truncated = File(tempFolder.root, "truncated.bin")
            .apply { writeBytes(byteArrayOf(0x7F, 'E'.code.toByte())) }
        val missing = File(tempFolder.root, "no-such-binary")

        assertFalse(m.isValidElfBinary(garbage))
        assertFalse(m.isValidElfBinary(truncated))
        assertFalse(m.isValidElfBinary(missing))
    }

    // ------------------------------------------------------ importBinary

    @Test
    fun `importBinary stores whitelisted elf under bin and inspects it`() {
        val m = manager()

        val target = m.importBinary(ByteArrayInputStream(elfBytes), "qemu-system-aarch64")

        assertEquals("qemu-system-aarch64", target.name)
        assertEquals(File(m.binDir, "qemu-system-aarch64").absolutePath, target.absolutePath)
        assertTrue(target.isFile)
        assertEquals(elfBytes.size.toLong(), target.length())
        assertTrue(target.canExecute())
        assertFalse(File(m.binDir, "qemu-system-aarch64.tmp").exists())

        val status = m.inspect()
        assertNotNull(status.qemuAarch64Path)
        assertNull(status.qemuX86_64Path)
        assertNull(status.qemuImgPath)
        assertTrue(status.hasAnyQemu)
        assertFalse(status.hasFirmware)
        assertFalse(status.isReady)
        assertEquals(target.length(), status.totalRuntimeBytes)
        assertEquals(target.absolutePath, m.resolveQemuBinary(VmArchitecture.ARM64))
        assertNull(m.resolveQemuBinary(VmArchitecture.X86_64))
    }

    @Test
    fun `importBinary routes firmware into the firmware directory`() {
        val m = manager()

        val target = m.importBinary(ByteArrayInputStream(firmwareBytes), "edk2-aarch64-code.fd")

        assertEquals(File(m.firmwareDir, "edk2-aarch64-code.fd").absolutePath, target.absolutePath)
        assertTrue(target.isFile)
        val status = m.inspect()
        assertNotNull(status.uefiArm64Path)
        assertTrue(status.hasFirmware)
        assertFalse(status.hasAnyQemu)
    }

    @Test
    fun `importBinary rejects non elf payloads`() {
        val m = manager()

        assertThrows(IllegalArgumentException::class.java) {
            m.importBinary(ByteArrayInputStream(garbageBytes), "qemu-img")
        }

        assertFalse(File(m.binDir, "qemu-img").exists())
        assertFalse(File(m.binDir, "qemu-img.tmp").exists())
    }

    @Test
    fun `importBinary rejects unknown names and path traversal`() {
        val m = manager()

        assertThrows(IllegalArgumentException::class.java) {
            m.importBinary(ByteArrayInputStream(elfBytes), "evil-binary")
        }
        assertThrows(IOException::class.java) {
            m.importBinary(ByteArrayInputStream(elfBytes), "../../evil.sh")
        }
        assertThrows(IOException::class.java) {
            m.importBinary(ByteArrayInputStream(elfBytes), "/data/local/tmp/qemu-img")
        }

        assertFalse(File(m.rootDir.parentFile, "evil.sh").exists())
        assertFalse(File(m.rootDir, "evil.sh").exists())
        assertEquals(0L, m.inspect().totalRuntimeBytes)
    }

    // ---------------------------------------------- importRuntimeArchive

    @Test
    fun `importRuntimeArchive extracts white listed elf and firmware only`() {
        val m = manager()
        val archive = zipOf(
            mapOf(
                "qemu/bin/qemu-system-aarch64" to elfBytes,
                "qemu/bin/qemu-img_fake" to garbageBytes,
                "qemu/firmware/edk2-aarch64-code.fd" to firmwareBytes,
                "qemu/README.txt" to "请忽略我".toByteArray()
            )
        )

        val created = m.importRuntimeArchive(ByteArrayInputStream(archive))

        assertEquals(2L, created.size.toLong())
        val names = created.map { it.name }.toSet()
        assertEquals(setOf("qemu-system-aarch64", "edk2-aarch64-code.fd"), names)
        created.forEach { assertTrue(it.isFile) }
        assertTrue(File(m.binDir, "qemu-system-aarch64").isFile)
        assertTrue(File(m.firmwareDir, "edk2-aarch64-code.fd").isFile)
        assertFalse(File(m.binDir, "qemu-img_fake").exists())
        assertFalse(File(m.runtimeDir, "README.txt").exists())
        assertFalse(File(m.binDir, "qemu-system-aarch64.tmp").exists())

        val status = m.inspect()
        assertNotNull(status.qemuAarch64Path)
        assertNotNull(status.uefiArm64Path)
        assertTrue(status.isReady)
        assertEquals(
            File(m.binDir, "qemu-system-aarch64").length() +
                File(m.firmwareDir, "edk2-aarch64-code.fd").length(),
            status.totalRuntimeBytes
        )
    }

    @Test
    fun `importRuntimeArchive rejects zip slip entries`() {
        val m = manager()
        val archive = zipOf(mapOf("../../qemu-system-x86_64" to elfBytes))

        assertThrows(IOException::class.java) {
            m.importRuntimeArchive(ByteArrayInputStream(archive))
        }

        assertFalse(File(m.rootDir, "qemu-system-x86_64").exists())
        assertFalse(File(m.rootDir.parentFile, "qemu-system-x86_64").exists())
        assertFalse(File(m.binDir, "qemu-system-x86_64").exists())
        assertEquals(0L, m.inspect().totalRuntimeBytes)
    }

    @Test
    fun `importRuntimeArchive skips non elf binaries instead of installing them`() {
        val m = manager()
        val archive = zipOf(
            mapOf(
                "bin/qemu-system-x86_64" to garbageBytes,
                "bin/qemu-img" to elfBytes
            )
        )

        val created = m.importRuntimeArchive(ByteArrayInputStream(archive))

        assertEquals(1L, created.size.toLong())
        assertEquals("qemu-img", created.first().name)
        assertFalse(File(m.binDir, "qemu-system-x86_64").exists())
        assertTrue(File(m.binDir, "qemu-img").isFile)
    }

    // ------------------------------------------------ 下载器纯函数与状态

    @Test
    fun `formatBytes renders binary units`() {
        assertEquals("未知", RomDownloader.formatBytes(-1L))
        assertEquals("0 B", RomDownloader.formatBytes(0L))
        assertEquals("512 B", RomDownloader.formatBytes(512L))
        assertEquals("1.0 KB", RomDownloader.formatBytes(1024L))
        assertEquals("1.5 KB", RomDownloader.formatBytes(1536L))
        assertEquals("1.0 MB", RomDownloader.formatBytes(1024L * 1024L))
        assertEquals("150 MB", RomDownloader.formatBytes(150L * 1024L * 1024L))
        assertEquals("2.5 GB", RomDownloader.formatBytes(2560L * 1024L * 1024L))
        assertEquals("2.5 TB", RomDownloader.formatBytes(2560L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `formatSpeed appends a per second suffix`() {
        assertEquals("未知", RomDownloader.formatSpeed(-1L))
        assertEquals("0 B/s", RomDownloader.formatSpeed(0L))
        assertEquals("512 B/s", RomDownloader.formatSpeed(512L))
        assertEquals("1.0 KB/s", RomDownloader.formatSpeed(1024L))
        assertEquals("3.0 MB/s", RomDownloader.formatSpeed(3L * 1024L * 1024L))
    }

    @Test
    fun `progressFraction handles known unknown and completed totals`() {
        // 50 / 100、0、上限截断都能用 Float 精确表示，直接比对象即可。
        assertEquals(
            0.5f,
            RomDownloadState("a", "a.img", downloadedBytes = 50L, totalBytes = 100L).progressFraction
        )
        assertEquals(
            0f,
            RomDownloadState("a", "a.img", downloadedBytes = 50L, totalBytes = -1L).progressFraction
        )
        assertEquals(
            0f,
            RomDownloadState("a", "a.img", downloadedBytes = 0L, totalBytes = 0L).progressFraction
        )
        assertEquals(
            1f,
            RomDownloadState("a", "a.img", downloadedBytes = 200L, totalBytes = 100L).progressFraction
        )
        assertEquals(
            1f,
            RomDownloadState(
                catalogId = "a",
                fileName = "a.img",
                downloadedBytes = 100L,
                totalBytes = -1L,
                status = DownloadStatus.COMPLETED
            ).progressFraction
        )
        assertEquals(
            1f,
            RomDownloadState("a", "a.img", status = DownloadStatus.COMPLETED).progressFraction
        )
    }

    @Test
    fun `download states expose chinese labels and finish flags`() {
        val active = RomDownloadState(
            catalogId = "android-x86-9",
            fileName = "android-x86-9.img",
            downloadedBytes = 1024L * 1024L,
            totalBytes = 4L * 1024L * 1024L,
            status = DownloadStatus.DOWNLOADING
        )
        assertEquals("下载中", active.status.label)
        assertEquals("25%", active.percentLabel)
        assertEquals("1.0 MB / 4.0 MB", active.sizeLabel)
        assertTrue(active.isActive)
        assertFalse(active.isFinished)

        val failed = active.copy(
            status = DownloadStatus.FAILED,
            errorMessage = "服务器返回 HTTP 404，无法下载"
        )
        assertEquals("下载失败", failed.status.label)
        assertEquals("服务器返回 HTTP 404，无法下载", failed.errorMessage)
        assertTrue(failed.isFinished)
        assertEquals("空闲", DownloadStatus.IDLE.label)
        assertEquals("已暂停", DownloadStatus.PAUSED.label)
        assertEquals("已取消", DownloadStatus.CANCELLED.label)
        assertEquals("已完成", DownloadStatus.COMPLETED.label)
    }

    @Test
    fun `downloader starts empty and tolerates unknown ids`() {
        val downloader = RomDownloader()

        assertTrue(downloader.downloads.value.isEmpty())
        assertNull(downloader.stateOf("missing"))
        downloader.cancel("missing")
        downloader.pause("missing")
        assertTrue(downloader.downloads.value.isEmpty())
    }
}
