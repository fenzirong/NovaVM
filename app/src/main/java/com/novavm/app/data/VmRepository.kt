package com.novavm.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
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

    companion object {
        const val DEFAULT_VNC_PORT = 5901
        const val DEFAULT_QMP_PORT = 4444
        const val DEFAULT_ADB_PORT = 15555

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
        return try {
            val raw = profilesFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    json.decodeFromString<VmProfilesFile>(raw).profiles
                }.getOrElse {
                    // Tolerate a bare JSON array written by older builds.
                    runCatching {
                        json.decodeFromString<List<VmProfile>>(raw)
                    }.getOrDefault(emptyList())
                }
            }
        } catch (io: IOException) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(profiles: List<VmProfile>) {
        if (!vmsDir.exists() && !vmsDir.mkdirs()) {
            throw IOException("Unable to create ${vmsDir.absolutePath}")
        }
        val payload = json.encodeToString(VmProfilesFile(profiles = profiles))
        val tmp = File(vmsDir, "profiles.json.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (profilesFile.exists() && !profilesFile.delete()) {
            // Fall through: File.renameTo cannot overwrite on every platform.
            profilesFile.writeText(payload, Charsets.UTF_8)
            tmp.delete()
            return
        }
        if (!tmp.renameTo(profilesFile)) {
            profilesFile.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun get(vmId: String): VmProfile? = loadAll().firstOrNull { it.id == vmId }

    fun require(vmId: String): VmProfile =
        get(vmId) ?: throw NoSuchElementException("No VM profile with id=$vmId")

    // ---------------------------------------------------------------- ports

    fun usedPorts(profiles: List<VmProfile> = loadAll()): Set<Int> =
        profiles.flatMap { listOf(it.vncPort, it.qmpPort, it.adbForwardPort) }.toSet()

    /** First free VNC/QMP/ADB triplet, scanning 1000 slots from the defaults. */
    fun allocatePorts(profiles: List<VmProfile> = loadAll()): VmPortAllocation {
        val used = usedPorts(profiles)
        for (offset in 0 until 1000) {
            val vnc = DEFAULT_VNC_PORT + offset
            val qmp = DEFAULT_QMP_PORT + offset
            val adb = DEFAULT_ADB_PORT + offset
            if (vnc !in used && qmp !in used && adb !in used) {
                return VmPortAllocation(vnc, qmp, adb)
            }
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
