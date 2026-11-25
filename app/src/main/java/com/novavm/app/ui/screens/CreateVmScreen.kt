package com.novavm.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofGenerator
import com.novavm.app.data.GpuMode
import com.novavm.app.data.LocationPresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.ui.NovaUiState
import com.novavm.app.ui.NovaVmViewModel
import com.novavm.app.ui.VmDraft
import com.novavm.app.ui.components.NovaTag
import com.novavm.app.ui.components.SectionTitle
import com.novavm.app.ui.theme.NovaPalette
import kotlin.math.roundToInt

@Composable
fun CreateVmScreen(
    state: NovaUiState,
    vm: NovaVmViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    var name by remember { mutableStateOf("NovaVM Pixel") }
    var architecture by remember { mutableStateOf(VmArchitecture.ARM64) }
    var backend by remember { mutableStateOf(VmBackendType.AUTO) }
    var romPath by remember { mutableStateOf("") }
    var romLabel by remember { mutableStateOf("创建空磁盘") }
    var romMenu by remember { mutableStateOf(false) }

    var cpuCores by remember { mutableStateOf(4) }
    var ramMb by remember { mutableStateOf(2048) }
    var diskGb by remember { mutableStateOf(16) }
    var resolution by remember { mutableStateOf(720 to 1280) }
    var dpi by remember { mutableStateOf(320) }
    var fps by remember { mutableStateOf(60) }
    var gpuMode by remember { mutableStateOf(GpuMode.VIRTIO_GPU) }

    var spoof by remember {
        mutableStateOf(DeviceSpoofGenerator.forPreset(DevicePresets.pixel8Pro))
    }
    var rootEnabled by remember { mutableStateOf(true) }
    var xposedEnabled by remember { mutableStateOf(true) }
    var selinuxPermissive by remember { mutableStateOf(false) }
    var magiskHide by remember { mutableStateOf(true) }

    var gpsEnabled by remember { mutableStateOf(false) }
    var gpsPreset by remember { mutableStateOf(LocationPresets.beijingTiananmen) }

    val resolutions = listOf(
        720 to 1280,
        1080 to 1920,
        1080 to 2400,
        1280 to 720
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaPalette.Background),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = NovaPalette.TextPrimary
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "创建虚拟机",
                        color = NovaPalette.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "// ${state.capabilities.primaryAbi} · ${state.capabilities.cpuCores} 核宿主",
                        color = NovaPalette.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        item {
            FormCard(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("虚拟机名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("架构")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VmArchitecture.entries.forEach { arch ->
                        FilterChip(
                            selected = architecture == arch,
                            onClick = { architecture = arch },
                            label = { Text(arch.name) },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("后端")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VmBackendType.entries.forEach { type ->
                        FilterChip(
                            selected = backend == type,
                            onClick = { backend = type },
                            label = { Text(type.name) },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("系统镜像")
                Box {
                    OutlinedButton(
                        onClick = { romMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = romLabel,
                            color = NovaPalette.TextPrimary,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = NovaPalette.TextSecondary)
                    }
                    DropdownMenu(
                        expanded = romMenu,
                        onDismissRequest = { romMenu = false },
                        modifier = Modifier.background(NovaPalette.SurfaceElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("创建空磁盘", color = NovaPalette.Cyan) },
                            onClick = {
                                romMenu = false
                                romPath = ""
                                romLabel = "创建空磁盘"
                            }
                        )
                        state.importedRoms.forEach { rom ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${rom.displayName}  (${rom.format})",
                                        color = NovaPalette.TextPrimary,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    romMenu = false
                                    romPath = rom.absolutePath
                                    romLabel = rom.displayName
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            FormCard(title = "硬件配置") {
                SliderField(
                    label = "CPU 核心",
                    valueText = "$cpuCores 核",
                    value = cpuCores.toFloat(),
                    range = 1f..8f,
                    steps = 6,
                    onChange = { cpuCores = it.roundToInt().coerceIn(1, 8) }
                )
                SliderField(
                    label = "内存",
                    valueText = "$ramMb MB",
                    value = ramMb.toFloat(),
                    range = 1024f..8192f,
                    steps = 0,
                    onChange = { ramMb = (it / 256f).roundToInt() * 256 }
                )
                SliderField(
                    label = "磁盘容量",
                    valueText = "$diskGb GB",
                    value = diskGb.toFloat(),
                    range = 4f..64f,
                    steps = 59,
                    onChange = { diskGb = it.roundToInt().coerceIn(4, 64) }
                )
                SliderField(
                    label = "DPI",
                    valueText = dpi.toString(),
                    value = dpi.toFloat(),
                    range = 160f..480f,
                    steps = 15,
                    onChange = { dpi = ((it / 20f).roundToInt() * 20).coerceIn(160, 480) }
                )

                Spacer(Modifier.height(6.dp))
                FieldLabel("分辨率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resolutions.forEach { preset ->
                        FilterChip(
                            selected = resolution == preset,
                            onClick = { resolution = preset },
                            label = { Text("${preset.first}x${preset.second}", fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("刷新率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 90, 120).forEach { rate ->
                        FilterChip(
                            selected = fps == rate,
                            onClick = { fps = rate },
                            label = { Text("$rate fps", fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("GPU 模式")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GpuMode.entries.forEach { mode ->
                        FilterChip(
                            selected = gpuMode == mode,
                            onClick = { gpuMode = mode },
                            label = { Text(mode.name, fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }
            }
        }

        item {
            FormCard(title = "设备伪装") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前指纹：${spoof.presetName}",
                        color = NovaPalette.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            spoof = DeviceSpoofGenerator.randomIdentity()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = NovaPalette.Amber
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("随机设备指纹", fontSize = 11.sp, color = NovaPalette.Amber)
                    }
                }
                Spacer(Modifier.height(10.dp))
                DevicePresets.all.chunked(2).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                selected = spoof.presetName == preset.presetName,
                                onClick = {
                                    spoof = DeviceSpoofGenerator.forPreset(preset)
                                },
                                label = { Text(preset.presetName, fontSize = 11.sp) },
                                colors = chipColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = "IMEI ${spoof.imei}  ·  ANDROID_ID ${spoof.androidId}",
                    color = NovaPalette.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        item {
            FormCard(title = "虚拟定位 (Mock GPS)") {
                ToggleRow("启用虚拟定位", gpsEnabled) { gpsEnabled = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel("预设城市")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocationPresets.all.forEach { preset ->
                        FilterChip(
                            selected = gpsPreset.presetName == preset.presetName,
                            onClick = { gpsPreset = preset },
                            label = { Text(preset.presetName, fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = String.format(
                        java.util.Locale.US,
                        "%.4f, %.4f  ·  NMEA + apply_location.sh 随 VM 生成",
                        gpsPreset.latitude,
                        gpsPreset.longitude
                    ),
                    color = NovaPalette.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        item {
            FormCard(title = "Root 与 Xposed") {
                ToggleRow("Root 权限 (Magisk / su)", rootEnabled) { rootEnabled = it }
                ToggleRow("Xposed / LSPosed 框架", xposedEnabled) { xposedEnabled = it }
                ToggleRow("SELinux Permissive", selinuxPermissive) { selinuxPermissive = it }
                ToggleRow("MagiskHide 隐藏 Root", magiskHide) { magiskHide = it }
            }
        }

        item {
            Button(
                onClick = {
                    val hardware = VmHardwareConfig(
                        cpuCores = cpuCores.coerceIn(1, 8),
                        ramMb = ramMb.coerceIn(1024, 8192),
                        diskSizeGb = diskGb.coerceIn(4, 64),
                        width = resolution.first,
                        height = resolution.second,
                        dpi = dpi.coerceIn(160, 480),
                        targetFps = fps,
                        gpuMode = gpuMode
                    )
                    vm.createVm(
                        VmDraft(
                            name = name,
                            architecture = architecture,
                            backendType = backend,
                            romPath = romPath,
                            hardware = hardware,
                            deviceSpoof = spoof,
                            rootXposed = RootXposedConfig(
                                rootEnabled = rootEnabled,
                                xposedEnabled = xposedEnabled,
                                selinuxPermissive = selinuxPermissive,
                                magiskHideEnabled = magiskHide
                            ),
                            virtualLocation = VirtualLocationConfig(
                                enabled = gpsEnabled,
                                presetName = gpsPreset.presetName,
                                latitude = gpsPreset.latitude,
                                longitude = gpsPreset.longitude,
                                altitude = gpsPreset.altitude
                            )
                        ),
                        onCreated = onCreated
                    )
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaPalette.Cyan,
                    contentColor = Color(0xFF04121C)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("创建并保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun FormCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPalette.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NovaPalette.Border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = NovaPalette.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SliderField(
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = NovaPalette.Cyan.copy(alpha = 0.18f),
    selectedLabelColor = NovaPalette.Cyan,
    labelColor = NovaPalette.TextSecondary,
    containerColor = NovaPalette.SurfaceMuted.copy(alpha = 0.4f)
)
