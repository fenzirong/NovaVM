package com.novavm.app

import com.novavm.app.overlay.HardwareInputMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only coverage for [HardwareInputMapper].
 *
 * The mapper never instantiates `android.view.KeyEvent`; it only reads the
 * `KEYCODE_*` compile-time constants, so these tests run on a bare JVM. The
 * numeric key codes below are spelled out literally on purpose: if the Android
 * constant inlining ever regressed, these tests would fail instead of silently
 * comparing 0 to 0.
 */
class HardwareInputMapperTest {

    // ------------------------------------------------------- charToKeysym

    @Test
    fun `maps printable ascii to its own code point`() {
        assertEquals(0x61, HardwareInputMapper.charToKeysym('a'))
        assertEquals(0x5A, HardwareInputMapper.charToKeysym('Z'))
        assertEquals(0x30, HardwareInputMapper.charToKeysym('0'))
        assertEquals(0x20, HardwareInputMapper.charToKeysym(' '))
        assertEquals(0x21, HardwareInputMapper.charToKeysym('!'))
        assertEquals(0x7E, HardwareInputMapper.charToKeysym('~'))
        assertEquals(0x2D, HardwareInputMapper.charToKeysym('-'))
    }

    @Test
    fun `maps newline tab backspace and delete`() {
        assertEquals(HardwareInputMapper.KEYSYM_RETURN, HardwareInputMapper.charToKeysym('\n'))
        assertEquals(0xFF0D, HardwareInputMapper.charToKeysym('\n'))
        // Carriage return collapses onto the same keysym: Windows-style line
        // endings must not produce a doubled Enter in the guest.
        assertEquals(HardwareInputMapper.KEYSYM_RETURN, HardwareInputMapper.charToKeysym('\r'))
        assertEquals(0xFF09, HardwareInputMapper.charToKeysym('\t'))
        assertEquals(0xFF08, HardwareInputMapper.charToKeysym('\b'))
        assertEquals(HardwareInputMapper.KEYSYM_DELETE, HardwareInputMapper.charToKeysym('\u007F'))
        assertEquals(0xFFFF, HardwareInputMapper.charToKeysym('\u007F'))
    }

    @Test
    fun `maps bmp unicode through the 0x01000000 offset range`() {
        // '中' is U+4E2D inside the BMP -> Unicode keysym 0x01004E2D.
        assertEquals(0x01000000 or 0x4E2D, HardwareInputMapper.charToKeysym('\u4E2D'))
        assertEquals(0x01004E2D, HardwareInputMapper.charToKeysym('\u4E2D'))
        // 'é' is U+00E9: above ASCII, still BMP.
        assertEquals(0x010000E9, HardwareInputMapper.charToKeysym('\u00E9'))
        // CJK punctuation '，' U+FF0C.
        assertEquals(0x0100FF0C, HardwareInputMapper.charToKeysym('\uFF0C'))
        assertEquals(
            HardwareInputMapper.KEYSYM_UNICODE_BASE or 0x4E2D,
            HardwareInputMapper.charToKeysym('\u4E2D')
        )
    }

    @Test
    fun `rejects characters that have no keysym`() {
        assertNull(HardwareInputMapper.charToKeysym('\u0000'))
        assertNull(HardwareInputMapper.charToKeysym('\u0001'))
        // 0x1B as a *character* is a C0 control code, not the ESC keysym.
        assertNull(HardwareInputMapper.charToKeysym('\u001B'))
        // Lone UTF-16 surrogate halves cannot be encoded as a keysym.
        assertNull(HardwareInputMapper.charToKeysym('\uD83D'))
        assertNull(HardwareInputMapper.charToKeysym('\uDE00'))
    }

    // ------------------------------------------------ androidKeyCodeToKeysym

