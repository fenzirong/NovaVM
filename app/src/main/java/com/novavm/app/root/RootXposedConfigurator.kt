package com.novavm.app.root

import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Generates the guest-side bootstrap that applies device spoofing, root and
 * Xposed/LSPosed configuration.
 *
 * Outputs:
 * - `shared/novavm_boot.sh`  – run as root inside the guest.
 * - `shared/init.novavm.rc`  – init service definition for the guest ramdisk.
 * - `modules/`               – Magisk/LSPosed module zips staged for install.
 */
class RootXposedConfigurator(private val vmDir: File) {

    constructor(repository: VmRepository, vmId: String) : this(repository.vmDir(vmId))

    val sharedDir: File = File(vmDir, "shared")
    val modulesDir: File = File(vmDir, "modules")
    val bootScriptFile: File = File(sharedDir, BOOT_SCRIPT_NAME)
    val initRcFile: File = File(sharedDir, INIT_RC_NAME)

    companion object {
        const val BOOT_SCRIPT_NAME = "novavm_boot.sh"
        const val INIT_RC_NAME = "init.novavm.rc"
        const val GUEST_STAGE_DIR = "/data/local/tmp/novavm"
        const val MODULE_MOUNT_DIR = "/data/local/tmp/novavm/modules"
    }

