package com.novavm.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Software fallback display surface.
 *
 * When the app has no QEMU binary, no ROM or no RFB server yet, the console
 * still needs to feel alive: this renderer paints a crisp boot console and
 * status HUD (5x7 bitmap font, scaled) straight into the same direct RGBA
 * [ByteBuffer] that [NativeEngine.blitFramebuffer] uploads to the Surface.
 */
class BootConsoleRenderer(
    val width: Int,
    val height: Int
) {

    private val lines = ArrayDeque<String>()
    private val maxLines = 40

    var status: String = "CONSOLE READY"
    var statusDetail: String = "No guest kernel attached"
    var progress: Float = 0.18f
    var footerHint: String = "Tap to interact  |  VNC 127.0.0.1:5901"
    var bootStage: String = "STAGE 1 / HOST PROBE"

    private val accent = 0xFF4ADE80.toInt()
    private val accentDim = 0xFF1F6F43.toInt()
    private val backgroundTop = 0xFF070B11.toInt()
    private val backgroundBottom = 0xFF0D1620.toInt()
    private val surfaceColor = 0xFF0B121B.toInt()
    private val textColor = 0xFFD7E3F4.toInt()
    private val mutedColor = 0xFF7C8DA6.toInt()
    private val warningColor = 0xFFFBBF24.toInt()
    private val dangerColor = 0xFFF87171.toInt()

    init {
        appendLine("NovaVM userland boot console")
        appendLine("----------------------------------------")
        appendLine("Guest payload not attached; rendering synthetic console.")
        appendLine("Attach a ROM image or place qemu-system-* in the app sandbox.")
    }

    fun appendLine(line: String): BootConsoleRenderer {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
        return this
    }

    fun clear() {
        lines.clear()
    }

    fun setProgress(value: Float): BootConsoleRenderer {
        progress = value.coerceIn(0f, 1f)
        return this
    }

    fun setStatus(value: String, detail: String = statusDetail): BootConsoleRenderer {
        status = value
        statusDetail = detail
        return this
    }

    fun visibleLines(): List<String> = lines.toList()

    /**
     * Paints the whole frame. Returns false when [target] is too small for the
     * configured resolution.
     */
    fun render(target: ByteBuffer, timeMs: Long = System.currentTimeMillis()): Boolean {
        if (target.capacity() < width * height * 4) return false
        target.order(ByteOrder.LITTLE_ENDIAN)
        drawBackground(target)

        val margin = (width * 0.05f).toInt().coerceAtLeast(12)
        var y = margin

        // Header: product mark + live status pill.
        drawText(target, "NOVAVM", margin, y, scale = headerScale(), color = accent)
        val markWidth = textWidth("NOVAVM", headerScale())
        drawText(
            target,
            "// VIRTUAL DEVICE",
            margin + markWidth + 10 * bodyScale(),
            y + (headerScale() - bodyScale()) * 7,
            scale = bodyScale(),
            color = mutedColor
        )

        val pillText = status.uppercase()
        val pillScale = bodyScale()
        val pillWidth = textWidth(pillText, pillScale) + 8 * pillScale
        val pillHeight = 7 * pillScale + 6 * pillScale
        val pillX = width - margin - pillWidth
        val pillColor = when {
            status.contains("ERROR", true) || status.contains("FAIL", true) -> dangerColor
            status.contains("PAUSE", true) || status.contains("WAIT", true) -> warningColor
            else -> accent
        }
        fillRect(target, pillX, y - 2 * pillScale, pillWidth, pillHeight, accentDim)
        fillRect(target, pillX, y - 2 * pillScale, 2 * pillScale, pillHeight, pillColor)
        drawText(
            target,
            pillText,
            pillX + 5 * pillScale,
            y + 1 * pillScale,
            pillScale,
            pillColor
        )

        y += 7 * headerScale() + 6 * bodyScale()
        drawStatusDetail(target, margin, y)
        y += 8 * bodyScale() + 4

        // Divider.
        fillRect(target, margin, y, width - margin * 2, lineThickness(), accentDim)
        y += lineThickness() + 8 * bodyScale()

        // Console body.
        val bodyTextScale = bodyScale()
        val lineHeight = 7 * bodyTextScale + 5 * bodyTextScale
        val maxChars = ((width - margin * 2) / (6 * bodyTextScale)).coerceAtLeast(16)
        val wrapped = lines.flatMap { wrap(it, maxChars) }
        val reserved = 20 * bodyTextScale + 40
        val available = ((height - y - reserved) / lineHeight).coerceAtLeast(3)
        val visible = wrapped.takeLast(available)
        val startY = y
        visible.forEachIndexed { index, line ->
            val isLast = index == visible.lastIndex
            val color = when {
                line.startsWith("[ OK ]") || line.startsWith("[  OK  ]") -> accent
                line.startsWith("[FAIL]") || line.contains("error", true) -> dangerColor
                line.startsWith("[ .. ]") || line.startsWith("[WAIT]") -> warningColor
                line.startsWith("---") -> mutedColor
                else -> textColor
            }
            drawText(target, line, margin, y, bodyTextScale, color)
            if (isLast && (timeMs / 500) % 2 == 0L) {
                val cursorX = margin + textWidth(line, bodyTextScale) + 2 * bodyTextScale
                fillRect(
                    target,
                    cursorX,
                    y,
                    4 * bodyTextScale,
                    7 * bodyTextScale,
                    accent
                )
            }
            y += lineHeight
        }

        // Progress panel anchored to the bottom.
        val panelTop = height - margin - 26 * bodyTextScale
        fillRect(target, margin, panelTop, width - margin * 2, 22 * bodyTextScale, surfaceColor)
        drawText(target, bootStage, margin + 6 * bodyTextScale, panelTop + 4 * bodyTextScale, bodyTextScale, mutedColor)
        val percent = "${(progress * 100).toInt()}%"
        drawText(
            target,
            percent,
            width - margin - 6 * bodyTextScale - textWidth(percent, bodyTextScale),
            panelTop + 4 * bodyTextScale,
            bodyTextScale,
            accent
        )
        val barLeft = margin + 6 * bodyTextScale
        val barTop = panelTop + 13 * bodyTextScale
        val barWidth = (width - margin * 2 - 12 * bodyTextScale).coerceAtLeast(1)
        val barHeight = 4 * bodyTextScale
        fillRect(target, barLeft, barTop, barWidth, barHeight, backgroundTop)
        fillRect(target, barLeft, barTop, (barWidth * progress).toInt(), barHeight, accent)
        val knobX = barLeft + (barWidth * progress).toInt()
        fillRect(target, knobX.coerceAtMost(barLeft + barWidth - 2), barTop - 2, 2, barHeight + 4, 0xFFFFFFFF.toInt())

        // Footer hint, just above the progress panel.
        drawText(
            target,
            footerHint,
            margin,
            panelTop - 8 * bodyTextScale,
            bodyTextScale,
            mutedColor
        )

        target.position(0)
        return true
    }

    private fun drawStatusDetail(target: ByteBuffer, x: Int, y: Int) {
        drawText(target, statusDetail, x, y, bodyScale(), mutedColor)
    }

    private fun drawBackground(target: ByteBuffer) {
        for (row in 0 until height) {
            val fraction = row.toFloat() / height
            val color = lerpColor(backgroundTop, backgroundBottom, fraction)
            var index = row * width * 4
            repeat(width) {
                target.putInt(index, color)
                index += 4
            }
        }
    }

    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun headerScale(): Int = (width / 180).coerceIn(2, 5)

    private fun bodyScale(): Int = (width / 360).coerceIn(1, 3)

    private fun lineThickness(): Int = (width / 360).coerceIn(1, 3)

    private fun drawText(target: ByteBuffer, text: String, x: Int, y: Int, scale: Int, color: Int) {
        var cursorX = x
        text.forEach { char ->
            val glyph = FONT[char.uppercaseChar()] ?: FONT['?']!!
            for (row in 0 until GLYPH_HEIGHT) {
                for (col in 0 until GLYPH_WIDTH) {
                    if (glyph[row * GLYPH_WIDTH + col] == '#') {
                        fillRect(target, cursorX + col * scale, y + row * scale, scale, scale, color)
                    }
                }
            }
            cursorX += (GLYPH_WIDTH + 1) * scale
        }
    }

    fun textWidth(text: String, scale: Int): Int = text.length * (GLYPH_WIDTH + 1) * scale

    private fun fillRect(target: ByteBuffer, x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        val left = x.coerceAtLeast(0)
        val top = y.coerceAtLeast(0)
        val right = (x + w).coerceAtMost(width)
        val bottom = (y + h).coerceAtMost(height)
        if (right <= left || bottom <= top) return
        for (row in top until bottom) {
            var index = (row * width + left) * 4
            repeat(right - left) {
                target.putInt(index, color)
                index += 4
            }
        }
    }

    private fun wrap(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            var split = remaining.lastIndexOf(' ', maxChars)
            if (split <= 0) split = maxChars
            result += remaining.substring(0, split)
            remaining = remaining.substring(split).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }

    companion object {
        const val GLYPH_WIDTH = 5
        const val GLYPH_HEIGHT = 7

        private fun glyph(vararg rows: String): String = rows.joinToString("")

        /**
         * 5x7 uppercase bitmap font. Only the glyphs the console actually uses
         * are included; everything else falls back to '?'.
         */
        private val FONT: Map<Char, String> = mapOf(
            ' ' to glyph(".....", ".....", ".....", ".....", ".....", ".....", "....."),
            'A' to glyph(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
            'B' to glyph("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
            'C' to glyph(".####", "#....", "#....", "#....", "#....", "#....", ".####"),
            'D' to glyph("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
            'E' to glyph("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
            'F' to glyph("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
            'G' to glyph(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
            'H' to glyph("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
            'I' to glyph("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
            'J' to glyph("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
            'K' to glyph("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
            'L' to glyph("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
            'M' to glyph("#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"),
            'N' to glyph("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
            'O' to glyph(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
            'P' to glyph("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
            'Q' to glyph(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
            'R' to glyph("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
            'S' to glyph(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
            'T' to glyph("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
            'U' to glyph("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
            'V' to glyph("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
            'W' to glyph("#...#", "#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#"),
            'X' to glyph("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
            'Y' to glyph("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
            'Z' to glyph("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
            '0' to glyph(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
            '1' to glyph("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
            '2' to glyph(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
            '3' to glyph("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
            '4' to glyph("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
            '5' to glyph("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
            '6' to glyph(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
            '7' to glyph("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
            '8' to glyph(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
            '9' to glyph(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
            '.' to glyph(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
            ',' to glyph(".....", ".....", ".....", ".....", ".##..", ".##..", ".#..."),
            ':' to glyph(".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
            ';' to glyph(".....", ".##..", ".##..", ".....", ".##..", ".##..", ".#..."),
            '-' to glyph(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
            '_' to glyph(".....", ".....", ".....", ".....", ".....", ".....", "#####"),
            '/' to glyph("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."),
            '\\' to glyph("#....", ".#...", ".#...", "..#..", "...#.", "...#.", "....#"),
            '|' to glyph("..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
            '[' to glyph(".###.", ".#...", ".#...", ".#...", ".#...", ".#...", ".###."),
            ']' to glyph(".###.", "...#.", "...#.", "...#.", "...#.", "...#.", ".###."),
            '(' to glyph("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."),
            ')' to glyph(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."),
            '>' to glyph(".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#..."),
            '<' to glyph("...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#."),
            '=' to glyph(".....", ".....", "#####", ".....", "#####", ".....", "....."),
            '+' to glyph(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
            '*' to glyph(".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", "....."),
            '#' to glyph(".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#."),
            '%' to glyph("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
            '!' to glyph("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
            '?' to glyph(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
            '@' to glyph(".###.", "#...#", "#.###", "#.#.#", "#.###", "#....", ".###."),
            '&' to glyph(".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"),
            '^' to glyph("..#..", ".#.#.", "#...#", ".....", ".....", ".....", "....."),
            '\'' to glyph("..#..", "..#..", ".....", ".....", ".....", ".....", "....."),
            '"' to glyph(".#.#.", ".#.#.", ".....", ".....", ".....", ".....", ".....")
        )
    }
}