    @Test
    fun `maps escape enter tab and backspace key codes`() {
        // KeyEvent.KEYCODE_ESCAPE / KEYCODE_BACK
        assertEquals(0xFF1B, HardwareInputMapper.androidKeyCodeToKeysym(111))
        assertEquals(0xFF1B, HardwareInputMapper.androidKeyCodeToKeysym(4))
        // KeyEvent.KEYCODE_ENTER / KEYCODE_NUMPAD_ENTER / KEYCODE_DPAD_CENTER
        assertEquals(0xFF0D, HardwareInputMapper.androidKeyCodeToKeysym(66))
        assertEquals(0xFF0D, HardwareInputMapper.androidKeyCodeToKeysym(160))
        assertEquals(0xFF0D, HardwareInputMapper.androidKeyCodeToKeysym(23))
        // KeyEvent.KEYCODE_TAB
        assertEquals(0xFF09, HardwareInputMapper.androidKeyCodeToKeysym(61))
        // KeyEvent.KEYCODE_DEL is the physical backspace key.
        assertEquals(0xFF08, HardwareInputMapper.androidKeyCodeToKeysym(67))
    }

    @Test
    fun `maps delete and navigation key codes`() {
        // KeyEvent.KEYCODE_FORWARD_DEL
        assertEquals(0xFFFF, HardwareInputMapper.androidKeyCodeToKeysym(112))
        // KeyEvent.KEYCODE_MOVE_HOME / KEYCODE_MOVE_END
        assertEquals(0xFF50, HardwareInputMapper.androidKeyCodeToKeysym(122))
        assertEquals(0xFF57, HardwareInputMapper.androidKeyCodeToKeysym(123))
        // KeyEvent.KEYCODE_PAGE_UP / KEYCODE_PAGE_DOWN / KEYCODE_INSERT
        assertEquals(0xFF55, HardwareInputMapper.androidKeyCodeToKeysym(92))
        assertEquals(0xFF56, HardwareInputMapper.androidKeyCodeToKeysym(93))
        assertEquals(0xFF63, HardwareInputMapper.androidKeyCodeToKeysym(124))
    }

    @Test
    fun `maps arrow key codes`() {
        // KEYCODE_DPAD_LEFT / UP / RIGHT / DOWN
        assertEquals(HardwareInputMapper.KEYSYM_LEFT, HardwareInputMapper.androidKeyCodeToKeysym(21))
        assertEquals(HardwareInputMapper.KEYSYM_UP, HardwareInputMapper.androidKeyCodeToKeysym(19))
        assertEquals(HardwareInputMapper.KEYSYM_RIGHT, HardwareInputMapper.androidKeyCodeToKeysym(22))
        assertEquals(HardwareInputMapper.KEYSYM_DOWN, HardwareInputMapper.androidKeyCodeToKeysym(20))
        assertEquals(0xFF51, HardwareInputMapper.androidKeyCodeToKeysym(21))
        assertEquals(0xFF52, HardwareInputMapper.androidKeyCodeToKeysym(19))
        assertEquals(0xFF53, HardwareInputMapper.androidKeyCodeToKeysym(22))
        assertEquals(0xFF54, HardwareInputMapper.androidKeyCodeToKeysym(20))
    }

    @Test
    fun `maps modifier key codes`() {
        // KEYCODE_CTRL_LEFT / KEYCODE_CTRL_RIGHT
        assertEquals(HardwareInputMapper.KEYSYM_CTRL_L, HardwareInputMapper.androidKeyCodeToKeysym(113))
        assertEquals(HardwareInputMapper.KEYSYM_CTRL_L, HardwareInputMapper.androidKeyCodeToKeysym(114))
        // KEYCODE_ALT_LEFT / KEYCODE_ALT_RIGHT
        assertEquals(HardwareInputMapper.KEYSYM_ALT_L, HardwareInputMapper.androidKeyCodeToKeysym(57))
        assertEquals(HardwareInputMapper.KEYSYM_ALT_L, HardwareInputMapper.androidKeyCodeToKeysym(58))
        // KEYCODE_SHIFT_LEFT / KEYCODE_SHIFT_RIGHT
        assertEquals(HardwareInputMapper.KEYSYM_SHIFT_L, HardwareInputMapper.androidKeyCodeToKeysym(59))
        assertEquals(HardwareInputMapper.KEYSYM_SHIFT_L, HardwareInputMapper.androidKeyCodeToKeysym(60))
        assertEquals(0xFFE3, HardwareInputMapper.androidKeyCodeToKeysym(113))
        assertEquals(0xFFE9, HardwareInputMapper.androidKeyCodeToKeysym(57))
        assertEquals(0xFFE1, HardwareInputMapper.androidKeyCodeToKeysym(59))
    }

