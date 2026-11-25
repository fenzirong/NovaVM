package com.novavm.app.engine

import android.util.Log
import android.view.Surface
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Result of probing a disk image header (native or JVM fallback). */
@Serializable
data class DiskImageInfo(
    val format: String = "missing",
    val version: Int = 0,
    val virtualSizeBytes: Long = 0,
    val actualSizeBytes: Long = 0
) {
    val isQcow2: Boolean get() = format == "qcow2"
    val exists: Boolean get() = format != "missing"
}

/**
 * JNI bridge to `libnovavm.so`.
 *
 * Every entry point degrades gracefully: when the shared library is missing
 * (host JVM unit tests, unsupported ABI, stripped build) the methods fall back
 * to pure Kotlin implementations instead of throwing [UnsatisfiedLinkError].
 */
object NativeEngine {

    const val LIBRARY_NAME = "novavm"
    private const val TAG = "NovaVM-Native"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val isLibraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "lib$LIBRARY_NAME.so unavailable, using JVM fallbacks", error)
            false
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to load lib$LIBRARY_NAME.so", error)
            false
        }
    }

    // -------------------------------------------------------------- natives

    private external fun nativeDetectCapabilities(): String

    private external fun nativeBlitFramebuffer(
        surface: Surface?,
        buffer: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean

    private external fun nativeProbeDiskImage(path: String): String

    private external fun nativeCreateEmptyRawImage(path: String, sizeMb: Long): Boolean

    private external fun nativeSendSignal(pid: Int, signal: Int): Boolean

    // ------------------------------------------------------------- wrappers

    /** Raw capability JSON (native when available, synthesized otherwise). */
    fun detectCapabilities(): String =
        if (isLibraryLoaded) {
            runCatching { nativeDetectCapabilities() }.getOrElse {
                Log.w(TAG, "nativeDetectCapabilities failed", it)
                fallbackCapabilitiesJson()
            }
        } else {
            fallbackCapabilitiesJson()
        }

    fun blitFramebuffer(surface: Surface?, buffer: ByteBuffer, width: Int, height: Int): Boolean {
        if (surface == null || width <= 0 || height <= 0) return false
        if (!buffer.isDirect) return false
        if (buffer.capacity() < width.toLong() * height * 4L) return false
        if (!isLibraryLoaded) return false
        return runCatching { nativeBlitFramebuffer(surface, buffer, width, height) }
            .getOrElse {
                Log.w(TAG, "nativeBlitFramebuffer failed", it)
                false
            }
    }

    fun probeDiskImage(path: String): DiskImageInfo {
        if (path.isBlank()) return DiskImageInfo()
        if (isLibraryLoaded) {
            val raw = runCatching { nativeProbeDiskImage(path) }.getOrNull()
            if (raw != null) {
                runCatching { json.decodeFromString<DiskImageInfo>(raw) }.getOrNull()?.let {
                    return it
                }
            }
        }
        return jvmProbe(path)
    }

    fun probeDiskImage(file: File): DiskImageInfo = probeDiskImage(file.absolutePath)

    fun createEmptyRawImage(path: String, sizeMb: Long): Boolean {
        if (path.isBlank() || sizeMb <= 0) return false
        if (isLibraryLoaded) {
            val ok = runCatching { nativeCreateEmptyRawImage(path, sizeMb) }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(sizeMb * 1024L * 1024L) }
            true
        }.getOrElse {
            Log.w(TAG, "Unable to create raw image at $path", it)
            false
        }
    }

    fun sendSignal(pid: Int, signal: Int): Boolean {
        if (pid <= 0) return false
        if (!isLibraryLoaded) return false
        return runCatching { nativeSendSignal(pid, signal) }.getOrDefault(false)
    }

    /** SIGTERM equivalent used when shutting a guest down. */
    fun terminate(pid: Int): Boolean = sendSignal(pid, 15)

    /** SIGKILL equivalent used after the graceful timeout expired. */
    fun kill(pid: Int): Boolean = sendSignal(pid, 9)

    // ------------------------------------------------------------- fallback

    private fun fallbackCapabilitiesJson(): String {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val abi = when {
            arch.contains("aarch64") || arch == "arm64" -> "arm64-v8a"
            arch.contains("64") -> "x86_64"
            else -> "unknown"
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val payload = NativeCapabilities(
            kvmAvailable = false,
            vhostVsockAvailable = false,
            avfSupported = false,
            cpuCores = cores,
            totalRamMb = -1,
            primaryAbi = abi,
            qemuBinaryPresent = false
        )
        return json.encodeToString(payload)
    }

    private fun jvmProbe(path: String): DiskImageInfo {
        val file = File(path)
        if (!file.isFile) return DiskImageInfo()
        val actualSize = file.length()
        val header = try {
            file.inputStream().use { stream ->
                val probe = ByteArray(32)
                val read = stream.read(probe)
                if (read < 8) null else probe.copyOf(read)
            }
        } catch (io: Exception) {
            null
        }

        if (header != null && header.size >= 8 &&
            header[0] == 0x51.toByte() && header[1] == 0x46.toByte() &&
            header[2] == 0x49.toByte() && header[3] == 0xFB.toByte()
        ) {
            val version = readIntBigEndian(header, 4)
            val virtualSize = if (header.size >= 32) readLongBigEndian(header, 24) else 0L
            return DiskImageInfo(
                format = "qcow2",
                version = version,
                virtualSizeBytes = if (virtualSize > 0) virtualSize else actualSize,
                actualSizeBytes = actualSize
            )
        }
        return DiskImageInfo(
            format = "raw",
            version = 0,
            virtualSizeBytes = actualSize,
            actualSizeBytes = actualSize
        )
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readLongBigEndian(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

/** Mirrors the JSON object produced by `nativeDetectCapabilities`. */
@Serializable
data class NativeCapabilities(
    val kvmAvailable: Boolean = false,
    val vhostVsockAvailable: Boolean = false,
    val avfSupported: Boolean = false,
    val cpuCores: Int = 1,
    val totalRamMb: Long = -1,
    val primaryAbi: String = "unknown",
    val qemuBinaryPresent: Boolean = false
)
