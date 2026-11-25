package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.data.VmStatus
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.VmInstanceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmRepositoryAndManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repository: VmRepository

    private val capabilities = HostCapabilities(
        kvmAvailable = false,
        vhostVsockAvailable = false,
        avfSupported = false,
        cpuCores = 8,
        totalRamMb = 8192,
        primaryAbi = "arm64-v8a",
        qemuBinaryPresent = false,
        sdkInt = 34
    )

    @Before
    fun setUp() {
        root = temp.newFolder("files")
        repository = VmRepository(root, clock = { 1_700_000_000_000L })
        VmInstanceManager.resetForTests()
    }

    @After
    fun tearDown() {
        VmInstanceManager.resetForTests()
    }

    private fun newProfile(name: String = "Alpha", id: String = "vm-alpha"): VmProfile =
        VmProfile.create(
            id = id,
            name = name,
            architecture = VmArchitecture.ARM64,
            hardware = VmHardwareConfig(cpuCores = 4, ramMb = 2048),
            deviceSpoof = DevicePresets.pixel8Pro,
            createdAtEpochMs = 1_700_000_000_000L
        )

    @Test
    fun `create writes profile and per-VM directory tree`() {
        val profile = repository.create(newProfile())

        assertEquals(5901, profile.vncPort)
        assertEquals(4444, profile.qmpPort)
        assertEquals(15555, profile.adbForwardPort)
        assertTrue(File(root, "vms/profiles.json").isFile)
        assertTrue(File(root, "vms/${profile.id}/shared").isDirectory)
        assertTrue(File(root, "vms/${profile.id}/shared/apks").isDirectory)
        assertTrue(File(root, "vms/${profile.id}/modules").isDirectory)
        assertTrue(File(root, "vms/${profile.id}/logs").isDirectory)
        assertEquals(listOf(profile), repository.loadAll())
    }

    @Test
    fun `profiles survive a JSON reload with a fresh repository instance`() {
        val created = repository.create(newProfile())
        val reloaded = VmRepository(root)

        val loaded = reloaded.get(created.id)
        assertNotNull(loaded)
        assertEquals(created, loaded)
        assertEquals(created.hardware, loaded!!.hardware)
        assertEquals(created.deviceSpoof, loaded.deviceSpoof)
        assertEquals(created.rootXposed, loaded.rootXposed)
        assertTrue(reloaded.profilesFile.readText().contains("\"profiles\""))
        assertTrue(reloaded.profilesFile.readText().contains("vm-alpha"))
    }

    @Test
    fun `port allocation is unique across VMs`() {
        val first = repository.create(newProfile("One", "vm-1"))
        val second = repository.create(newProfile("Two", "vm-2"))
        val third = repository.create(newProfile("Three", "vm-3"))

        assertEquals(5901, first.vncPort)
        assertEquals(5902, second.vncPort)
        assertEquals(5903, third.vncPort)
        assertEquals(4445, second.qmpPort)
        assertEquals(15556, second.adbForwardPort)

        val allPorts = repository.loadAll()
            .flatMap { listOf(it.vncPort, it.qmpPort, it.adbForwardPort) }
        assertEquals(9, allPorts.size)
        assertEquals(9, allPorts.toSet().size)
        assertEquals(5904, repository.allocatePorts().vncPort)
        assertFalse(repository.isPortFree(5901))
        assertTrue(repository.isPortFree(5999))
    }

    @Test
    fun `clone duplicates metadata with new id ports and disk path`() {
        val source = repository.create(newProfile("Source", "vm-source"))
        val clone = repository.clone(source.id, "Snapshot Clone")

        assertNotNull(clone)
        assertNotEquals(source.id, clone!!.id)
        assertEquals("Snapshot Clone", clone.name)
        assertEquals(source.architecture, clone.architecture)
        assertEquals(source.hardware, clone.hardware)
        assertEquals(source.deviceSpoof, clone.deviceSpoof)
        assertEquals(5902, clone.vncPort)
        assertNotEquals(source.diskImagePath, clone.diskImagePath)
        assertTrue(clone.diskImagePath.contains(clone.id))
        assertTrue(repository.vmDir(clone.id).isDirectory)
        assertEquals(2, repository.loadAll().size)
        assertNull(repository.clone("missing-vm"))
    }

    @Test
    fun `snapshot metadata is persisted and removable`() {
        val vm = repository.create(newProfile())
        val snapshot = repository.addSnapshot(
            vmId = vm.id,
            name = "Before OTA",
            description = "clean state",
            createdAtEpochMs = 1_700_000_123_000L,
            snapshotId = "snap-1"
        )

        assertNotNull(snapshot)
        assertEquals("snap-1", snapshot!!.id)
        assertEquals("Before OTA", snapshot.name)
        assertEquals(1_700_000_123_000L, snapshot.createdAtEpochMs)
        assertEquals("clean state", snapshot.description)

        val reloaded = VmRepository(root).get(vm.id)!!
        assertEquals(1, reloaded.snapshots.size)
        assertEquals(snapshot, reloaded.snapshots.single())
        assertNotNull(reloaded.snapshots.firstOrNull { it.id == "snap-1" })
        assertNotNull(VmRepository(root).findSnapshot(vm.id, "snap-1"))

        assertTrue(repository.removeSnapshot(vm.id, "snap-1"))
        assertTrue(VmRepository(root).get(vm.id)!!.snapshots.isEmpty())
        assertFalse(repository.removeSnapshot(vm.id, "snap-1"))
        assertNull(repository.addSnapshot("unknown", "nope"))
    }

    @Test
    fun `delete removes profile and files and is idempotent`() {
        val vm = repository.create(newProfile())
        val vmDir = repository.vmDir(vm.id)
        assertTrue(vmDir.isDirectory)

        assertTrue(repository.delete(vm.id))
        assertNull(repository.get(vm.id))
        assertFalse(vmDir.exists())
        assertTrue(repository.loadAll().isEmpty())
        assertFalse(repository.delete(vm.id))
    }

    @Test
    fun `manager exposes runtime state through StateFlow`() {
        val manager = VmInstanceManager(
            repository,
            capabilitiesProvider = { capabilities },
            clock = { 1_700_000_500_000L }
        )
        val profile = manager.createProfile(
            name = "Engine VM",
            architecture = VmArchitecture.ARM64,
            rootXposed = RootXposedConfig(rootEnabled = true)
        )

        val initial = manager.states.value.single()
        assertEquals(profile.id, initial.vmId)
        assertEquals(VmStatus.STOPPED, initial.status)
        assertEquals(profile.vncPort, initial.vncPort)
        assertTrue(manager.consoleLog(profile.id).isNotEmpty())

        assertTrue(manager.start(profile.id))
        assertEquals(VmStatus.RUNNING, manager.state(profile.id)!!.status)
        val command = manager.lastLaunchCommand(profile.id)
        assertNotNull(command)
        assertTrue(command!!.first().startsWith("qemu-system-aarch64"))
        assertTrue(manager.consoleLog(profile.id).any { it.contains("Launching") })
        assertFalse("already running", manager.start(profile.id))

        assertTrue(manager.pause(profile.id))
        assertEquals(VmStatus.PAUSED, manager.state(profile.id)!!.status)
        assertFalse(manager.pause(profile.id))
        assertTrue(manager.resume(profile.id))
        assertEquals(VmStatus.RUNNING, manager.state(profile.id)!!.status)

        assertTrue(manager.stop(profile.id))
        assertEquals(VmStatus.STOPPED, manager.state(profile.id)!!.status)
        assertFalse(manager.stop(profile.id))
    }

    @Test
    fun `manager creates clones snapshots and deletion`() {
        val manager = VmInstanceManager(
            repository,
            capabilitiesProvider = { capabilities },
            clock = { 1_700_000_600_000L }
        )
        val first = manager.createProfile("First")
        val second = manager.createProfile("Second")

        assertNotEquals(first.vncPort, second.vncPort)
        assertEquals(2, manager.states.value.size)

        val clone = manager.cloneProfile(first.id, "First Clone")
        assertNotNull(clone)
        assertEquals("First Clone", clone!!.name)
        assertEquals(3, repository.loadAll().size)
        assertEquals(clone.vncPort, manager.state(clone.id)!!.vncPort)

        val snapshot = manager.takeSnapshot(first.id, "Golden", "baseline")
        assertNotNull(snapshot)
        assertEquals("Golden", repository.findSnapshot(first.id, snapshot!!.id)!!.name)
        assertTrue(manager.restoreSnapshot(first.id, snapshot.id))
        assertFalse(manager.restoreSnapshot(first.id, "missing"))
        assertTrue(manager.deleteSnapshot(first.id, snapshot.id))

        assertTrue(manager.deleteProfile(second.id))
        assertNull(manager.state(second.id))
        assertEquals(2, manager.states.value.size)
        assertFalse(manager.deleteProfile("missing-vm"))
    }

    @Test
    fun `manager start requires an existing profile`() {
        val manager = VmInstanceManager(repository, capabilitiesProvider = { capabilities })
        assertFalse(manager.start("nope"))
        assertNull(manager.buildLaunchCommand("nope"))
        assertNull(manager.state("nope"))
    }
}
