package com.novavm.app.engine

import com.novavm.app.data.GpuMode
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmProfile
import java.io.File
import java.util.Locale

/**
 * Builds a complete QEMU system-emulator command line for a VM profile.
 *
 * The generated argv is deliberately deterministic so it can be asserted in
 * unit tests and logged verbatim when a launch fails.
 */
class QemuBackend(private val logger: (String) -> Unit = {}) : VmBackend {

    override val backendName: String = "QEMU"

    override val backendType: VmBackendType = VmBackendType.QEMU

    override fun isSupported(capabilities: HostCapabilities): Boolean = true

    override fun buildLaunchCommand(
        profile: VmProfile,
        capabilities: HostCapabilities,
        sharedDir: File?,
        logDir: File?,
        binaryPath: String?
    ): List<String> {
        val arch = profile.architecture
        val hardware = profile.hardware
        val args = mutableListOf<String>()

        args += binaryPath ?: arch.qemuSystemBinary

        // Machine & acceleration.
        args += listOf("-machine", arch.qemuMachine)
        if (capabilities.kvmAvailable) {
            args += listOf("-accel", "kvm", "-cpu", "host")
        } else {
            args += listOf("-accel", "tcg,thread=multi", "-cpu", "max")
        }

        // Compute & memory.
        args += listOf("-smp", hardware.cpuCores.toString())
        args += listOf("-m", "${hardware.ramMb}M")
        args += listOf("-name", escapeValue(profile.displayName))

        // Display: headless host, RFB clients attach over VNC.
        args += listOf("-display", "none")
        args += gpuArguments(hardware.gpuMode)
        args += listOf("-vnc", "127.0.0.1:${vncDisplayNumber(profile.vncPort)}")

        // QMP control channel.
        args += listOf(
            "-qmp",
            "tcp:127.0.0.1:${profile.qmpPort},server=on,wait=off"
        )

        // Bootable kernel, when the profile points at one. The kernel command
        // line is passed through verbatim: commas are literal here.
        if (profile.romPath.isNotBlank()) {
            args += listOf("-kernel", escapeValue(profile.romPath))
            args += listOf("-append", buildKernelCmdline(profile))
        }

        // Root disk. `if=virtio` makes QEMU instantiate the matching
        // virtio-blk-pci device, so no explicit -device is required.
        if (profile.diskImagePath.isNotBlank()) {
            val drive = buildString {
                append("file=").append(escapeValue(profile.diskImagePath))
                append(",if=virtio")
                append(",format=").append(diskFormatFor(profile.diskImagePath))
                append(",cache=writeback")
            }
            args += listOf("-drive", drive)
        }

        // User-mode networking with an ADB forward to the guest's 5555.
        args += listOf(
            "-netdev",
            "user,id=net0,hostfwd=tcp:127.0.0.1:${profile.adbForwardPort}-:5555"
        )
        args += listOf("-device", "virtio-net-pci,netdev=net0")

        // Shared folder over VirtIO-9p.
        if (sharedDir != null) {
            args += listOf(
                "-fsdev",
                "local,id=fsdev0,path=${escapeValue(sharedDir.absolutePath)},security_model=none"
            )
            args += listOf("-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=novavm_share")
        }

        // SMBIOS identity spoofing.
        if (profile.deviceSpoof.enabled) {
            val smbios = buildString {
                append("type=1")
                append(",manufacturer=").append(escapeValue(profile.deviceSpoof.manufacturer))
                append(",product=").append(escapeValue(profile.deviceSpoof.model))
                append(",version=").append(escapeValue(profile.deviceSpoof.brand))
                append(",serial=").append(escapeValue(profile.deviceSpoof.imei.ifBlank { "NOVAVM0001" }))
            }
            args += listOf("-smbios", smbios)
        }

        // Serial console log for the in-app console viewer.
        if (logDir != null) {
            args += listOf(
                "-serial",
                "file:${escapeValue(File(logDir, "console.log").absolutePath)}"
            )
        }

        args += listOf("-rtc", "base=utc")
        args += listOf("-no-reboot")

        // Virtual location status is logged before every launch so the console
        // shows whether a mock GPS fix will reach the guest.
        val gps = profile.virtualLocation
        if (gps.enabled) {
            logger(
                "virtual GPS enabled (${gps.mode.name.lowercase(Locale.US)}): " +
                    "${gps.presetName} ${coordinate(gps.latitude)}, ${coordinate(gps.longitude)} " +
                    "alt ${coordinate(gps.altitude)}m"
            )
        } else {
            logger("virtual GPS disabled; guest keeps its real location provider")
        }

        return args
    }

