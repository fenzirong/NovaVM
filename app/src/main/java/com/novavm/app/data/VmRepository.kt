package com.novavm.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Port triplet handed out to a VM. */
data class VmPortAllocation(
    val vncPort: Int,
    val qmpPort: Int,
    val adbForwardPort: Int
)

@Serializable
internal data class VmProfilesFile(
    val version: Int = 1,
    val profiles: List<VmProfile> = emptyList()
)

/**
 * JSON backed VM profile store.
 *
 * Deliberately free of Android APIs (no Context, no SharedPreferences) so the
 * whole persistence layer runs on a plain JVM in unit tests: pass any writable
 * directory as [rootDir].
 *
 * Layout:
 * ```
 * <rootDir>/vms/profiles.json
 * <rootDir>/vms/<vmId>/shared/
 * <rootDir>/vms/<vmId>/shared/apks/
 * <rootDir>/vms/<vmId>/modules/
 * <rootDir>/vms/<vmId>/logs/
 * ```
 */
class VmRepository(
    val rootDir: File,
    private val json: Json = defaultJson,
    private val clock: () -> Long = System::currentTimeMillis
) {

    val vmsDir: File = File(rootDir, "vms")
    val profilesFile: File = File(vmsDir, "profiles.json")

    /** Last known-good snapshot of [profilesFile], refreshed on every save. */
    val profilesBackupFile: File = File(vmsDir, "profiles.json.bak")

    companion object {
        const val DEFAULT_VNC_PORT = 5901
        const val DEFAULT_QMP_PORT = 4444
        const val DEFAULT_ADB_PORT = 15555

        /** Loopback address probed by [isHostPortAvailable]. */
        const val HOST_LOOPBACK = "127.0.0.1"

        private const val MAX_TCP_PORT = 65535

        val defaultJson: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // ---------------------------------------------------------------- paths

    fun vmDir(vmId: String): File = File(vmsDir, vmId)

    fun sharedDir(vmId: String): File = File(vmDir(vmId), "shared")

    fun apkStagingDir(vmId: String): File = File(sharedDir(vmId), "apks")

    fun modulesDir(vmId: String): File = File(vmDir(vmId), "modules")

    fun logsDir(vmId: String): File = File(vmDir(vmId), "logs")

    fun sharedFolderFor(profile: VmProfile): File = sharedDir(profile.id)

    /** Creates the per-VM directory tree, returns the VM root. */
    fun ensureVmDirectories(vmId: String): File {
        val dirs = listOf(
            vmDir(vmId),
            sharedDir(vmId),
            apkStagingDir(vmId),
            modulesDir(vmId),
            logsDir(vmId)
        )
        dirs.forEach { dir ->
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Unable to create directory ${dir.absolutePath}")
            }
        }
        return vmDir(vmId)
    }

    // ------------------------------------------------------------ read/write

    @Synchronized
    fun loadAll(): List<VmProfile> {
        if (!profilesFile.exists()) return emptyList()
        val raw = JsonPersistence.readOrNull(profilesFile) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        parseProfiles(raw)?.let { return it }

        // The primary file exists and is non-empty, yet does not parse. Never
        // report "no VMs" for that: fall back to the last good backup and keep
        // the unreadable bytes aside so a later save cannot destroy them.
        val recovered = JsonPersistence.readOrNull(profilesBackupFile)?.let(::parseProfiles)
        JsonPersistence.preserveCorrupt(profilesFile, clock())
        if (recovered != null) {
            // Repair the primary so the next save starts from good state again.
            runCatching {
                JsonPersistence.writeAtomically(profilesFile, encodeProfiles(recovered))
            }
            return recovered
        }
        return emptyList()
    }

    @Synchronized
    fun saveAll(profiles: List<VmProfile>) {
        if (!vmsDir.exists() && !vmsDir.mkdirs()) {
            throw IOException("Unable to create ${vmsDir.absolutePath}")
        }
        // Only a parseable primary is worth keeping as the fallback snapshot; a
        // corrupt one must not overwrite the last good backup.
        JsonPersistence.backupIfValid(profilesFile, profilesBackupFile) { parseProfiles(it) != null }
        JsonPersistence.writeAtomically(profilesFile, encodeProfiles(profiles))
    }

    private fun encodeProfiles(profiles: List<VmProfile>): String =
        json.encodeToString(VmProfilesFile(profiles = profiles))

    /** Parses a profiles document, or null when [raw] cannot be decoded. */
    private fun parseProfiles(raw: String): List<VmProfile>? {
        if (raw.isBlank()) return null
        runCatching { json.decodeFromString<VmProfilesFile>(raw).profiles }
            .getOrNull()
            ?.let { return it }
        // Tolerate a bare JSON array written by older builds.
        runCatching { json.decodeFromString<List<VmProfile>>(raw) }
            .getOrNull()
            ?.let { return it }
        return null
    }

    fun get(vmId: String): VmProfile? = loadAll().firstOrNull { it.id == vmId }

    fun require(vmId: String): VmProfile =
        get(vmId) ?: throw NoSuchElementException("No VM profile with id=$vmId")

    // ---------------------------------------------------------------- ports

    fun usedPorts(profiles: List<VmProfile> = loadAll()): Set<Int> =
        profiles.flatMap { listOf(it.vncPort, it.qmpPort, it.adbForwardPort) }.toSet()

    /**
     * True when nothing on the host currently holds [port] on the loopback
     * interface. A throwaway [ServerSocket] is bound and released immediately,
     * which catches a QEMU instance that is still running (or one that died
     * without releasing its listener) and would otherwise collide on launch.
     */
    fun isHostPortAvailable(port: Int): Boolean {
        if (port !in 1..MAX_TCP_PORT) return false
        return try {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(HOST_LOOPBACK), port))
                true
            } finally {
                socket.close()
            }
        } catch (io: IOException) {
            false
        } catch (security: SecurityException) {
            false
        }
    }

    /**
     * First free VNC/QMP/ADB triplet, scanning 1000 slots from the defaults.
     * A candidate has to be unclaimed in the profile store *and* free on the
     * host, so two VMs never collide on a port that is already listening.
     */
    fun allocatePorts(profiles: List<VmProfile> = loadAll()): VmPortAllocation {
        val used = usedPorts(profiles)
        for (offset in 0 until 1000) {
            val vnc = DEFAULT_VNC_PORT + offset
            val qmp = DEFAULT_QMP_PORT + offset
            val adb = DEFAULT_ADB_PORT + offset
            if (vnc in used || qmp in used || adb in used) continue
            if (!isHostPortAvailable(vnc)) continue
            if (!isHostPortAvailable(qmp)) continue
            if (!isHostPortAvailable(adb)) continue
            return VmPortAllocation(vnc, qmp, adb)
        }
        throw IllegalStateException("No free port triplet available")
    }

    fun isPortFree(port: Int, profiles: List<VmProfile> = loadAll()): Boolean =
        port !in usedPorts(profiles)

    // ------------------------------------------------------------ lifecycle

    @Synchronized
    fun create(profile: VmProfile): VmProfile {
        val existing = loadAll()
        require(existing.none { it.id == profile.id }) {
            "VM with id=${profile.id} already exists"
        }
        val requested = setOf(profile.vncPort, profile.qmpPort, profile.adbForwardPort)
        val free = allocatePorts(existing)
        val finalPorts = if (requested.intersect(usedPorts(existing)).isEmpty() &&
            requested.size == 3 && requested.all { it > 1024 }
        ) {
            VmPortAllocation(profile.vncPort, profile.qmpPort, profile.adbForwardPort)
        } else {
            free
        }
        val stored = profile.copy(
            vncPort = finalPorts.vncPort,
            qmpPort = finalPorts.qmpPort,
            adbForwardPort = finalPorts.adbForwardPort
        )
        ensureVmDirectories(stored.id)
        saveAll(existing + stored)
        return stored
    }

    @Synchronized
    fun update(profile: VmProfile): VmProfile {
        val existing = loadAll()
        val index = existing.indexOfFirst { it.id == profile.id }
        require(index >= 0) { "No VM with id=${profile.id}" }
        val updated = existing.toMutableList().also { it[index] = profile }
        ensureVmDirectories(profile.id)
        saveAll(updated)
        return profile
    }

    @Synchronized
    fun delete(vmId: String, deleteFiles: Boolean = true): Boolean {
        val existing = loadAll()
        val remaining = existing.filterNot { it.id == vmId }
        if (remaining.size == existing.size) return false
        saveAll(remaining)
        if (deleteFiles) {
            vmDir(vmId).deleteRecursively()
        }
        return true
    }

    /**
     * Duplicates [sourceId] metadata under a new id with fresh ports. When
     * [copyDisk] is true and the source disk exists it is copied into the new
     * VM directory.
     */
    @Synchronized
    fun clone(sourceId: String, newName: String? = null, copyDisk: Boolean = true): VmProfile? {
        val source = get(sourceId) ?: return null
        val newId = UUID.randomUUID().toString()
        ensureVmDirectories(newId)
        val newDiskPath = File(vmDir(newId), "disk.qcow2")
        if (copyDisk) {
            val sourceDisk = File(source.diskImagePath)
            if (sourceDisk.isFile) {
                sourceDisk.copyTo(newDiskPath, overwrite = true)
            }
        }
        val clone = source.copy(
            id = newId,
            name = newName ?: "${source.name} Copy",
            diskImagePath = newDiskPath.absolutePath,
            createdAtEpochMs = clock(),
            snapshots = emptyList()
        )
        return create(clone.copy(vncPort = 0, qmpPort = 0, adbForwardPort = 0))
    }

    // ------------------------------------------------------------ snapshots

    @Synchronized
    fun addSnapshot(
        vmId: String,
        name: String,
        description: String = "",
        createdAtEpochMs: Long = clock(),
        snapshotId: String = UUID.randomUUID().toString()
    ): VmSnapshot? {
        val profile = get(vmId) ?: return null
        val snapshot = VmSnapshot(
            id = snapshotId,
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            description = description
        )
        update(profile.withSnapshot(snapshot))
        return snapshot
    }

    @Synchronized
    fun removeSnapshot(vmId: String, snapshotId: String): Boolean {
        val profile = get(vmId) ?: return false
        if (profile.snapshots.none { it.id == snapshotId }) return false
        update(profile.withoutSnapshot(snapshotId))
        return true
    }

    fun listSnapshots(vmId: String): List<VmSnapshot> = get(vmId)?.snapshots.orEmpty()

    fun findSnapshot(vmId: String, snapshotId: String): VmSnapshot? =
        get(vmId)?.snapshots?.firstOrNull { it.id == snapshotId }
}