    @Test
    fun `maps the whole F1 to F12 block`() {
        // KEYCODE_F1 = 131 .. KEYCODE_F12 = 142, contiguous in Android and X11.
        for (index in 0 until 12) {
            assertEquals(
                HardwareInputMapper.KEYSYM_F1 + index,
                HardwareInputMapper.androidKeyCodeToKeysym(131 + index)
            )
        }
        assertEquals(0xFFBE, HardwareInputMapper.androidKeyCodeToKeysym(131))
        assertEquals(0xFFC9, HardwareInputMapper.androidKeyCodeToKeysym(142))
        assertEquals(0xFFC3, HardwareInputMapper.androidKeyCodeToKeysym(136))
    }

    @Test
    fun `falls back to the unicode char for letter key codes`() {
        // KEYCODE_A has no dedicated table entry, so getUnicodeChar() decides.
        assertEquals(0x61, HardwareInputMapper.androidKeyCodeToKeysym(29, 'a'.code))
        assertEquals(0x41, HardwareInputMapper.androidKeyCodeToKeysym(29, 'A'.code))
        assertEquals(0x01004E2D, HardwareInputMapper.androidKeyCodeToKeysym(29, 0x4E2D))
        assertNull(HardwareInputMapper.androidKeyCodeToKeysym(29))
        assertNull(HardwareInputMapper.androidKeyCodeToKeysym(29, 0))
        // Non-BMP payloads cannot be represented as a single keysym.
        assertNull(HardwareInputMapper.androidKeyCodeToKeysym(29, 0x1F600))
    }

    // ------------------------------------------------------- textToKeysyms

    @Test
    fun `converts the ls dash la string into keysyms`() {
        val keysyms = HardwareInputMapper.textToKeysyms("ls -la\n")

        assertEquals(listOf(0x6C, 0x73, 0x20, 0x2D, 0x6C, 0x61, 0xFF0D), keysyms)
        assertEquals(7, keysyms.size)
        assertEquals(HardwareInputMapper.KEYSYM_RETURN, keysyms.last())
    }

    @Test
    fun `converts mixed text and drops unmappable characters`() {
        assertEquals(listOf(0x61, 0x01004E2D), HardwareInputMapper.textToKeysyms("a\u0001\u4E2D"))
        assertEquals(emptyList<Int>(), HardwareInputMapper.textToKeysyms(""))
        assertEquals(listOf(0xFF0D), HardwareInputMapper.textToKeysyms("\n"))
        assertTrue(HardwareInputMapper.textToKeysyms("x").isNotEmpty())
    }

    // -------------------------------------------------------- keysymLabel

    @Test
    fun `renders readable chip labels`() {
        assertEquals("ESC", HardwareInputMapper.keysymLabel(HardwareInputMapper.KEYSYM_ESC))
        assertEquals("ENTER", HardwareInputMapper.keysymLabel(HardwareInputMapper.KEYSYM_RETURN))
        assertEquals("F1", HardwareInputMapper.keysymLabel(HardwareInputMapper.KEYSYM_F1))
        assertEquals("F12", HardwareInputMapper.keysymLabel(HardwareInputMapper.KEYSYM_F12))
        assertEquals("0x00AB", HardwareInputMapper.keysymLabel(0xAB))
    }
}
