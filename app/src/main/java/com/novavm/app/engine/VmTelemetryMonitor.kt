package com.novavm.app.engine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** One MiB, as a `Long` so no intermediate conversion can overflow. */
private const val BYTES_PER_MB: Long = 1024L * 1024L

/**
 * Deepest directory level walked while sizing a VM folder.
 *
 * A VM tree is shallow (`disk.qcow2`, `shared/`, `modules/`, `logs/`). Capping
 * the walk keeps a pathological or hostile tree from pinning the diagnostics
 * thread, and bounds the cost on a huge shared directory.
 */
private const val MAX_WALK_DEPTH: Int = 16

/**
 * Storage footprint of one VM, in bytes.
 *
 * [vmDirBytes] is the total of everything under the VM directory, so the
 * per-directory fields are a breakdown rather than additional bytes — except
 * [diskImageBytes], which can point outside the VM directory (a ROM-backed
 * image, for instance).
 */
data class VmStorageStats(
    val vmDirBytes: Long,
    val diskImageBytes: Long,
    val sharedDirBytes: Long,
    val modulesDirBytes: Long,
    val snapshotCount: Int
) {

    val vmDirMb: Long get() = vmDirBytes / BYTES_PER_MB

    val diskImageMb: Long get() = diskImageBytes / BYTES_PER_MB

    val sharedDirMb: Long get() = sharedDirBytes / BYTES_PER_MB

    val modulesDirMb: Long get() = modulesDirBytes / BYTES_PER_MB

    /** True when the disk image lives outside the VM directory. */
    val diskImageIsExternal: Boolean get() = diskImageBytes > vmDirBytes

    /** Lines for the VM detail "存储" card. */
    fun summaryLines(): List<String> = listOf(
        "虚拟机目录：${VmTelemetryMonitor.formatBytes(vmDirBytes)}",
        "磁盘镜像：${VmTelemetryMonitor.formatBytes(diskImageBytes)}",
        "共享目录：${VmTelemetryMonitor.formatBytes(sharedDirBytes)}",
        "模块目录：${VmTelemetryMonitor.formatBytes(modulesDirBytes)}",
        "快照数量：$snapshotCount"
    )
}

/**
 * Host-side pressure indicators: JVM heap plus the volume holding the VM data.
 */
data class HostMemoryStats(
    val jvmUsedMb: Long,
    val jvmMaxMb: Long,
    val freeDiskMb: Long,
    val totalDiskMb: Long
) {

    /** Used heap as a percentage of the maximum, clamped to 0..100. */
    val jvmUsagePercent: Int
        get() = if (jvmMaxMb <= 0L) {
            0
        } else {
            ((jvmUsedMb * 100) / jvmMaxMb).coerceIn(0L, 100L).toInt()
        }

    /** Free space as a percentage of the volume, clamped to 0..100. */
    val freeDiskPercent: Int
        get() = if (totalDiskMb <= 0L) {
            0
        } else {
            ((freeDiskMb * 100) / totalDiskMb).coerceIn(0L, 100L).toInt()
        }

    fun summaryLines(): List<String> = listOf(
        "JVM 内存：$jvmUsedMb MB / $jvmMaxMb MB（$jvmUsagePercent%）",
        "可用存储：$freeDiskMb MB / $totalDiskMb MB（剩余 $freeDiskPercent%）"
    )
}

/**
 * Read-only measurements for the diagnostics panel.
 *
 * Nothing here mutates the filesystem, allocates large buffers, or throws: a
 * missing directory, an unreadable child or a symlink simply contributes zero
 * bytes so the panel always renders.
 */
object VmTelemetryMonitor {

    /** Subdirectory holding the shared/9p files (see `VmRepository.sharedDir`). */
    const val SHARED_DIR_NAME: String = "shared"

    /** Subdirectory holding Zygisk/KernelSU modules. */
    const val MODULES_DIR_NAME: String = "modules"

    /**
     * Measures the VM directory plus its two user-facing subdirectories.
     *
     * [diskImagePath] may be blank, relative (resolved against [vmDir]) or
     * absolute; a missing or non-regular target yields 0.
     */
    fun inspectVmStorage(
        vmDir: File,
        diskImagePath: String,
        snapshotCount: Int
    ): VmStorageStats = VmStorageStats(
        vmDirBytes = directorySize(vmDir),
        diskImageBytes = fileSizeOrZero(resolveAgainst(vmDir, diskImagePath)),
        sharedDirBytes = directorySize(File(vmDir, SHARED_DIR_NAME)),
        modulesDirBytes = directorySize(File(vmDir, MODULES_DIR_NAME)),
        snapshotCount = snapshotCount.coerceAtLeast(0)
    )

    /**
     * JVM heap usage plus the capacity of the volume that holds [rootDir].
     *
     * [rootDir] itself may not exist yet (first launch, deleted data dir), so
     * the nearest existing ancestor is measured instead of reporting 0/0.
     */
    fun inspectHostMemory(rootDir: File): HostMemoryStats {
        val runtime = Runtime.getRuntime()
        val usedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val anchor = nearestExisting(rootDir)
        return HostMemoryStats(
            jvmUsedMb = usedBytes / BYTES_PER_MB,
            jvmMaxMb = runtime.maxMemory() / BYTES_PER_MB,
            freeDiskMb = anchor.usableSpace.coerceAtLeast(0L) / BYTES_PER_MB,
            totalDiskMb = anchor.totalSpace.coerceAtLeast(0L) / BYTES_PER_MB
        )
    }

    /**
     * Total size of the regular files below [dir].
     *
     * Symlinks are never followed: a link contributes zero bytes so a shared
     * folder pointing at `/sdcard` (or forming a loop) cannot inflate the
     * number or hang the walk. Unreadable children are skipped, and the partial
     * total is returned instead of throwing.
     */
    fun directorySize(dir: File): Long {
        val root = dir.toPath()
        if (Files.isSymbolicLink(root)) return 0L
        if (!Files.isDirectory(root)) return 0L

        var total = 0L
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Attributes are read without FOLLOW_LINKS, so a link reports as
                // a link here rather than as its (possibly huge) target.
                if (!attrs.isSymbolicLink && attrs.isRegularFile) {
                    total += attrs.size().coerceAtLeast(0L)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult =
                FileVisitResult.CONTINUE
        }

        try {
            Files.walkFileTree(root, emptySet<FileVisitOption>(), MAX_WALK_DEPTH, visitor)
        } catch (error: IOException) {
            // Keep whatever was measured; a diagnostics panel must not fail.
        }
        return total
    }

    /** `1024`-based human readable size, e.g. `3.4 GB` — used by the UI cards. */
    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "$safe B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = safe.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val text = if (rounded >= 10.0) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", rounded)
        }
        return "$text ${units[unitIndex]}"
    }

    private fun resolveAgainst(baseDir: File, path: String): File? {
        if (path.isBlank()) return null
        val candidate = File(path)
        return if (candidate.isAbsolute) candidate else File(baseDir, path)
    }

    private fun fileSizeOrZero(file: File?): Long {
        if (file == null) return 0L
        return runCatching { if (file.isFile) file.length() else 0L }.getOrDefault(0L)
    }

    /** Walks up from [dir] until an existing path is found (bounded). */
    private fun nearestExisting(dir: File?): File {
        var current: File? = dir
        var hops = 0
        while (current != null && hops < 32) {
            if (current.exists()) return current
            current = current.parentFile
            hops++
        }
        return File("/")
    }
}
