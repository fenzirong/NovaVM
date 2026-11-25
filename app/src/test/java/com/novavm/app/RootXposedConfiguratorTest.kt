package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.root.RootXposedConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootXposedConfiguratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var vmDir: File
    private lateinit var configurator: RootXposedConfigurator

    private val hardware = VmHardwareConfig(width = 1080, height = 2400, dpi = 420)

    @Before
    fun setUp() {
        vmDir = temp.newFolder("vm-under-test")
        configurator = RootXposedConfigurator(vmDir)
    }

    @Test
    fun `everything enabled emits spoof root xposed and SELinux directives`() {
        val config = RootXposedConfig(
            rootEnabled = true,
            xposedEnabled = true,
            selinuxPermissive = true,
            magiskHideEnabled = true
        )
        val spoof = DevicePresets.galaxyS24Ultra.copy(
            imei = "490154203237518",
            androidId = "0123456789abcdef",
            wifiMac = "02:11:22:33:44:55"
        )

        val script = configurator.buildBootScript(config, spoof, hardware, "Galaxy VM")

        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("Profile: Galaxy VM"))
        assertTrue(script.contains("resetprop ro.product.brand \"samsung\""))
        assertTrue(script.contains("resetprop ro.product.manufacturer \"samsung\""))
        assertTrue(script.contains("resetprop ro.product.model \"SM-S928B\""))
        assertTrue(script.contains("resetprop ro.product.board \"e3q\""))
        assertTrue(script.contains("resetprop ro.build.fingerprint"))
        assertTrue(script.contains("resetprop ro.ril.oem.imei \"490154203237518\""))
        assertTrue(script.contains("resetprop persist.novavm.android_id \"0123456789abcdef\""))
        assertTrue(script.contains("resetprop persist.novavm.wifi_mac \"02:11:22:33:44:55\""))
        assertTrue(script.contains("resetprop ro.sf.lcd_density 420"))
        assertTrue(script.contains("wm density 420"))
        assertTrue(script.contains("/system/bin/magiskd --daemon"))
        assertTrue(script.contains("/system/bin/su --daemon"))
        assertTrue(script.contains("magiskhide enable"))
        assertTrue(script.contains("resetprop persist.novavm.magisk_hide 1"))
        assertTrue(script.contains("magisk --install-module"))
        assertTrue(script.contains("setenforce 0"))
        assertTrue(script.contains("resetprop ro.boot.selinux permissive"))

        val initRc = configurator.buildInitRc(config, spoof, hardware, "Galaxy VM")
        assertTrue(initRc.contains("service novavm_boot /system/bin/sh"))
        assertTrue(initRc.contains("seclabel u:r:magisk:s0"))
        assertTrue(initRc.contains("setprop ro.boot.selinux permissive"))
        assertTrue(initRc.contains("setprop persist.novavm.root 1"))
        assertTrue(initRc.contains("setprop persist.novavm.xposed 1"))
        assertTrue(initRc.contains("write /sys/fs/selinux/enforce 0"))
        assertTrue(initRc.contains("start novavm_boot"))
    }

    @Test
    fun `all toggles off emits no privileged directives`() {
        val spoof = DevicePresets.pixel8Pro.copy(enabled = false, imei = "490154203237518")
        val script = configurator.buildBootScript(RootXposedConfig(), spoof, hardware)
        val initRc = configurator.buildInitRc(RootXposedConfig(), spoof, hardware)

        assertFalse(script.contains("resetprop"))
        assertFalse(script.contains("magiskd --daemon"))
        assertFalse(script.contains("su --daemon"))
        assertFalse(script.contains("magisk --install-module"))
        assertFalse(script.contains("setenforce 0"))
        assertFalse(script.contains("magiskhide enable"))
        assertTrue(script.contains("Device identity spoofing disabled"))
        assertTrue(script.contains("SELinux remains enforcing"))

        assertFalse(initRc.contains("seclabel u:r:magisk:s0"))
        assertFalse(initRc.contains("persist.novavm.root"))
        assertFalse(initRc.contains("persist.novavm.xposed"))
        assertTrue(initRc.contains("setprop ro.boot.selinux enforcing"))
    }

    @Test
    fun `root alone does not stage xposed modules`() {
        val script = configurator.buildBootScript(
            RootXposedConfig(rootEnabled = true, magiskHideEnabled = true),
            DevicePresets.pixel8Pro,
            hardware
        )
        assertTrue(script.contains("magiskd --daemon"))
        assertFalse(script.contains("magisk --install-module"))
        assertFalse(script.contains("persist.novavm.xposed"))
    }

    @Test
    fun `xposed alone still stages modules without root daemons`() {
        val script = configurator.buildBootScript(
            RootXposedConfig(xposedEnabled = true),
            DevicePresets.pixel8Pro,
            hardware
        )
        assertTrue(script.contains("magisk --install-module"))
        assertTrue(script.contains("persist.novavm.xposed 1"))
        assertFalse(script.contains("su --daemon"))
        assertFalse(script.contains("magiskd --daemon"))
    }

    @Test
    fun `writeAll persists both scripts in the shared folder`() {
        val files = configurator.writeAll(
            RootXposedConfig(rootEnabled = true),
            DevicePresets.pixel8Pro,
            hardware,
            "Written VM"
        )
        assertEquals(2, files.size)
        assertTrue(File(vmDir, "shared/novavm_boot.sh").isFile)
        assertTrue(File(vmDir, "shared/init.novavm.rc").isFile)
        assertTrue(files[0].readText().contains("Written VM"))
        assertTrue(files[1].readText().contains("Written VM"))
    }

    @Test
    fun `quotes in spoof values are escaped`() {
        val spoof = DevicePresets.pixel8Pro.copy(model = "Pixel \"8\" Pro")
        val script = configurator.buildBootScript(RootXposedConfig(), spoof, hardware)
        assertTrue(script.contains("resetprop ro.product.model \"Pixel \\\"8\\\" Pro\""))
    }

    @Test
    fun `module staging lists and removes files`() {
        configurator.stageModule("lsposed.zip", "zip-bytes".toByteArray())
        configurator.stageModule("../escape.zip", "x".toByteArray())

        assertEquals(listOf("escape.zip", "lsposed.zip"), configurator.listModules())
        assertNotNull(configurator.findModule("lsposed.zip"))
        assertNull(configurator.findModule("missing.zip"))
        assertTrue(File(vmDir, "modules/lsposed.zip").isFile)
        assertFalse("path traversal must be sanitised", File(vmDir.parentFile, "escape.zip").exists())

        assertTrue(configurator.removeModule("lsposed.zip"))
        assertFalse(configurator.removeModule("lsposed.zip"))
        assertEquals(listOf("escape.zip"), configurator.listModules())
    }

    @Test
    fun `syncStagedModules removes unlisted module files`() {
        configurator.stageModule("keep.zip", "a".toByteArray())
        configurator.stageModule("drop.zip", "b".toByteArray())

        val removed = configurator.syncStagedModules(
            RootXposedConfig(xposedEnabled = true, stagedModules = listOf("keep.zip"))
        )
        assertEquals(listOf("drop.zip"), removed)
        assertEquals(listOf("keep.zip"), configurator.listModules())
    }
}
