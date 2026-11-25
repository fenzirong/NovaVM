package com.novavm.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmStatus
import com.novavm.app.ui.NovaUiState
import com.novavm.app.ui.NovaVmViewModel
import com.novavm.app.ui.components.CapabilityBadge
import com.novavm.app.ui.components.MetricChip
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.components.SectionTitle
import com.novavm.app.ui.components.StatusPill
import com.novavm.app.ui.theme.NovaPalette

@Composable
fun HomeScreen(
    state: NovaUiState,
    vm: NovaVmViewModel,
    onOpenCreate: () -> Unit,
    onOpenDisplay: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenFloating: (String) -> Unit
) {
    Scaffold(
        containerColor = NovaPalette.Background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenCreate,
                containerColor = NovaPalette.Cyan,
                contentColor = Color(0xFF04121C),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("创建虚拟机", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header(onRefresh = vm::refresh) }
            item {
                CapabilityCard(
                    state = state,
                    onRefresh = vm::refresh
                )
            }
            if (state.vms.isEmpty()) {
                item {
                    EmptyStateCard(
                        onQuickCreate = {
                            vm.quickCreateDefaultVm { id -> onOpenDisplay(id) }
                        },
                        onOpenCreate = onOpenCreate
                    )
                }
            } else {
                item {
                    SectionTitle(
                        title = "虚拟机实例",
                        subtitle = "${state.vms.size} 台设备 · 本地沙箱"
                    )
                }
                items(state.vms, key = { it.id }) { profile ->
                    VmInstanceCard(
                        profile = profile,
                        state = state,
                        onToggleRun = { vm.toggleRunning(profile.id) },
                        onOpenDisplay = { onOpenDisplay(profile.id) },
                        onFloating = { onOpenFloating(profile.id) },
                        onSettings = { onOpenSettings(profile.id) },
                        onClone = { vm.cloneVm(profile.id) },
                        onDelete = { vm.deleteVm(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "NOVAVM",
                color = NovaPalette.Cyan,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Text(
                text = "// 全系统 Android 虚拟机控制台",
                color = NovaPalette.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新",
                tint = NovaPalette.TextSecondary
            )
        }
    }
}

@Composable
private fun CapabilityCard(state: NovaUiState, onRefresh: () -> Unit) {
    val caps = state.capabilities
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "宿主虚拟化能力",
                        color = NovaPalette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = caps.summary(),
                        color = NovaPalette.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重新探测",
                        tint = NovaPalette.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityBadge(
                    label = "CPU",
                    value = "${caps.cpuCores} 核",
                    modifier = Modifier.weight(1f)
                )
                CapabilityBadge(
                    label = "RAM",
                    value = if (caps.totalRamMb > 0) "${caps.totalRamMb} MB" else "未知",
                    modifier = Modifier.weight(1f)
                )
                CapabilityBadge(
                    label = "ABI",
                    value = caps.primaryAbi,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityBadge(
                    label = "KVM",
                    value = if (caps.kvmAvailable) "可用" else "TCG 软件",
                    accent = if (caps.kvmAvailable) NovaPalette.Emerald else NovaPalette.Amber,
                    modifier = Modifier.weight(1f)
                )
                CapabilityBadge(
                    label = "AVF",
                    value = when {
                        caps.avfUsable -> "可用"
                        caps.sdkInt >= 33 -> "已探测 / 未启用"
                        else -> "需 Android 13+"
                    },
                    accent = if (caps.avfUsable) NovaPalette.Emerald else NovaPalette.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                CapabilityBadge(
                    label = "QEMU",
                    value = if (caps.qemuBinaryPresent) "已就绪" else "控制台模式",
                    accent = if (caps.qemuBinaryPresent) NovaPalette.Emerald else NovaPalette.Amber,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(onQuickCreate: () -> Unit, onOpenCreate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NovaPalette.CyanSoft)
                    .border(1.dp, NovaPalette.Cyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N",
                    color = NovaPalette.Cyan,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "还没有虚拟机",
                color = NovaPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "一键创建一台 Pixel 8 Pro 设备，或自定义硬件规格",
                color = NovaPalette.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onQuickCreate) {
                    Text("一键创建默认设备", color = NovaPalette.Cyan)
                }
                TextButton(onClick = onOpenCreate) {
                    Text("自定义创建", color = NovaPalette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun VmInstanceCard(
    profile: VmProfile,
    state: NovaUiState,
    onToggleRun: () -> Unit,
    onOpenDisplay: () -> Unit,
    onFloating: () -> Unit,
    onSettings: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit
) {
    val runtime = state.runtimeOf(profile.id)
    val status = runtime?.status ?: VmStatus.STOPPED
    var confirmDelete by remember { mutableStateOf(false) }
    val hardware = profile.hardware
    val backend = runtime?.backendName ?: profile.backendType.name

    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName,
                        color = NovaPalette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NovaTag(profile.architecture.name, NovaPalette.Violet)
                        NovaTag(backend.split(" ").first(), NovaPalette.Cyan)
                        if (runtime?.pid != null && runtime.pid > 0) {
                            NovaTag("PID ${runtime.pid}", NovaPalette.TextMuted)
                        }
                    }
                }
                StatusPill(status)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricChip("CPU", "${hardware.cpuCores} vCPU")
                MetricChip("RAM", "${hardware.ramMb} MB")
                MetricChip("分辨率", "${hardware.width}x${hardware.height}")
                MetricChip("DPI", hardware.dpi.toString())
                MetricChip("FPS", hardware.targetFps.toString())
                MetricChip("GPU", hardware.gpuMode.name)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NovaTag(
                    text = if (profile.rootXposed.rootEnabled) "ROOT" else "未 Root",
                    color = if (profile.rootXposed.rootEnabled) NovaPalette.Emerald else NovaPalette.TextMuted
                )
                NovaTag(
                    text = if (profile.rootXposed.xposedEnabled) "LSPOSED" else "Xposed 关闭",
                    color = if (profile.rootXposed.xposedEnabled) NovaPalette.Cyan else NovaPalette.TextMuted
                )
                NovaTag(
                    text = if (profile.deviceSpoof.enabled) profile.deviceSpoof.presetName else "原厂指纹",
                    color = NovaPalette.Amber
                )
                if (profile.virtualLocation.enabled) {
                    NovaTag(text = "GPS", color = NovaPalette.Emerald)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionChip(
                    icon = if (status == VmStatus.RUNNING || status == VmStatus.PAUSED) {
                        Icons.Default.Stop
                    } else {
                        Icons.Default.PlayArrow
                    },
                    label = if (status == VmStatus.RUNNING || status == VmStatus.PAUSED) "停止" else "启动",
                    accent = if (status == VmStatus.RUNNING) NovaPalette.Coral else NovaPalette.Emerald,
                    onClick = onToggleRun
                )
                ActionChip(Icons.Default.OpenInFull, "全屏", NovaPalette.Cyan, onOpenDisplay)
                ActionChip(Icons.Default.PictureInPictureAlt, "悬浮窗", NovaPalette.Violet, onFloating)
                ActionChip(Icons.Default.Settings, "设置", NovaPalette.TextSecondary, onSettings)
                ActionChip(Icons.Default.ContentCopy, "克隆", NovaPalette.TextSecondary, onClone)
                ActionChip(Icons.Default.Delete, "删除", NovaPalette.Coral, onClick = { confirmDelete = true })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${profile.displayName}？") },
            text = { Text("将同时删除磁盘镜像、共享目录与快照，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = NovaPalette.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
            containerColor = NovaPalette.SurfaceElevated
        )
    }
}

@Composable
fun ActionChip(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
