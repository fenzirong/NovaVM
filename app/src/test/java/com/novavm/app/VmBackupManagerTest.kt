package com.novavm.app

import com.novavm.app.data.BackupSummary
import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofGenerator
import com.novavm.app.data.GeoPoint
import com.novavm.app.data.GpuMode
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.RouteLoopMode
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmBackupManager
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class VmBackupManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val baseTime = 1_700_000_000_000L

    private lateinit var root: File
    private lateinit var repository: VmRepository
    private lateinit var manager: VmBackupManager

    @Before
    fun setUp() {
        root = temp.newFolder("files")
        repository = VmRepository(root, clock = { baseTime })
        manager = VmBackupManager(clock = { baseTime + 60_000L })
    }

    // ------------------------------------------------------------- fixtures

    /** Fully featured profile: nothing here should change across a backup. */
    private fun sourceProfile(): VmProfile {
        val vmDirectory = File(repository.vmsDir, "vm-source")
        return VmProfile(
            id = "vm-source",
            name = "Field Phone",
            architecture = VmArchitecture.ARM64,
            backendType = VmBackendType.QEMU,
            romPath = "/roms/novavm-android14.img",
            diskImagePath = File(vmDirectory, "disk.qcow2").absolutePath,
            hardware = VmHardwareConfig(
                cpuCores = 6,
                ramMb = 4096,
                diskSizeGb = 32,
                width = 1080,
                height = 2400,
                dpi = 420,
                targetFps = 90,
                gpuMode = GpuMode.VIRGL_OPENGL
            ),
            deviceSpoof = DeviceSpoofGenerator.forPreset(
                DevicePresets.galaxyS24Ultra,
                Random(7)
            ),
            rootXposed = RootXposedConfig(
                rootEnabled = true,
                xposedEnabled = true,
                selinuxPermissive = true,
                magiskHideEnabled = true,
                stagedModules = listOf("lsposed-1.9.2.zip", "shamiko-1.1.zip")
            ),
            virtualLocation = VirtualLocationConfig(
                enabled = true,
                presetName = "东京 · 涩谷",
                latitude = 35.6595,
                longitude = 139.7004,
                altitude = 40.0,
                accuracyMeters = 3.5f,
                speedMps = 2.5f,
                bearingDegrees = 128.0f,
                mode = VirtualLocationMode.ROUTE_WALK,
                jitterEnabled = false,
                routeWaypoints = listOf(
                    GeoPoint(35.6595, 139.7004, 40.0, "start"),
                    GeoPoint(35.6601, 139.7012, 41.5, "end")
                ),
                loopMode = RouteLoopMode.PING_PONG
            ),
            vncPort = VmRepository.DEFAULT_VNC_PORT,
            qmpPort = VmRepository.DEFAULT_QMP_PORT,
            adbForwardPort = VmRepository.DEFAULT_ADB_PORT,
            createdAtEpochMs = baseTime
        )
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun failureOf(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

    private fun stagingDirectories(): List<File> =
        root.listFiles().orEmpty().filter { it.name.startsWith(".novavm-import-") }

    // --------------------------------------------------------------- export

    @Test
    fun `export writes profile and user data but skips disk images`() {
        val source = repository.create(sourceProfile())
        val sourceDir = repository.vmDir(source.id)

        val notes = "hello from the host share\n"
        val apkBytes = ByteArray(4096) { (it % 251).toByte() }
        val moduleProp = "id=shamiko\nname=Shamiko\nversion=v1.1\n"
        File(sourceDir, "shared/notes.txt").apply { parentFile.mkdirs(); writeText(notes) }
        File(sourceDir, "shared/apks/demo.apk").apply { parentFile.mkdirs(); writeBytes(apkBytes) }
        File(sourceDir, "modules/shamiko/module.prop").apply {
            parentFile.mkdirs()
            writeText(moduleProp)
        }
        // A stand-in disk image: must never reach the archive.
        File(sourceDir, "disk.qcow2").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }

        val exported = ByteArrayOutputStream()
        val summary: BackupSummary = manager.exportVmToZip(source, sourceDir, exported)
        val bytes = exported.toByteArray()

        assertTrue(bytes.isNotEmpty())
        assertEquals(source.displayName, summary.vmName)
        // profile.json + notes.txt + demo.apk + module.prop
        assertEquals(4, summary.fileCount)
        assertTrue(
            summary.totalBytes >=
                apkBytes.size + notes.toByteArray().size + moduleProp.toByteArray().size
        )

        val entries = entryNames(bytes)
        assertTrue(entries.contains("profile.json"))
        assertTrue(entries.contains("shared/notes.txt"))
        assertTrue(entries.contains("shared/apks/demo.apk"))
        assertTrue(entries.contains("modules/shamiko/module.prop"))
        assertFalse("disk image leaked into backup: $entries", entries.any { it.contains("qcow2") })
    }

    // ----------------------------------------------------------- round trip

    @Test
    fun `import restores the exported VM with a fresh identity and user data`() {
        val source = repository.create(sourceProfile())
        val sourceDir = repository.vmDir(source.id)

        val notes = "hello from the host share\n"
        val apkBytes = ByteArray(4096) { (it % 251).toByte() }
        val moduleProp = "id=shamiko\nname=Shamiko\nversion=v1.1\n"
        File(sourceDir, "shared/notes.txt").apply { parentFile.mkdirs(); writeText(notes) }
        File(sourceDir, "shared/apks/demo.apk").apply { parentFile.mkdirs(); writeBytes(apkBytes) }
        File(sourceDir, "modules/shamiko/module.prop").apply {
            parentFile.mkdirs()
            writeText(moduleProp)
        }
        File(sourceDir, "disk.qcow2").apply { writeBytes(ByteArray(1024 * 1024)) }

        val exported = ByteArrayOutputStream()
        manager.exportVmToZip(source, sourceDir, exported)
        val bytes = exported.toByteArray()

        val imported = manager.importVmFromZip(ByteArrayInputStream(bytes), repository)

        // --- fresh identity -------------------------------------------------
        assertNotEquals(source.id, imported.id)
        assertEquals(36, imported.id.length)
        assertEquals(imported, repository.get(imported.id))
        assertEquals("Field Phone", imported.name)
        assertEquals(baseTime + 60_000L, imported.createdAtEpochMs)
        assertTrue(imported.snapshots.isEmpty())

        // --- hardware preserved --------------------------------------------
        assertEquals(source.architecture, imported.architecture)
        assertEquals(source.backendType, imported.backendType)
        assertEquals(source.romPath, imported.romPath)
        assertEquals(source.hardware, imported.hardware)
        assertEquals(6, imported.hardware.cpuCores)
        assertEquals(4096, imported.hardware.ramMb)
        assertEquals(32, imported.hardware.diskSizeGb)
        assertEquals(1080, imported.hardware.width)
        assertEquals(2400, imported.hardware.height)
        assertEquals(420, imported.hardware.dpi)
        assertEquals(90, imported.hardware.targetFps)
        assertEquals(GpuMode.VIRGL_OPENGL, imported.hardware.gpuMode)

        // --- spoof preserved ------------------------------------------------
        assertEquals(source.deviceSpoof, imported.deviceSpoof)
        assertEquals(source.deviceSpoof.imei, imported.deviceSpoof.imei)
        assertEquals(source.deviceSpoof.androidId, imported.deviceSpoof.androidId)
        assertEquals(source.deviceSpoof.wifiMac, imported.deviceSpoof.wifiMac)

        // --- root / xposed preserved ---------------------------------------
        assertEquals(source.rootXposed, imported.rootXposed)
        assertTrue(imported.rootXposed.rootEnabled)
        assertTrue(imported.rootXposed.xposedEnabled)
        assertTrue(imported.rootXposed.magiskHideEnabled)
        assertEquals(
            listOf("lsposed-1.9.2.zip", "shamiko-1.1.zip"),
            imported.rootXposed.stagedModules
        )

        // --- virtual location preserved ------------------------------------
        assertEquals(source.virtualLocation, imported.virtualLocation)
        assertEquals(35.6595, imported.virtualLocation.latitude, 1e-9)
        assertEquals(139.7004, imported.virtualLocation.longitude, 1e-9)
        assertEquals(VirtualLocationMode.ROUTE_WALK, imported.virtualLocation.mode)
        assertEquals(RouteLoopMode.PING_PONG, imported.virtualLocation.loopMode)
        assertEquals(2, imported.virtualLocation.routeWaypoints.size)
        assertEquals("东京 · 涩谷", imported.virtualLocation.presetName)

        // --- fresh disk path, no disk payload, no snapshots -----------------
        assertEquals(
            File(repository.vmDir(imported.id), "disk.qcow2").absolutePath,
            imported.diskImagePath
        )
        assertNotEquals(source.diskImagePath, imported.diskImagePath)
        assertTrue(imported.diskImagePath.contains(imported.id))
        assertFalse(File(imported.diskImagePath).exists())

        // --- fresh non-colliding ports -------------------------------------
        assertNotEquals(source.vncPort, imported.vncPort)
        assertNotEquals(source.qmpPort, imported.qmpPort)
        assertNotEquals(source.adbForwardPort, imported.adbForwardPort)
        assertTrue(imported.vncPort > 1024)
        assertTrue(imported.qmpPort > 1024)
        assertTrue(imported.adbForwardPort > 1024)
        val allPorts = repository.loadAll()
            .flatMap { listOf(it.vncPort, it.qmpPort, it.adbForwardPort) }
        assertEquals(6, allPorts.size)
        assertEquals("ports collided after import: $allPorts", 6, allPorts.toSet().size)

        // --- user data landed in the new VM directory ----------------------
        val importedDir = repository.vmDir(imported.id)
        assertEquals(notes, File(importedDir, "shared/notes.txt").readText())
        assertArrayEquals(apkBytes, File(importedDir, "shared/apks/demo.apk").readBytes())
        assertEquals(moduleProp, File(importedDir, "modules/shamiko/module.prop").readText())
        assertTrue(File(importedDir, "shared/apks").isDirectory)
        assertTrue(File(importedDir, "logs").isDirectory)
        assertFalse(File(importedDir, "disk.qcow2").exists())
        assertTrue(stagingDirectories().isEmpty())
    }

    @Test
    fun `name override renames the import and blank overrides are ignored`() {
        val source = repository.create(sourceProfile())
        File(repository.vmDir(source.id), "shared/notes.txt").apply {
            parentFile.mkdirs()
            writeText("seeded")
        }
        val exported = ByteArrayOutputStream()
        manager.exportVmToZip(source, repository.vmDir(source.id), exported)
        val bytes = exported.toByteArray()

        val restored = manager.importVmFromZip(
            ByteArrayInputStream(bytes),
            repository,
            nameOverride = "Restored Copy"
        )
        val blankOverride = manager.importVmFromZip(
            ByteArrayInputStream(bytes),
            repository,
            nameOverride = "   "
        )

        assertEquals("Restored Copy", restored.name)
        assertEquals("Field Phone", blankOverride.name)
        assertNotEquals(restored.id, blankOverride.id)

        assertEquals(3, repository.loadAll().size)
        val ports = repository.loadAll()
            .flatMap { listOf(it.vncPort, it.qmpPort, it.adbForwardPort) }
        assertEquals(9, ports.size)
        assertEquals("three VMs must not share ports: $ports", 9, ports.toSet().size)
        assertEquals("seeded", File(repository.vmDir(restored.id), "shared/notes.txt").readText())
    }

    // -------------------------------------------------------------- Zip-Slip

    @Test
    fun `zip slip entry is rejected and nothing is imported`() {
        val profileJson = VmRepository.defaultJson.encodeToString(sourceProfile())
        val malicious = zipOf(
            "profile.json" to profileJson.toByteArray(),
            "../../evil.txt" to "pwned".toByteArray()
        )

        val failure = failureOf {
            manager.importVmFromZip(ByteArrayInputStream(malicious), repository)
        }

        assertNotNull("a traversal entry must not import cleanly", failure)
        assertTrue(
            "expected SecurityException or IllegalArgumentException but got ${failure!!::class.java.name}",
            failure is SecurityException || failure is IllegalArgumentException
        )
        assertTrue(repository.loadAll().isEmpty())
        assertFalse(File(root.parentFile, "evil.txt").exists())
        assertFalse(File(root, "evil.txt").exists())
        assertTrue(stagingDirectories().isEmpty())
    }

    @Test
    fun `nested and backslash traversal entries are rejected too`() {
        val profileJson = VmRepository.defaultJson.encodeToString(sourceProfile())

        listOf("shared/../../evil.txt", "..\\..\\evil.txt", "/etc/passwd").forEach { entryName ->
            val archive = zipOf(
                "profile.json" to profileJson.toByteArray(),
                entryName to "pwned".toByteArray()
            )
            val failure = failureOf {
                manager.importVmFromZip(ByteArrayInputStream(archive), repository)
            }
            assertNotNull("entry $entryName must be rejected", failure)
            assertTrue(
                "entry $entryName should raise SecurityException/IllegalArgumentException " +
                    "but got ${failure!!::class.java.name}",
                failure is SecurityException || failure is IllegalArgumentException
            )
        }

        assertTrue(repository.loadAll().isEmpty())
        assertFalse(File(root.parentFile, "evil.txt").exists())
        assertTrue(stagingDirectories().isEmpty())
    }

    @Test
    fun `archive without profile json is rejected`() {
        val archive = zipOf("shared/notes.txt" to "orphan".toByteArray())

        val failure = failureOf {
            manager.importVmFromZip(ByteArrayInputStream(archive), repository)
        }

        assertTrue(
            "expected IllegalArgumentException but got ${failure?.let { it::class.java.name }}",
            failure is IllegalArgumentException
        )
        assertTrue(repository.loadAll().isEmpty())
        assertTrue(stagingDirectories().isEmpty())
    }

    @Test
    fun `corrupt profile json is rejected`() {
        val archive = zipOf("profile.json" to "{ not json".toByteArray())

        val failure = failureOf {
            manager.importVmFromZip(ByteArrayInputStream(archive), repository)
        }

        assertTrue(
            "expected IllegalArgumentException but got ${failure?.let { it::class.java.name }}",
            failure is IllegalArgumentException
        )
        assertTrue(repository.loadAll().isEmpty())
    }
}
