package com.novavm.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novavm.app.NovaVmApp
import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.ImportedRom
import com.novavm.app.data.RomCatalogItem
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.engine.DiskImageInfo
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.NativeEngine
import com.novavm.app.engine.VmRuntimeState
import com.novavm.app.location.GeoMath
import com.novavm.app.transfer.SharedFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Transient user-facing message rendered as a snackbar. */
data class UiMessage(
    val id: Long,
    val text: String,
    val isError: Boolean = false
)

/** Everything the create-VM wizard collects before persisting a profile. */
data class VmDraft(
    val name: String = "NovaVM VM",
    val architecture: VmArchitecture = VmArchitecture.ARM64,
    val backendType: VmBackendType = VmBackendType.AUTO,
    val romPath: String = "",
    val hardware: VmHardwareConfig = VmHardwareConfig(),
    val deviceSpoof: DeviceSpoofConfig = DeviceSpoofConfig(),
    val rootXposed: RootXposedConfig = RootXposedConfig(rootEnabled = true),
    val virtualLocation: VirtualLocationConfig = VirtualLocationConfig()
)

data class NovaUiState(
    val vms: List<VmProfile> = emptyList(),
    val runtimes: Map<String, VmRuntimeState> = emptyMap(),
    val capabilities: HostCapabilities = HostCapabilities.conservative(),
    val importedRoms: List<ImportedRom> = emptyList(),
    val romCatalog: List<RomCatalogItem> = emptyList(),
    val romProbes: Map<String, DiskImageInfo> = emptyMap(),
    val messages: List<UiMessage> = emptyList(),
    val selectedVmId: String? = null,
    val sharedFiles: List<SharedFileInfo> = emptyList(),
    val apkFiles: List<SharedFileInfo> = emptyList(),
    val adbInstallCommands: List<String> = emptyList(),
    val consoleLog: List<String> = emptyList(),
    val bootScriptPreview: String = "",
    val modules: List<String> = emptyList(),
    val gpsNmeaLines: List<String> = emptyList(),
    val gpsAdbCommand: String = "",
    val overlayGranted: Boolean = false,
    val loading: Boolean = false
) {
    val selectedVm: VmProfile? get() = vms.firstOrNull { it.id == selectedVmId }

    fun runtimeOf(vmId: String): VmRuntimeState? = runtimes[vmId]

    fun probeOf(romId: String): DiskImageInfo? = romProbes[romId]
}

/**
 * State holder for every Compose screen. All disk work runs on [Dispatchers.IO];
 * UI state is published through a single [StateFlow].
 */
class NovaVmViewModel(application: Application) : AndroidViewModel(application) {

    private val app: NovaVmApp = application as NovaVmApp
    private val messageIds = AtomicLong(0L)

    private val _state = MutableStateFlow(
        NovaUiState(
            capabilities = app.hostCapabilities,
            romCatalog = app.romRepository.catalog,
            overlayGranted = Settings.canDrawOverlays(application)
        )
    )
    val state: StateFlow<NovaUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            app.vmInstanceManager.states.collect { states ->
                _state.update { it.copy(runtimes = states.associateBy { state -> state.vmId }) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val vmId = _state.value.selectedVmId
                if (vmId != null) {
                    val logs = runCatching { app.vmInstanceManager.consoleLog(vmId) }.getOrDefault(emptyList())
                    _state.update { it.copy(consoleLog = logs) }
                }
                delay(1_400L)
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private fun toast(text: String, isError: Boolean = false) {
        val message = UiMessage(messageIds.incrementAndGet(), text, isError)
        _state.update { it.copy(messages = (it.messages + message).takeLast(4)) }
    }

    fun dismissMessage(id: Long) {
        _state.update { current -> current.copy(messages = current.messages.filterNot { it.id == id }) }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                toast(error.message ?: error.javaClass.simpleName, isError = true)
            }
        }
    }

