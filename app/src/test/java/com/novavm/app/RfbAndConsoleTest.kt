package com.novavm.app

import com.novavm.app.engine.BootConsoleRenderer
import com.novavm.app.engine.RfbProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RfbAndConsoleTest {

    @Test
    fun `set pixel format requests RGBA8888 little endian`() {
        val message = RfbProtocol.setPixelFormatMessage()
        assertEquals(20, message.size)
        assertEquals(0, message[0].toInt())          // SetPixelFormat
        assertEquals(32, message[4].toInt())         // bits per pixel
        assertEquals(24, message[5].toInt())         // depth
        assertEquals(0, message[6].toInt())          // big endian flag
        assertEquals(1, message[7].toInt())          // true colour
        assertEquals(0, message[14].toInt())         // red shift
        assertEquals(8, message[15].toInt())         // green shift
        assertEquals(16, message[16].toInt())        // blue shift
    }

    @Test
    fun `handshake byte sequences match RFC 6143`() {
        assertEquals("RFB 003.008\n", RfbProtocol.VERSION_3_8)
        assertEquals(1, RfbProtocol.clientInit(shared = true).single().toInt())
        assertEquals(0, RfbProtocol.clientInit(shared = false).single().toInt())
        assertEquals(1, RfbProtocol.securityTypeSelection().single().toInt())

        val (major, minor) = RfbProtocol.majorMinor("RFB 003.008\n")
        assertEquals(3, major)
        assertEquals(8, minor)
        assertEquals("RFB 003.008\n", RfbProtocol.parseVersion(RfbProtocol.VERSION_3_8.toByteArray()))
    }

    @Test
    fun `framebuffer pointer and key messages have correct layouts`() {
        val request = RfbProtocol.framebufferUpdateRequest(false, 0, 0, 720, 1280)
        assertEquals(10, request.size)
        assertEquals(3, request[0].toInt())
        assertEquals(0, request[1].toInt())
        assertEquals(720, readShort(request, 6))
        assertEquals(1280, readShort(request, 8))

        val pointer = RfbProtocol.pointerEvent(buttonMask = 1, x = 300, y = 600)
        assertEquals(6, pointer.size)
        assertEquals(5, pointer[0].toInt())
        assertEquals(1, pointer[1].toInt())
        assertEquals(300, readShort(pointer, 2))
        assertEquals(600, readShort(pointer, 4))

        val keyDown = RfbProtocol.keyEvent(down = true, keysym = RfbProtocol.KEYSYM_ENTER)
        assertEquals(8, keyDown.size)
        assertEquals(4, keyDown[0].toInt())
        assertEquals(1, keyDown[1].toInt())
        assertEquals(RfbProtocol.KEYSYM_ENTER, readInt(keyDown, 4))

        val keyUp = RfbProtocol.keyEvent(down = false, keysym = 'a'.code)
        assertEquals(0, keyUp[1].toInt())
        assertEquals(97, readInt(keyUp, 4))
    }

    @Test
    fun `set encodings advertises raw and copyrect`() {
        val message = RfbProtocol.setEncodingsMessage()
        assertEquals(2, message[0].toInt())
        assertEquals(2, readShort(message, 2))
        assertEquals(RfbProtocol.ENCODING_RAW, readInt(message, 4))
        assertEquals(RfbProtocol.ENCODING_COPY_RECT, readInt(message, 8))
    }

    @Test
    fun `boot console renderer paints a full frame`() {
        val width = 360
        val height = 640
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val renderer = BootConsoleRenderer(width, height)
        renderer.appendLine("[ OK ] virtio-gpu initialized")
        renderer.appendLine("[ .. ] waiting for guest kernel")
        renderer.setStatus("CONSOLE READY", "No guest attached")
        renderer.setProgress(0.42f)

        assertTrue(renderer.render(buffer, timeMs = 0))
        assertEquals(0, buffer.position())

        var nonZero = 0
        for (index in 0 until width * height) {
            if (buffer.getInt(index * 4) != 0) nonZero++
        }
        assertTrue("renderer should fill the framebuffer", nonZero > width * height / 2)

        assertTrue(renderer.visibleLines().any { it.contains("virtio-gpu") })
        renderer.clear()
        assertTrue(renderer.visibleLines().isEmpty())
    }

    @Test
    fun `boot console renderer rejects undersized buffers`() {
        val renderer = BootConsoleRenderer(720, 1280)
        val tooSmall = ByteBuffer.allocateDirect(16)
        assertFalse(renderer.render(tooSmall))
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
