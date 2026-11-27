package com.novavm.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Result of [VmBackupManager.exportVmToZip].
 *
 * @param vmName display name of the exported profile.
 * @param fileCount number of ZIP entries written, `profile.json` included.
 * @param totalBytes uncompressed payload size of everything written.
 */
data class BackupSummary(
    val vmName: String,
    val fileCount: Int,
    val totalBytes: Long
)

/**
 * Portable, single-file backup of a VM's *metadata plus user data*.
 *
 * A backup contains exactly:
 * ```
 * profile.json          serialized VmProfile (hardware, spoof, root, location …)
 * shared/…              everything the VirtIO-9p share exposes
 * modules/…             Magisk / LSPosed module payloads
 * ```
 *
 * Disk images are deliberately left out: a qcow2 is multi-GB, never compresses
 * well and would make a "backup" neither fast nor portable. They are excluded
 * by extension, by [VmProfile.diskImagePath] identity, and by a per-entry size
 * ceiling ([MAX_ENTRY_BYTES]) so a stray blob cannot slip in either.
 *
 * Like [VmRepository] this class touches no Android API, so the whole
 * export/import path runs on a plain JVM in unit tests.
 *
 * ## Import safety
 *
 * Import is written for untrusted archives: entry names are normalized,
 * absolute paths, drive-qualified names and `..` segments are rejected with
 * [SecurityException] (Zip-Slip), every resolved file is canonicalized and must
 * stay inside the extraction root, unknown top-level trees (`__MACOSX/`,
 * `logs/`) are ignored, and entries larger than [MAX_ENTRY_BYTES] abort the
 * import. Nothing is written into the repository until the archive has been
 * spooled into a staging directory *and* `profile.json` parsed successfully.
 */
