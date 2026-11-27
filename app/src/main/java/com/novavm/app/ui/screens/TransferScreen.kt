package com.novavm.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.transfer.HostAppInfo
import com.novavm.app.transfer.SharedFileInfo
import com.novavm.app.ui.NovaUiState
import com.novavm.app.ui.NovaVmViewModel
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.components.SectionTitle
import com.novavm.app.ui.components.formatBytes
import com.novavm.app.ui.theme.NovaPalette

@Composable
fun TransferScreen(
    state: NovaUiState,
    vm: NovaVmViewModel
) {
    val selectedId = state.selectedVmId
    val profile = state.vms.firstOrNull { it.id == selectedId }
    var menuOpen by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    /** 0 = 「文件与 APK」, 1 = 「导入本机应用」. */
    var segment by remember { mutableStateOf(0) }
    var appQuery by remember { mutableStateOf("") }
    var includeSystemApps by remember { mutableStateOf(false) }
    val hostApps by vm.hostApps.collectAsState()

    /**
     * Host apps are only rendered on the 「导入本机应用」 segment, so the scan is
     * driven by the segment too: entering the tab (or flipping the system-app
     * filter) always issues a fresh load, and a failed/empty first scan can no
     * longer leave the tab permanently blank.
     */
    LaunchedEffect(segment, includeSystemApps) {
        if (segment == 1) vm.refreshHostApps(includeSystemApps)
    }

    // Computed in the composable body: LazyListScope itself is not composable.
    val filteredHostApps = remember(hostApps, appQuery) {
        val key = appQuery.trim()
        if (key.isEmpty()) {
            hostApps
        } else {
            hostApps.filter {
                it.appName.contains(key, ignoreCase = true) ||
                    it.packageName.contains(key, ignoreCase = true)
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && selectedId != null) vm.importSharedFile(selectedId, uri)
    }
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && selectedId != null) vm.pushApk(selectedId, uri)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val relative = exportTarget
        exportTarget = null
        if (uri != null && relative != null && selectedId != null) {
            vm.exportSharedFile(selectedId, relative, uri)
        }
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
                    text = "文件互传",
                    color = NovaPalette.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "// VirtIO-9p 共享目录 · ADB over TCP",
                    color = NovaPalette.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("目标虚拟机", color = NovaPalette.TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = profile?.displayName ?: "选择虚拟机",
                                color = NovaPalette.TextPrimary,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = NovaPalette.TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.background(NovaPalette.SurfaceElevated)
                        ) {
                            state.vms.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            item.displayName,
                                            color = NovaPalette.TextPrimary,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        vm.selectVm(item.id)
                                    }
                                )
                            }
                        }
                    }
                    if (profile != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NovaTag("ADB 127.0.0.1:${profile.adbForwardPort}", NovaPalette.Cyan)
                            NovaTag("VNC :${profile.vncPort}", NovaPalette.TextMuted)
                        }
                        Text(
                            text = "共享目录挂载点：/mnt/novavm_share  →  ${vm.lastLaunchCommand(profile.id).size} 参数已生成",
                            color = NovaPalette.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        if (profile == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "请先创建并选择一台虚拟机，然后即可向它的共享目录传输文件。",
                        color = NovaPalette.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            item {
                SegmentToggle(
                    selected = segment,
                    onSelect = { segment = it }
                )
            }
        }

        if (profile != null && segment == 1) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = appQuery,
                            onValueChange = { appQuery = it },
                            singleLine = true,
                            label = { Text("搜索本机应用", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = NovaPalette.TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { vm.refreshHostApps(includeSystemApps) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("刷新列表", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { includeSystemApps = !includeSystemApps },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = if (includeSystemApps) "含系统应用" else "仅用户应用",
                                    fontSize = 11.sp,
                                    color = if (includeSystemApps) NovaPalette.Cyan else NovaPalette.TextSecondary
                                )
                            }
                            NovaTag("${hostApps.size} 个应用", NovaPalette.Violet)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "APK 会被复制到 shared/apks/<包名>.apk，客户机可直接安装。",
                            color = NovaPalette.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            val filtered = filteredHostApps

            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (hostApps.isEmpty()) {
                            "未读取到本机应用。可点击「刷新列表」，或打开「含系统应用」再试一次。"
                        } else {
                            "没有匹配「$appQuery」的应用。"
                        },
                        color = NovaPalette.TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(filtered, key = { "${it.packageName}#${it.apkPath}" }) { hostApp ->
                    HostAppRow(
                        hostApp = hostApp,
                        onImport = { vm.importHostAppToVm(profile.id, hostApp) }
                    )
                }
            }
        }

        if (profile != null && segment == 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaPalette.Cyan,
                            contentColor = Color(0xFF04121C)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入文件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaPalette.Violet.copy(alpha = 0.9f),
                            contentColor = Color(0xFF120A24)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("推送 APK", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                // `getText()` is not observable, so the value read during
                // composition goes stale as soon as the user copies something.
                // The snapshot is only used for the label; the click re-reads
                // the live clipboard and lets the ViewModel reject empty text
                // with a Chinese toast instead of writing a blank file.
                // Clipboard access can throw on locked-down OEM builds, so even
                // the label read is guarded.
                val hostClipboard = runCatching { clipboard.getText()?.text }
                    .getOrNull()
                    .orEmpty()
                Button(
                    onClick = {
                        val latest = runCatching { clipboard.getText()?.text }
                            .getOrNull()
                            .orEmpty()
                        vm.syncHostClipboardToVm(profile.id, latest)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaPalette.Emerald.copy(alpha = 0.9f),
                        contentColor = Color(0xFF04140E)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (hostClipboard.isBlank()) {
                            "同步本机剪切板到虚拟机（当前剪切板为空）"
                        } else {
                            "同步本机剪切板到虚拟机"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                SectionTitle(
                    title = "共享文件夹",
                    subtitle = "shared/ · 导入后立刻对客户机可见"
                )
            }

            if (state.sharedFiles.isEmpty()) {
                item {
                    Text(
                        text = "共享目录为空。",
                        color = NovaPalette.TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(state.sharedFiles, key = { it.relativePath }) { file ->
                    SharedFileRow(
                        file = file,
                        onExport = {
                            if (!file.isDirectory) {
                                exportTarget = file.relativePath
                                exportPicker.launch(file.name)
                            }
                        },
                        onDelete = { vm.deleteSharedFile(profile.id, file.relativePath) }
                    )
                }
            }

            item {
                SectionTitle(
                    title = "ADB 安装命令",
                    subtitle = "install_apks.sh 已随 ${
                        state.apkFiles.size
                    } 个 APK 重新生成"
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (state.adbInstallCommands.isEmpty()) {
                            Text(
                                text = "推送 APK 后将在此生成 adb connect / install 命令。",
                                color = NovaPalette.TextMuted,
                                fontSize = 12.sp
                            )
                        } else {
                            state.adbInstallCommands.forEach { command ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = command,
                                        color = NovaPalette.Emerald,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { clipboard.setText(AnnotatedString(command)) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "复制",
                                            tint = NovaPalette.TextMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "客户端脚本：shared/install_apks.sh",
                                    color = NovaPalette.TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                NovaTag("${state.apkFiles.size} APK", NovaPalette.Cyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentToggle(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("文件与 APK", "导入本机应用")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NovaPalette.SurfaceMuted.copy(alpha = 0.5f))
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (active) NovaPalette.Cyan.copy(alpha = 0.18f) else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (active) NovaPalette.Cyan.copy(alpha = 0.55f) else Color.Transparent,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (active) NovaPalette.Cyan else NovaPalette.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun HostAppRow(hostApp: HostAppInfo, onImport: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NovaPalette.SurfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = NovaPalette.Violet,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = hostApp.appName,
                    color = NovaPalette.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = hostApp.packageName,
                    color = NovaPalette.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Text(
                    text = "版本 ${hostApp.versionName.ifBlank { "未知" }} · " +
                        formatBytes(hostApp.apkSizeBytes) +
                        if (hostApp.isSystemApp) " · 系统应用" else "",
                    color = NovaPalette.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onImport,
                enabled = hostApp.isExtractable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("导入", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SharedFileRow(
    file: SharedFileInfo,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NovaPalette.SurfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = fileIcon(file),
                    contentDescription = null,
                    tint = NovaPalette.Cyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = NovaPalette.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = if (file.isDirectory) "目录" else formatBytes(file.sizeBytes),
                    color = NovaPalette.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onExport, enabled = !file.isDirectory) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "导出",
                    tint = if (file.isDirectory) NovaPalette.TextMuted else NovaPalette.Cyan,
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = NovaPalette.Coral,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

private fun fileIcon(file: SharedFileInfo): ImageVector {
    if (file.isDirectory) return Icons.Default.FolderZip
    val name = file.name.lowercase()
    return when {
        name.endsWith(".apk") -> Icons.Default.Android
        name.endsWith(".zip") || name.endsWith(".jar") -> Icons.Default.FolderZip
        name.endsWith(".sh") || name.endsWith(".rc") -> Icons.Default.Code
        name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".json") ->
            Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