    /** androidboot/novavm kernel arguments for the mock GPS contract. */
    fun gpsKernelArgs(profile: VmProfile): List<String> {
        val gps = profile.virtualLocation
        if (!gps.enabled) return emptyList()
        return listOf(
            "androidboot.novavm.gps=1",
            "novavm.gps.lat=${coordinate(gps.latitude)}",
            "novavm.gps.lon=${coordinate(gps.longitude)}",
            "novavm.gps.alt=${coordinate(gps.altitude)}"
        )
    }

    /** `-device` arguments matching the requested GPU emulation mode. */
    fun gpuArguments(mode: GpuMode): List<String> = when (mode) {
        GpuMode.SOFTWARE_RAMFB -> listOf("-device", "ramfb")
        GpuMode.VIRTIO_GPU -> listOf("-device", "virtio-gpu-pci")
        GpuMode.VIRGL_OPENGL -> listOf("-device", "virtio-gpu-gl-pci")
    }

    /**
     * QEMU's `-vnc host:display` uses a display number, not a TCP port:
     * 5901 -> :1.
     */
    fun vncDisplayNumber(vncPort: Int): Int = when {
        vncPort > 5900 -> vncPort - 5900
        vncPort > 0 -> vncPort
        else -> 1
    }

    /**
     * Detects the `-drive format=` value for [path].
     *
     * A file that already exists wins over the file name: the QCOW2 magic is
     * authoritative, because a `disk.qcow2` placeholder that was never converted
     * is really a raw image and QEMU refuses to open it as QCOW2. The extension
     * is only a hint for images that do not exist on disk yet.
     */
    fun diskFormatFor(path: String): String {
        if (path.isBlank()) return "raw"
        val file = File(path)
        if (file.isFile) {
            when (NativeEngine.probeDiskImage(path).format.lowercase(Locale.US)) {
                "qcow2" -> return "qcow2"
                "raw" -> return "raw"
                // "missing"/unknown: fall through to the extension heuristic.
            }
        }
        return if (path.lowercase(Locale.US).endsWith(".qcow2")) "qcow2" else "raw"
    }

    /** Guest kernel command line, including NovaVM's androidboot contracts. */
    fun buildKernelCmdline(profile: VmProfile): String {
        val arch = profile.architecture
        val hardware = profile.hardware
        val rootXposed = profile.rootXposed
        val console = if (arch == VmArchitecture.ARM64) "ttyAMA0" else "ttyS0"

        return buildList {
            add("console=$console,115200")
            add("earlycon")
            add("androidboot.hardware=novavm")
            add("androidboot.serialno=${profile.deviceSpoof.imei.ifBlank { "NOVAVM0001" }}")
            add("androidboot.novavm.root=${if (rootXposed.rootEnabled) 1 else 0}")
            add("androidboot.novavm.xposed=${if (rootXposed.xposedEnabled) 1 else 0}")
            if (rootXposed.selinuxPermissive) {
                add("androidboot.selinux=permissive")
            }
            add("androidboot.novavm.width=${hardware.width}")
            add("androidboot.novavm.height=${hardware.height}")
            add("androidboot.novavm.dpi=${hardware.dpi}")
            add("androidboot.novavm.fps=${hardware.targetFps}")
            add("novavm.gpu=${hardware.gpuMode.name.lowercase()}")
            addAll(gpsKernelArgs(profile))
            add("qemu=1")
        }.joinToString(" ")
    }

    /** Locale-stable fixed-point rendering that never emits a comma. */
    fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    /**
     * QEMU option values split on commas, so literal commas are doubled.
     */
    fun escapeValue(value: String): String = value.replace(",", ",,")
}