class VmBackupManager(
    private val json: Json = VmRepository.defaultJson,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** Metadata entry, always at the archive root. */
        const val PROFILE_ENTRY = "profile.json"

        /** Directories mirrored into a backup. */
        const val SHARED_DIR = "shared"
        const val MODULES_DIR = "modules"

        /** Archive order, also the set of prefixes [importVmFromZip] accepts. */
        val ARCHIVED_DIRECTORIES: List<String> = listOf(SHARED_DIR, MODULES_DIR)

        /** Extensions treated as guest disk media and never archived. */
        val DISK_IMAGE_EXTENSIONS: Set<String> = setOf(
            "qcow", "qcow2", "img", "raw", "vmdk", "vdi", "vhd", "vhdx",
            "iso", "dmg", "sparseimage", "sparsebundle"
        )

        /** Per-entry ceiling: skipped on export, rejected on import (512 MiB). */
        const val MAX_ENTRY_BYTES: Long = 512L * 1024L * 1024L

        private const val COPY_BUFFER = 32 * 1024
        private const val STAGING_PREFIX = ".novavm-import-"
        private const val IMPORTED_NAME_FALLBACK = "Imported VM"
    }

    // -------------------------------------------------------------- export

    /**
     * Streams [profile] and the user-data trees of [vmDir] into [outputStream]
     * as a standard ZIP archive.
     *
     * The caller owns [outputStream]: it is flushed but never closed, so the
     * same stream can keep going (e.g. into a `ContentResolver` URI).
     */
    fun exportVmToZip(profile: VmProfile, vmDir: File, outputStream: OutputStream): BackupSummary {
        val buffered = BufferedOutputStream(outputStream, COPY_BUFFER)
        val zip = ZipOutputStream(buffered, Charsets.UTF_8)
        var fileCount = 0
        var totalBytes = 0L

        try {
            val payload = json.encodeToString(profile).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(timedEntry(PROFILE_ENTRY, clock()))
            zip.write(payload)
            zip.closeEntry()
            fileCount++
            totalBytes += payload.size

            for (directoryName in ARCHIVED_DIRECTORIES) {
                val root = File(vmDir, directoryName)
                if (!root.isDirectory) continue
                for (file in archivedFiles(root)) {
                    if (!shouldArchive(file, profile)) continue
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val entryName = normalizeEntryName("$directoryName/$relative")
                    zip.putNextEntry(timedEntry(entryName, file.lastModified()))
                    file.inputStream().use { input -> input.copyTo(zip, COPY_BUFFER) }
                    zip.closeEntry()
                    fileCount++
                    totalBytes += file.length()
                }
            }
        } finally {
            runCatching { zip.finish() }
            runCatching { buffered.flush() }
        }

        return BackupSummary(
            vmName = profile.displayName,
            fileCount = fileCount,
            totalBytes = totalBytes
        )
    }

    // -------------------------------------------------------------- import

    /**
     * Restores an archive produced by [exportVmToZip] as a brand new VM.
     *
     * The imported profile keeps every hardware / spoof / root / location field
     * but always gets a fresh [VmProfile.id], fresh non-colliding ports (the
     * repository allocates them because the ports are handed over as `0`), a
     * fresh disk image path inside the new VM directory and an empty snapshot
     * list — a backup is not a clone of a running instance.
     *
     * @param nameOverride optional new display name; blank values are ignored.
     * @throws SecurityException when an entry attempts a Zip-Slip escape.
     * @throws IllegalArgumentException when the archive carries no readable
     *   `profile.json` or an entry exceeds [MAX_ENTRY_BYTES].
     */
    fun importVmFromZip(
        inputStream: InputStream,
        repository: VmRepository,
        nameOverride: String? = null
    ): VmProfile {
        val staging = File(repository.rootDir, STAGING_PREFIX + UUID.randomUUID())
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("Unable to create staging directory ${staging.absolutePath}")
        }
        try {
            extractArchive(inputStream, staging)

            val profileFile = File(staging, PROFILE_ENTRY)
            if (!profileFile.isFile) {
                throw IllegalArgumentException("Archive does not contain $PROFILE_ENTRY")
            }
            val parsed = parseProfile(profileFile)

            val newId = UUID.randomUUID().toString()
            val targetDir = repository.vmDir(newId)
            val resolvedName = when {
                !nameOverride.isNullOrBlank() -> nameOverride
                else -> parsed.name.ifBlank { IMPORTED_NAME_FALLBACK }
            }
            val requested = parsed.copy(
                id = newId,
                name = resolvedName,
                diskImagePath = File(targetDir, "disk.qcow2").absolutePath,
                vncPort = 0,
                qmpPort = 0,
                adbForwardPort = 0,
                createdAtEpochMs = clock(),
                snapshots = emptyList()
            )

            val created = repository.create(requested)
            try {
                for (directoryName in ARCHIVED_DIRECTORIES) {
                    relocateChildren(
                        sourceDir = File(staging, directoryName),
                        targetDir = File(repository.vmDir(created.id), directoryName)
                    )
                }
            } catch (error: Throwable) {
                // Never leave a half-populated VM behind.
                runCatching { repository.delete(created.id) }
                throw error
            }
            return created
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    // ------------------------------------------------------- entry handling

    /**
     * Normalizes an archive entry name to `a/b/c` form.
     *
     * Rejects absolute paths, Windows drive prefixes and `..` segments; empty
     * names and names that collapse to nothing are invalid too.
     */
    internal fun normalizeEntryName(raw: String): String {
        val cleaned = raw.replace('\\', '/').trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Zip entry has an empty name")
        }
        if (cleaned.startsWith("/")) {
            throw SecurityException("Absolute zip entry rejected: $raw")
        }
        if (cleaned.length >= 2 && cleaned[1] == ':' && cleaned[0].isLetter()) {
            throw SecurityException("Drive-qualified zip entry rejected: $raw")
        }
        val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) {
            throw IllegalArgumentException("Zip entry resolves to no path: $raw")
        }
        if (segments.any { it == ".." }) {
            throw SecurityException("Path traversal in zip entry rejected: $raw")
        }
        return segments.joinToString("/")
    }

    // ------------------------------------------------------ internals: export

    /**
     * Recursive file walk that keeps a canonical-path guard so a symlink loop
     * cannot hang the export. Ordering is name-sorted for reproducible archives.
     */
    private fun archivedFiles(root: File): List<File> {
        val collected = mutableListOf<File>()
        val visited = HashSet<String>()

        fun walk(directory: File) {
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: return
            if (!visited.add(canonical)) return
            val children = directory.listFiles() ?: return
            children.sortedBy { it.name.lowercase() }.forEach { child ->
                when {
                    child.isDirectory -> walk(child)
                    child.isFile -> collected += child
                }
            }
        }

        walk(root)
        return collected
    }

    /** True when [file] is small, non-disk-media user data worth archiving. */
    private fun shouldArchive(file: File, profile: VmProfile): Boolean {
        if (!file.isFile) return false
        if (file.length() > MAX_ENTRY_BYTES) return false
        if (file.extension.lowercase() in DISK_IMAGE_EXTENSIONS) return false
        val diskImagePath = profile.diskImagePath
        if (diskImagePath.isNotBlank()) {
            val isDiskImage = runCatching {
                File(diskImagePath).canonicalPath == file.canonicalPath
            }.getOrDefault(false)
            if (isDiskImage) return false
        }
        return true
    }

    private fun timedEntry(name: String, lastModified: Long): ZipEntry =
        ZipEntry(name).apply { time = if (lastModified > 0L) lastModified else clock() }

    // ------------------------------------------------------ internals: import

    /**
     * Spools an untrusted archive into [staging], accepting only `profile.json`
     * and the [ARCHIVED_DIRECTORIES] trees.
     */
    private fun extractArchive(inputStream: InputStream, staging: File) {
        val base = staging.canonicalFile
        ZipInputStream(BufferedInputStream(inputStream, COPY_BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalizeEntryName(entry.name)
                val target = targetFor(base, name)
                if (!entry.isDirectory && isArchivedEntry(name)) {
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("Unable to create ${parent.absolutePath}")
                    }
                    target.outputStream().use { sink -> copyEntry(zip, sink) }
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * Maps a normalized entry name onto a file inside [base], canonicalizing the
     * result and requiring it to stay under the extraction root.
     */
    private fun targetFor(base: File, name: String): File {
        val canonicalName = if (name.equals(PROFILE_ENTRY, ignoreCase = true)) PROFILE_ENTRY else name
        val candidate = File(base, canonicalName).canonicalFile
        val allowed = candidate.path == base.path ||
            candidate.path.startsWith(base.path + File.separator)
        if (!allowed) {
            throw SecurityException("Zip entry escapes the staging directory: $name")
        }
        return candidate
    }

    private fun isArchivedEntry(name: String): Boolean {
        if (name.equals(PROFILE_ENTRY, ignoreCase = true)) return true
        return ARCHIVED_DIRECTORIES.any { directory -> name.startsWith("$directory/") }
    }

    private fun copyEntry(zip: ZipInputStream, sink: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        var written = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            written += read
            if (written > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException(
                    "Zip entry exceeds the ${MAX_ENTRY_BYTES / (1024 * 1024)} MiB limit"
                )
            }
            sink.write(buffer, 0, read)
        }
    }

    private fun parseProfile(file: File): VmProfile {
        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException("Unable to read $PROFILE_ENTRY", it) }
        if (raw.isBlank()) {
            throw IllegalArgumentException("$PROFILE_ENTRY is empty")
        }
        return runCatching { json.decodeFromString<VmProfile>(raw) }
            .getOrElse {
                throw IllegalArgumentException("$PROFILE_ENTRY is not a valid NovaVM profile", it)
            }
    }

    /**
     * Moves the immediate children of [sourceDir] into [targetDir], preferring a
     * rename and falling back to copy + delete across filesystems.
     */
    private fun relocateChildren(sourceDir: File, targetDir: File) {
        val children = sourceDir.listFiles() ?: return
        if (!targetDir.isDirectory && !targetDir.mkdirs() && !targetDir.isDirectory) {
            throw IOException("Unable to create ${targetDir.absolutePath}")
        }
        children.forEach { child ->
            val destination = File(targetDir, child.name)
            if (destination.exists()) destination.deleteRecursively()
            val moved = runCatching { child.renameTo(destination) }.getOrDefault(false)
            if (!moved) {
                child.copyRecursively(destination, overwrite = true)
                child.deleteRecursively()
            }
        }
    }
}
