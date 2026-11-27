package com.novavm.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novavm.app.NovaVmApp
import com.novavm.app.data.BackgroundKeepAliveConfig
import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.DownloadStatus
import com.novavm.app.data.GeoPoint
import com.novavm.app.data.GmsConfig
import com.novavm.app.data.ImportedRom
import com.novavm.app.data.OneKeyNewDeviceGenerator
import com.novavm.app.data.PinHashUtil
import com.novavm.app.data.PortForwardRule
import com.novavm.app.data.RomCatalogItem
import com.novavm.app.data.RomDownloadState
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.RouteLoopMode
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VirtualSensorConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmBackupManager
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmPrivacyLockConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.DiskImageInfo
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.NativeEngine
import com.novavm.app.engine.QmpConsoleEntry
import com.novavm.app.engine.QmpTerminalController
import com.novavm.app.engine.RuntimeSetupManager
import com.novavm.app.engine.RuntimeStatus
import com.novavm.app.engine.VmRuntimeState
import com.novavm.app.engine.VmStorageStats
import com.novavm.app.engine.VmTelemetryMonitor
import com.novavm.app.location.GeoMath
import com.novavm.app.location.RoutePlaybackState
import com.novavm.app.overlay.KeepAliveService
import com.novavm.app.transfer.HostAppExtractor
import com.novavm.app.transfer.HostAppInfo
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
import java.util.UUID
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

    companion object {
        /** Guest-visible clipboard file the screen-off bridge polls. */
        const val CLIPBOARD_FILE_NAME = "clipboard.txt"

        const val PRIVACY_PIN_MIN_LENGTH = 4
        const val PRIVACY_PIN_MAX_LENGTH = 6

        /** 每台虚拟机保留的 QMP 控制台行数上限（防止长时间使用后无限增长）。 */
        const val QMP_HISTORY_LIMIT = 200
    }

    private val _state = MutableStateFlow(
        NovaUiState(
            capabilities = app.hostCapabilities,
            romCatalog = app.romRepository.catalog,
            overlayGranted = Settings.canDrawOverlays(application)
        )
    )
    val state: StateFlow<NovaUiState> = _state.asStateFlow()

    /** Live route playback map, mirrored from the process-wide [RouteSimulator]. */
    val routePlaybackStates: StateFlow<Map<String, RoutePlaybackState>> =
        app.routeSimulator.playbackStates

    // ------------------------------------------- 运行时 / 下载 / 控制台状态

    /** QEMU 运行时组件的安装快照，由 [refreshRuntimeStatus] 刷新。 */
    private val _runtimeStatus = MutableStateFlow(
        RuntimeStatus(
            qemuAarch64Path = null,
            qemuX86_64Path = null,
            qemuImgPath = null,
            uefiArm64Path = null,
            totalRuntimeBytes = 0L
        )
    )
    val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus.asStateFlow()

    /** 目录镜像下载进度，键为 [RomCatalogItem.id]。 */
    val romDownloads: StateFlow<Map<String, RomDownloadState>> = app.romDownloader.downloads

    /** 每台虚拟机的 QMP/HMP 控制台历史，最新的在末尾。 */
    private val _qmpHistory = MutableStateFlow<Map<String, List<QmpConsoleEntry>>>(emptyMap())
    val qmpHistory: StateFlow<Map<String, List<QmpConsoleEntry>>> = _qmpHistory.asStateFlow()

    /** 每台虚拟机最近一次存储遥测结果。 */
    private val _storageStats = MutableStateFlow<Map<String, VmStorageStats>>(emptyMap())
    val storageStats: StateFlow<Map<String, VmStorageStats>> = _storageStats.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { trackRomDownloads() }
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
            // Tear the playback down *before* the profile disappears: after the
            // deletion `stopAndReset` can no longer resolve the profile, and a
            // leftover ticker would resurrect the deleted VM's GPS state.
            app.routeSimulator.forget(vmId)
            val removed = onDisk { app.vmInstanceManager.deleteProfile(vmId) }
            if (_state.value.selectedVmId == vmId) {
                _state.update { it.copy(selectedVmId = null, sharedFiles = emptyList(), consoleLog = emptyList()) }
            }
            // 控制台历史与遥测属于已消失的虚拟机，一并丢弃避免内存泄漏。
            _qmpHistory.update { it - vmId }
            _storageStats.update { it - vmId }
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
            // A stopped VM must not keep ticking: the simulator would otherwise
            // keep rewriting the profile and re-syncing NMEA for a dead instance.
            if (stopped) app.routeSimulator.stopAndReset(vmId)
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
                virtualLocation = profile.virtualLocation,
                // Keeps shared/novavm_vmos_ext.sh in step with the profile too.
                vmos = profile.vmosBundle()
            )
        }
    }

    // ------------------------------------------------------ VMOS parity suite

    /** Host apps offered by the 「导入本机应用」 segment on the transfer screen. */
    private val _hostApps = MutableStateFlow<List<HostAppInfo>>(emptyList())
    val hostApps: StateFlow<List<HostAppInfo>> = _hostApps.asStateFlow()

    /** Screen-off keep-alive service state, mirrored for the home banner and HUD. */
    val keepAliveRunning: StateFlow<Boolean> = KeepAliveService.isRunning

    private val backupManager = VmBackupManager()

    /**
     * 一键新机：同时重置 [DeviceSpoofConfig]（IMEI / Android ID / Wi-Fi MAC）与
     * [ExtendedDeviceIdentity]（IMSI / ICCID / GAID / 序列号 / 运营商 / BSSID /
     * 蓝牙 MAC），落盘后重新生成客户机引导脚本。
     */
    fun oneKeyNewDevice(vmId: String) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val (spoof, identity) = OneKeyNewDeviceGenerator.generateFullDeviceIdentity(
                profile.deviceSpoof
            )
            val updated = profile.copy(
                deviceSpoof = spoof.copy(presetName = profile.deviceSpoof.presetName),
                extendedIdentity = identity
            )
            onDisk { app.vmRepository.update(updated) }
            regenerateBootFiles(updated)
            refresh()
            reloadSelected()
            toast("已完成一键新机")
        }
    }

    fun updateGms(vmId: String, gms: GmsConfig) {
        saveFeatureConfig(vmId, "Google 服务框架配置已更新") { it.copy(gms = gms) }
    }

    fun updateSensors(vmId: String, sensors: VirtualSensorConfig) {
        saveFeatureConfig(vmId, "虚拟传感器配置已更新") { it.copy(sensors = sensors) }
    }

    /**
     * Persists the keep-alive preferences and mirrors
     * [BackgroundKeepAliveConfig.screenOffKeepAlive] onto the foreground service:
     * the first VM opting in starts it, turning the last one off stops it.
     */
    fun updateKeepAlive(vmId: String, keepAlive: BackgroundKeepAliveConfig) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val updated = profile.copy(keepAlive = keepAlive)
            onDisk { app.vmRepository.update(updated) }
            regenerateBootFiles(updated)
            val profiles = _state.value.vms.map { if (it.id == vmId) updated else it }
            syncKeepAliveService(profiles)
            refresh()
            reloadSelected()
            toast(if (keepAlive.screenOffKeepAlive) "已开启息屏挂机" else "已关闭息屏挂机")
        }
    }

    fun setPrivacyPin(vmId: String, pin: String) {
        val normalized = pin.trim()
        val validLength = normalized.length in PRIVACY_PIN_MIN_LENGTH..PRIVACY_PIN_MAX_LENGTH
        if (!validLength || normalized.any { !it.isDigit() }) {
            toast("隐私密码需为 4-6 位数字", isError = true)
            return
        }
        saveFeatureConfig(vmId, "隐私密码已设置") { profile ->
            profile.copy(
                privacyLock = VmPrivacyLockConfig(
                    enabled = true,
                    pinHash = PinHashUtil.hashPin(normalized)
                )
            )
        }
    }

    fun disablePrivacyLock(vmId: String) {
        saveFeatureConfig(vmId, "隐私锁已关闭") {
            it.copy(privacyLock = VmPrivacyLockConfig())
        }
    }

    /** Verifies a PIN against the in-memory profile; no disk read on the UI thread. */
    fun verifyPrivacyPin(vmId: String, pin: String): Boolean {
        val profile = _state.value.vms.firstOrNull { it.id == vmId } ?: return false
        val lock = profile.privacyLock
        if (!lock.enabled || lock.pinHash.isBlank()) return false
        return PinHashUtil.verifyPin(pin.trim(), lock.pinHash)
    }

    fun refreshHostApps(includeSystem: Boolean = false) {
        io {
            val installed = onDisk {
                HostAppExtractor.listInstalledApps(app, includeSystem = includeSystem)
            }
            _hostApps.value = installed
        }
    }

    /**
     * Extracts [hostApp]'s base APK into the VM share on [Dispatchers.IO] and
     * refreshes the staged-APK listing.
     */
    fun importHostAppToVm(vmId: String, app: HostAppInfo) {
        val novaApp = this.app
        io {
            val staged = onDisk {
                HostAppExtractor.extractApkToVm(app, novaApp.transferManagerFor(vmId))
            }
            novaApp.vmInstanceManager.appendConsole(
                vmId,
                "[ OK ] host app staged: ${app.packageName} -> ${staged.name}"
            )
            reloadSelected()
            toast("已导入本机应用：${app.appName}")
        }
    }

    /** 整机备份导出：把 `.nvmbak`（ZIP）写入用户选择的 URI。 */
    fun exportVmBackup(vmId: String, uri: Uri) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val summary = onDisk {
                val output = app.contentResolver.openOutputStream(uri)
                    ?: error("无法写入备份文件")
                output.use {
                    backupManager.exportVmToZip(
                        profile = profile,
                        vmDir = app.vmRepository.vmDir(vmId),
                        outputStream = it
                    )
                }
            }
            toast("备份已导出：${summary.fileCount} 个文件 · ${summary.totalBytes / 1024} KB")
        }
    }

    /** 整机备份导入：从 `.nvmbak` 还原为一台新虚拟机。 */
    fun importVmBackup(uri: Uri) {
        io {
            val imported = onDisk {
                val input = app.contentResolver.openInputStream(uri)
                    ?: error("无法读取备份文件")
                input.use { backupManager.importVmFromZip(it, app.vmRepository) }
            }
            app.vmInstanceManager.refresh()
            refresh()
            selectVm(imported.id)
            toast("已导入备份：${imported.displayName}")
        }
    }

    /**
     * Writes [text] to `shared/clipboard.txt`, the file the guest-side bridge
     * polls so the VM clipboard follows the host clipboard.
     *
     * An empty host clipboard is a no-op: overwriting the guest clipboard file
     * with zero bytes would silently wipe whatever the guest had copied.
     */
    fun syncHostClipboardToVm(vmId: String, text: String) {
        if (text.isEmpty()) {
            toast("本机剪切板为空，未同步", isError = true)
            return
        }
        io {
            val target = onDisk {
                app.transferManagerFor(vmId).importFile(
                    input = text.toByteArray(Charsets.UTF_8).inputStream(),
                    fileName = CLIPBOARD_FILE_NAME
                )
            }
            app.vmInstanceManager.appendConsole(
                vmId,
                "[ OK ] clipboard synced to shared/${target.name}"
            )
            reloadSelected()
            toast("已同步本机剪切板到虚拟机")
        }
    }

    /** Applies [transform] to the stored profile and re-syncs every guest artifact. */
    private fun saveFeatureConfig(
        vmId: String,
        message: String,
        transform: (VmProfile) -> VmProfile
    ) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val updated = transform(profile)
            onDisk { app.vmRepository.update(updated) }
            regenerateBootFiles(updated)
            refresh()
            reloadSelected()
            toast(message)
        }
    }

    /** Starts or stops [KeepAliveService] so it tracks the given profiles. */
    private fun syncKeepAliveService(profiles: List<VmProfile>) {
        val wanted = profiles.any { it.keepAlive.screenOffKeepAlive }
        if (wanted) {
            val running = _state.value.runtimes.values.count { it.isRunning }
            KeepAliveService.start(app, running)
        } else {
            KeepAliveService.stop(app)
        }
    }

    /** Manual 「停止挂机」 from the home banner / display HUD. */
    fun stopKeepAliveService() {
        KeepAliveService.stop(app)
        toast("已停止息屏挂机")
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

    // -------------------------------------------- map picking & route playback

    /** Teleports the mock fix to a coordinate tapped on the interactive map. */
    fun pickMapLocation(vmId: String, point: GeoPoint) {
        io {
            val found = mutateGps(vmId) { gps ->
                gps.copy(
                    enabled = true,
                    latitude = GeoMath.clampLat(point.latitude),
                    longitude = GeoMath.normalizeLon(point.longitude),
                    presetName = "地图选点定位"
                )
            }
            if (found) toast("已按地图选点更新定位")
        }
    }

    /** Appends a waypoint tapped while in 规划路径 mode. */
    fun addRouteWaypoint(vmId: String, point: GeoPoint) {
        io {
            val found = mutateGps(vmId) { gps ->
                val waypoint = GeoPoint(
                    latitude = GeoMath.clampLat(point.latitude),
                    longitude = GeoMath.normalizeLon(point.longitude),
                    altitude = gps.altitude,
                    label = "途经点 ${gps.routeWaypoints.size + 1}"
                )
                gps.copy(
                    enabled = true,
                    mode = VirtualLocationMode.ROUTE_WALK,
                    routeWaypoints = gps.routeWaypoints + waypoint
                )
            }
            if (found) toast("已添加途经点")
        }
    }

    fun removeRouteWaypoint(vmId: String, index: Int) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val waypoints = profile.virtualLocation.routeWaypoints
            if (index !in waypoints.indices) {
                toast("没有可删除的途经点", isError = true)
                return@io
            }
            applyVirtualLocation(
                profile,
                profile.virtualLocation.copy(
                    routeWaypoints = waypoints.filterIndexed { i, _ -> i != index }
                )
            )
            toast("已移除途经点")
        }
    }

    fun clearRouteWaypoints(vmId: String) {
        io {
            val found = mutateGps(vmId) { gps ->
                gps.copy(
                    routeWaypoints = emptyList(),
                    mode = VirtualLocationMode.STATIC
                )
            }
            if (found) {
                app.routeSimulator.forget(vmId)
                toast("路径已清空")
            }
        }
    }

    /** Six-point closed demo loop around the current fix, for one-tap testing. */
    fun generateSampleRoute(vmId: String) {
        io {
            val found = mutateGps(vmId) { gps ->
                gps.copy(
                    enabled = true,
                    mode = VirtualLocationMode.ROUTE_WALK,
                    routeWaypoints = GeoMath.generateSampleLoopRoute(
                        centerLat = gps.latitude,
                        centerLon = gps.longitude,
                        radiusMeters = 300.0
                    )
                )
            }
            if (found) {
                app.routeSimulator.stopAndReset(vmId)
                toast("已生成示例路线（6 个途经点）")
            }
        }
    }

    fun setRouteLoopMode(vmId: String, mode: RouteLoopMode) {
        io {
            val found = mutateGps(vmId) { it.copy(loopMode = mode) }
            if (found) toast("循环模式已更新")
        }
    }

    fun setRouteSpeed(vmId: String, speedMps: Float) {
        io {
            val found = mutateGps(vmId) {
                it.copy(speedMps = speedMps.coerceIn(0.5f, 35f))
            }
            if (found) toast("模拟速度已更新")
        }
    }

    fun startRouteSimulation(vmId: String) {
        if (app.routeSimulator.start(vmId)) {
            toast("路线模拟已开始")
            refreshConsole(vmId)
        } else {
            toast("请先规划至少两个途经点", isError = true)
        }
    }

    fun pauseRouteSimulation(vmId: String) {
        val paused = app.routeSimulator.pause(vmId)
        toast(if (paused) "路线模拟已暂停" else "当前没有正在播放的路线", isError = !paused)
    }

    fun resetRouteSimulation(vmId: String) {
        app.routeSimulator.stopAndReset(vmId)
        toast("路线模拟已重置")
    }

    private suspend fun mutateGps(
        vmId: String,
        transform: (VirtualLocationConfig) -> VirtualLocationConfig
    ): Boolean {
        val profile = onDisk { app.vmRepository.get(vmId) }
        if (profile == null) {
            toast("找不到该虚拟机", isError = true)
            return false
        }
        applyVirtualLocation(profile, transform(profile.virtualLocation))
        return true
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
            try {
                val name = displayName(uri)
                val rom = onDisk {
                    // `use {}` guarantees the SAF descriptor is released even when
                    // the copy throws, which otherwise leaks an fd per attempt.
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    input.use {
                        app.romRepository.importRom(
                            input = it,
                            fileName = name,
                            displayName = name,
                            architecture = architecture
                        )
                    }
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
            } finally {
                // Failure path: never leave the wizard stuck behind a spinner.
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun deleteRom(romId: String) {
        io {
            val deleted = onDisk { app.romRepository.delete(romId) }
            refresh()
            toast(if (deleted) "镜像已删除" else "镜像删除失败", isError = !deleted)
        }
    }

    // --------------------------------------------------------- 运行时环境

    /** 重新扫描 `runtime/bin` 与 `runtime/firmware`，刷新四个组件状态。 */
    fun refreshRuntimeStatus() {
        io { inspectRuntime() }
    }

    /** 读取一次运行时状态并同步到 [runtimeStatus]；失败时退回“全部未导入”。 */
    private suspend fun inspectRuntime(): RuntimeStatus {
        val status = onDisk { runCatching { app.runtimeSetupManager.inspect() } }
            .getOrElse {
                RuntimeStatus(
                    qemuAarch64Path = null,
                    qemuX86_64Path = null,
                    qemuImgPath = null,
                    uefiArm64Path = null,
                    totalRuntimeBytes = 0L
                )
            }
        _runtimeStatus.value = status
        return status
    }

    /** 导入运行时 `.zip` 包；白名单与 Zip-Slip 防护由安装器负责。 */
    fun importRuntimeZip(uri: Uri) {
        _state.update { it.copy(loading = true) }
        io {
            try {
                val created = onDisk {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选运行时压缩包")
                    // `use {}` 保证 SAF 描述符一定被释放，导入失败也不泄漏 fd。
                    input.use { app.runtimeSetupManager.importRuntimeArchive(it) }
                }
                inspectRuntime()
                toast(
                    text = if (created.isEmpty()) {
                        "压缩包内没有可识别的运行时组件"
                    } else {
                        "已导入 ${created.size} 个运行时组件"
                    },
                    isError = created.isEmpty()
                )
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** 导入单个 QEMU 可执行文件或 UEFI 固件；[targetName] 必须在白名单内。 */
    fun importQemuBinary(uri: Uri, targetName: String) {
        val name = targetName.trim()
        if (name !in RuntimeSetupManager.ALLOWED_NAMES) {
            toast("不支持的运行时文件名：${name.ifBlank { "（空）" }}", isError = true)
            return
        }
        _state.update { it.copy(loading = true) }
        io {
            try {
                val target = onDisk {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    input.use { app.runtimeSetupManager.importBinary(it, name) }
                }
                inspectRuntime()
                toast("已导入 ${target.name}")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    // --------------------------------------------------------- 镜像下载

    /** 开始（或续传）下载目录镜像，完成后自动登记进镜像库。 */
    fun startRomDownload(item: RomCatalogItem) {
        app.romDownloader.startDownload(item, app.romRepository)
    }

    /** 取消下载并丢弃未完成的残片。 */
    fun cancelRomDownload(catalogId: String) {
        app.romDownloader.cancel(catalogId)
    }

    /** 下载完成时把新镜像并入 UI 列表，用户无需再手动导入。 */
    private suspend fun trackRomDownloads() {
        val handled = mutableSetOf<String>()
        app.romDownloader.downloads.collect { downloads ->
            val finished = downloads
                .filterValues { it.status == DownloadStatus.COMPLETED }
                .keys
                .filterNot { it in handled }
            if (finished.isEmpty()) return@collect
            handled += finished
            refreshImportedRoms()
        }
    }

    private suspend fun refreshImportedRoms() {
        val roms = onDisk { app.romRepository.imported() }
        val probes = onDisk {
            roms.associate { rom -> rom.id to NativeEngine.probeDiskImage(rom.absolutePath) }
        }
        _state.update { it.copy(importedRoms = roms, romProbes = probes) }
    }

    // --------------------------------------------------------- 端口转发

    /** 新增一条端口转发规则；同名 id 视为覆盖并重新启用。 */
    fun addPortForward(vmId: String, rule: PortForwardRule) {
        val invalid = rule.validationMessage()
        if (invalid != null) {
            toast(invalid, isError = true)
            return
        }
        mutatePortForwards(vmId, "已添加端口转发：${rule.label}") { rules ->
            if (rules.any { it.id == rule.id }) {
                rules.map { existing ->
                    if (existing.id == rule.id) rule.copy(enabled = true) else existing
                }
            } else {
                rules + rule
            }
        }
    }

    fun removePortForward(vmId: String, ruleId: String) {
        mutatePortForwards(vmId, "已删除端口转发") { rules ->
            rules.filterNot { it.id == ruleId }
        }
    }

    fun togglePortForward(vmId: String, ruleId: String) {
        mutatePortForwards(vmId, "端口转发状态已更新") { rules ->
            rules.map { rule ->
                if (rule.id == ruleId) rule.copy(enabled = !rule.enabled) else rule
            }
        }
    }

    /** 统一的规则改写入口：落盘、刷新列表与选中态。 */
    private fun mutatePortForwards(
        vmId: String,
        message: String,
        transform: (List<PortForwardRule>) -> List<PortForwardRule>
    ) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val updated = profile.copy(portForwards = transform(profile.portForwards))
            onDisk { app.vmRepository.update(updated) }
            refresh()
            if (_state.value.selectedVmId == vmId) reloadSelected()
            toast(message)
        }
    }

    // ------------------------------------------------- 端口转发 / QMP 控制台

    /** 自定义规则使用的短 id（预设规则用 [com.novavm.app.data.PortForwardPresets] 的固定 id）。 */
    fun newRuleId(): String = UUID.randomUUID().toString().take(8)

    /**
     * 执行一条 HMP 指令。
     *
     * 有真实 QEMU 进程时走 QMP 通道，否则 [QmpTerminalController] 返回带离线标记
     * 的诊断；两种结果都会追加到 [qmpHistory]，供终端面板滚动查看。
     */
    fun executeQmpCommand(vmId: String, rawCommand: String) {
        io {
            val runtime = app.vmInstanceManager.state(vmId)
            val live = runtime != null && runtime.isRunning && runtime.pid > 0
            val qmpPort = onDisk { app.vmRepository.get(vmId)?.qmpPort }
                ?: runtime?.qmpPort
                ?: VmRepository.DEFAULT_QMP_PORT
            val entry = onDisk {
                app.qmpTerminalController.executeCommand(
                    qmpPort = qmpPort,
                    rawCommand = rawCommand,
                    isVmRunningWithQemu = live
                )
            }
            _qmpHistory.update { history ->
                val existing = history[vmId].orEmpty()
                history + (vmId to (existing + entry).takeLast(QMP_HISTORY_LIMIT))
            }
        }
    }

    // --------------------------------------------------------- 存储遥测

    /** 重新统计虚拟机目录 / 磁盘镜像 / 共享目录 / 模块目录与快照数量。 */
    fun refreshStorageStats(vmId: String) {
        io {
            val profile = onDisk { app.vmRepository.get(vmId) }
            if (profile == null) {
                toast("找不到该虚拟机", isError = true)
                return@io
            }
            val stats = onDisk {
                VmTelemetryMonitor.inspectVmStorage(
                    vmDir = app.vmRepository.vmDir(vmId),
                    diskImagePath = profile.diskImagePath,
                    snapshotCount = profile.snapshots.size
                )
            }
            _storageStats.update { it + (vmId to stats) }
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
            try {
                val name = displayName(uri)
                val target = onDisk {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    input.use { app.transferManagerFor(vmId).importFile(it, name) }
                }
                reloadSelected()
                toast("已导入到共享目录：${target.name}")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun pushApk(vmId: String, uri: Uri) {
        _state.update { it.copy(loading = true) }
        io {
            try {
                val name = displayName(uri)
                val staged = onDisk {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    input.use { app.transferManagerFor(vmId).stageApk(it, name) }
                }
                reloadSelected()
                toast("APK 已推送：${staged.name}")
            } finally {
                _state.update { it.copy(loading = false) }
            }
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
                input.use { configurator.stageModule(it, name) }
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
