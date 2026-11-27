package com.novavm.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.ui.theme.NovaPalette

/**
 * Compact translucent key bar for [VmDisplayActivity].
 *
 * The guest framebuffer swallows every touch, so the phone's own soft keyboard
 * has nowhere to type into. This floating bar gives the user two escape hatches:
 *
 * - row 1, a horizontally scrollable strip of special keys (ESC / TAB / CTRL /
 *   ALT / arrows / DEL / ENTER / F1–F12 / Ctrl+Alt+Del) that fire straight
 *   through [HardwareInputMapper] into the RFB key event channel;
 * - row 2, a one-line text field so the user can compose text with the normal
 *   Android IME and inject the whole string with 「发送」.
 *
 * Every keysym is delivered through [onSendKey]; multi-character payloads go
 * through [onSendText] so the caller decides how to frame them.
 *
 * @param onSendKey emits a single keysym (the caller sends press + release).
 * @param onSendText emits a whole string to be converted with
 *   [HardwareInputMapper.textToKeysyms].
 * @param onSendCtrlAltDel emits the guest reboot / secure-attention chord.
 * @param onClose collapses the bar again.
 */
@Composable
fun VirtualKeyboardBar(
    onSendKey: (Int) -> Unit,
    onSendText: (String) -> Unit,
    onSendCtrlAltDel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val sendDraft: () -> Unit = {
        val payload = draft
        if (payload.isNotEmpty()) {
            onSendText(payload)
            draft = ""
        }
    }

    Column(
        modifier = modifier
            .imePadding()
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NovaPalette.Background.copy(alpha = 0.93f))
            .border(
                width = 1.dp,
                color = NovaPalette.Border.copy(alpha = 0.9f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // ---------------------------------------------------------- row 1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QUICK_KEYS.forEach { (label, keysym) ->
                KeyChip(
                    label = label,
                    accent = NovaPalette.Cyan,
                    onClick = { onSendKey(keysym) }
                )
            }
            KeyChip(
                label = "Ctrl+Alt+Del",
                accent = NovaPalette.Coral,
                onClick = onSendCtrlAltDel
            )
        }

        Spacer(Modifier.height(6.dp))

        // ---------------------------------------------------------- row 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NovaPalette.SurfaceMuted)
                    .border(
                        width = 1.dp,
                        color = NovaPalette.Border,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 9.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = NovaPalette.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NovaPalette.Cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "输入字符发送到虚拟机…",
                                    color = NovaPalette.TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
            KeyChip(
                label = "发送",
                accent = NovaPalette.Emerald,
                onClick = sendDraft
            )
            KeyChip(
                label = "收起",
                accent = NovaPalette.TextSecondary,
                onClick = onClose
            )
        }
    }
}

/** Row-1 special keys, ordered for muscle memory on a small screen. */
private val QUICK_KEYS: List<Pair<String, Int>> = buildList {
    add("ESC" to HardwareInputMapper.KEYSYM_ESC)
    add("TAB" to HardwareInputMapper.KEYSYM_TAB)
    add("CTRL" to HardwareInputMapper.KEYSYM_CTRL_L)
    add("ALT" to HardwareInputMapper.KEYSYM_ALT_L)
    add("←" to HardwareInputMapper.KEYSYM_LEFT)
    add("↑" to HardwareInputMapper.KEYSYM_UP)
    add("↓" to HardwareInputMapper.KEYSYM_DOWN)
    add("→" to HardwareInputMapper.KEYSYM_RIGHT)
    add("DEL" to HardwareInputMapper.KEYSYM_DELETE)
    add("ENTER" to HardwareInputMapper.KEYSYM_RETURN)
    for (index in 0 until 12) {
        add("F${index + 1}" to (HardwareInputMapper.KEYSYM_F1 + index))
    }
}

@Composable
private fun KeyChip(
    label: String,
    accent: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(NovaPalette.SurfaceElevated)
            .border(width = 1.dp, color = NovaPalette.Border.copy(alpha = 0.8f), shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
