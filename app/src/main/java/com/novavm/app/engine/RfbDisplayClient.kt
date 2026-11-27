package com.novavm.app.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class RfbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Wire helpers for RFC 6143 (The Remote Framebuffer Protocol). Pure functions
 * keep byte-level formatting testable without a live server.
 */
object RfbProtocol {

    const val VERSION_3_8 = "RFB 003.008\n"
    const val SECURITY_NONE = 1

    const val ENCODING_RAW = 0
    const val ENCODING_COPY_RECT = 1
    const val ENCODING_DESKTOP_SIZE = -223

    const val KEYSYM_SHIFT = 0xFFE1
    const val KEYSYM_CONTROL = 0xFFE3
    const val KEYSYM_ENTER = 0xFF0D
    const val KEYSYM_BACKSPACE = 0xFF08
    const val KEYSYM_TAB = 0xFF09
    const val KEYSYM_ESCAPE = 0xFF1B
    const val KEYSYM_UP = 0xFF52
    const val KEYSYM_DOWN = 0xFF54
    const val KEYSYM_LEFT = 0xFF51
    const val KEYSYM_RIGHT = 0xFF53

    /** RGBA8888, 24bpp, little endian, true colour with 8 bits per channel. */
    fun setPixelFormatMessage(): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0)               // message-type: SetPixelFormat
        buffer.put(byteArrayOf(0, 0, 0)) // padding
        buffer.put(32)              // bits-per-pixel
        buffer.put(24)              // depth
        buffer.put(0)               // big-endian-flag: false
        buffer.put(1)               // true-colour-flag
        buffer.putShort(255)        // red-max
        buffer.putShort(255)        // green-max
        buffer.putShort(255)        // blue-max
        buffer.put(0)               // red-shift
        buffer.put(8)               // green-shift
        buffer.put(16)              // blue-shift
        buffer.put(byteArrayOf(0, 0, 0)) // padding
        return buffer.array()
    }

    fun setEncodingsMessage(encodings: IntArray = intArrayOf(ENCODING_RAW, ENCODING_COPY_RECT)): ByteArray {
        val buffer = ByteBuffer.allocate(4 + encodings.size * 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(2)               // message-type: SetEncodings
        buffer.put(0)               // padding
        buffer.putShort(encodings.size.toShort())
        encodings.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    fun clientInit(shared: Boolean = true): ByteArray = byteArrayOf(if (shared) 1 else 0)

    fun securityTypeSelection(type: Int = SECURITY_NONE): ByteArray = byteArrayOf(type.toByte())

    fun framebufferUpdateRequest(
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buffer.put(3)               // message-type: FramebufferUpdateRequest
        buffer.put(if (incremental) 1 else 0)
        buffer.putShort(x.toShort())
        buffer.putShort(y.toShort())
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        return buffer.array()
    }

    fun pointerEvent(buttonMask: Int, x: Int, y: Int): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buffer.put(5)               // message-type: PointerEvent
        buffer.put(buttonMask.toByte())
        buffer.putShort(x.toShort())
        buffer.putShort(y.toShort())
        return buffer.array()
    }

    fun keyEvent(down: Boolean, keysym: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.put(4)               // message-type: KeyEvent
        buffer.put(if (down) 1 else 0)
        buffer.put(byteArrayOf(0, 0)) // padding
        buffer.putInt(keysym)
        return buffer.array()
    }

    /** Maps a printable ASCII char to an X11 keysym. */
    fun keysymFor(char: Char): Int = char.code

    /** Parses the 12 byte protocol version string. */
    fun parseVersion(bytes: ByteArray): String {
        require(bytes.size >= 12) { "version must be 12 bytes" }
        return String(bytes, 0, 12, StandardCharsets.US_ASCII)
    }

    /** 3.3 servers send the security type directly instead of a list. */
    fun majorMinor(version: String): Pair<Int, Int> {
        val match = Regex("RFB (\\d{3})\\.(\\d{3})").find(version)
            ?: throw RfbException("Unsupported RFB version: $version")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
}

/**
 * Minimal blocking RFB client able to decode Raw and CopyRect updates straight
 * into a direct RGBA [ByteBuffer] that Android can blit to a Surface.
 */
class RfbDisplayClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    val width: Int,
    val height: Int,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
    buffer: ByteBuffer? = null
) : Closeable {

    val framebuffer: ByteBuffer = (buffer ?: ByteBuffer.allocateDirect(width * height * 4))
        .order(ByteOrder.LITTLE_ENDIAN)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val lock = Any()

    @Volatile var serverWidth: Int = width
        private set
    @Volatile var serverHeight: Int = height
        private set
    @Volatile var serverName: String = ""
        private set
    @Volatile var protocolVersion: String = ""
        private set
    @Volatile var isConnected: Boolean = false
        private set

    private val rowBuffer = ByteArray(width * 4)

    fun connect(shared: Boolean = true) {
        synchronized(lock) {
            if (isConnected) throw RfbException("RFB client already connected")
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                this.socket = socket
                val dataIn = DataInputStream(BufferedInputStream(socket.getInputStream()))
                input = dataIn
                output = BufferedOutputStream(socket.getOutputStream())

                // ProtocolVersion handshake.
                val serverVersion = ByteArray(12)
                dataIn.readFully(serverVersion)
                protocolVersion = RfbProtocol.parseVersion(serverVersion)
                val (major, minor) = RfbProtocol.majorMinor(protocolVersion)
                if (major != 3) throw RfbException("Unsupported RFB major version $major")
                write(RfbProtocol.VERSION_3_8.toByteArray(StandardCharsets.US_ASCII))

                // Security handshake: None only.
                val securityType = if (major == 3 && minor >= 7) {
                    val count = dataIn.readUnsignedByte()
                    if (count == 0) {
                        val reasonLength = dataIn.readInt()
                        val reason = ByteArray(reasonLength.coerceAtLeast(0))
                        dataIn.readFully(reason)
                        throw RfbException(
                            "Server refused connection: ${String(reason, StandardCharsets.UTF_8)}"
                        )
                    }
                    val types = IntArray(count) { dataIn.readUnsignedByte() }
                    if (RfbProtocol.SECURITY_NONE !in types) {
                        throw RfbException("Server requires authentication; only None is supported")
                    }
                    RfbProtocol.SECURITY_NONE
                } else {
                    // RFB 3.3: server dictates the security type.
                    dataIn.readInt()
                }

                write(RfbProtocol.securityTypeSelection(securityType))
                val securityResult = dataIn.readInt()
                if (securityResult != 0) {
                    throw RfbException("Security handshake failed (result=$securityResult)")
                }

                write(RfbProtocol.clientInit(shared))

                serverWidth = dataIn.readUnsignedShort()
                serverHeight = dataIn.readUnsignedShort()
                // 16 byte PIXEL_FORMAT followed by the desktop name.
                val pixelFormat = ByteArray(16)
                dataIn.readFully(pixelFormat)
                val nameLength = dataIn.readInt()
                val nameBytes = ByteArray(nameLength.coerceIn(0, 4096))
                dataIn.readFully(nameBytes)
                serverName = String(nameBytes, StandardCharsets.UTF_8)

                write(RfbProtocol.setPixelFormatMessage())
                write(RfbProtocol.setEncodingsMessage())
                isConnected = true
            } catch (error: IOException) {
                closeQuietly()
                throw if (error is RfbException) error else RfbException("RFB connect failed", error)
            }
        }
    }

    fun requestUpdate(incremental: Boolean = false) {
        requireConnected()
        write(
            RfbProtocol.framebufferUpdateRequest(
                incremental,
                0,
                0,
                serverWidth.coerceAtLeast(1),
                serverHeight.coerceAtLeast(1)
            )
        )
    }

    /**
     * Reads one server message and, for FramebufferUpdate, decodes every
     * rectangle into [framebuffer]. Returns the number of decoded rectangles.
     */
    fun readUpdate(): Int {
        synchronized(lock) {
            requireConnected()
            val dataIn = input ?: throw RfbException("RFB socket is closed")
            try {
                while (true) {
                    when (val messageType = dataIn.readUnsignedByte()) {
                        0 -> return readFramebufferUpdate(dataIn)
                        1 -> skipColourMap(dataIn)
                        2 -> Unit                    // Bell
                        3 -> skipServerCutText(dataIn)
                        else -> throw RfbException("Unknown server message type $messageType")
                    }
                }
            } catch (error: EOFException) {
                closeQuietly()
                throw RfbException("RFB server closed the connection", error)
            } catch (error: IOException) {
                closeQuietly()
                throw if (error is RfbException) error else RfbException("RFB read failed", error)
            }
        }
    }

    private fun readFramebufferUpdate(dataIn: DataInputStream): Int {
        dataIn.readUnsignedByte() // padding
        val rectangles = dataIn.readUnsignedShort()
        repeat(rectangles) {
            val x = dataIn.readUnsignedShort()
            val y = dataIn.readUnsignedShort()
            val rectWidth = dataIn.readUnsignedShort()
            val rectHeight = dataIn.readUnsignedShort()
            when (val encoding = dataIn.readInt()) {
                RfbProtocol.ENCODING_RAW -> readRawRect(dataIn, x, y, rectWidth, rectHeight)
                RfbProtocol.ENCODING_COPY_RECT -> {
                    val srcX = dataIn.readUnsignedShort()
                    val srcY = dataIn.readUnsignedShort()
                    readCopyRect(srcX, srcY, x, y, rectWidth, rectHeight)
                }
                RfbProtocol.ENCODING_DESKTOP_SIZE -> {
                    serverWidth = rectWidth
                    serverHeight = rectHeight
                }
                else -> throw RfbException("Unsupported RFB encoding $encoding")
            }
        }
        return rectangles
    }

    /**
     * Byte distance from the start of [framebuffer] that rectangles may write
     * to. Both `capacity` and `limit` are honoured so a guest that announces a
     * larger desktop than the staging buffer simply gets clipped.
     */
    private fun destinationByteLimit(): Int =
        minOf(framebuffer.capacity(), framebuffer.limit()).coerceAtLeast(0)

    /**
     * Decodes one Raw rectangle.
     *
     * The destination span is clipped to `[0, width)` instead of dropping the
     * whole row when `x + rectWidth > width`, but every row is still read from
     * [dataIn] so the RFB stream can never desynchronise.
     */
    private fun readRawRect(
        dataIn: DataInputStream,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int
    ) {
        val rowBytes = rectWidth * 4
        val scratch = if (rowBytes <= rowBuffer.size) rowBuffer else ByteArray(rowBytes)
        val byteLimit = destinationByteLimit()
        // Horizontal span the framebuffer can actually hold. The bytes outside
        // it stay in `scratch` and are simply not blitted.
        val clipFromX = maxOf(0, x)
        val clipToX = minOf(width, x + rectWidth)
        val clipWidth = clipToX - clipFromX
        val scratchOffset = (clipFromX - x) * 4
        for (row in 0 until rectHeight) {
            dataIn.readFully(scratch, 0, rowBytes)
            val targetRow = y + row
            if (targetRow !in 0 until height || clipWidth <= 0) continue
            val offset = (targetRow * width + clipFromX) * 4
            val length = clipWidth * 4
            if (offset < 0 || offset + length > byteLimit) continue
            framebuffer.position(offset)
            framebuffer.put(scratch, scratchOffset, length)
        }
        framebuffer.position(0)
    }

    /**
     * Decodes one CopyRect rectangle.
     *
     * Rows are staged through a scratch buffer first, so overlapping moves and
     * out-of-bounds source regions behave deterministically. Source and
     * destination spans are both clipped to `[0, width)` x `[0, height)`.
     */
    private fun readCopyRect(
        srcX: Int,
        srcY: Int,
        dstX: Int,
        dstY: Int,
        rectWidth: Int,
        rectHeight: Int
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return
        val bytesPerPixel = 4
        val rowBytes = rectWidth * bytesPerPixel
        val rowScratch = ByteArray(rowBytes)
        val byteLimit = destinationByteLimit()
        val srcFromX = maxOf(0, srcX)
        val srcToX = minOf(width, srcX + rectWidth)
        val dstFromX = maxOf(0, dstX)
        val dstToX = minOf(width, dstX + rectWidth)
        val copyWidth = dstToX - dstFromX
        // Only when the source row is not fully readable does the scratch carry
        // stale bytes from the previous row.
        val needsZeroFill = (srcToX - srcFromX) < rectWidth

        for (row in 0 until rectHeight) {
            val sourceRow = srcY + row
            if (needsZeroFill || sourceRow !in 0 until height) rowScratch.fill(0)
            if (sourceRow in 0 until height && srcToX > srcFromX) {
                val srcOffset = (sourceRow * width + srcFromX) * bytesPerPixel
                val srcLength = (srcToX - srcFromX) * bytesPerPixel
                if (srcOffset >= 0 && srcOffset + srcLength <= byteLimit) {
                    framebuffer.position(srcOffset)
                    framebuffer.get(rowScratch, (srcFromX - srcX) * bytesPerPixel, srcLength)
                }
            }
            val targetRow = dstY + row
            if (targetRow !in 0 until height || copyWidth <= 0) continue
            val dstOffset = (targetRow * width + dstFromX) * bytesPerPixel
            val dstLength = copyWidth * bytesPerPixel
            if (dstOffset < 0 || dstOffset + dstLength > byteLimit) continue
            framebuffer.position(dstOffset)
            framebuffer.put(rowScratch, (dstFromX - dstX) * bytesPerPixel, dstLength)
        }
        framebuffer.position(0)
    }

    private fun skipColourMap(dataIn: DataInputStream) {
        dataIn.readUnsignedByte()
        dataIn.readUnsignedShort()
        val count = dataIn.readUnsignedShort()
        dataIn.skipBytes(count * 6)
    }

    private fun skipServerCutText(dataIn: DataInputStream) {
        dataIn.skipBytes(3)
        val length = dataIn.readInt().coerceAtLeast(0)
        dataIn.skipBytes(length)
    }

    fun sendPointerEvent(buttonMask: Int, x: Int, y: Int) {
        requireConnected()
        write(RfbProtocol.pointerEvent(buttonMask, x, y))
    }

    fun sendKeyEvent(down: Boolean, keysym: Int) {
        requireConnected()
        write(RfbProtocol.keyEvent(down, keysym))
    }

    fun sendKeyPress(keysym: Int) {
        sendKeyEvent(true, keysym)
        sendKeyEvent(false, keysym)
    }

    /** Types an ASCII string, synthesising Shift for uppercase/symbols. */
    fun sendText(text: String) {
        text.forEach { char ->
            when {
                char == '\n' -> sendKeyPress(RfbProtocol.KEYSYM_ENTER)
                char == '\t' -> sendKeyPress(RfbProtocol.KEYSYM_TAB)
                char in 'a'..'z' || char in '0'..'9' || char == ' ' -> {
                    sendKeyPress(RfbProtocol.keysymFor(char))
                }
                char in 'A'..'Z' -> {
                    sendKeyEvent(true, RfbProtocol.KEYSYM_SHIFT)
                    sendKeyPress(RfbProtocol.keysymFor(char.lowercaseChar()))
                    sendKeyEvent(false, RfbProtocol.KEYSYM_SHIFT)
                }
                else -> sendKeyPress(RfbProtocol.keysymFor(char))
            }
        }
    }

    private fun write(bytes: ByteArray) {
        val out = output ?: throw RfbException("RFB socket is closed")
        out.write(bytes)
        out.flush()
    }

    private fun requireConnected() {
        if (!isConnected) throw RfbException("RFB client is not connected")
    }

    private fun closeQuietly() {
        isConnected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    override fun close() {
        synchronized(lock) { closeQuietly() }
    }
}
