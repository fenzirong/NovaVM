package com.novavm.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.novavm.app.data.DownloadStatus
import com.novavm.app.data.ImportedRom
import com.novavm.app.data.RomCatalogItem
import com.novavm.app.data.RomDownloadState
import com.novavm.app.data.RomDownloader
import com.novavm.app.data.VmArchitecture
import com.novavm.app.engine.DiskImageInfo
import com.novavm.app.engine.RuntimeSetupManager
import com.novavm.app.engine.RuntimeStatus
import com.novavm.app.engine.VmTelemetryMonitor
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
    // 「导入 QEMU 二进制」先选目标文件名，再拉起 SAF；两个状态必须分开保存，
    // 否则 Activity 重建后文件名会跟着 URI 一起丢失。
    var pendingBinaryName by remember { mutableStateOf<String?>(null) }
    var showBinaryDialog by remember { mutableStateOf(false) }

    val runtimeStatus by vm.runtimeStatus.collectAsState()
    val downloads by vm.romDownloads.collectAsState()

    LaunchedEffect(Unit) { vm.refreshRuntimeStatus() }

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

    val runtimeZipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importRuntimeZip(uri)
    }

    val runtimeBinaryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingBinaryName
        pendingBinaryName = null
        if (uri != null && target != null) vm.importQemuBinary(uri, target)
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
            RuntimeEnvironmentCard(
                status = runtimeStatus,
                onImportZip = {
                    runtimeZipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onImportBinary = { showBinaryDialog = true }
            )
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
                            "支持 Android-x86、BlissOS、GSI 与 Linux 云镜像；也可以创建空磁盘虚拟机，" +
                                "或直接从下方开源目录下载。",
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
                subtitle = "官方发布渠道 · 自由分发 · 支持断点续传"
            )
        }

        items(state.romCatalog, key = { it.id }) { item ->
            CatalogCard(
                item = item,
                download = downloads[item.id],
                onStartDownload = { vm.startRomDownload(item) },
                onCancelDownload = { vm.cancelRomDownload(item.id) }
            )
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

    if (showBinaryDialog) {
        AlertDialog(
            onDismissRequest = { showBinaryDialog = false },
            title = { Text("选择要导入的运行时文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "QEMU 程序必须是有效的 ELF 可执行文件；导入后写入 runtime/bin，" +
                            "并自动补齐可执行权限。",
                        color = NovaPalette.TextMuted,
                        fontSize = 11.sp
                    )
                    RuntimeSetupManager.ALLOWED_NAMES.sorted().forEach { name ->
                        TextButton(onClick = {
                            showBinaryDialog = false
                            pendingBinaryName = name
                            runtimeBinaryPicker.launch(arrayOf("*/*"))
                        }) {
                            Text(
                                text = name,
                                color = if (name in RuntimeSetupManager.EXECUTABLE_NAMES) {
                                    NovaPalette.Cyan
                                } else {
                                    NovaPalette.Violet
                                },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBinaryDialog = false }) { Text("取消") }
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

// ------------------------------------------------------------ 运行时环境卡片

@Composable
private fun RuntimeEnvironmentCard(
    status: RuntimeStatus,
    onImportZip: () -> Unit,
    onImportBinary: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = NovaPalette.Cyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "QEMU 运行时环境",
                    color = NovaPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                NovaTag(
                    text = if (status.isReady) "已就绪" else "待配置",
                    color = if (status.isReady) NovaPalette.Emerald else NovaPalette.Amber
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RuntimePill("qemu-system-aarch64", status.qemuAarch64Path)
                RuntimePill("qemu-system-x86_64", status.qemuX86_64Path)
                RuntimePill("qemu-img", status.qemuImgPath)
                RuntimePill("UEFI 固件", status.uefiArm64Path)
            }

            Text(
                text = "运行时总占用 ${VmTelemetryMonitor.formatBytes(status.totalRuntimeBytes)} · " +
                    "存放于私有目录 runtime/bin 与 runtime/firmware",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = onImportZip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入运行时包 (.zip)", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onImportBinary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Upload,
                    contentDescription = null,
                    tint = NovaPalette.Violet,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("导入 QEMU 二进制", color = NovaPalette.Violet, fontSize = 12.sp)
            }

            Text(
                text = "提示：把 qemu-system-aarch64 / qemu-system-x86_64 / qemu-img 与 " +
                    "${RuntimeSetupManager.UEFI_AARCH64} 放进 zip 的任意子目录即可，其余内容会被忽略。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RuntimePill(name: String, path: String?) {
    val ready = !path.isNullOrBlank()
    val accent = if (ready) NovaPalette.Emerald else NovaPalette.TextMuted
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(NovaPalette.SurfaceMuted.copy(alpha = 0.45f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = name,
            color = if (ready) NovaPalette.Cyan else NovaPalette.TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Text(
            text = if (ready) "已就绪" else "未导入",
            color = accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ------------------------------------------------------------------- 本地镜像

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
                        text = formatBytes(
                            if ((probe?.actualSizeBytes ?: 0L) > 0) {
                                probe!!.actualSizeBytes
                            } else {
                                rom.sizeBytes
                            }
                        ),
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

// ------------------------------------------------------------------- 目录卡片

@Composable
private fun CatalogCard(
    item: RomCatalogItem,
    download: RomDownloadState?,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
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

            DownloadControl(
                download = download,
                onStartDownload = onStartDownload,
                onCancelDownload = onCancelDownload
            )
        }
    }
}

/** 「下载」/「取消」按钮 + 实时进度条 + 速度与已下载/总字节。 */
@Composable
private fun DownloadControl(
    download: RomDownloadState?,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val status = download?.status
    val totalLabel = if (download != null && download.totalBytes > 0L) {
        RomDownloader.formatBytes(download.totalBytes)
    } else {
        "未知"
    }

    Column(
        modifier = Modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
            download == null || status == DownloadStatus.IDLE -> DownloadButton(onStartDownload)

            status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED -> {
                Text(
                    text = "${download.percentLabel} · ${download.speedLabel} · " +
                        "${RomDownloader.formatBytes(download.downloadedBytes)} / $totalLabel",
                    color = NovaPalette.Cyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                LinearProgressIndicator(
                    progress = { download.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = NovaPalette.Cyan,
                    trackColor = NovaPalette.SurfaceMuted
                )
                Text(
                    text = "保存为 ${download.fileName} · 当前速度 " +
                        RomDownloader.formatSpeed(download.speedBytesPerSec),
                    color = NovaPalette.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedButton(
                    onClick = onCancelDownload,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消", color = NovaPalette.Coral, fontSize = 12.sp)
                }
            }

            status == DownloadStatus.COMPLETED -> {
                Text(
                    text = "已下载：${download.fileName} · " +
                        RomDownloader.formatBytes(download.downloadedBytes),
                    color = NovaPalette.Emerald,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = download.savedPath ?: "已自动登记到本地镜像库",
                    color = NovaPalette.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
            }

            else -> {
                Text(
                    text = download.errorMessage ?: status?.label ?: "下载失败",
                    color = NovaPalette.Coral,
                    fontSize = 11.sp
                )
                DownloadButton(onStartDownload)
            }
        }
    }
}

@Composable
private fun DownloadButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = NovaPalette.Cyan,
            contentColor = Color(0xFF04121C)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("下载", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
