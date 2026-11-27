package com.novavm.app.engine

import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Result of probing the platform's AVF/pKVM stack. */
data class AvfProbeResult(
    val available: Boolean,
    val apiLevel: Int,
    val virtualMachineManagerPresent: Boolean,
    val detail: String
)

/** Payload handed to the AVF/VirtualMachineManager layer. */
@Serializable
data class AvfLaunchDescriptor(
    val backend: String = "AVF",
    val vmName: String,
    val architecture: String,
    val cpuCores: Int,
    val memoryMb: Int,
    val diskImagePath: String,
    val kernelPath: String? = null,
    val kernelCommandLine: String? = null,
    val gpuMode: String,
    val vsockCid: Int = 3,
    val protectedVm: Boolean = true,
    val vncPort: Int,
    val qmpPort: Int
)

/**
 * Android Virtualization Framework backend.
 *
 * Batch 1 intentionally does not link `android.system.virtualmachine`: the
 * classes only exist on Android 13+ devices shipping pKVM, so they are probed
 * reflectively. When the stack is missing, [buildLaunchCommand] transparently
 * falls back to [QemuBackend] and [probe] explains why.
 *
 * The class is `open` so tests can substitute [virtualMachineManagerClass] and
 * exercise the AVF-usable branches on a plain JVM.
 */
open class AvfBackend(
    private val fallback: QemuBackend = QemuBackend()
) : VmBackend {

    override val backendName: String = "AVF"

    override val backendType: VmBackendType = VmBackendType.AVF

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    companion object {
        const val VIRTUAL_MACHINE_MANAGER = "android.system.virtualmachine.VirtualMachineManager"
        const val VIRTUAL_MACHINE = "android.system.virtualmachine.VirtualMachine"
        const val MIN_API_LEVEL = 33
    }

    /** Looks up the platform class without a compile-time dependency. */
    open fun virtualMachineManagerClass(): Class<*>? =
        runCatching { Class.forName(VIRTUAL_MACHINE_MANAGER, false, javaClass.classLoader) }
            .getOrNull()

    fun probe(capabilities: HostCapabilities): AvfProbeResult {
        val managerPresent = virtualMachineManagerClass() != null
        val apiOk = capabilities.sdkInt >= MIN_API_LEVEL
        val available = apiOk && capabilities.avfSupported && managerPresent
        val detail = when {
            !apiOk -> "AVF requires Android 13 (API 33); host is API ${capabilities.sdkInt}"
            !managerPresent -> "$VIRTUAL_MACHINE_MANAGER not present in this framework"
            !capabilities.avfSupported -> "No pKVM/devicetree virtualization support detected"
            else -> "AVF/pKVM available"
        }
        return AvfProbeResult(
            available = available,
            apiLevel = capabilities.sdkInt,
            virtualMachineManagerPresent = managerPresent,
            detail = detail
        )
    }

    override fun isSupported(capabilities: HostCapabilities): Boolean = probe(capabilities).available

    fun buildLaunchDescriptor(profile: VmProfile, capabilities: HostCapabilities): AvfLaunchDescriptor =
        AvfLaunchDescriptor(
            vmName = profile.displayName,
            architecture = profile.architecture.name,
            cpuCores = profile.hardware.cpuCores.coerceAtMost(capabilities.effectiveCores),
            memoryMb = profile.hardware.ramMb,
            diskImagePath = profile.diskImagePath,
            kernelPath = profile.romPath.ifBlank { null },
            kernelCommandLine = fallback.buildKernelCmdline(profile),
            gpuMode = profile.hardware.gpuMode.name,
            vsockCid = 3 + (profile.qmpPort % 100),
            protectedVm = capabilities.kvmAvailable,
            vncPort = profile.vncPort,
            qmpPort = profile.qmpPort
        )

    fun descriptorJson(profile: VmProfile, capabilities: HostCapabilities): String =
        json.encodeToString(buildLaunchDescriptor(profile, capabilities))

    /**
     * Emits the QEMU argv whenever QEMU can actually execute the guest.
     *
     * AVF does not spawn a guest process yet, so an `avf://launch` descriptor
     * would be unexecutable; a present qemu-system binary always wins. The
     * `avf://` stub is only produced when QEMU is unavailable *and* the AVF
     * stack is usable.
     */
    override fun buildLaunchCommand(
        profile: VmProfile,
        capabilities: HostCapabilities,
        sharedDir: File?,
        logDir: File?,
        binaryPath: String?
    ): List<String> {
        if (!isSupported(capabilities) || capabilities.qemuBinaryPresent) {
            return fallback.buildLaunchCommand(profile, capabilities, sharedDir, logDir, binaryPath)
        }
        return listOf(
            "avf://launch",
            "--descriptor",
            descriptorJson(profile, capabilities),
            "--shared-dir",
            sharedDir?.absolutePath.orEmpty()
        )
    }

    /** Human-readable explanation of why QEMU is used instead. */
    fun fallbackNotice(capabilities: HostCapabilities): String? = when {
        !isSupported(capabilities) ->
            "${probe(capabilities).detail}; falling back to ${fallback.backendName}"

        capabilities.qemuBinaryPresent ->
            "AVF cannot execute a guest process yet; launching through ${fallback.backendName}"

        else -> null
    }
}