/**
 * Crash-safe JSON file plumbing shared by [VmRepository] and [RomRepository].
 *
 * Every write lands in `<name>.tmp` first and is then moved into place, so a
 * process death halfway through a save can never leave a truncated live file.
 * The previous contents are kept as `<name>.bak`, and anything unreadable is
 * copied aside as `<name>.corrupt-<timestamp>` before it is replaced.
 */
internal object JsonPersistence {

    private const val CORRUPT_MARKER = ".corrupt-"

    /** Reads [file] as UTF-8, or null when it is missing or unreadable. */
    fun readOrNull(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Writes [payload] to `<target>.tmp` and moves it onto [target], preferring
     * an atomic rename and degrading to copy + delete when the filesystem
     * refuses one.
     */
    fun writeAtomically(target: File, payload: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create ${parent.absolutePath}")
        }
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        moveIntoPlace(tmp, target)
    }

    /**
     * Copies [source] to [backup] only when [isValid] accepts its current
     * contents: a corrupt primary must never clobber the last good backup.
     */
    fun backupIfValid(source: File, backup: File, isValid: (String) -> Boolean) {
        val current = readOrNull(source) ?: return
        if (!isValid(current)) return
        runCatching { source.copyTo(backup, overwrite = true) }
    }

    /**
     * Copies an unparseable [source] aside as `<name>.corrupt-<stamp>` and
     * returns the new file (null when the copy could not be written).
     */
    fun preserveCorrupt(source: File, stamp: Long): File? {
        val parent = source.parentFile ?: return null
        val target = File(parent, "${source.name}$CORRUPT_MARKER$stamp")
        return runCatching { source.copyTo(target, overwrite = true) }.getOrNull()
    }

    private fun moveIntoPlace(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()

        val movedAtomically = runCatching {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.isSuccess
        if (movedAtomically) return

        val moved = runCatching {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (moved) return

        // Last resort: plain copy, then drop the staging file.
        source.copyTo(target, overwrite = true)
        source.delete()
    }
}

