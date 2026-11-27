package com.novavm.app.overlay

import android.view.KeyEvent
import java.util.Locale

/**
 * Translates Android input into the X11 / RFB keysyms that
 * [com.novavm.app.engine.RfbProtocol.keyEvent] ships to a guest.
 *
 * The mapper only touches `KeyEvent.KEYCODE_*` integer constants (compile-time
 * constants that the Kotlin compiler inlines), so every function here is pure
 * Kotlin and runs unmodified in a plain JVM unit test — no Robolectric, no
 * `android.jar` stubs and no `KeyEvent` instance required.
 *
 * Keysym values follow the standard X11 `keysymdef.h` layout:
 * - Latin-1 printable characters map to their own code point (`0x20..0x7E`);
 * - non-ASCII BMP characters use the Unicode keysym range `0x01000000 | code`;
 * - special keys live in the `0xFF00..0xFFFF` block.
 */
object HardwareInputMapper {

    // ------------------------------------------------------------ special keys

    const val KEYSYM_ESC = 0xFF1B
    const val KEYSYM_TAB = 0xFF09
    const val KEYSYM_BACKSPACE = 0xFF08
    const val KEYSYM_RETURN = 0xFF0D
    const val KEYSYM_DELETE = 0xFFFF
    const val KEYSYM_HOME = 0xFF50
    const val KEYSYM_LEFT = 0xFF51
    const val KEYSYM_UP = 0xFF52
    const val KEYSYM_RIGHT = 0xFF53
    const val KEYSYM_DOWN = 0xFF54

    /** Extra navigation keys, useful for editors and shells inside the guest. */
    const val KEYSYM_PAGE_UP = 0xFF55
    const val KEYSYM_PAGE_DOWN = 0xFF56
    const val KEYSYM_END = 0xFF57
    const val KEYSYM_INSERT = 0xFF63

    // -------------------------------------------------------------- modifiers

    const val KEYSYM_CTRL_L = 0xFFE3
    const val KEYSYM_ALT_L = 0xFFE9
    const val KEYSYM_SHIFT_L = 0xFFE1

    // ------------------------------------------------------------ function row

    const val KEYSYM_F1 = 0xFFBE
    const val KEYSYM_F2 = 0xFFBF
    const val KEYSYM_F3 = 0xFFC0
    const val KEYSYM_F4 = 0xFFC1
    const val KEYSYM_F5 = 0xFFC2
    const val KEYSYM_F6 = 0xFFC3
    const val KEYSYM_F7 = 0xFFC4
    const val KEYSYM_F8 = 0xFFC5
    const val KEYSYM_F9 = 0xFFC6
    const val KEYSYM_F10 = 0xFFC7
    const val KEYSYM_F11 = 0xFFC8
    const val KEYSYM_F12 = 0xFFC9

    /** Base of the Unicode keysym range: `0x01000000 | codePoint`. */
    const val KEYSYM_UNICODE_BASE = 0x01000000

    // ---------------------------------------------------------------- mapping

    /**
     * Maps a single UTF-16 [Char] to an RFB keysym.
     *
     * - `\n` and `\r` -> [KEYSYM_RETURN]
     * - `\t` -> [KEYSYM_TAB]
     * - `\b` -> [KEYSYM_BACKSPACE]
     * - `0x7F` (DEL) -> [KEYSYM_DELETE]
     * - printable ASCII `0x20..0x7E` -> the character code itself
     * - other BMP code points -> `0x01000000 or c.code`
     *
     * Other C0 control characters and UTF-16 surrogate halves have no keysym
     * and yield `null`, so callers can simply skip them.
     */
    fun charToKeysym(c: Char): Int? = when {
        c == '\n' || c == '\r' -> KEYSYM_RETURN
        c == '\t' -> KEYSYM_TAB
        c == '\b' -> KEYSYM_BACKSPACE
        c.code == 0x7F -> KEYSYM_DELETE
        c.code in 0x20..0x7E -> c.code
        c.code < 0x20 -> null
        c.code in 0xD800..0xDFFF -> null
        else -> KEYSYM_UNICODE_BASE or c.code
    }

    /**
     * Maps an Android `KeyEvent.KEYCODE_*` to an RFB keysym.
     *
     * Non-character keys (arrows, Enter, Delete, Escape, Tab, modifiers, F1–F12,
     * Home/End, PageUp/PageDown, Insert) are resolved from the key code alone.
     * Everything else falls back to [unicodeChar] — the value of
     * `KeyEvent.getUnicodeChar()` — so plain letters and symbols work without a
     * per-key table.
     */
    fun androidKeyCodeToKeysym(keyCode: Int, unicodeChar: Int = 0): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> KEYSYM_ESC
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> KEYSYM_RETURN
        KeyEvent.KEYCODE_TAB -> KEYSYM_TAB
        KeyEvent.KEYCODE_DEL -> KEYSYM_BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> KEYSYM_DELETE
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_HOME -> KEYSYM_HOME
        KeyEvent.KEYCODE_MOVE_END -> KEYSYM_END
        KeyEvent.KEYCODE_DPAD_LEFT -> KEYSYM_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> KEYSYM_UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEYSYM_RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> KEYSYM_DOWN
        KeyEvent.KEYCODE_PAGE_UP -> KEYSYM_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> KEYSYM_PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> KEYSYM_INSERT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KEYSYM_CTRL_L
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KEYSYM_ALT_L
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KEYSYM_SHIFT_L
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            KEYSYM_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> unicodeCharToKeysym(unicodeChar)
    }

    /** Resolves a `KeyEvent.getUnicodeChar()` value, ignoring non-BMP payloads. */
    fun unicodeCharToKeysym(unicodeChar: Int): Int? =
        if (unicodeChar in 1..0xFFFF) charToKeysym(unicodeChar.toChar()) else null

    /**
     * Converts a typed [text] into the keysym sequence that reproduces it inside
     * the guest. Characters without a keysym are dropped.
     */
    fun textToKeysyms(text: String): List<Int> = text.mapNotNull { charToKeysym(it) }

    /** Human readable label used by [VirtualKeyboardBar]; never null. */
    fun keysymLabel(keysym: Int): String = when (keysym) {
        KEYSYM_ESC -> "ESC"
        KEYSYM_TAB -> "TAB"
        KEYSYM_BACKSPACE -> "⌫"
        KEYSYM_RETURN -> "ENTER"
        KEYSYM_DELETE -> "DEL"
        KEYSYM_HOME -> "HOME"
        KEYSYM_END -> "END"
        KEYSYM_LEFT -> "←"
        KEYSYM_UP -> "↑"
        KEYSYM_RIGHT -> "→"
        KEYSYM_DOWN -> "↓"
        KEYSYM_PAGE_UP -> "PGUP"
        KEYSYM_PAGE_DOWN -> "PGDN"
        KEYSYM_INSERT -> "INS"
        KEYSYM_CTRL_L -> "CTRL"
        KEYSYM_ALT_L -> "ALT"
        KEYSYM_SHIFT_L -> "SHIFT"
        in KEYSYM_F1..KEYSYM_F12 -> "F${keysym - KEYSYM_F1 + 1}"
        else -> String.format(Locale.US, "0x%04X", keysym)
    }
}
