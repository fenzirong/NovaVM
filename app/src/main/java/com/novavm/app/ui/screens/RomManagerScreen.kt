package com.novavm.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.ImportedRom
import com.novavm.app.data.RomCatalogItem
import com.novavm.app.data.VmArchitecture
import com.novavm.app.engine.DiskImageInfo
import com.novavm.app.ui.NovaUiState
import com.novavm.app.ui.NovaVmViewModel
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.components.SectionTitle
import com.novavm.app.ui.components.formatBytes
import com.novavm.app.ui.theme.NovaPalette

@Composable
fun RomManagerScreen(
    state: NovaUiState,
    vm: NovaVmViewModel
) {
    var pendingArch by remember { mutableStateOf<VmArchitecture?>(null) }
    var showArchDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ImportedRom?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // `pendingArch` lives in composition state, so it is dropped when the
        // activity is recreated while the SAF picker is on screen. Falling back
        // to ARM64 keeps the chosen file from being silently ignored.
        val arch = pendingArch ?: VmArchitecture.ARM64
        pendingArch = null
        if (uri != null) vm.importRom(uri, arch)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaPalette.Background),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "镜像库",
                    color = NovaPalette.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "// .qcow2 / .img / .iso 本地镜像与开源系统目录",
                    color = NovaPalette.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        item {
            Button(
                onClick = { showArchDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导入本地镜像 (SAF)", fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            SectionTitle(
                title = "本地镜像",
                subtitle = "${state.importedRoms.size} 个文件 · 存放于私有沙箱"
            )
        }

        if (state.importedRoms.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("尚未导入镜像", color = NovaPalette.TextPrimary, fontSize = 14.sp)
                        Text(
                            "支持 Android-x86、BlissOS、GSI 与 Linux 云镜像；也可以创建空磁盘虚拟机。",
                            color = NovaPalette.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else {
            items(state.importedRoms, key = { it.id }) { rom ->
                RomRow(
                    rom = rom,
                    probe = state.probeOf(rom.id),
                    onDelete = { deleteTarget = rom }
                )
            }
        }

        item {
            SectionTitle(
                title = "开源镜像目录",
                subtitle = "官方发布渠道 · 自由分发"
            )
        }

        items(state.romCatalog, key = { it.id }) { item ->
            CatalogCard(item)
        }
    }

    if (showArchDialog) {
        AlertDialog(
            onDismissRequest = { showArchDialog = false },
            title = { Text("选择镜像架构") },
            text = { Text("请选择与镜像匹配的客户机架构，创建虚拟机时将按此架构生成 QEMU 参数。") },
            confirmButton = {
                TextButton(onClick = {
                    showArchDialog = false
                    pendingArch = VmArchitecture.ARM64
                    picker.launch(arrayOf("*/*"))
                }) { Text("ARM64", color = NovaPalette.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showArchDialog = false
                    pendingArch = VmArchitecture.X86_64
                    picker.launch(arrayOf("*/*"))
                }) { Text("X86_64", color = NovaPalette.Violet) }
            },
            containerColor = NovaPalette.SurfaceElevated
        )
    }

    deleteTarget?.let { rom ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除镜像 ${rom.displayName}？") },
            text = { Text("将从私有镜像目录中删除该文件，已引用它的虚拟机需要重新选择镜像。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRom(rom.id)
                    deleteTarget = null
                }) { Text("删除", color = NovaPalette.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            containerColor = NovaPalette.SurfaceElevated
        )
    }
}

@Composable
private fun RomRow(rom: ImportedRom, probe: DiskImageInfo?, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NovaPalette.CyanSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = NovaPalette.Cyan,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = rom.displayName,
                    color = NovaPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NovaTag(
                        text = (probe?.format ?: rom.format).uppercase(),
                        color = if (probe?.isQcow2 == true) NovaPalette.Cyan else NovaPalette.TextSecondary
                    )
                    NovaTag(rom.architecture.name, NovaPalette.Violet)
                    Text(
                        text = formatBytes(if ((probe?.actualSizeBytes ?: 0L) > 0) probe!!.actualSizeBytes else rom.sizeBytes),
                        color = NovaPalette.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (probe != null && probe.isQcow2) {
                    Text(
                        text = "qcow2 v${probe.version} · 虚拟容量 ${formatBytes(probe.virtualSizeBytes)}",
                        color = NovaPalette.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除镜像",
                    tint = NovaPalette.Coral,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CatalogCard(item: RomCatalogItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = NovaPalette.Cyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = NovaPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                NovaTag(item.architecture.name, NovaPalette.Violet)
            }
            Text(
                text = "${item.version} · ${item.category} · 约 ${item.approxSizeMb} MB",
                color = NovaPalette.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = item.description,
                color = NovaPalette.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NovaTag(item.license, NovaPalette.TextMuted)
                if (item.requiresKvm) {
                    NovaTag("需 KVM", NovaPalette.Amber)
                }
            }
        }
    }
}
