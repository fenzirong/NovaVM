package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.GpuMode
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.QemuBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QemuBackendTest {

    private val backend = QemuBackend()

    private fun capabilities(kvm: Boolean = true, qemuPresent: Boolean = true) = HostCapabilities(
        kvmAvailable = kvm,
        vhostVsockAvailable = true,
        avfSupported = false,
        cpuCores = 8,
        totalRamMb = 8192,
        primaryAbi = "arm64-v8a",
        qemuBinaryPresent = qemuPresent,
        sdkInt = 34
    )

    private fun profile(
        architecture: VmArchitecture = VmArchitecture.ARM64,
        gpuMode: GpuMode = GpuMode.VIRTIO_GPU,
        spoof: DeviceSpoofConfig = DevicePresets.pixel8Pro,
        root: RootXposedConfig = RootXposedConfig(rootEnabled = true, xposedEnabled = true),
        romPath: String = "",
        diskPath: String = "/data/user/0/com.novavm.app/files/vms/vm1/disk.qcow2"
    ) = VmProfile.create(
        id = "vm1",
        name = "Test VM",
        architecture = architecture,
        backendType = VmBackendType.QEMU,
        romPath = romPath,
        diskImagePath = diskPath,
        hardware = VmHardwareConfig(
            cpuCores = 4,
            ramMb = 2048,
            diskSizeGb = 16,
            width = 1080,
            height = 2400,
            dpi = 420,
            gpuMode = gpuMode
        ),
        deviceSpoof = spoof,
        rootXposed = root,
        vncPort = 5901,
        qmpPort = 4444,
        adbForwardPort = 15555
    )

    private fun valueOf(command: List<String>, flag: String): String {
        val index = command.indexOf(flag)
        assertTrue("missing $flag in $command", index >= 0)
        assertTrue("missing value after $flag", index + 1 < command.size)
        return command[index + 1]
    }

    @Test
    fun `ARM64 KVM command uses virt machine and host acceleration`() {
        val command = backend.buildLaunchCommand(profile(), capabilities(kvm = true))

        assertEquals("qemu-system-aarch64", command.first())
        assertEquals("virt", valueOf(command, "-machine"))
        assertEquals("kvm", valueOf(command, "-accel"))
        assertEquals("host", valueOf(command, "-cpu"))
        assertEquals("4", valueOf(command, "-smp"))
        assertEquals("2048M", valueOf(command, "-m"))
        assertTrue(command.contains("-enable-kvm").not())
    }

    @Test
    fun `x86_64 without KVM falls back to multi-threaded TCG on q35`() {
        val command = backend.buildLaunchCommand(
            profile(architecture = VmArchitecture.X86_64),
            capabilities(kvm = false)
        )

        assertEquals("qemu-system-x86_64", command.first())
        assertEquals("q35", valueOf(command, "-machine"))
        assertEquals("tcg,thread=multi", valueOf(command, "-accel"))
        assertEquals("max", valueOf(command, "-cpu"))
    }

    @Test
    fun `GPU mode selects the matching virtio device`() {
        val virtio = backend.buildLaunchCommand(profile(gpuMode = GpuMode.VIRTIO_GPU), capabilities())
        assertTrue(virtio.contains("virtio-gpu-pci"))
        assertFalse(virtio.contains("virtio-gpu-gl-pci"))
        assertFalse(virtio.contains("ramfb"))

        val virgl = backend.buildLaunchCommand(profile(gpuMode = GpuMode.VIRGL_OPENGL), capabilities())
        assertTrue(virgl.contains("virtio-gpu-gl-pci"))

        val ramfb = backend.buildLaunchCommand(profile(gpuMode = GpuMode.SOFTWARE_RAMFB), capabilities())
        assertTrue(ramfb.contains("ramfb"))
        assertFalse(ramfb.contains("virtio-gpu-pci"))
    }

    @Test
    fun `VNC QMP and ADB ports are wired to loopback`() {
        val command = backend.buildLaunchCommand(profile(), capabilities())
        assertEquals("127.0.0.1:1", valueOf(command, "-vnc"))
        assertEquals(
            "tcp:127.0.0.1:4444,server=on,wait=off",
            valueOf(command, "-qmp")
        )
        assertEquals(
            "user,id=net0,hostfwd=tcp:127.0.0.1:15555-:5555",
            valueOf(command, "-netdev")
        )
        assertTrue(command.contains("virtio-net-pci,netdev=net0"))
    }

    @Test
    fun `disk image is attached over virtio with detected format`() {
        val qcow = backend.buildLaunchCommand(profile(), capabilities())
        val qcowDrive = valueOf(qcow, "-drive")
        assertTrue(qcowDrive.startsWith("file=/data/user/0/com.novavm.app/files/vms/vm1/disk.qcow2"))
        assertTrue(qcowDrive.contains("if=virtio"))
        assertTrue(qcowDrive.contains("format=qcow2"))

        val raw = backend.buildLaunchCommand(profile(diskPath = "/tmp/disk.img"), capabilities())
        assertTrue(valueOf(raw, "-drive").contains("format=raw"))
        assertEquals("qcow2", backend.diskFormatFor("/x/disk.QCOW2"))
        assertEquals("raw", backend.diskFormatFor("/x/disk.img"))
    }

    @Test
    fun `shared folder is exported over virtio 9p`() {
        val shared = File(System.getProperty("java.io.tmpdir"), "novavm-shared")
        val command = backend.buildLaunchCommand(profile(), capabilities(), sharedDir = shared)

        val fsdev = valueOf(command, "-fsdev")
        assertTrue(fsdev.contains("local,id=fsdev0"))
        assertTrue(fsdev.contains("path=${shared.absolutePath}"))
        assertTrue(fsdev.contains("security_model=none"))
        assertTrue(command.contains("virtio-9p-pci,fsdev=fsdev0,mount_tag=novavm_share"))
    }

    @Test
    fun `SMBIOS reflects the spoofed identity and can be disabled`() {
        val spoofed = profile(spoof = DevicePresets.galaxyS24Ultra.copy(imei = "490154203237518"))
        val smbios = valueOf(backend.buildLaunchCommand(spoofed, capabilities()), "-smbios")
        assertTrue(smbios.startsWith("type=1"))
        assertTrue(smbios.contains("manufacturer=samsung"))
        assertTrue(smbios.contains("product=SM-S928B"))
        assertTrue(smbios.contains("serial=490154203237518"))

        val disabled = backend.buildLaunchCommand(
            profile(spoof = DevicePresets.pixel8Pro.copy(enabled = false)),
            capabilities()
        )
        assertFalse(disabled.contains("-smbios"))
    }

    @Test
    fun `kernel arguments include androidboot contracts`() {
        val command = backend.buildLaunchCommand(
            profile(
                romPath = "/data/vms/vm1/kernel",
                root = RootXposedConfig(
                    rootEnabled = true,
                    xposedEnabled = true,
                    selinuxPermissive = true
                )
            ),
            capabilities()
        )

        assertEquals("/data/vms/vm1/kernel", valueOf(command, "-kernel"))
        val append = valueOf(command, "-append")
        assertTrue(append.contains("console=ttyAMA0,115200"))
        assertTrue(append.contains("androidboot.novavm.root=1"))
        assertTrue(append.contains("androidboot.novavm.xposed=1"))
        assertTrue(append.contains("androidboot.selinux=permissive"))
        assertTrue(append.contains("androidboot.novavm.width=1080"))
        assertTrue(append.contains("androidboot.novavm.height=2400"))
        assertTrue(append.contains("androidboot.novavm.dpi=420"))
        assertTrue(append.contains("qemu=1"))

        val x86 = backend.buildKernelCmdline(
            profile(architecture = VmArchitecture.X86_64, root = RootXposedConfig())
        )
        assertTrue(x86.contains("console=ttyS0,115200"))
        assertTrue(x86.contains("androidboot.novavm.root=0"))
        assertFalse(x86.contains("androidboot.selinux=permissive"))
    }

    @Test
    fun `no kernel arguments when ROM path is blank`() {
        val command = backend.buildLaunchCommand(profile(romPath = ""), capabilities())
        assertFalse(command.contains("-kernel"))
        assertFalse(command.contains("-append"))
    }

    @Test
    fun `VNC display numbers map from TCP ports`() {
        assertEquals(1, backend.vncDisplayNumber(5901))
        assertEquals(2, backend.vncDisplayNumber(5902))
        assertEquals(1, backend.vncDisplayNumber(1))
    }

    @Test
    fun `commas in paths are escaped for QEMU option lists`() {
        val command = backend.buildLaunchCommand(
            profile(diskPath = "/data/vms/vm,1/disk.qcow2"),
            capabilities()
        )
        assertTrue(valueOf(command, "-drive").contains("file=/data/vms/vm,,1/disk.qcow2"))
    }
}
