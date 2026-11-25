package com.novavm.app.engine

import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmProfile
import java.io.File

/**
 * A hypervisor backend able to turn a [VmProfile] into a concrete launch
 * command (or launch descriptor).
 */
interface VmBackend {

    /** Stable identifier shown in the UI and logs, e.g. "QEMU" or "AVF". */
    val backendName: String

    /** Which persisted backend type this implementation serves. */
    val backendType: VmBackendType

    /** Whether this host can run the backend at all. */
    fun isSupported(capabilities: HostCapabilities): Boolean

    /**
     * Builds the full argv (element 0 is the executable) for the guest.
     *
     * @param sharedDir host directory exported to the guest over VirtIO-9p.
     * @param logDir directory receiving serial console logs.
     * @param binaryPath optional override for the emulator binary.
     */
    fun buildLaunchCommand(
        profile: VmProfile,
        capabilities: HostCapabilities,
        sharedDir: File? = null,
        logDir: File? = null,
        binaryPath: String? = null
    ): List<String>
}