    private suspend fun <T> onDisk(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun displayName(uri: Uri): String {
        val resolver = app.contentResolver
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "import.bin"
    }

    // ------------------------------------------------------------- loading

    fun refresh() {
        io {
            val profiles = onDisk { app.vmRepository.loadAll() }
            app.vmInstanceManager.refresh()
            val roms = onDisk { app.romRepository.imported() }
            val probes = onDisk {
                roms.associate { rom -> rom.id to NativeEngine.probeDiskImage(rom.absolutePath) }
            }
            _state.update {
                it.copy(
                    vms = profiles,
                    importedRoms = roms,
                    romProbes = probes,
                    romCatalog = app.romRepository.catalog,
                    capabilities = app.hostCapabilities,
                    overlayGranted = Settings.canDrawOverlays(app),
                    loading = false
                )
            }
        }
    }

    fun refreshOverlayPermission() {
        _state.update { it.copy(overlayGranted = Settings.canDrawOverlays(app)) }
    }

    fun selectedProfile(vmId: String): VmProfile? =
        _state.value.vms.firstOrNull { it.id == vmId }

    // ------------------------------------------------------------- creation

    fun createVm(draft: VmDraft, onCreated: (String) -> Unit = {}) {
        _state.update { it.copy(loading = true) }
        io {
            val profileId = onDisk {
                val created = app.vmInstanceManager.createProfile(
                    name = draft.name.ifBlank { "NovaVM VM" },
                    architecture = draft.architecture,
                    backendType = draft.backendType,
                    hardware = draft.hardware,
                    deviceSpoof = draft.deviceSpoof,
                    rootXposed = draft.rootXposed,
                    virtualLocation = draft.virtualLocation
                )
                ensureDisk(created)
                val romPath = draft.romPath
                app.vmRepository.update(created.copy(romPath = romPath))
                app.rootXposedConfiguratorFor(created.id).writeAll(
                    draft.rootXposed,
                    draft.deviceSpoof,
                    draft.hardware,
                    created.name,
                    draft.virtualLocation
                )
                if (draft.virtualLocation.enabled) {
                    app.vmInstanceManager.syncVirtualLocation(created.id, "create")
                }
                app.setActiveVm(created.id)
                created.id
            }
            app.vmInstanceManager.refresh()
            refresh()
            selectVm(profileId)
            _state.update { it.copy(loading = false) }
            toast("虚拟机已创建")
            onCreated(profileId)
        }
    }

    fun quickCreateDefaultVm(onCreated: (String) -> Unit = {}) {
        _state.update { it.copy(loading = true) }
        io {
            val profileId = onDisk {
                val created = app.createDefaultVm()
                ensureDisk(created)
                created.id
            }
            app.vmInstanceManager.refresh()
            refresh()
            selectVm(profileId)
            _state.update { it.copy(loading = false) }
            toast("已创建默认虚拟机")
            onCreated(profileId)
        }
    }

    private fun ensureDisk(profile: VmProfile) {
        val disk = File(profile.diskImagePath)
        if (disk.isFile && disk.length() > 0L) return
        val sizeMb = profile.hardware.diskSizeGb.toLong() * 1024L
        NativeEngine.createEmptyRawImage(disk.absolutePath, sizeMb)
    }

    fun cloneVm(vmId: String) {
        io {
            val clone = onDisk {
                val sourceDisk = app.vmRepository.get(vmId)?.diskImagePath?.let { File(it) }
                // Only stream the disk when it is small enough to copy quickly;
                // multi-gigabyte sparse images are re-created instead.
                val copyDisk = sourceDisk != null &&
                    sourceDisk.isFile &&
                    sourceDisk.length() in 1L..(256L * 1024L * 1024L)
                val created = app.vmRepository.clone(vmId, copyDisk = copyDisk)
                created?.let { ensureDisk(it) }
                created
            }
            if (clone == null) {
                toast("克隆失败：找不到该虚拟机", isError = true)
                return@io
            }
            app.vmInstanceManager.refresh()
            app.vmInstanceManager.appendConsole(clone.id, "Cloned from $vmId")
            app.setActiveVm(clone.id)
            refresh()
            toast("已克隆为 ${clone.name}")
        }
    }

    fun deleteVm(vmId: String) {
        io {
            val removed = onDisk { app.vmInstanceManager.deleteProfile(vmId) }
            if (_state.value.selectedVmId == vmId) {
                _state.update { it.copy(selectedVmId = null, sharedFiles = emptyList(), consoleLog = emptyList()) }
            }
            refresh()
            toast(if (removed) "虚拟机已删除" else "删除失败", isError = !removed)
        }
    }

    // ------------------------------------------------------------- lifecycle

    fun startVm(vmId: String) {
        io {
            val started = app.vmInstanceManager.start(vmId)
            if (started) {
                app.setActiveVm(vmId)
                app.vmInstanceManager.appendConsole(vmId, "display requested: VNC 127.0.0.1:${selectedProfile(vmId)?.vncPort ?: 5901}")
            }
            _state.update {
                it.copy(runtimes = app.vmInstanceManager.states.value.associateBy { s -> s.vmId })
            }
            toast(if (started) "虚拟机已启动" else "虚拟机已在运行", isError = !started)
        }
    }

    fun stopVm(vmId: String) {
        io {
            val stopped = app.vmInstanceManager.stop(vmId)
            _state.update {
                it.copy(runtimes = app.vmInstanceManager.states.value.associateBy { s -> s.vmId })
            }
            toast(if (stopped) "虚拟机已停止" else "虚拟机已处于停止状态", isError = !stopped)
        }
    }

    fun pauseVm(vmId: String) {
        io {
            val paused = app.vmInstanceManager.pause(vmId)
            _state.update {
                it.copy(runtimes = app.vmInstanceManager.states.value.associateBy { s -> s.vmId })
            }
            toast(if (paused) "已暂停" else "当前状态无法暂停", isError = !paused)
        }
    }

    fun resumeVm(vmId: String) {
        io {
            val resumed = app.vmInstanceManager.resume(vmId)
            _state.update {
                it.copy(runtimes = app.vmInstanceManager.states.value.associateBy { s -> s.vmId })
            }
            toast(if (resumed) "已恢复运行" else "当前状态无法恢复", isError = !resumed)
        }
    }

    fun toggleRunning(vmId: String) {
        val runtime = _state.value.runtimeOf(vmId)
        if (runtime?.status == com.novavm.app.data.VmStatus.RUNNING ||
            runtime?.status == com.novavm.app.data.VmStatus.STARTING ||
            runtime?.status == com.novavm.app.data.VmStatus.PAUSED
        ) {
            stopVm(vmId)
        } else {
            startVm(vmId)
        }
    }

    // ------------------------------------------------------------- config

    fun saveHardware(vmId: String, hardware: VmHardwareConfig) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            onDisk { app.vmRepository.update(profile.copy(hardware = hardware)) }
            regenerateBootFiles(profile.copy(hardware = hardware))
            refresh()
            reloadSelected()
            toast("硬件配置已保存")
        }
    }

    fun saveDeviceSpoof(vmId: String, spoof: DeviceSpoofConfig) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            onDisk { app.vmRepository.update(profile.copy(deviceSpoof = spoof)) }
            regenerateBootFiles(profile.copy(deviceSpoof = spoof))
            refresh()
            reloadSelected()
            toast("设备指纹已更新")
        }
    }

    fun saveRootXposed(vmId: String, config: RootXposedConfig) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            onDisk { app.vmRepository.update(profile.copy(rootXposed = config)) }
            regenerateBootFiles(profile.copy(rootXposed = config))
            refresh()
            reloadSelected()
            toast("Root / Xposed 配置已保存")
        }
    }

    private fun regenerateBootFiles(profile: VmProfile) {
        runCatching {
            app.rootXposedConfiguratorFor(profile.id).writeAll(
                config = profile.rootXposed,
                spoof = profile.deviceSpoof,
                hardware = profile.hardware,
                profileName = profile.name,
                virtualLocation = profile.virtualLocation
            )
        }
    }

    // ------------------------------------------------------- virtual location

    /**
     * Persists a new mock GPS configuration, regenerates the guest scripts and
     * re-syncs `novavm_gps.nmea` / `apply_location.sh`.
     */
    fun updateVirtualLocation(vmId: String, config: VirtualLocationConfig) {
        saveVirtualLocation(vmId, config, toastText = "虚拟定位已更新")
    }

    /**
     * Steps the current fix [distanceMeters] along [bearingDegrees] and syncs the
     * guest artifacts immediately (D-Pad / joystick input).
     */
    fun nudgeVirtualLocation(vmId: String, bearingDegrees: Float, distanceMeters: Float) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val gps = profile.virtualLocation
            val point = GeoMath.stepPosition(
                gps.latitude,
                gps.longitude,
                bearingDegrees.toDouble(),
                distanceMeters.toDouble()
            )
            val updated = gps.copy(
                enabled = true,
                latitude = point.latitude,
                longitude = point.longitude,
                bearingDegrees = normalizeBearing(bearingDegrees)
            )
            applyVirtualLocation(profile, updated)
        }
    }

    private fun saveVirtualLocation(
        vmId: String,
        config: VirtualLocationConfig,
        toastText: String
    ) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            applyVirtualLocation(
                profile,
                config.copy(
                    latitude = GeoMath.clampLat(config.latitude),
                    longitude = GeoMath.normalizeLon(config.longitude)
                )
            )
            toast(toastText)
        }
    }

    private suspend fun applyVirtualLocation(profile: VmProfile, config: VirtualLocationConfig) {
        val updated = profile.copy(virtualLocation = config)
        onDisk { app.vmRepository.update(updated) }
        regenerateBootFiles(updated)
        onDisk { app.vmInstanceManager.syncVirtualLocation(profile.id, "update") }
        refresh()
        reloadSelected()
    }

    private fun normalizeBearing(bearing: Float): Float {
        var value = bearing % 360f
        if (value < 0f) value += 360f
        return value
    }

    // ------------------------------------------------------------- snapshots

    fun createSnapshot(vmId: String, name: String) {
        io {
            val snapshot = onDisk {
                app.vmInstanceManager.takeSnapshot(vmId, name.ifBlank { "Snapshot" })
            }
            refresh()
            reloadSelected()
            toast(if (snapshot != null) "快照已创建" else "快照创建失败", isError = snapshot == null)
        }
    }

    fun restoreSnapshot(vmId: String, snapshotId: String) {
        io {
            val restored = app.vmInstanceManager.restoreSnapshot(vmId, snapshotId)
            refresh()
            reloadSelected()
            toast(if (restored) "正在恢复快照" else "快照不存在", isError = !restored)
        }
    }

    fun deleteSnapshot(vmId: String, snapshotId: String) {
        io {
            val deleted = onDisk { app.vmInstanceManager.deleteSnapshot(vmId, snapshotId) }
            refresh()
            reloadSelected()
            toast(if (deleted) "快照已删除" else "快照删除失败", isError = !deleted)
        }
    }

    // ------------------------------------------------------------- ROMs

    fun importRom(uri: Uri, architecture: VmArchitecture) {
        _state.update { it.copy(loading = true) }
        io {
            val name = displayName(uri)
            val rom = onDisk {
                val input = app.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件")
                app.romRepository.importRom(
                    input = input,
                    fileName = name,
                    displayName = name,
                    architecture = architecture
                )
            }
            val probe = onDisk { NativeEngine.probeDiskImage(rom.absolutePath) }
            _state.update {
                it.copy(
                    importedRoms = it.importedRoms + rom,
                    romProbes = it.romProbes + (rom.id to probe),
                    loading = false
                )
            }
            toast("镜像已导入：${rom.displayName}")
        }
    }

    fun deleteRom(romId: String) {
        io {
            val deleted = onDisk { app.romRepository.delete(romId) }
            refresh()
            toast(if (deleted) "镜像已删除" else "镜像删除失败", isError = !deleted)
        }
    }

    // ------------------------------------------------------------- transfer

    fun selectVm(vmId: String) {
        _state.update { it.copy(selectedVmId = vmId) }
        app.setActiveVm(vmId)
        io {
            reloadSelected()
        }
    }

    private suspend fun reloadSelected() {
        val vmId = _state.value.selectedVmId ?: return
        val profile = onDisk { app.vmRepository.get(vmId) } ?: return
        val transfer = app.transferManagerFor(vmId)
        val files = onDisk { runCatching { transfer.listFiles() }.getOrDefault(emptyList()) }
        val apks = onDisk { runCatching { transfer.apks() }.getOrDefault(emptyList()) }
        val commands = onDisk {
            runCatching { transfer.buildAdbInstallCommands(profile.adbForwardPort) }.getOrDefault(emptyList())
        }
        val modules = onDisk {
            runCatching { app.rootXposedConfiguratorFor(vmId).listModules() }.getOrDefault(emptyList())
        }
        val bootScript = onDisk {
            runCatching {
                app.rootXposedConfiguratorFor(vmId).buildBootScript(
                    profile.rootXposed,
                    profile.deviceSpoof,
                    profile.hardware,
                    profile.name
                )
            }.getOrDefault("")
        }
        val log = runCatching { app.vmInstanceManager.consoleLog(vmId) }.getOrDefault(emptyList())
        val nmeaLines = onDisk {
            runCatching { app.virtualGpsEngine.previewNmea(profile.virtualLocation) }
                .getOrDefault(emptyList())
        }
        val adbCommand = runCatching {
            app.virtualGpsEngine.adbGeoCommand(profile.virtualLocation, profile.adbForwardPort)
        }.getOrDefault("")
        _state.update {
            it.copy(
                selectedVmId = vmId,
                sharedFiles = files,
                apkFiles = apks,
                adbInstallCommands = commands,
                modules = modules,
                bootScriptPreview = bootScript,
                consoleLog = log,
                gpsNmeaLines = nmeaLines,
                gpsAdbCommand = adbCommand
            )
        }
    }

    fun importSharedFile(vmId: String, uri: Uri) {
        _state.update { it.copy(loading = true) }
        io {
            val name = displayName(uri)
            val target = onDisk {
                val input = app.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件")
                app.transferManagerFor(vmId).importFile(input, name)
            }
            reloadSelected()
            _state.update { it.copy(loading = false) }
            toast("已导入到共享目录：${target.name}")
        }
    }

    fun pushApk(vmId: String, uri: Uri) {
        _state.update { it.copy(loading = true) }
        io {
            val name = displayName(uri)
            val staged = onDisk {
                val input = app.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件")
                app.transferManagerFor(vmId).stageApk(input, name)
            }
            reloadSelected()
            _state.update { it.copy(loading = false) }
            toast("APK 已推送：${staged.name}")
        }
    }

    fun deleteSharedFile(vmId: String, relativePath: String) {
        io {
            val deleted = onDisk { app.transferManagerFor(vmId).deleteFile(relativePath) }
            reloadSelected()
            toast(if (deleted) "已删除 $relativePath" else "删除失败", isError = !deleted)
        }
    }

    fun exportSharedFile(vmId: String, relativePath: String, targetUri: Uri) {
        io {
            val transfer = app.transferManagerFor(vmId)
            onDisk {
                val output = app.contentResolver.openOutputStream(targetUri)
                    ?: error("无法写入目标文件")
                output.use { transfer.exportFile(relativePath, it) }
            }
            toast("已导出 $relativePath")
        }
    }

    fun stageModule(vmId: String, uri: Uri) {
        io {
            val name = displayName(uri)
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val configurator = app.rootXposedConfiguratorFor(vmId)
            val staged = onDisk {
                val input = app.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件")
                configurator.stageModule(input, name)
            }
            val config = profile.rootXposed.copy(
                stagedModules = (profile.rootXposed.stagedModules + staged.name).distinct()
            )
            onDisk { app.vmRepository.update(profile.copy(rootXposed = config)) }
            regenerateBootFiles(profile.copy(rootXposed = config))
            refresh()
            reloadSelected()
            toast("模块已添加：${staged.name}")
        }
    }

    fun removeModule(vmId: String, fileName: String) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            onDisk { app.rootXposedConfiguratorFor(vmId).removeModule(fileName) }
            val config = profile.rootXposed.copy(
                stagedModules = profile.rootXposed.stagedModules.filterNot { it == fileName }
            )
            onDisk { app.vmRepository.update(profile.copy(rootXposed = config)) }
            regenerateBootFiles(profile.copy(rootXposed = config))
            refresh()
            reloadSelected()
            toast("模块已移除")
        }
    }

    fun refreshConsole(vmId: String) {
        val log = runCatching { app.vmInstanceManager.consoleLog(vmId) }.getOrDefault(emptyList())
        _state.update { it.copy(consoleLog = log) }
    }

    fun lastLaunchCommand(vmId: String): List<String> =
        runCatching { app.vmInstanceManager.lastLaunchCommand(vmId) }.getOrNull().orEmpty()

    fun overlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}")
        )
}
