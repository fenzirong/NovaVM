package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.AvfBackend
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.QemuBackend
import com.novavm.app.engine.VmInstanceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Regression coverage for the engine fixes: disk-format detection driven by the
 * real image header, QEMU-first backend routing, and the console fallback that
 * keeps pause/resume working when no guest process is alive.
 */
class EngineBugFixTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repository: VmRepository

    private val clock = 1_700_000_000_000L

    /**
     * AVF stub that always reports a usable `VirtualMachineManager`.
     *
     * The real class lives in the system-API android.jar, which the JVM unit
     * test classpath does not ship, so [AvfBackend.virtualMachineManagerClass]
     * has to be substituted for the "AVF is usable" branches to be reachable.
     */
    private class AvailableAvfBackend : AvfBackend() {
        override fun virtualMachineManagerClass(): Class<*>? = AvfBackend::class.java
    }

    @Before
    fun setUp() {
        repository = VmRepository(temp.newFolder("files"), clock = { clock })
        VmInstanceManager.resetForTests()
    }

    @After
    fun tearDown() {
        VmInstanceManager.resetForTests()
    }

    // ------------------------------------------------------------- fixtures

    private fun capabilities(
        avfSupported: Boolean = false,
        qemuBinaryPresent: Boolean = false,
        sdkInt: Int = 34
    ) = HostCapabilities(
        kvmAvailable = false,
        vhostVsockAvailable = true,
        avfSupported = avfSupported,
        cpuCores = 8,
        totalRamMb = 8192,
        primaryAbi = "arm64-v8a",
        qemuBinaryPresent = qemuBinaryPresent,
        sdkInt = sdkInt
    )

    private fun profile(
        id: String = "vm-engine",
        backendType: VmBackendType = VmBackendType.QEMU,
        diskImagePath: String = ""
    ) = VmProfile.create(
        id = id,
        name = "Engine VM",
        architecture = VmArchitecture.ARM64,
        backendType = backendType,
        romPath = "",
        diskImagePath = diskImagePath,
        hardware = VmHardwareConfig(cpuCores = 4, ramMb = 2048),
        deviceSpoof = DevicePresets.pixel8Pro,
        rootXposed = RootXposedConfig(),
        createdAtEpochMs = clock
    )

    /** Writes a zero-filled image, i.e. a raw image whatever the file name says. */
    private fun writeZeroFilledImage(file: File, sizeBytes: Long = 1L shl 20) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
    }

    /** Writes the `QFI\xfb` QCOW2 v3 magic plus a plausible virtual size. */
    private fun writeQcow2Header(file: File, virtualSizeBytes: Long = 16L shl 30) {
        val header = ByteArray(64)
        header[0] = 0x51
        header[1] = 0x46
        header[2] = 0x49
        header[3] = 0xFB.toByte()
        header[4] = 0x00
        header[5] = 0x00
        header[6] = 0x00
        header[7] = 0x03 // version 3, big endian
        for (index in 0 until 8) {
            header[24 + index] =
                ((virtualSizeBytes shr (56 - 8 * index)) and 0xFF).toByte()
        }
        file.parentFile?.mkdirs()
        file.writeBytes(header)
    }

    // ------------------------------------------------- P0-2: disk format

    @Test
    fun `disk format follows the image header instead of the file extension`() {
        val backend = QemuBackend()
        val images = temp.newFolder("images")

        // A raw image that merely *claims* to be QCOW2 by name: QEMU would refuse
        // to open it with format=qcow2, so the header must win.
        val mislabelled = File(images, "disk.qcow2")
        writeZeroFilledImage(mislabelled)
        assertEquals("raw", backend.diskFormatFor(mislabelled.absolutePath))

        // The real thing keeps working.
        val qcow2 = File(images, "golden.qcow2")
        writeQcow2Header(qcow2)
        assertEquals("qcow2", backend.diskFormatFor(qcow2.absolutePath))

        // A real raw image stays raw even with a neutral extension.
        val rawImage = File(images, "system.img")
        writeZeroFilledImage(rawImage, sizeBytes = 4096L)
        assertEquals("raw", backend.diskFormatFor(rawImage.absolutePath))

        // Only images that do not exist yet fall back to the extension.
        assertEquals("qcow2", backend.diskFormatFor(File(images, "future.qcow2").absolutePath))
        assertEquals("raw", backend.diskFormatFor(File(images, "future.img").absolutePath))
    }

    @Test
    fun `launch command attaches a mislabelled image as raw`() {
        val backend = QemuBackend()
        val disk = File(temp.newFolder("vms-disk"), "disk.qcow2")
        writeZeroFilledImage(disk)

        val command = backend.buildLaunchCommand(
            profile = profile(diskImagePath = disk.absolutePath),
            capabilities = capabilities()
        )
        val drive = command[command.indexOf("-drive") + 1]
        assertTrue("drive must attach the probed format: $drive", drive.contains("format=raw"))
        assertFalse(drive.contains("format=qcow2"))
    }

    // ------------------------------------------------ P0-3: AUTO => QEMU

    @Test
    fun `AUTO picks QEMU even when AVF reports support`() {
        val avf = AvailableAvfBackend()
        val host = capabilities(avfSupported = true, qemuBinaryPresent = false)
        // Guard: the seam really makes AVF look usable, otherwise this test
        // would prove nothing about the AUTO preference.
        assertTrue("AVF must be reported usable here", avf.isSupported(host))

        val manager = VmInstanceManager(
            repository = repository,
            capabilitiesProvider = { host },
            qemuBackend = QemuBackend(),
            avfBackend = avf,
            clock = { clock }
        )
        val auto = manager.createProfile(
            name = "Auto VM",
            architecture = VmArchitecture.ARM64,
            backendType = VmBackendType.AUTO
        )

        val built = manager.buildLaunchCommand(auto.id)
        assertTrue("AUTO must build a QEMU argv: $built", built != null && built.isNotEmpty())
        val command: List<String> = built ?: emptyList()
        assertEquals("qemu-system-aarch64", command.first())
        assertFalse("AUTO must not emit the AVF stub", command.any { it.startsWith("avf://") })

        assertTrue(manager.start(auto.id))
        assertTrue(
            "runtime must report the QEMU backend, was ${manager.state(auto.id)?.backendName}",
            manager.state(auto.id)!!.backendName.startsWith("QEMU")
        )
        assertEquals("qemu-system-aarch64", manager.lastLaunchCommand(auto.id)!!.first())
    }

    // ----------------------------------------- P0-3: AVF delegates to QEMU

    @Test
    fun `AVF delegates to QEMU when the qemu binary is present`() {
        val avf = AvailableAvfBackend()
        val withQemu = capabilities(avfSupported = true, qemuBinaryPresent = true)
        assertTrue("isSupported must stay untouched by the delegation", avf.isSupported(withQemu))

        val delegated = avf.buildLaunchCommand(profile(), withQemu)
        assertEquals("qemu-system-aarch64", delegated.first())
        assertFalse(delegated.contains("avf://launch"))
        assertTrue(delegated.contains("-qmp"))

        // Without a runnable QEMU binary the AVF descriptor remains the output.
        val stub = avf.buildLaunchCommand(profile(), withQemu.copy(qemuBinaryPresent = false))
        assertEquals("avf://launch", stub.first())
        assertTrue(stub.contains("--descriptor"))
    }

    // ------------------------------ P0-5: pause keeps the console fallback

    @Test
    fun `pause and resume keep console fallback without a live guest process`() {
        val host = capabilities(avfSupported = false, qemuBinaryPresent = false)
        val manager = VmInstanceManager(
            repository = repository,
            capabilitiesProvider = { host },
            clock = { clock }
        )
        val created = manager.createProfile(name = "Fallback VM")

        assertTrue(manager.start(created.id))
        assertTrue(manager.pause(created.id))
        assertTrue(
            manager.consoleLog(created.id).any { it.contains("VM paused (QMP stop requested)") }
        )
        assertTrue(manager.resume(created.id))
        assertTrue(
            manager.consoleLog(created.id).any { it.contains("VM resumed (QMP cont requested)") }
        )
        // No QMP attempt may be logged when no guest process exists.
        assertFalse(manager.consoleLog(created.id).any { it.contains("QMP stop on") })

        val snapshot = manager.takeSnapshot(created.id, "Golden", "baseline")
        assertTrue(manager.restoreSnapshot(created.id, snapshot!!.id))
        assertTrue(
            manager.consoleLog(created.id).any { it.contains("Restoring snapshot Golden") }
        )
        assertFalse(manager.restoreSnapshot(created.id, "missing-snapshot"))
    }
}
