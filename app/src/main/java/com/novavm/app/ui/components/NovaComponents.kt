package com.novavm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.VmStatus
import com.novavm.app.ui.theme.NovaPalette
import java.util.Locale

fun vmStatusColor(status: VmStatus): Color = when (status) {
    VmStatus.RUNNING -> NovaPalette.Emerald
    VmStatus.STARTING -> NovaPalette.Cyan
    VmStatus.PAUSED -> NovaPalette.Amber
    VmStatus.ERROR -> NovaPalette.Coral
    VmStatus.STOPPED -> NovaPalette.TextMuted
}

fun vmStatusLabel(status: VmStatus): String = when (status) {
    VmStatus.RUNNING -> "运行中"
    VmStatus.STARTING -> "启动中"
    VmStatus.PAUSED -> "已暂停"
    VmStatus.ERROR -> "异常"
    VmStatus.STOPPED -> "已停止"
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

@Composable
fun StatusPill(status: VmStatus, modifier: Modifier = Modifier) {
    val color = vmStatusColor(status)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = vmStatusLabel(status),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun NovaTag(
    text: String,
    color: Color = NovaPalette.TextSecondary,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NovaPalette.SurfaceMuted.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(text = label, color = NovaPalette.TextMuted, fontSize = 9.sp)
        Text(
            text = value,
            color = NovaPalette.TextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun CapabilityBadge(
    label: String,
    value: String,
    accent: Color = NovaPalette.Cyan,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(NovaPalette.SurfaceMuted.copy(alpha = 0.55f))
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label.uppercase(Locale.US),
            color = NovaPalette.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp
        )
        Text(
            text = value,
            color = accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = NovaPalette.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = NovaPalette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = NovaPalette.TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = NovaPalette.TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
