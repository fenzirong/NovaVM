package com.novavm.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 下载任务状态。 */
enum class DownloadStatus(val label: String) {
    IDLE("空闲"),
    DOWNLOADING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("下载失败"),
    CANCELLED("已取消")
}

/**
 * 单个镜像的下载进度快照。
 *
 * [totalBytes] 为 `-1` 表示服务器没给出 `Content-Length`（总量未知），
 * 此时 [progressFraction] 返回 0，界面应改用“已下载 xx”文案而不是百分比。
 */
data class RomDownloadState(
    val catalogId: String,
    val fileName: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val errorMessage: String? = null,
    val savedPath: String? = null
) {

    /** 0f..1f 的完成度；总量未知且未完成时为 0f。 */
    val progressFraction: Float
        get() {
            if (status == DownloadStatus.COMPLETED) return 1f
            if (totalBytes <= 0L) return 0f
            return (downloadedBytes.toDouble() / totalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    /** 形如 `42%` 的百分比文案。 */
    val percentLabel: String
        get() = "${(progressFraction * 100f).toInt()}%"

    /** 形如 `128.0 MB / 1.5 GB` 的体量文案，总量未知时右侧显示“未知”。 */
    val sizeLabel: String
        get() {
            val total = if (totalBytes > 0L) RomDownloader.formatBytes(totalBytes) else "未知"
            return "${RomDownloader.formatBytes(downloadedBytes)} / $total"
        }

    /** 形如 `3.2 MB/s` 的速度文案。 */
    val speedLabel: String
        get() = RomDownloader.formatSpeed(speedBytesPerSec)

    /** 任务仍在进行（下载中或暂停，可继续）。 */
    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED

    /** 任务已结束（成功、失败或取消）。 */
    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED
}

/**
 * 目录镜像下载器：断点续传 + 进度广播 + 完成后自动登记进镜像库。
 *
 * 行为约定：
 * - 先写 `<rootDir>/downloads/<catalogId>.part`，支持 `Range: bytes=<n>-` 续传；
 * - 服务器不支持 Range（返回 200 而非 206）时，丢弃残片从头开始，绝不拼出一个坏镜像；
 * - 进度写入按 [progressIntervalMs] 节流，避免高频刷新拖垮 Compose；
 * - 完成后原子重命名进 `romRepository.imagesDir`，并调用
 *   [RomRepository.registerExistingImage] 自动登记，用户无需再手动导入；
 * - [cancel] 会删掉残片，[pause] 保留残片以便再次 [startDownload] 续传。
 *
 * 全程不依赖 Android API，可直接在 JVM 单元测试里断言。
 */
class RomDownloader(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val progressIntervalMs: Long = 250L
) {

    private val _downloads = MutableStateFlow<Map<String, RomDownloadState>>(emptyMap())

    /** 以 catalogId 为键的全部下载任务状态。 */
    val downloads: StateFlow<Map<String, RomDownloadState>> = _downloads.asStateFlow()

    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val tokens = HashMap<String, Long>()
    private var tokenSeed = 0L

    companion object {
        const val DOWNLOAD_DIR_NAME = "downloads"
        const val PART_SUFFIX = ".part"

        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "NovaVM/1.0 (Android)"

        private val UNITS = arrayOf("KB", "MB", "GB", "TB", "PB")

        /** 人类可读的体量文案，例如 `0 B` / `512 B` / `1.5 KB` / `2.0 GB`。 */
        fun formatBytes(bytes: Long): String {
            if (bytes < 0L) return "未知"
            if (bytes < 1024L) return "$bytes B"
            var value = bytes.toDouble() / 1024.0
            var index = 0
            while (value >= 1024.0 && index < UNITS.lastIndex) {
                value /= 1024.0
                index++
            }
            val text = if (value >= 100.0) {
                String.format(Locale.ROOT, "%.0f", value)
            } else {
                String.format(Locale.ROOT, "%.1f", value)
            }
            return "$text ${UNITS[index]}"
        }

        /** 速度文案，例如 `0 B/s` / `1.0 KB/s` / `3.0 MB/s`。 */
        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec < 0L) return "未知"
            if (bytesPerSec == 0L) return "0 B/s"
            return formatBytes(bytesPerSec) + "/s"
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 取某个镜像的下载状态。 */
    fun stateOf(catalogId: String): RomDownloadState? = _downloads.value[catalogId]

    // ---------------------------------------------------------------- 控制

    /**
     * 开始（或继续）下载 [item]。
     *
     * 已经开始下载的任务会被忽略；已暂停/失败/取消的任务会从残片续传。
     * 完成后自动登记进传进来的 [romRepository]。
     */
    fun startDownload(item: RomCatalogItem, romRepository: RomRepository) {
        val current = stateOf(item.id)
        if (current?.status == DownloadStatus.DOWNLOADING) return

        val fileName = resolveFileName(item)
        val partFile = partFileFor(romRepository, item)
        val alreadyOnDisk = if (partFile.isFile) partFile.length() else 0L
        val knownTotal = current?.totalBytes?.takeIf { it > 0L } ?: -1L

        val token = synchronized(lock) {
            jobs.remove(item.id)?.cancel()
            val next = ++tokenSeed
            tokens[item.id] = next
            next
        }

        publish(
            RomDownloadState(
                catalogId = item.id,
                fileName = fileName,
                downloadedBytes = alreadyOnDisk,
                totalBytes = knownTotal,
                speedBytesPerSec = 0L,
                status = DownloadStatus.DOWNLOADING
            )
        )

        val job = scope.launch {
            runDownload(item, romRepository, partFile, fileName, token)
        }
        synchronized(lock) { jobs[item.id] = job }
    }

    /** 暂停下载：保留残片，下次 [startDownload] 续传。 */
    fun pause(catalogId: String) {
        val current = stateOf(catalogId) ?: return
        if (current.status != DownloadStatus.DOWNLOADING) return
        publish(current.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L))
        cancelJob(catalogId)
    }

    /** 取消下载并丢弃残片。 */
    fun cancel(catalogId: String) {
        val current = stateOf(catalogId) ?: return
        if (current.isFinished) return
        publish(current.copy(status = DownloadStatus.CANCELLED, speedBytesPerSec = 0L))
        cancelJob(catalogId)
    }

    /** 取消全部进行中的任务。 */
    fun cancelAll() {
        val ids = synchronized(lock) { jobs.keys.toList() }
        ids.forEach(::cancel)
    }

    /** 从状态表中移除一条记录（不影响已落盘的镜像文件）。 */
    fun forget(catalogId: String) {
        _downloads.update { it - catalogId }
    }

    // ---------------------------------------------------------------- 内部

    private fun publish(state: RomDownloadState) {
        _downloads.update { it + (state.catalogId to state) }
    }

    /** 只有当前 token 还匹配时才写入，避免旧任务覆盖新任务的状态。 */
    private fun publishIfCurrent(catalogId: String, token: Long, state: RomDownloadState) {
        if (!isCurrent(catalogId, token)) return
        publish(state)
    }

    private fun isCurrent(catalogId: String, token: Long): Boolean =
        synchronized(lock) { tokens[catalogId] == token }

    private fun cancelJob(catalogId: String) {
        synchronized(lock) { jobs.remove(catalogId) }?.cancel()
    }

    private suspend fun runDownload(
        item: RomCatalogItem,
        romRepository: RomRepository,
        partFile: File,
        fileName: String,
        token: Long
    ) = withContext(Dispatchers.IO) {
        var activeConnection: HttpURLConnection? = null
        var downloaded = 0L
        var totalBytes = -1L
        try {
            val downloadsDir = partFile.parentFile
            if (downloadsDir != null) ensureDir(downloadsDir)

            var offset = if (partFile.isFile) partFile.length() else 0L
            var conn: HttpURLConnection = openConnection(item.downloadUrl, offset)
            activeConnection = conn
            var code = conn.responseCode
            if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                // 服务器无视 Range，残片作废，从头再来。
                conn.disconnect()
                partFile.delete()
                offset = 0L
                conn = openConnection(item.downloadUrl, 0L)
                activeConnection = conn
                code = conn.responseCode
            }
            if (code !in 200..299) {
                throw IOException("服务器返回 HTTP $code，无法下载")
            }

            val contentLength = conn.contentLengthLong
            totalBytes = if (contentLength > 0L) contentLength + offset else -1L
            downloaded = offset
            publishIfCurrent(
                item.id,
                token,
                RomDownloadState(
                    catalogId = item.id,
                    fileName = fileName,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.DOWNLOADING
                )
            )

            var lastSampleBytes = downloaded
            var lastSampleAt = clock()
            var lastPublishAt = lastSampleAt
            val buffer = ByteArray(BUFFER_SIZE)

            conn.inputStream.use { input ->
                FileOutputStream(partFile, offset > 0L).use { output ->
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = clock()
                        if (now - lastPublishAt >= progressIntervalMs) {
                            val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
                            val speed = (downloaded - lastSampleBytes) * 1000L / elapsed
                            lastSampleBytes = downloaded
                            lastSampleAt = now
                            lastPublishAt = now
                            publishIfCurrent(
                                item.id,
                                token,
                                RomDownloadState(
                                    catalogId = item.id,
                                    fileName = fileName,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speed,
                                    status = DownloadStatus.DOWNLOADING
                                )
                            )
                        }
                    }
                }
            }

            if (totalBytes > 0L && downloaded < totalBytes) {
                throw IOException(
                    "连接提前结束：${formatBytes(downloaded)} / ${formatBytes(totalBytes)}"
                )
            }
            if (downloaded <= 0L) {
                throw IOException("服务器返回空内容，无法作为镜像使用")
            }

            val target = commitFile(partFile, romRepository, fileName)
            var registrationError: String? = null
            runCatching {
                romRepository.registerExistingImage(
                    file = target,
                    displayName = "${item.name} ${item.version}",
                    architecture = item.architecture
                )
            }.onFailure { error ->
                registrationError = "镜像已下载，但登记到镜像库失败：${error.message ?: error.javaClass.simpleName}"
            }

            publishIfCurrent(
                item.id,
                token,
                RomDownloadState(
                    catalogId = item.id,
                    fileName = target.name,
                    downloadedBytes = downloaded,
                    totalBytes = maxOf(totalBytes, downloaded),
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.COMPLETED,
                    errorMessage = registrationError,
                    savedPath = target.absolutePath
                )
            )
        } catch (cancelled: CancellationException) {
            val existing = stateOf(item.id)
            val finalStatus = when (existing?.status) {
                DownloadStatus.PAUSED -> DownloadStatus.PAUSED
                else -> DownloadStatus.CANCELLED
            }
            val bytesKept = bytesOnDisk(partFile, downloaded)
            if (finalStatus == DownloadStatus.CANCELLED) partFile.delete()
            publishIfCurrent(
                item.id,
                token,
                RomDownloadState(
                    catalogId = item.id,
                    fileName = fileName,
                    downloadedBytes = bytesKept,
                    totalBytes = totalBytes,
                    speedBytesPerSec = 0L,
                    status = finalStatus,
                    errorMessage = null,
                    savedPath = null
                )
            )
            throw cancelled
        } catch (error: Exception) {
            publishIfCurrent(
                item.id,
                token,
                RomDownloadState(
                    catalogId = item.id,
                    fileName = fileName,
                    downloadedBytes = bytesOnDisk(partFile, downloaded),
                    totalBytes = totalBytes,
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.FAILED,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    savedPath = null
                )
            )
        } finally {
            activeConnection?.disconnect()
            synchronized(lock) {
                // 只清理自己的任务槽，避免把后来重启的任务引用抹掉。
                if (tokens[item.id] == token) jobs.remove(item.id)
            }
        }
    }

    private fun openConnection(urlString: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        if (resumeFrom > 0L) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        return connection
    }

    /** 下载目录：`<romRepository.rootDir>/downloads`。 */
    private fun downloadsDir(romRepository: RomRepository): File =
        File(romRepository.rootDir, DOWNLOAD_DIR_NAME)

    private fun partFileFor(romRepository: RomRepository, item: RomCatalogItem): File =
        File(downloadsDir(romRepository), "${item.id}$PART_SUFFIX")

    /**
     * 落到 `roms/images/` 下的最终文件名：优先取 URL 末段，否则用“名称-版本.img”，
     * 再清洗成安全字符并去重。
     */
    private fun resolveFileName(item: RomCatalogItem): String {
        val lastSegment = item.downloadUrl
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        val candidate = if (lastSegment.contains('.') && !lastSegment.startsWith("index")) {
            lastSegment
        } else {
            "${item.name}-${item.version}.img"
        }
        val cleaned = candidate
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
            .take(120)
        return if (cleaned.isBlank()) "${item.id}.img" else cleaned
    }

    private fun commitFile(partFile: File, romRepository: RomRepository, fileName: String): File {
        val imagesDir = romRepository.imagesDir
        ensureDir(imagesDir)
        val target = uniqueTarget(imagesDir, fileName)
        if (!partFile.renameTo(target)) {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
        }
        return target
    }

    private fun uniqueTarget(imagesDir: File, fileName: String): File {
        var candidate = File(imagesDir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(imagesDir, "$stem-$counter$ext")
            counter++
        }
        return candidate
    }

    private fun bytesOnDisk(partFile: File, downloaded: Long): Long =
        if (partFile.isFile) maxOf(downloaded, partFile.length()) else downloaded

    private fun ensureDir(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建目录：${dir.absolutePath}")
        }
    }
}
