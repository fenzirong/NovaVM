package com.novavm.app

import com.novavm.app.data.ImportedRom
import com.novavm.app.data.RomRepository
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Regression coverage for the persistence hardening batch.
 *
 * **P1-3** — `saveAll()` has to be crash safe (`.tmp` staging + `.bak` of the
 * previous snapshot + rename), and `loadAll()` must never report an empty store
 * just because the primary JSON is unreadable: it recovers from `.bak` and
 * otherwise keeps the corrupt bytes as `<name>.corrupt-<timestamp>`.
 *
 * **P1-4** — `allocatePorts()` has to skip a candidate triplet whose ports are
 * already bound on the host, not merely unmentioned in `profiles.json`.
 */
class RepositoryBugFixTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val clock = 1_700_000_000_000L

    /** Truncated JSON: the file exists, is non-empty, and cannot be decoded. */
    private val corruptPrimary = "{\"profiles\": [ {\"id\": \"broken-vm\","

    private fun newRoot(): File = temp.newFolder()

    private fun profile(id: String): VmProfile = VmProfile.create(
        name = "VM $id",
        architecture = VmArchitecture.ARM64,
        vncPort = VmRepository.DEFAULT_VNC_PORT,
        qmpPort = VmRepository.DEFAULT_QMP_PORT,
        adbForwardPort = VmRepository.DEFAULT_ADB_PORT,
        createdAtEpochMs = clock,
        id = id
    )

    private fun rom(id: String): ImportedRom = ImportedRom(
        id = id,
        displayName = "Image $id",
        fileName = "$id.img",
        absolutePath = File(temp.root, "images/$id.img").absolutePath,
        format = RomRepository.FORMAT_RAW,
        sizeBytes = 4096L,
        architecture = VmArchitecture.ARM64,
        importedAtEpochMs = clock
    )

    private fun corruptCopies(dir: File, fileName: String): List<File> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("$fileName.corrupt-") }
            .sortedBy { it.name }

    // ------------------------------------------------------------ P1-3 VMs

    @Test
    fun `vm loadAll recovers the profiles from backup when the primary is corrupt`() {
        val root = newRoot()
        val repository = VmRepository(root, clock = { clock })
        repository.saveAll(listOf(profile("vm-a")))
        repository.saveAll(listOf(profile("vm-a"), profile("vm-b")))

        assertTrue("second save must leave a backup", repository.profilesBackupFile.isFile)
        repository.profilesFile.writeText(corruptPrimary, Charsets.UTF_8)

        val recovered = VmRepository(root, clock = { clock }).loadAll()

        assertEquals(listOf("vm-a"), recovered.map { it.id })
        val copies = corruptCopies(repository.vmsDir, "profiles.json")
        assertEquals("corrupt primary must be preserved", 1, copies.size)
        assertEquals(corruptPrimary, copies.single().readText())

        // The primary is repaired, so reading again is stable and adds no copy.
        assertEquals(listOf("vm-a"), VmRepository(root).loadAll().map { it.id })
        assertEquals(1, corruptCopies(repository.vmsDir, "profiles.json").size)
    }

    @Test
    fun `vm loadAll preserves a corrupt primary when there is no backup`() {
        val root = newRoot()
        val repository = VmRepository(root, clock = { clock })
        repository.saveAll(listOf(profile("vm-a")))

        assertFalse("nothing existed to back up", repository.profilesBackupFile.exists())
        repository.profilesFile.writeText(corruptPrimary, Charsets.UTF_8)

        assertTrue(VmRepository(root, clock = { clock }).loadAll().isEmpty())

        val copies = corruptCopies(repository.vmsDir, "profiles.json")
        assertEquals(1, copies.size)
        assertEquals(corruptPrimary, copies.single().readText())
        assertEquals(
            "the unreadable file itself stays in place for manual repair",
            corruptPrimary,
            repository.profilesFile.readText()
        )
    }

    @Test
    fun `vm loadAll ignores an unparseable backup and still preserves the primary`() {
        val root = newRoot()
        val repository = VmRepository(root, clock = { clock })
        repository.saveAll(listOf(profile("vm-a")))
        repository.saveAll(listOf(profile("vm-a"), profile("vm-b")))

        repository.profilesFile.writeText(corruptPrimary, Charsets.UTF_8)
        repository.profilesBackupFile.writeText(corruptPrimary, Charsets.UTF_8)

        assertTrue(VmRepository(root, clock = { clock }).loadAll().isEmpty())
        assertEquals(1, corruptCopies(repository.vmsDir, "profiles.json").size)
    }

    @Test
    fun `vm saveAll backs up the previous snapshot and leaves no staging file`() {
        val root = newRoot()
        val repository = VmRepository(root)

        repository.saveAll(listOf(profile("vm-a")))
        repository.saveAll(listOf(profile("vm-a"), profile("vm-b")))

        val backup = repository.profilesBackupFile.readText()
        assertTrue(backup.contains("vm-a"))
        assertFalse("the backup holds the previous snapshot only", backup.contains("vm-b"))
        assertFalse(File(repository.vmsDir, "profiles.json.tmp").exists())
        assertEquals(listOf("vm-a", "vm-b"), repository.loadAll().map { it.id })
    }

    @Test
    fun `vm saveAll never replaces a good backup with a corrupt primary`() {
        val root = newRoot()
        val repository = VmRepository(root, clock = { clock })
        repository.saveAll(listOf(profile("vm-a")))
        repository.saveAll(listOf(profile("vm-a"), profile("vm-b")))
        repository.profilesFile.writeText(corruptPrimary, Charsets.UTF_8)

        VmRepository(root, clock = { clock }).saveAll(listOf(profile("vm-c")))

        val backup = repository.profilesBackupFile.readText()
        assertTrue(backup.contains("vm-a"))
        assertFalse("corrupt bytes must not become the fallback", backup.contains("broken-vm"))
        assertEquals(listOf("vm-c"), VmRepository(root).loadAll().map { it.id })
    }

    // ----------------------------------------------------------- P1-3 ROMs

    @Test
    fun `rom loadAll recovers the catalogue from backup when the index is corrupt`() {
        val root = newRoot()
        val repository = RomRepository(root, clock = { clock })
        repository.saveAll(listOf(rom("rom-a")))
        repository.saveAll(listOf(rom("rom-a"), rom("rom-b")))

        assertTrue("second save must leave a backup", repository.indexBackupFile.isFile)
        repository.indexFile.writeText(corruptPrimary, Charsets.UTF_8)

        val recovered = RomRepository(root, clock = { clock }).loadAll()

        assertEquals(listOf("rom-a"), recovered.map { it.id })
        val copies = corruptCopies(repository.romsDir, "roms.json")
        assertEquals("corrupt index must be preserved", 1, copies.size)
        assertEquals(corruptPrimary, copies.single().readText())

        assertEquals(listOf("rom-a"), RomRepository(root).loadAll().map { it.id })
        assertEquals(1, corruptCopies(repository.romsDir, "roms.json").size)
    }

    @Test
    fun `rom loadAll preserves a corrupt index when there is no backup`() {
        val root = newRoot()
        val repository = RomRepository(root, clock = { clock })
        repository.saveAll(listOf(rom("rom-a")))

        assertFalse(repository.indexBackupFile.exists())
        repository.indexFile.writeText(corruptPrimary, Charsets.UTF_8)

        assertTrue(RomRepository(root, clock = { clock }).loadAll().isEmpty())

        val copies = corruptCopies(repository.romsDir, "roms.json")
        assertEquals(1, copies.size)
        assertEquals(corruptPrimary, copies.single().readText())
        assertEquals(corruptPrimary, repository.indexFile.readText())
    }

    @Test
    fun `rom saveAll writes through a staging file and backs up the previous index`() {
        val root = newRoot()
        val repository = RomRepository(root)

        repository.saveAll(listOf(rom("rom-a")))
        repository.saveAll(listOf(rom("rom-a"), rom("rom-b")))

        val backup = repository.indexBackupFile.readText()
        assertTrue(backup.contains("rom-a"))
        assertFalse(backup.contains("rom-b"))
        assertFalse(File(repository.romsDir, "roms.json.tmp").exists())
        assertEquals(listOf("rom-a", "rom-b"), repository.loadAll().map { it.id })
    }

    @Test
    fun `rom saveAll never replaces a good backup with a corrupt index`() {
        val root = newRoot()
        val repository = RomRepository(root, clock = { clock })
        repository.saveAll(listOf(rom("rom-a")))
        repository.saveAll(listOf(rom("rom-a"), rom("rom-b")))
        repository.indexFile.writeText(corruptPrimary, Charsets.UTF_8)

        RomRepository(root, clock = { clock }).saveAll(listOf(rom("rom-c")))

        val backup = repository.indexBackupFile.readText()
        assertTrue(backup.contains("rom-a"))
        assertFalse(backup.contains("broken-vm"))
        assertEquals(listOf("rom-c"), RomRepository(root).loadAll().map { it.id })
    }

    // ---------------------------------------------------------------- P1-4

    @Test
    fun `allocatePorts skips a port that is already bound on the host`() {
        val root = newRoot()
        val repository = VmRepository(root, clock = { clock })

        var listener: ServerSocket? = null
        var blockedPort = -1
        for (offset in 0 until 32) {
            val candidate = VmRepository.DEFAULT_VNC_PORT + offset
            val socket = bindLoopback(candidate)
            if (socket != null) {
                listener = socket
                blockedPort = candidate
                break
            }
        }
        assumeTrue("no default VNC slot was bindable on this host", listener != null)

        try {
            assertFalse(
                "a live listener must be reported as unavailable",
                repository.isHostPortAvailable(blockedPort)
            )

            val allocation = repository.allocatePorts(emptyList())

            assertNotEquals(blockedPort, allocation.vncPort)
            assertTrue(
                "allocation must move past the busy slot",
                allocation.vncPort > blockedPort
            )
            val offset = allocation.vncPort - VmRepository.DEFAULT_VNC_PORT
            assertEquals(VmRepository.DEFAULT_QMP_PORT + offset, allocation.qmpPort)
            assertEquals(VmRepository.DEFAULT_ADB_PORT + offset, allocation.adbForwardPort)
            assertTrue(repository.isHostPortAvailable(allocation.vncPort))
            assertTrue(repository.isHostPortAvailable(allocation.qmpPort))
            assertTrue(repository.isHostPortAvailable(allocation.adbForwardPort))
        } finally {
            runCatching { listener?.close() }
        }
    }

    @Test
    fun `isHostPortAvailable reports true again once the listener is released`() {
        val port = VmRepository.DEFAULT_VNC_PORT + 64
        val probe = bindLoopback(port)
        assumeTrue("port probe unavailable on this host", probe != null)
        try {
            assertFalse(VmRepository(newRoot()).isHostPortAvailable(port))
        } finally {
            runCatching { probe?.close() }
        }
        assertTrue(VmRepository(newRoot()).isHostPortAvailable(port))
    }

    private fun bindLoopback(port: Int): ServerSocket? = try {
        val socket = ServerSocket()
        // Deliberately *not* reusing the address: the blocker has to own the
        // port exclusively, exactly like a real QEMU listener would.
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName(VmRepository.HOST_LOOPBACK), port))
        socket
    } catch (io: IOException) {
        null
    }
}
