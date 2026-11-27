package com.novavm.app.overlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 虚拟机帧缓冲截图工具。
 *
 * 像素格式与 `VmDisplayPipeline` 完全一致：**RGBA8888、tight packed、
 * `ByteOrder.LITTLE_ENDIAN` 的 direct `ByteBuffer`**，也就是 RFB
 * `SetPixelFormat`（32bpp / depth 24 / little endian / true colour，
 * red-shift 0、green-shift 8、blue-shift 16）协商后的原始排布；
 * 直接 `Bitmap.copyPixelsFromBuffer()` 读进 `ARGB_8888` 位图即可，
 * 不需要逐像素搬移通道。
 *
 * 保存路径策略：
 *  * API 29+：`MediaStore.Images.Media.EXTERNAL_CONTENT_URI`，
 *    `RELATIVE_PATH = Pictures/NovaVM`，用 `IS_PENDING` 两段式写入；
 *  * API 26–28：`context.getExternalFilesDir(DIRECTORY_PICTURES)`，
 *    写完再用 `MediaScannerConnection` 通知媒体库。
 */
object VmScreenshotHelper {

    /** 文件名前缀，用于区分 NovaVM 生成的图片。 */
    const val FILE_PREFIX = "NovaVM"

    /** 虚拟机名在文件名里的最大字符数（中文按字符计，不是字节）。 */
    const val MAX_NAME_LENGTH = 40

    /** 名字被清空（全非法字符）时的兜底片段。 */
    const val FALLBACK_NAME = "vm"

    /** 相册子目录名。 */
    const val GALLERY_ALBUM = "NovaVM"

    private const val PNG_MIME = "image/png"
    private const val BYTES_PER_PIXEL = 4

    /** 固定 UTC，保证同一时间戳在任何机器/时区生成同样的文件名。 */
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).withZone(ZoneOffset.UTC)

    /** 文件系统非法字符 + 控制字符。 */
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    /** 各种空白（含全角空格 / NBSP）折叠为单个下划线。 */
    private val whitespaceRun = Regex("[\\s\\u00A0\\u3000]+")

    private val underscoreRun = Regex("_+")

    // ------------------------------------------------------------ filename

    /**
     * 生成 `NovaVM_<sanitized-vm-name>_<yyyyMMdd_HHmmss>.png`。
     *
     * 时间戳按 UTC 渲染，纯函数、无 IO，方便单测断言。
     * 中文/日文/字母数字/`-`/`+`/`(`/`)` 保留；路径分隔符、Windows 保留字符、
     * 控制字符替换为 `_`；连续空白折叠为 `_`；两端 `_`/`.` 去掉。
     */
    @JvmStatic
    fun buildScreenshotFileName(vmName: String, timestampMs: Long): String {
        val slug = sanitizeNameSegment(vmName)
        val stamp = timestampFormatter.format(Instant.ofEpochMilli(timestampMs))
        return "${FILE_PREFIX}_${slug}_$stamp.png"
    }

    /** 单独暴露清洗逻辑，UI 预览文件名时不必拼接时间戳。 */
    @JvmStatic
    fun sanitizeNameSegment(vmName: String): String {
        val replaced = illegalChars.replace(vmName, "_")
        val spaced = whitespaceRun.replace(replaced, "_")
        val collapsed = underscoreRun.replace(spaced, "_")
        val trimmed = collapsed.trim('_', '.', ' ')
        val clipped = trimmed.take(MAX_NAME_LENGTH).trim('_', '.').trimEnd()
        return clipped.ifEmpty { FALLBACK_NAME }
    }

    // -------------------------------------------------------------- buffer

    /**
     * 把 RGBA8888 direct buffer 编码并写成 PNG 文件，返回同一个 [targetFile]。
     *
     * 读的是 `duplicate()` 出来的视图，调用方 buffer 的 position/limit 不变。
     * 剩余字节不足 `width * height * 4` 抛 [IllegalArgumentException]，
     * PNG 编码失败抛 [IllegalStateException]。
     */
    @JvmStatic
    fun saveRgbaBufferAsPng(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        targetFile: File
    ): File {
        require(width > 0 && height > 0) { "非法的帧缓冲尺寸 ${width}x$height" }
        val required = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        // duplicate() 复制 position/limit 但共享内容；重设字节序不污染调用方。
        val pixels = rgbaBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(pixels.remaining().toLong() >= required) {
            "RGBA buffer 只有 ${pixels.remaining()} 字节，${width}x$height 需要 $required 字节"
        }

        targetFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("无法创建截图目录 ${parent.absolutePath}")
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(pixels)
            FileOutputStream(targetFile).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                out.flush()
            }
        } finally {
            bitmap.recycle()
        }
        return targetFile
    }

    // ---------------------------------------------------------- public save

    /**
     * 截图落盘：API 29+ 进相册（`Pictures/NovaVM`），API 26–28 进 App 外部图片目录。
     *
     * 成功时 `Result.success` 携带 URI 字符串（API 29+）或绝对路径（API 26–28）；
     * 失败时 `Result.failure` 携带原始异常，由调用方决定是否 toast。
     */
    @JvmStatic
    fun captureToGalleryOrAppDir(
        context: Context,
        vmName: String,
        rgbaBytes: ByteArray,
        width: Int,
        height: Int
    ): Result<String> = runCatching {
        require(width > 0 && height > 0) { "非法的帧缓冲尺寸 ${width}x$height" }
        val required = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        require(rgbaBytes.size.toLong() >= required) {
            "截图字节数 ${rgbaBytes.size} 不足，${width}x$height 需要 $required 字节"
        }
        val fileName = buildScreenshotFileName(vmName, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, fileName, rgbaBytes, width, height)
        } else {
            saveToAppPictures(context, fileName, rgbaBytes, width, height)
        }
    }

    // ------------------------------------------------------------- backends

    private fun saveToMediaStore(
        context: Context,
        fileName: String,
        rgbaBytes: ByteArray,
        width: Int,
        height: Int
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}${File.separator}$GALLERY_ALBUM"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore 拒绝写入截图")

        try {
            val out = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("无法打开 MediaStore 输出流")
            out.use { writePng(rgbaBytes, width, height, it) }
            // 写完清 pending 标记，相册里才可见。
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // 半成品必须删掉，否则相册里会留 0 字节/损坏条目。
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveToAppPictures(
        context: Context,
        fileName: String,
        rgbaBytes: ByteArray,
        width: Int,
        height: Int
    ): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, GALLERY_ALBUM)
        val target = File(dir, fileName)
        saveRgbaBufferAsPng(ByteBuffer.wrap(rgbaBytes), width, height, target)
        // 私有图片目录不会被媒体库自动扫描，主动通知一次。
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(PNG_MIME),
                null
            )
        }
        return target.absolutePath
    }

    /** 字节 → Bitmap → PNG 流；不落临时文件，失败也要回收位图。 */
    private fun writePng(rgbaBytes: ByteArray, width: Int, height: Int, out: OutputStream) {
        val pixels = ByteBuffer.wrap(rgbaBytes).order(ByteOrder.LITTLE_ENDIAN)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(pixels)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
            out.flush()
        } finally {
            bitmap.recycle()
        }
    }
}