    fun ensureDirectories() {
        listOf(sharedDir, modulesDir).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Unable to create ${dir.absolutePath}")
            }
        }
    }

    // -------------------------------------------------------------- scripts

    /**
     * Full `novavm_boot.sh`. Only directives relevant to the enabled switches
     * are emitted, so a "clean" profile produces a script with no root or
     * spoofing side effects.
     */
    fun buildBootScript(
        config: RootXposedConfig,
        spoof: DeviceSpoofConfig,
        hardware: VmHardwareConfig = VmHardwareConfig(),
        profileName: String = "",
        virtualLocation: VirtualLocationConfig = VirtualLocationConfig()
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# ------------------------------------------------------------------")
        appendLine("# NovaVM guest bootstrap (generated, do not edit)")
        if (profileName.isNotBlank()) appendLine("# Profile: $profileName")
        appendLine("# Mount tag: novavm_share    Modules: $MODULE_MOUNT_DIR")
        appendLine("# ------------------------------------------------------------------")
        appendLine("log -t novavm \"novavm_boot.sh starting\"")
        appendLine("")

        if (spoof.enabled) {
            appendLine("# --- Device identity spoofing ---")
            appendLine("resetprop ro.product.brand \"${escape(spoof.brand)}\"")
            appendLine("resetprop ro.product.manufacturer \"${escape(spoof.manufacturer)}\"")
            appendLine("resetprop ro.product.model \"${escape(spoof.model)}\"")
            appendLine("resetprop ro.product.device \"${escape(spoof.device)}\"")
            appendLine("resetprop ro.product.board \"${escape(spoof.board)}\"")
            appendLine("resetprop ro.build.fingerprint \"${escape(spoof.buildFingerprint)}\"")
            appendLine("resetprop ro.product.name \"${escape(spoof.device)}\"")
            if (spoof.imei.isNotBlank()) {
                appendLine("resetprop ro.ril.oem.imei \"${escape(spoof.imei)}\"")
                appendLine("resetprop persist.novavm.imei \"${escape(spoof.imei)}\"")
            }
            if (spoof.androidId.isNotBlank()) {
                appendLine("resetprop persist.novavm.android_id \"${escape(spoof.androidId)}\"")
            }
            if (spoof.wifiMac.isNotBlank()) {
                appendLine("resetprop persist.novavm.wifi_mac \"${escape(spoof.wifiMac)}\"")
            }
            appendLine("resetprop ro.sf.lcd_density ${hardware.dpi}")
            appendLine("resetprop qemu.sf.lcd_density ${hardware.dpi}")
            appendLine("wm density ${hardware.dpi} >/dev/null 2>&1 || true")
            appendLine("wm size ${hardware.width}x${hardware.height} >/dev/null 2>&1 || true")
            appendLine("")
        } else {
            appendLine("# Device identity spoofing disabled; stock guest values are kept.")
            appendLine("")
        }

        if (config.rootEnabled) {
            appendLine("# --- Root / Magisk bootstrap ---")
            appendLine("resetprop ro.debuggable 1")
            appendLine("resetprop ro.secure 0")
            appendLine("if [ -x /system/bin/magiskd ]; then")
            appendLine("  /system/bin/magiskd --daemon &")
            appendLine("fi")
            appendLine("if [ -x /system/bin/su ]; then")
            appendLine("  /system/bin/su --daemon &")
            appendLine("fi")
            appendLine("mkdir -p $GUEST_STAGE_DIR")
            appendLine("chmod 0755 $GUEST_STAGE_DIR")
            appendLine("")
            if (config.magiskHideEnabled) {
                appendLine("# --- MagiskHide ---")
                appendLine("if command -v magiskhide >/dev/null 2>&1; then")
                appendLine("  magiskhide enable")
                appendLine("fi")
                appendLine("resetprop persist.novavm.magisk_hide 1")
                appendLine("")
            }
        } else {
            appendLine("# Root integration disabled; no su or Magisk daemon is started.")
            appendLine("")
        }

        if (config.xposedEnabled) {
            appendLine("# --- LSPosed / Xposed framework ---")
            appendLine("resetprop persist.novavm.xposed 1")
            appendLine("MODULE_DIR=\"\${NOVAVM_MODULES:-$MODULE_MOUNT_DIR}\"")
            appendLine("if command -v magisk >/dev/null 2>&1 && [ -d \"\$MODULE_DIR\" ]; then")
            appendLine("  for module in \"\$MODULE_DIR\"/*.zip; do")
            appendLine("    [ -e \"\$module\" ] || continue")
            appendLine("    log -t novavm \"installing module \$module\"")
            appendLine("    magisk --install-module \"\$module\" || log -t novavm \"module failed: \$module\"")
            appendLine("  done")
            appendLine("fi")
            appendLine("if [ -d /data/adb/lspd ]; then")
            appendLine("  resetprop persist.novavm.lsposed 1")
            appendLine("fi")
            appendLine("")
        } else {
            appendLine("# Xposed/LSPosed disabled; no framework hooks are staged.")
            appendLine("")
        }

        if (virtualLocation.enabled) {
            appendLine("# --- Virtual location (mock GPS) ---")
            appendLine("resetprop persist.novavm.gps.enabled 1")
            appendLine("resetprop persist.novavm.gps.lat \"${coordinate(virtualLocation.latitude)}\"")
            appendLine("resetprop persist.novavm.gps.lon \"${coordinate(virtualLocation.longitude)}\"")
            appendLine("resetprop persist.novavm.gps.alt \"${coordinate(virtualLocation.altitude)}\"")
            appendLine("resetprop persist.novavm.gps.mode \"${virtualLocation.mode.name.lowercase(Locale.US)}\"")
            appendLine("GPS_SCRIPT=\"$GUEST_STAGE_DIR/apply_location.sh\"")
            appendLine("[ -f \"\$GPS_SCRIPT\" ] || GPS_SCRIPT=\"/mnt/novavm_share/apply_location.sh\"")
            appendLine("if [ -f \"\$GPS_SCRIPT\" ]; then")
            appendLine("  /system/bin/sh \"\$GPS_SCRIPT\" || log -t novavm \"apply_location.sh failed\"")
            appendLine("else")
            appendLine("  log -t novavm \"apply_location.sh missing; NMEA not injected\"")
            appendLine("fi")
            appendLine("")
        } else {
            appendLine("# Virtual location disabled; no mock GPS properties are set.")
            appendLine("")
        }

        if (config.selinuxPermissive) {
            appendLine("# --- SELinux ---")
            appendLine("setenforce 0")
            appendLine("resetprop ro.boot.selinux permissive")
            appendLine("")
        } else {
            appendLine("# SELinux remains enforcing.")
            appendLine("")
        }

        appendLine("log -t novavm \"novavm_boot.sh complete\"")
        appendLine("exit 0")
    }

    /** Guest init service that launches the boot script at late-start. */
    fun buildInitRc(
        config: RootXposedConfig,
        spoof: DeviceSpoofConfig,
        hardware: VmHardwareConfig = VmHardwareConfig(),
        profileName: String = "",
        virtualLocation: VirtualLocationConfig = VirtualLocationConfig()
    ): String = buildString {
        appendLine("# ------------------------------------------------------------------")
        appendLine("# NovaVM init service (generated, do not edit)")
        if (profileName.isNotBlank()) appendLine("# Profile: $profileName")
        appendLine("# ------------------------------------------------------------------")
        appendLine("")
        appendLine("on early-init")
        if (spoof.enabled) {
            appendLine("    setprop ro.product.brand ${spoof.brand}")
            appendLine("    setprop ro.product.model ${spoof.model}")
            appendLine("    setprop ro.sf.lcd_density ${hardware.dpi}")
        } else {
            appendLine("    # identity spoofing disabled")
        }
        if (config.selinuxPermissive) {
            appendLine("    setprop ro.boot.selinux permissive")
            appendLine("    write /sys/fs/selinux/enforce 0")
        } else {
            appendLine("    setprop ro.boot.selinux enforcing")
        }
        appendLine("")
        appendLine("on post-fs-data")
        appendLine("    mkdir $GUEST_STAGE_DIR 0770 root root")
        appendLine("    copy /system/etc/novavm/$BOOT_SCRIPT_NAME $GUEST_STAGE_DIR/$BOOT_SCRIPT_NAME")
        appendLine("    chmod 0755 $GUEST_STAGE_DIR/$BOOT_SCRIPT_NAME")
        appendLine("    exec - root root -- /system/bin/sh $GUEST_STAGE_DIR/$BOOT_SCRIPT_NAME")
        appendLine("")
        appendLine("service novavm_boot /system/bin/sh $GUEST_STAGE_DIR/$BOOT_SCRIPT_NAME")
        appendLine("    class late_start")
        appendLine("    user root")
        appendLine("    group root system")
        appendLine("    oneshot")
        appendLine("    disabled")
        if (config.rootEnabled) {
            appendLine("    seclabel u:r:magisk:s0")
        }
        appendLine("")
        appendLine("on property:sys.boot_completed=1")
        appendLine("    start novavm_boot")
        if (config.xposedEnabled) {
            appendLine("    setprop persist.novavm.xposed 1")
        }
        if (config.rootEnabled) {
            appendLine("    setprop persist.novavm.root 1")
        }
        if (virtualLocation.enabled) {
            appendLine("    setprop persist.novavm.gps.enabled 1")
            appendLine("    setprop persist.novavm.gps.lat ${coordinate(virtualLocation.latitude)}")
            appendLine("    setprop persist.novavm.gps.lon ${coordinate(virtualLocation.longitude)}")
        }
    }

    /** Writes both generated files and returns them in write order. */
    fun writeAll(
        config: RootXposedConfig,
        spoof: DeviceSpoofConfig,
        hardware: VmHardwareConfig = VmHardwareConfig(),
        profileName: String = "",
        virtualLocation: VirtualLocationConfig = VirtualLocationConfig()
    ): List<File> {
        ensureDirectories()
        bootScriptFile.writeText(
            buildBootScript(config, spoof, hardware, profileName, virtualLocation),
            StandardCharsets.UTF_8
        )
        bootScriptFile.setExecutable(true, false)
        initRcFile.writeText(
            buildInitRc(config, spoof, hardware, profileName, virtualLocation),
            StandardCharsets.UTF_8
        )
        return listOf(bootScriptFile, initRcFile)
    }

    // -------------------------------------------------------------- modules

    fun listModules(): List<String> =
        modulesDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun stageModule(fileName: String, content: ByteArray): File {
        ensureDirectories()
        val safeName = sanitize(fileName)
        require(safeName.isNotBlank()) { "module fileName must not be blank" }
        val target = File(modulesDir, safeName)
        target.writeBytes(content)
        return target
    }

    fun stageModule(input: InputStream, fileName: String): File {
        ensureDirectories()
        val safeName = sanitize(fileName)
        require(safeName.isNotBlank()) { "module fileName must not be blank" }
        val target = File(modulesDir, safeName)
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return target
    }

    fun removeModule(fileName: String): Boolean {
        val safeName = sanitize(fileName)
        if (safeName.isBlank()) return false
        val target = File(modulesDir, safeName)
        if (!target.isFile) return false
        return target.delete()
    }

    fun findModule(fileName: String): File? =
        File(modulesDir, sanitize(fileName)).takeIf { it.isFile }

    /**
     * Removes modules that are no longer referenced by [config]. Only files
     * literally listed in `stagedModules` are considered managed.
     */
    fun syncStagedModules(config: RootXposedConfig): List<String> {
        ensureDirectories()
        val keep = config.stagedModules.map { sanitize(it) }.toSet()
        val removed = mutableListOf<String>()
        modulesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep) {
                if (file.delete()) removed += file.name
            }
        }
        return removed
    }

    private fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        return base.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch in "-_. " -> ch
                else -> '_'
            }
        }.joinToString("").trim().take(120)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Locale-stable fixed-point coordinate for generated shell scripts. */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
}
