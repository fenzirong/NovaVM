package com.novavm.app

import android.app.Application
import android.content.Context
import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofGenerator
import com.novavm.app.data.RomRepository
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.VmInstanceManager
import com.novavm.app.location.VirtualGpsEngine
import com.novavm.app.root.RootXposedConfigurator
import com.novavm.app.transfer.HostGuestTransferManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide dependency container for NovaVM.
 *
 * The repositories are plain, Android-free classes (see Batch 1): the app only
 * supplies their private sandbox directory. Per-VM collaborators
 * ([HostGuestTransferManager], [RootXposedConfigurator]) are cached by VM id and
 * additionally exposed for the "active" VM so services and activities that do
 * not care about ids can grab them directly.
 */
class NovaVmApp : Application() {

    val storageRoot: File get() = File(filesDir, "novavm")

    val hostCapabilities: HostCapabilities by lazy {
        runCatching { HostCapabilities.detect() }.getOrElse { HostCapabilities.conservative() }
    }

    val vmRepository: VmRepository by lazy { VmRepository(storageRoot) }

    val romRepository: RomRepository by lazy { RomRepository(storageRoot) }

    /** Mock GPS renderer shared by the ViewModel, the display HUD and tests. */
    val virtualGpsEngine: VirtualGpsEngine by lazy { VirtualGpsEngine() }

    val vmInstanceManager: VmInstanceManager by lazy {
        VmInstanceManager(
            repository = vmRepository,
            capabilitiesProvider = { hostCapabilities },
            virtualGps = virtualGpsEngine
        ).also { manager ->
            runCatching { manager.refresh() }
        }
    }

    private val transferManagers = ConcurrentHashMap<String, HostGuestTransferManager>()
    private val rootConfigurators = ConcurrentHashMap<String, RootXposedConfigurator>()

    @Volatile
    private var activeVmId: String? = null

    /** Manager bound to the most recently opened VM (or the first profile). */
    val transferManager: HostGuestTransferManager
        get() = transferManagerFor(resolveActiveVmId())

    /** Configurator bound to the most recently opened VM (or the first profile). */
    val rootXposedConfigurator: RootXposedConfigurator
        get() = rootXposedConfiguratorFor(resolveActiveVmId())

    fun transferManagerFor(vmId: String): HostGuestTransferManager =
        transferManagers.getOrPut(vmId) { HostGuestTransferManager(vmRepository, vmId) }

    fun rootXposedConfiguratorFor(vmId: String): RootXposedConfigurator =
        rootConfigurators.getOrPut(vmId) { RootXposedConfigurator(vmRepository, vmId) }

    fun setActiveVm(vmId: String?) {
        activeVmId = vmId
    }

    private fun resolveActiveVmId(): String =
        activeVmId
            ?: vmRepository.loadAll().firstOrNull()?.id
            ?: FALLBACK_VM_ID

    /** One-tap default profile used by the empty state on the instances tab. */
    fun createDefaultVm(name: String = "NovaVM Pixel"): VmProfile {
        val capabilities = hostCapabilities
        val architecture =
            if (capabilities.primaryAbi.equals("x86_64", ignoreCase = true)) {
                VmArchitecture.X86_64
            } else {
                VmArchitecture.ARM64
            }
        val hardware = VmHardwareConfig(
            cpuCores = capabilities.cpuCores.coerceIn(1, 8).coerceAtLeast(2),
            ramMb = capabilities.recommendedRamMb.coerceIn(1024, 8192),
            diskSizeGb = 16,
            width = 720,
            height = 1280,
            dpi = 320,
            targetFps = 60
        )
        val created = vmInstanceManager.createProfile(
            name = name,
            architecture = architecture,
            backendType = if (capabilities.avfUsable) VmBackendType.AVF else VmBackendType.AUTO,
            hardware = hardware,
            deviceSpoof = DeviceSpoofGenerator.forPreset(DevicePresets.pixel8Pro),
            rootXposed = RootXposedConfig(rootEnabled = true)
        )
        setActiveVm(created.id)
        return created
    }

    companion object {
        private const val FALLBACK_VM_ID = "novavm-preview"

        fun from(context: Context): NovaVmApp =
            context.applicationContext as NovaVmApp
    }
}
