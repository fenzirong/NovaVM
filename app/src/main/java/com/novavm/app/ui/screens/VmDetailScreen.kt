package com.novavm.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.BackgroundKeepAliveConfig
import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.DeviceSpoofGenerator
import com.novavm.app.data.GeoPoint
import com.novavm.app.data.GmsConfig
import com.novavm.app.data.GpuMode
import com.novavm.app.data.LocationPresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.RouteLoopMode
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VirtualSensorConfig
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmStatus
import com.novavm.app.location.GeoMath
import com.novavm.app.location.MapTileSource
import com.novavm.app.location.RoutePlaybackState
import com.novavm.app.location.RouteSimulator
import com.novavm.app.overlay.KeepAliveService
import com.novavm.app.ui.NovaUiState
import com.novavm.app.ui.NovaVmViewModel
import com.novavm.app.ui.components.InteractiveGeoMap
import com.novavm.app.ui.components.MapInteractionMode
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.components.StatusPill
import com.novavm.app.ui.theme.NovaPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val detailTabs = listOf(
    "硬件配置",
    "设备伪装",
    "虚拟定位",
    "Root 与 Xposed",
    "VMOS 增强",
    "快照与日志"
)

@Composable
fun VmDetailScreen(
    state: NovaUiState,
    profileId: String,
    vm: NovaVmViewModel,
    onBack: () -> Unit,
    onOpenDisplay: (String) -> Unit,
    onOpenFloating: (String) -> Unit
) {
    val profile = state.vms.firstOrNull { it.id == profileId }
    if (profile == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NovaPalette.Background)
                .padding(24.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = NovaPalette.TextPrimary
                )
            }
            Text("虚拟机不存在或已被删除", color = NovaPalette.TextSecondary)
        }
        return
    }

    var tab by remember(profile.id) { mutableStateOf(0) }
    val runtime = state.runtimeOf(profile.id)
    val status = runtime?.status ?: VmStatus.STOPPED

    LaunchedEffect(profile.id) {
        vm.selectVm(profile.id)
        vm.refreshConsole(profile.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaPalette.Background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = NovaPalette.TextPrimary
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    color = NovaPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    NovaTag(profile.architecture.name, NovaPalette.Violet)
                    NovaTag(runtime?.backendName?.split(" ")?.first() ?: profile.backendType.name, NovaPalette.Cyan)
                    NovaTag("VNC :${profile.vncPort}", NovaPalette.TextMuted)
                }
            }
            StatusPill(status)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(
                icon = if (status == VmStatus.RUNNING || status == VmStatus.PAUSED) {
                    Icons.Default.Stop
                } else {
                    Icons.Default.PlayArrow
                },
                label = if (status == VmStatus.RUNNING || status == VmStatus.PAUSED) "停止" else "启动",
                accent = if (status == VmStatus.RUNNING) NovaPalette.Coral else NovaPalette.Emerald,
                onClick = { vm.toggleRunning(profile.id) }
            )
            ActionChip(Icons.Default.OpenInFull, "全屏显示", NovaPalette.Cyan) {
                onOpenDisplay(profile.id)
            }
            ActionChip(Icons.Default.PictureInPictureAlt, "悬浮窗", NovaPalette.Violet) {
                onOpenFloating(profile.id)
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = NovaPalette.Background,
            contentColor = NovaPalette.Cyan,
            edgePadding = 8.dp
        ) {
            detailTabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> HardwareTab(profile, vm)
            1 -> SpoofTab(profile, vm)
            2 -> VirtualLocationTab(profile, state, vm)
            3 -> RootTab(profile, state, vm)
            4 -> VmosTab(profile, vm)
            else -> SnapshotLogTab(profile, state, vm)
        }
    }
}

// --------------------------------------------------------------------- tabs

@Composable
private fun HardwareTab(profile: VmProfile, vm: NovaVmViewModel) {
    var cpu by remember(profile.id) { mutableStateOf(profile.hardware.cpuCores) }
    var ram by remember(profile.id) { mutableStateOf(profile.hardware.ramMb) }
    var disk by remember(profile.id) { mutableStateOf(profile.hardware.diskSizeGb) }
    var dpi by remember(profile.id) { mutableStateOf(profile.hardware.dpi) }
    var fps by remember(profile.id) { mutableStateOf(profile.hardware.targetFps) }
    var width by remember(profile.id) { mutableStateOf(profile.hardware.width) }
    var height by remember(profile.id) { mutableStateOf(profile.hardware.height) }
    var gpu by remember(profile.id) { mutableStateOf(profile.hardware.gpuMode) }

    val presets = listOf(720 to 1280, 1080 to 1920, 1080 to 2400, 1280 to 720)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("硬件参数") {
            DetailSlider("CPU 核心", "$cpu 核", cpu.toFloat(), 1f..8f, 6) {
                cpu = it.roundToInt().coerceIn(1, 8)
            }
            DetailSlider("内存", "$ram MB", ram.toFloat(), 1024f..8192f, 0) {
                ram = (it / 256f).roundToInt() * 256
            }
            DetailSlider("磁盘", "$disk GB", disk.toFloat(), 4f..64f, 59) {
                disk = it.roundToInt().coerceIn(4, 64)
            }
            DetailSlider("DPI", dpi.toString(), dpi.toFloat(), 160f..480f, 15) {
                dpi = ((it / 20f).roundToInt() * 20).coerceIn(160, 480)
            }
            Text("分辨率", color = NovaPalette.TextSecondary, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = width == preset.first && height == preset.second,
                        onClick = {
                            width = preset.first
                            height = preset.second
                        },
                        label = { Text("${preset.first}x${preset.second}", fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("刷新率", color = NovaPalette.TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 120).forEach { rate ->
                    FilterChip(
                        selected = fps == rate,
                        onClick = { fps = rate },
                        label = { Text("$rate", fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("GPU 模式", color = NovaPalette.TextSecondary, fontSize = 12.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GpuMode.entries.forEach { mode ->
                    FilterChip(
                        selected = gpu == mode,
                        onClick = { gpu = mode },
                        label = { Text(mode.name, fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                vm.saveHardware(
                    profile.id,
                    VmHardwareConfig(
                        cpuCores = cpu,
                        ramMb = ram,
                        diskSizeGb = disk,
                        width = width,
                        height = height,
                        dpi = dpi,
                        targetFps = fps,
                        gpuMode = gpu
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = NovaPalette.Cyan,
                contentColor = Color(0xFF04121C)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存硬件配置")
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SpoofTab(profile: VmProfile, vm: NovaVmViewModel) {
    var enabled by remember(profile.id) { mutableStateOf(profile.deviceSpoof.enabled) }
    var presetName by remember(profile.id) { mutableStateOf(profile.deviceSpoof.presetName) }
    var brand by remember(profile.id) { mutableStateOf(profile.deviceSpoof.brand) }
    var manufacturer by remember(profile.id) { mutableStateOf(profile.deviceSpoof.manufacturer) }
    var model by remember(profile.id) { mutableStateOf(profile.deviceSpoof.model) }
    var device by remember(profile.id) { mutableStateOf(profile.deviceSpoof.device) }
    var board by remember(profile.id) { mutableStateOf(profile.deviceSpoof.board) }
    var imei by remember(profile.id) { mutableStateOf(profile.deviceSpoof.imei) }
    var androidId by remember(profile.id) { mutableStateOf(profile.deviceSpoof.androidId) }
    var wifiMac by remember(profile.id) { mutableStateOf(profile.deviceSpoof.wifiMac) }
    var fingerprint by remember(profile.id) { mutableStateOf(profile.deviceSpoof.buildFingerprint) }

    fun applyPreset(preset: DeviceSpoofConfig) {
        val generated = DeviceSpoofGenerator.forPreset(preset)
        presetName = generated.presetName
        brand = generated.brand
        manufacturer = generated.manufacturer
        model = generated.model
        device = generated.device
        board = generated.board
        imei = generated.imei
        androidId = generated.androidId
        wifiMac = generated.wifiMac
        fingerprint = generated.buildFingerprint
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("伪装开关与预设") {
            DetailToggle("启用设备伪装", enabled) { enabled = it }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DevicePresets.all.forEach { preset ->
                    FilterChip(
                        selected = presetName == preset.presetName,
                        onClick = { applyPreset(preset) },
                        label = { Text(preset.presetName, fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val random = DeviceSpoofGenerator.randomIdentity()
                    enabled = true
                    applyPreset(random)
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = NovaPalette.Amber,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("随机设备指纹", color = NovaPalette.Amber, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        DetailFormCard("身份字段") {
            DetailTextField("品牌 (brand)", brand) { brand = it }
            DetailTextField("厂商 (manufacturer)", manufacturer) { manufacturer = it }
            DetailTextField("型号 (model)", model) { model = it }
            DetailTextField("设备代号 (device)", device) { device = it }
            DetailTextField("主板 (board)", board) { board = it }
            DetailTextField("IMEI", imei) { imei = it }
            DetailTextField("Android ID", androidId) { androidId = it }
            DetailTextField("WiFi MAC", wifiMac) { wifiMac = it }
            DetailTextField("构建指纹 (Build Fingerprint)", fingerprint) { fingerprint = it }
            Text(
                text = "字段将写入 novavm_boot.sh 的 resetprop 指令并同步到 QEMU SMBIOS。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                vm.saveDeviceSpoof(
                    profile.id,
                    DeviceSpoofConfig(
                        enabled = enabled,
                        presetName = presetName,
                        brand = brand,
                        manufacturer = manufacturer,
                        model = model,
                        device = device,
                        board = board,
                        imei = imei,
                        androidId = androidId,
                        wifiMac = wifiMac,
                        buildFingerprint = fingerprint
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = NovaPalette.Cyan,
                contentColor = Color(0xFF04121C)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存设备伪装")
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("一键新机") {
            Text(
                text = "同时重置设备指纹（IMEI / Android ID / Wi-Fi MAC）与 SIM 及网络指纹" +
                    "（IMSI / ICCID / GAID / 序列号 / 运营商 / BSSID / 蓝牙 MAC），" +
                    "并重新生成客户机引导脚本。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
            Button(
                onClick = { vm.oneKeyNewDevice(profile.id) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Amber,
                    contentColor = Color(0xFF1A1206)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("一键新机（重置全部设备/SIM/网络指纹）", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("扩展设备指纹") {
            IdentityRow("IMSI", profile.extendedIdentity.imsi)
            IdentityRow("ICCID", profile.extendedIdentity.iccid)
            IdentityRow("GAID", profile.extendedIdentity.gaid)
            IdentityRow("序列号", profile.extendedIdentity.serialNumber)
            IdentityRow(
                label = "运营商",
                value = listOf(profile.extendedIdentity.carrierName, profile.extendedIdentity.mccMnc)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            )
            IdentityRow("手机号", profile.extendedIdentity.phoneNumber)
            IdentityRow("Wi-Fi SSID", profile.extendedIdentity.wifiSsid)
            IdentityRow("Wi-Fi BSSID", profile.extendedIdentity.wifiBssid)
            IdentityRow("蓝牙 MAC", profile.extendedIdentity.bluetoothMac)
            Text(
                text = "以上字段写入 shared/novavm_vmos_ext.sh，随每次启动同步到客户机。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun IdentityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = NovaPalette.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value.ifBlank { "—" },
            color = if (value.isBlank()) NovaPalette.TextMuted else NovaPalette.Cyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RootTab(profile: VmProfile, state: NovaUiState, vm: NovaVmViewModel) {
    var rootEnabled by remember(profile.id) { mutableStateOf(profile.rootXposed.rootEnabled) }
    var xposedEnabled by remember(profile.id) { mutableStateOf(profile.rootXposed.xposedEnabled) }
    var selinux by remember(profile.id) { mutableStateOf(profile.rootXposed.selinuxPermissive) }
    var magiskHide by remember(profile.id) { mutableStateOf(profile.rootXposed.magiskHideEnabled) }

    var gmsEnabled by remember(profile.id) { mutableStateOf(profile.gms.enabled) }
    var microGMode by remember(profile.id) { mutableStateOf(profile.gms.microGMode) }
    var playStore by remember(profile.id) { mutableStateOf(profile.gms.playStoreEnabled) }
    var denyList by remember(profile.id) { mutableStateOf(profile.gms.zygiskDenyList) }
    var newDenyPackage by remember(profile.id) { mutableStateOf("") }

    val modulePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.stageModule(profile.id, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("权限开关") {
            DetailToggle("Root (Magisk / su)", rootEnabled) { rootEnabled = it }
            DetailToggle("Xposed / LSPosed", xposedEnabled) { xposedEnabled = it }
            DetailToggle("SELinux 宽容模式 (Permissive)", selinux) { selinux = it }
            DetailToggle("MagiskHide", magiskHide) { magiskHide = it }
            Button(
                onClick = {
                    vm.saveRootXposed(
                        profile.id,
                        RootXposedConfig(
                            rootEnabled = rootEnabled,
                            xposedEnabled = xposedEnabled,
                            selinuxPermissive = selinux,
                            magiskHideEnabled = magiskHide,
                            stagedModules = profile.rootXposed.stagedModules
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存 Root / Xposed 配置")
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("Google 服务框架（GMS / MicroG）") {
            DetailToggle("GMS 总开关", gmsEnabled) { gmsEnabled = it }
            DetailToggle("MicroG 轻量模式", microGMode) { microGMode = it }
            DetailToggle("Play 商店", playStore) { playStore = it }
            Text(
                text = "Zygisk DenyList（${denyList.size} 个包名）",
                color = NovaPalette.TextSecondary,
                fontSize = 11.sp
            )
            if (denyList.isEmpty()) {
                Text(
                    text = "DenyList 为空：客户机不隐藏任何包名。",
                    color = NovaPalette.TextMuted,
                    fontSize = 10.sp
                )
            } else {
                denyList.forEach { packageName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = packageName,
                            color = NovaPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { denyList = denyList.filterNot { it == packageName } },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "移除 $packageName",
                                tint = NovaPalette.Coral,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newDenyPackage,
                    onValueChange = { newDenyPackage = it.trim() },
                    label = { Text("添加包名", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val candidate = newDenyPackage.trim()
                        if (candidate.isNotBlank() && denyList.none { it == candidate }) {
                            denyList = denyList + candidate
                            newDenyPackage = ""
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("添加", fontSize = 11.sp) }
            }
            Button(
                onClick = {
                    vm.updateGms(
                        profile.id,
                        GmsConfig(
                            enabled = gmsEnabled,
                            microGMode = microGMode,
                            playStoreEnabled = playStore,
                            zygiskDenyList = denyList
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存 GMS 配置", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("已暂存模块 (${state.modules.size})") {
            if (state.modules.isEmpty()) {
                Text(
                    text = "尚未导入 Magisk / LSPosed 模块。",
                    color = NovaPalette.TextMuted,
                    fontSize = 12.sp
                )
            } else {
                state.modules.forEach { module ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = module,
                            color = NovaPalette.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.removeModule(profile.id, module) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "移除模块",
                                tint = NovaPalette.Coral,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { modulePicker.launch(arrayOf("application/zip", "application/vnd.android.package-archive", "*/*")) },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入模块 (.zip / .apk)", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("novavm_boot.sh 预览") {
            Text(
                text = state.bootScriptPreview.ifBlank { "# 保存 Root 配置后生成" },
                color = NovaPalette.Emerald,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NovaPalette.Background)
                    .padding(10.dp)
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VmosTab(profile: VmProfile, vm: NovaVmViewModel) {
    val context = LocalContext.current

    var keepAlive by remember(profile.id) { mutableStateOf(profile.keepAlive) }
    var sensors by remember(profile.id) { mutableStateOf(profile.sensors) }
    var pinInput by remember(profile.id) { mutableStateOf("") }
    var pinError by remember(profile.id) { mutableStateOf<String?>(null) }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) vm.exportVmBackup(profile.id, uri)
    }

    fun applyPin(action: (String) -> Unit) {
        val pin = pinInput.trim()
        val validLength = pin.length in
            NovaVmViewModel.PRIVACY_PIN_MIN_LENGTH..NovaVmViewModel.PRIVACY_PIN_MAX_LENGTH
        if (!validLength || pin.any { !it.isDigit() }) {
            pinError = "请输入 4-6 位数字密码"
            return
        }
        pinError = null
        action(pin)
        pinInput = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("息屏挂机与后台保活") {
            DetailToggle("息屏挂机（屏幕关闭后继续运行）", keepAlive.screenOffKeepAlive) { checked ->
                keepAlive = keepAlive.copy(screenOffKeepAlive = checked)
                vm.updateKeepAlive(profile.id, keepAlive.copy(screenOffKeepAlive = checked))
            }
            OutlinedButton(
                onClick = {
                    val intent = KeepAliveService.requestIgnoreBatteryOptimizationsIntent(context)
                    if (intent == null) {
                        Toast.makeText(context, "已在电池优化白名单内", Toast.LENGTH_SHORT).show()
                    } else {
                        context.startActivity(intent)
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text("申请忽略电池优化", fontSize = 11.sp) }
            DetailToggle("剪切板同步（shared/clipboard.txt）", keepAlive.clipboardSyncEnabled) {
                keepAlive = keepAlive.copy(clipboardSyncEnabled = it)
            }
            DetailToggle("默认横屏锁定", keepAlive.landscapeLocked) {
                keepAlive = keepAlive.copy(landscapeLocked = it)
            }
            Button(
                onClick = { vm.updateKeepAlive(profile.id, keepAlive) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存挂机设置", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("虚拟传感器与电量模拟") {
            DetailToggle("自定义电量", sensors.batteryOverrideEnabled) {
                sensors = sensors.copy(batteryOverrideEnabled = it)
            }
            DetailSlider(
                label = "电量",
                valueText = "${sensors.batteryLevel}%",
                value = sensors.batteryLevel.toFloat(),
                range = 5f..100f,
                steps = 18
            ) { sensors = sensors.copy(batteryLevel = it.roundToInt().coerceIn(5, 100)) }
            DetailToggle("充电状态", sensors.batteryCharging) {
                sensors = sensors.copy(batteryCharging = it)
            }
            DetailSlider(
                label = "电池温度",
                valueText = String.format(Locale.US, "%.1f ℃", sensors.batteryTempC),
                value = sensors.batteryTempC.coerceIn(20f, 45f),
                range = 20f..45f,
                steps = 24
            ) { sensors = sensors.copy(batteryTempC = (it * 10f).roundToInt() / 10f) }
            DetailToggle("虚拟步数", sensors.stepCounterEnabled) {
                sensors = sensors.copy(stepCounterEnabled = it)
            }
            DetailSlider(
                label = "每日步数",
                valueText = "${sensors.dailySteps} 步",
                value = sensors.dailySteps.toFloat(),
                range = 0f..30000f,
                steps = 29
            ) { sensors = sensors.copy(dailySteps = (it / 1000f).roundToInt() * 1000) }
            DetailToggle("虚拟摄像头", sensors.virtualCameraEnabled) {
                sensors = sensors.copy(virtualCameraEnabled = it)
            }
            DetailToggle("虚拟麦克风", sensors.virtualMicEnabled) {
                sensors = sensors.copy(virtualMicEnabled = it)
            }
            Button(
                onClick = { vm.updateSensors(profile.id, sensors) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存传感器配置", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("虚拟机隐私锁") {
            if (profile.privacyLock.enabled) {
                Text(
                    text = "隐私锁已开启：打开 / 启动 / 编辑该虚拟机前需要输入 4-6 位数字密码。",
                    color = NovaPalette.Emerald,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "尚未启用隐私锁。设置密码后，打开 / 启动 / 编辑该虚拟机需要先解锁。",
                    color = NovaPalette.TextMuted,
                    fontSize = 11.sp
                )
            }
            OutlinedTextField(
                value = pinInput,
                onValueChange = { input ->
                    pinInput = input.filter { it.isDigit() }.take(6)
                    pinError = null
                },
                label = { Text(if (profile.privacyLock.enabled) "新密码" else "隐私密码") },
                singleLine = true,
                isError = pinError != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            if (pinError != null) {
                Text(text = pinError.orEmpty(), color = NovaPalette.Coral, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { applyPin { pin -> vm.setPrivacyPin(profile.id, pin) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaPalette.Cyan,
                        contentColor = Color(0xFF04121C)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (profile.privacyLock.enabled) "修改密码" else "设置隐私密码",
                        fontSize = 12.sp
                    )
                }
                if (profile.privacyLock.enabled) {
                    OutlinedButton(
                        onClick = { vm.disablePrivacyLock(profile.id) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("关闭隐私锁", color = NovaPalette.Coral, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("整机备份导出") {
            Text(
                text = "导出 profile.json + shared/ + modules/ 为 .nvmbak 压缩包；" +
                    "磁盘镜像体积过大，不包含在备份内。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
            Button(
                onClick = {
                    val suggested = profile.displayName.replace('/', '_').replace('\\', '_')
                    backupPicker.launch("$suggested.nvmbak")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Violet.copy(alpha = 0.92f),
                    contentColor = Color(0xFF120A24)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出 .nvmbak 备份包", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SnapshotLogTab(profile: VmProfile, state: NovaUiState, vm: NovaVmViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var snapshotName by remember { mutableStateOf("Snapshot") }
    val log = state.consoleLog

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("快照 (${profile.snapshots.size})") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCreate = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("创建快照", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { vm.refreshConsole(profile.id) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新日志", fontSize = 12.sp)
                }
            }
            if (profile.snapshots.isEmpty()) {
                Text(
                    text = "暂无快照。建议在启动前创建基线快照。",
                    color = NovaPalette.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                profile.snapshots.forEach { snapshot ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = snapshot.name,
                                color = NovaPalette.TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "ID ${snapshot.id.take(8)}",
                                color = NovaPalette.TextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(onClick = { vm.restoreSnapshot(profile.id, snapshot.id) }) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = "恢复",
                                tint = NovaPalette.Cyan,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(onClick = { vm.deleteSnapshot(profile.id, snapshot.id) }) {
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
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("控制台日志 (${log.size})") {
            if (log.isEmpty()) {
                Text(
                    text = "启动虚拟机后将在此显示实时控制台输出。",
                    color = NovaPalette.TextMuted,
                    fontSize = 12.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NovaPalette.Background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    log.takeLast(40).forEach { line ->
                        Text(
                            text = line,
                            color = when {
                                line.startsWith("[FAIL]") -> NovaPalette.Coral
                                line.startsWith("[ OK ]") -> NovaPalette.Emerald
                                else -> NovaPalette.TextSecondary
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("创建快照") },
            text = {
                OutlinedTextField(
                    value = snapshotName,
                    onValueChange = { snapshotName = it },
                    label = { Text("快照名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    vm.createSnapshot(profile.id, snapshotName)
                }) { Text("创建", color = NovaPalette.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
            containerColor = NovaPalette.SurfaceElevated
        )
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun VirtualLocationTab(profile: VmProfile, state: NovaUiState, vm: NovaVmViewModel) {
    val gps = profile.virtualLocation
    var enabled by remember(profile.id, gps) { mutableStateOf(gps.enabled) }
    var presetName by remember(profile.id, gps) { mutableStateOf(gps.presetName) }
    var latText by remember(profile.id, gps) { mutableStateOf(formatCoordinate(gps.latitude)) }
    var lonText by remember(profile.id, gps) { mutableStateOf(formatCoordinate(gps.longitude)) }
    var altText by remember(profile.id, gps) {
        mutableStateOf(String.format(Locale.US, "%.1f", gps.altitude))
    }
    var accuracyText by remember(profile.id, gps) {
        mutableStateOf(String.format(Locale.US, "%.1f", gps.accuracyMeters))
    }
    var speedText by remember(profile.id, gps) {
        mutableStateOf(String.format(Locale.US, "%.2f", gps.speedMps))
    }
    var mode by remember(profile.id, gps) { mutableStateOf(gps.mode) }
    var jitter by remember(profile.id, gps) { mutableStateOf(gps.jitterEnabled) }
    var stepMeters by remember(profile.id) { mutableStateOf(20f) }
    var nmeaOpen by remember(profile.id) { mutableStateOf(false) }
    var copied by remember(profile.id) { mutableStateOf(false) }
    var mapMode by remember(profile.id) { mutableStateOf(MapInteractionMode.PICK) }
    var tileSource by remember(profile.id) { mutableStateOf(MapTileSource.AMAP) }

    val playbackStates by vm.routePlaybackStates.collectAsState()
    val playback = playbackStates[profile.id]

    fun draft(): VirtualLocationConfig = VirtualLocationConfig(
        enabled = enabled,
        presetName = presetName,
        latitude = latText.toDoubleOrNull() ?: gps.latitude,
        longitude = lonText.toDoubleOrNull() ?: gps.longitude,
        altitude = altText.toDoubleOrNull() ?: gps.altitude,
        accuracyMeters = accuracyText.toFloatOrNull() ?: gps.accuracyMeters,
        speedMps = speedText.toFloatOrNull() ?: gps.speedMps,
        bearingDegrees = gps.bearingDegrees,
        mode = mode,
        jitterEnabled = jitter,
        routeWaypoints = gps.routeWaypoints,
        loopMode = gps.loopMode
    )

    val config = draft()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        DetailFormCard("虚拟定位总控") {
            DetailToggle("启用虚拟定位", enabled) { checked ->
                enabled = checked
                vm.updateVirtualLocation(profile.id, draft().copy(enabled = checked))
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NovaTag(
                    text = if (enabled) "GPS 已开启" else "GPS 已关闭",
                    color = if (enabled) NovaPalette.Emerald else NovaPalette.TextMuted
                )
                NovaTag(modeLabel(mode), NovaPalette.Cyan)
                NovaTag(if (jitter) "抖动 ±2m" else "固定坐标", NovaPalette.Violet)
                NovaTag("NMEA 0183", NovaPalette.Amber)
            }
            Text(
                text = "${presetName.ifBlank { "自定义坐标" }} · " +
                    "${formatCoordinate(config.latitude)}, ${formatCoordinate(config.longitude)} · " +
                    "海拔 ${String.format(Locale.US, "%.1f", config.altitude)}m",
                color = NovaPalette.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(14.dp))
        InteractiveGeoMap(
            resetKey = profile.id,
            centerLat = config.latitude,
            centerLon = config.longitude,
            accuracyMeters = config.accuracyMeters,
            routeWaypoints = config.routeWaypoints,
            currentPoint = GeoPoint(
                latitude = config.latitude,
                longitude = config.longitude,
                altitude = config.altitude,
                label = config.presetName
            ),
            bearingDegrees = playback?.bearingDegrees ?: config.bearingDegrees,
            interactionMode = mapMode,
            tileSource = tileSource,
            onInteractionModeChange = { mapMode = it },
            onTileSourceChange = { tileSource = it },
            onPickLocation = { point ->
                enabled = true
                presetName = "地图选点定位"
                latText = formatCoordinate(point.latitude)
                lonText = formatCoordinate(point.longitude)
                vm.pickMapLocation(profile.id, point)
            },
            onAddWaypoint = { point ->
                enabled = true
                vm.addRouteWaypoint(profile.id, point)
            }
        )
        Text(
            text = "「选点定位」轻点地图立即传送；「规划路径」逐点添加途经点，" +
                "支持双指缩放、拖动平移与离线网格。",
            color = NovaPalette.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(14.dp))
        RouteSimulationCard(
            profile = profile,
            playback = playback,
            speedMps = config.speedMps,
            onSpeedPreset = { metersPerSecond ->
                speedText = String.format(Locale.US, "%.2f", metersPerSecond)
                vm.setRouteSpeed(profile.id, metersPerSecond)
            },
            onSpeedCommit = { metersPerSecond ->
                speedText = String.format(Locale.US, "%.2f", metersPerSecond)
                vm.setRouteSpeed(profile.id, metersPerSecond)
            },
            onLoopMode = { loop -> vm.setRouteLoopMode(profile.id, loop) },
            onUndo = {
                vm.removeRouteWaypoint(profile.id, config.routeWaypoints.lastIndex)
            },
            onClear = { vm.clearRouteWaypoints(profile.id) },
            onGenerate = { vm.generateSampleRoute(profile.id) },
            onStart = { vm.startRouteSimulation(profile.id) },
            onPause = { vm.pauseRouteSimulation(profile.id) },
            onReset = { vm.resetRouteSimulation(profile.id) },
            onRemoveWaypoint = { index -> vm.removeRouteWaypoint(profile.id, index) }
        )

        Spacer(Modifier.height(14.dp))
        DetailFormCard("城市预设 · 一键传送") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocationPresets.all.forEach { preset ->
                    FilterChip(
                        selected = presetName == preset.presetName,
                        onClick = {
                            presetName = preset.presetName
                            latText = formatCoordinate(preset.latitude)
                            lonText = formatCoordinate(preset.longitude)
                            altText = String.format(Locale.US, "%.1f", preset.altitude)
                            enabled = true
                            vm.updateVirtualLocation(
                                profile.id,
                                draft().copy(
                                    enabled = true,
                                    presetName = preset.presetName,
                                    latitude = preset.latitude,
                                    longitude = preset.longitude,
                                    altitude = preset.altitude
                                )
                            )
                        },
                        label = { Text(preset.presetName, fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("坐标与信号参数") {
            DetailTextField("纬度 (latitude)", latText) { latText = it }
            DetailTextField("经度 (longitude)", lonText) { lonText = it }
            DetailTextField("海拔 (altitude m)", altText) { altText = it }
            DetailTextField("精度 (accuracy m)", accuracyText) { accuracyText = it }
            DetailTextField("速度 (speed m/s)", speedText) { speedText = it }
            DetailToggle("随机微抖动 (±2m)", jitter) { jitter = it }
            Text("模式", color = NovaPalette.TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VirtualLocationMode.entries.forEach { entry ->
                    FilterChip(
                        selected = mode == entry,
                        onClick = { mode = entry },
                        label = { Text(modeLabel(entry), fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("实时摇杆 · 步进定位") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5f, 20f, 100f, 500f).forEach { step ->
                    FilterChip(
                        selected = stepMeters == step,
                        onClick = { stepMeters = step },
                        label = { Text("${step.roundToInt()}m", fontSize = 11.sp) },
                        colors = detailChipColors()
                    )
                }
            }
            GpsDirectionPad(
                stepMeters = stepMeters,
                onStep = { bearing ->
                    enabled = true
                    vm.nudgeVirtualLocation(profile.id, bearing, stepMeters)
                }
            )
            Text(
                text = "每次点按立即写入 novavm_gps.nmea 并刷新 persist.novavm.gps.*。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        DetailFormCard("NMEA-0183 实时报文") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { nmeaOpen = !nmeaOpen }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "报文 & ADB 注入命令预览",
                    color = NovaPalette.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (nmeaOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (nmeaOpen) "收起" else "展开",
                    tint = NovaPalette.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = nmeaOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val payload = state.gpsNmeaLines.joinToString("\n") +
                        "\n" + state.gpsAdbCommand
                    Text(
                        text = payload.ifBlank { "# 启用并保存定位后生成 NMEA 报文" },
                        color = NovaPalette.Emerald,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NovaPalette.Background)
                            .padding(10.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(payload))
                            copied = true
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "已复制" else "复制报文", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.updateVirtualLocation(profile.id, draft()) },
            colors = ButtonDefaults.buttonColors(
                containerColor = NovaPalette.Cyan,
                contentColor = Color(0xFF04121C)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存并同步虚拟定位")
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun RouteSimulationCard(
    profile: VmProfile,
    playback: RoutePlaybackState?,
    speedMps: Float,
    onSpeedPreset: (Float) -> Unit,
    onSpeedCommit: (Float) -> Unit,
    onLoopMode: (RouteLoopMode) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onGenerate: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit
) {
    val gps = profile.virtualLocation
    val waypoints = gps.routeWaypoints
    val selectedSpeed = speedMps.coerceIn(0.5f, 35f)

    DetailFormCard("路径模拟控制") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaTag("途经点 ${waypoints.size}", NovaPalette.Cyan)
            NovaTag(loopModeLabel(gps.loopMode), NovaPalette.Violet)
            NovaTag(
                text = if (playback?.isPlaying == true) "播放中" else "未播放",
                color = if (playback?.isPlaying == true) NovaPalette.Emerald else NovaPalette.TextMuted
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactAction("撤销上一点", NovaPalette.TextSecondary, onUndo)
            CompactAction("清空路径", NovaPalette.Coral, onClear)
            CompactAction("生成示例路线", NovaPalette.Amber, onGenerate)
        }

        Text("速度预设", color = NovaPalette.TextSecondary, fontSize = 11.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            speedPresets.forEach { (label, metersPerSecond) ->
                FilterChip(
                    selected = abs(selectedSpeed - metersPerSecond) < 0.06f,
                    onClick = { onSpeedPreset(metersPerSecond) },
                    label = { Text(label, fontSize = 10.sp) },
                    colors = detailChipColors()
                )
            }
        }

        var sliderSpeed by remember(profile.id, selectedSpeed) {
            mutableStateOf(selectedSpeed)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("自定义速度", color = NovaPalette.TextSecondary, fontSize = 11.sp)
            Text(
                text = String.format(Locale.US, "%.1f m/s", sliderSpeed),
                color = NovaPalette.Cyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = sliderSpeed,
            onValueChange = { sliderSpeed = it },
            onValueChangeFinished = { onSpeedCommit(sliderSpeed) },
            valueRange = 0.5f..35f,
            colors = SliderDefaults.colors(
                thumbColor = NovaPalette.Cyan,
                activeTrackColor = NovaPalette.Cyan,
                inactiveTrackColor = NovaPalette.SurfaceMuted
            )
        )

        Text("循环模式", color = NovaPalette.TextSecondary, fontSize = 11.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RouteLoopMode.entries.forEach { loop ->
                FilterChip(
                    selected = gps.loopMode == loop,
                    onClick = { onLoopMode(loop) },
                    label = { Text(loopModeLabel(loop), fontSize = 10.sp) },
                    colors = detailChipColors()
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = waypoints.size >= 2,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("开始模拟", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onPause,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = null,
                    tint = NovaPalette.Amber,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("暂停", color = NovaPalette.Amber, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = NovaPalette.TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("重置", fontSize = 11.sp)
            }
        }

        val totalMeters = playback?.takeIf { it.hasRoute }?.totalMeters
            ?: GeoMath.totalRouteDistanceMeters(waypoints)
        val progress = playback?.progressFraction ?: 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = NovaPalette.Cyan,
            trackColor = NovaPalette.SurfaceMuted
        )
        Text(
            text = if (playback != null) {
                RouteSimulator.progressText(playback)
            } else {
                String.format(
                    Locale.US,
                    "已行进 0m / 总长 %.0fm · 航向 %.0f°",
                    totalMeters,
                    gps.bearingDegrees
                )
            },
            color = NovaPalette.TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        if (waypoints.isEmpty()) {
            Text(
                text = "尚未规划路径。切换到「规划路径」在地图上点选，或点击「生成示例路线」。",
                color = NovaPalette.TextMuted,
                fontSize = 10.sp
            )
        } else {
            waypoints.forEachIndexed { index, waypoint ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        color = NovaPalette.Cyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(18.dp)
                    )
                    Text(
                        text = "${formatCoordinate(waypoint.latitude)}, " +
                            formatCoordinate(waypoint.longitude),
                        color = NovaPalette.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemoveWaypoint(index) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除第 ${index + 1} 个途经点",
                            tint = NovaPalette.Coral,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private val speedPresets: List<Pair<String, Float>> = listOf(
    "步行 5km/h" to 1.4f,
    "跑步 11km/h" to 3.0f,
    "骑行 20km/h" to 5.5f,
    "驾车 60km/h" to 16.7f
)

@Composable
private fun CompactAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun GpsDirectionPad(stepMeters: Float, onStep: (Float) -> Unit) {
    val rows = listOf(
        listOf(Triple("西北", "↖", 315f), Triple("北", "↑", 0f), Triple("东北", "↗", 45f)),
        listOf(Triple("西", "←", 270f), Triple("东", "→", 90f)),
        listOf(Triple("西南", "↙", 225f), Triple("南", "↓", 180f), Triple("东南", "↘", 135f))
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (index == 1) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(NovaPalette.CyanSoft.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${stepMeters.roundToInt()}m",
                            color = NovaPalette.Cyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                row.forEach { (label, arrow, bearing) ->
                    GpsPadButton(label = label, arrow = arrow) { onStep(bearing) }
                }
            }
        }
    }
}

@Composable
private fun GpsPadButton(label: String, arrow: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NovaPalette.SurfaceMuted.copy(alpha = 0.75f))
            .border(1.dp, NovaPalette.Cyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = arrow, color = NovaPalette.Cyan, fontSize = 15.sp)
        Text(text = label, color = NovaPalette.TextSecondary, fontSize = 8.sp)
    }
}

private fun modeLabel(mode: VirtualLocationMode): String = when (mode) {
    VirtualLocationMode.STATIC -> "定点"
    VirtualLocationMode.ROUTE_WALK -> "轨迹"
    VirtualLocationMode.JOYSTICK -> "摇杆"
}

private fun loopModeLabel(mode: RouteLoopMode): String = when (mode) {
    RouteLoopMode.ONCE -> "单次到达"
    RouteLoopMode.LOOP -> "首尾循环"
    RouteLoopMode.PING_PONG -> "往返折返"
}

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)

@Composable
private fun DetailFormCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = NovaPalette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun DetailSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = NovaPalette.TextSecondary, fontSize = 12.sp)
        Text(
            text = valueText,
            color = NovaPalette.Cyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = NovaPalette.Cyan,
            activeTrackColor = NovaPalette.Cyan,
            inactiveTrackColor = NovaPalette.SurfaceMuted
        )
    )
}

@Composable
private fun DetailToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = NovaPalette.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04121C),
                checkedTrackColor = NovaPalette.Cyan,
                uncheckedTrackColor = NovaPalette.SurfaceMuted
            )
        )
    }
}

@Composable
private fun DetailTextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun detailChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = NovaPalette.Cyan.copy(alpha = 0.18f),
    selectedLabelColor = NovaPalette.Cyan,
    labelColor = NovaPalette.TextSecondary,
    containerColor = NovaPalette.SurfaceMuted.copy(alpha = 0.4f)
)
