package com.novavm.app.root

import com.novavm.app.data.BackgroundKeepAliveConfig
import com.novavm.app.data.ExtendedDeviceIdentity
import com.novavm.app.data.GmsConfig
import com.novavm.app.data.VirtualSensorConfig
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Generates the VMOS-parity guest extension script.
 *
 * The script is intentionally self-contained: it uses `resetprop` for
 * properties that must survive `ro.*` read-only protection, `dumpsys battery`
 * for the virtual battery, and a staging file that a later Zygisk helper can
 * consume for the DenyList. It does not execute anything on the host.
 */
object VmosFeatureInjector {

    const val EXTENSION_SCRIPT_NAME = "novavm_vmos_ext.sh"
    const val SHARED_DIR_NAME = "shared"

    /**
     * Builds `novavm_vmos_ext.sh` from the four feature configs.
     *
     * Identity values are only emitted when non-blank, so a fresh profile does
     * not overwrite stock guest identifiers with empty strings.
     */
    fun buildExtensionScript(
        gms: GmsConfig,
        sensors: VirtualSensorConfig,
        identity: ExtendedDeviceIdentity,
        keepAlive: BackgroundKeepAliveConfig
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# ------------------------------------------------------------------")
        appendLine("# NovaVM VMOS parity extension (generated, do not edit)")
        appendLine("# GMS/MicroG, extended identity, virtual sensors, keep-alive.")
        appendLine("# ------------------------------------------------------------------")
        appendLine("log -t novavm \"novavm_vmos_ext.sh starting\"")
        appendLine("")

        appendIdentity(identity)
        appendGms(gms)
        appendSensors(sensors)
        appendKeepAlive(keepAlive)

        appendLine("log -t novavm \"novavm_vmos_ext.sh complete\"")
        appendLine("exit 0")
    }

    /**
     * Writes the generated script to `<vmDir>/shared/novavm_vmos_ext.sh`.
     *
     * @throws IOException when the shared directory cannot be created.
     */
    fun writeExtensionScript(
        vmDir: File,
        gms: GmsConfig,
        sensors: VirtualSensorConfig,
        identity: ExtendedDeviceIdentity,
        keepAlive: BackgroundKeepAliveConfig
    ): File {
        val sharedDir = File(vmDir, SHARED_DIR_NAME)
        if (!sharedDir.isDirectory && !sharedDir.mkdirs()) {
            throw IOException("Unable to create ${sharedDir.absolutePath}")
        }
        val script = File(sharedDir, EXTENSION_SCRIPT_NAME)
        script.writeText(
            buildExtensionScript(gms, sensors, identity, keepAlive),
            StandardCharsets.UTF_8
        )
        script.setExecutable(true, false)
        return script
    }

    // ------------------------------------------------------------- identity

    private fun StringBuilder.appendIdentity(identity: ExtendedDeviceIdentity) {
        appendLine("# --- Extended device identity ---")
        if (identity.imsi.isNotBlank()) {
            appendProp("persist.novavm.imsi", identity.imsi)
            appendProp("ro.ril.oem.imsi", identity.imsi)
        }
        if (identity.iccid.isNotBlank()) {
            appendProp("persist.novavm.iccid", identity.iccid)
            appendProp("ro.ril.oem.iccid", identity.iccid)
        }
        if (identity.gaid.isNotBlank()) {
            appendProp("persist.novavm.gaid", identity.gaid)
        }
        if (identity.serialNumber.isNotBlank()) {
            appendProp("persist.novavm.serial", identity.serialNumber)
            appendProp("ro.serialno", identity.serialNumber)
            appendProp("ro.boot.serialno", identity.serialNumber)
        }
        if (identity.phoneNumber.isNotBlank()) {
            appendProp("persist.novavm.phone_number", identity.phoneNumber)
            appendProp("ro.ril.oem.msisdn", identity.phoneNumber)
        }
        if (identity.carrierName.isNotBlank()) {
            appendProp("persist.novavm.carrier", identity.carrierName)
            appendProp("gsm.sim.operator.alpha", identity.carrierName)
            appendProp("gsm.operator.alpha", identity.carrierName)
            appendProp("ro.carrier", identity.carrierName)
        }
        if (identity.mccMnc.isNotBlank()) {
            appendProp("persist.novavm.mccmnc", identity.mccMnc)
            appendProp("gsm.sim.operator.numeric", identity.mccMnc)
            appendProp("gsm.operator.numeric", identity.mccMnc)
        }
        if (identity.wifiSsid.isNotBlank()) {
            appendProp("persist.novavm.wifi_ssid", identity.wifiSsid)
        }
        if (identity.wifiBssid.isNotBlank()) {
            appendProp("persist.novavm.wifi_bssid", identity.wifiBssid)
        }
        if (identity.bluetoothMac.isNotBlank()) {
            appendProp("persist.novavm.bt_mac", identity.bluetoothMac)
            appendProp("ro.boot.btmacaddr", identity.bluetoothMac)
            appendProp("bluetooth.address", identity.bluetoothMac)
        }
        appendLine("")
    }

    // ------------------------------------------------------------------ GMS

    private fun StringBuilder.appendGms(gms: GmsConfig) {
        appendLine("# --- GMS / MicroG ---")
        appendFlag("persist.novavm.gms.enabled", gms.enabled)
        appendFlag("persist.novavm.microg.enabled", gms.microGMode)
        appendFlag("persist.novavm.gms.microg_mode", gms.microGMode)
        appendFlag("persist.novavm.playstore.enabled", gms.playStoreEnabled)
        appendLine(
            "resetprop persist.novavm.gms.backend " +
                shellQuote(if (gms.microGMode) "microg" else "gms")
        )

        appendLine("if command -v pm >/dev/null 2>&1; then")
        if (gms.enabled) {
            appendLine("  pm enable com.google.android.gms >/dev/null 2>&1 || true")
            if (gms.playStoreEnabled) {
                appendLine("  pm enable com.android.vending >/dev/null 2>&1 || true")
            } else {
                appendLine(
                    "  pm disable-user --user 0 com.android.vending " +
                        ">/dev/null 2>&1 || true"
                )
            }
        } else {
            appendLine(
                "  pm disable-user --user 0 com.google.android.gms " +
                    ">/dev/null 2>&1 || true"
            )
            appendLine(
                "  pm disable-user --user 0 com.android.vending " +
                    ">/dev/null 2>&1 || true"
            )
        }
        appendLine("fi")
        appendLine("")

        appendZygiskDenyList(gms)
    }

    private fun StringBuilder.appendZygiskDenyList(gms: GmsConfig) {
        val packages = gms.zygiskDenyList.filter { it.isNotBlank() }.distinct()
        appendLine("# --- Zygisk DenyList staging ---")
        appendLine("ZYGISK_DIR=\"/data/adb/zygisksu\"")
        appendLine("DENYLIST_FILE=\"\$ZYGISK_DIR/novavm_denylist.txt\"")
        appendLine("mkdir -p \"\$ZYGISK_DIR\"")
        appendLine(": > \"\$DENYLIST_FILE\"")
        packages.forEach { pkg ->
            appendLine("printf '%s\\n' ${shellQuote(pkg)} >> \"\$DENYLIST_FILE\"")
        }
        appendFlag("persist.novavm.zygisk.enabled", packages.isNotEmpty())
        appendLine(
            "resetprop persist.novavm.zygisk.denylist " +
                shellQuote(packages.joinToString(";"))
        )
        appendLine(
            "resetprop persist.novavm.zygisk.denylist_file " +
                shellQuote("/data/adb/zygisksu/novavm_denylist.txt")
        )
        appendLine("")
    }

    // -------------------------------------------------------------- sensors

    private fun StringBuilder.appendSensors(sensors: VirtualSensorConfig) {
        appendLine("# --- Virtual sensors ---")
        appendBattery(sensors)
        appendFlag("persist.novavm.steps.enabled", sensors.stepCounterEnabled)
        appendLine(
            "resetprop persist.novavm.steps.daily " +
                sensors.dailySteps.coerceAtLeast(0)
        )
        appendFlag("persist.novavm.camera.virtual", sensors.virtualCameraEnabled)
        appendFlag("persist.novavm.mic.virtual", sensors.virtualMicEnabled)
        appendLine("")
    }

    private fun StringBuilder.appendBattery(sensors: VirtualSensorConfig) {
        if (!sensors.batteryOverrideEnabled) {
            appendLine("# Virtual battery override disabled; restoring stock values.")
            appendLine("resetprop persist.novavm.battery.override 0")
            appendLine("dumpsys battery reset >/dev/null 2>&1 || true")
            appendLine("")
            return
        }

        val level = sensors.batteryLevel.coerceIn(0, 100)
        val temperatureTenths = batteryTempTenths(sensors.batteryTempC)
        val status = if (sensors.batteryCharging) 2 else 3
        appendLine("# Virtual battery override")
        appendLine("resetprop persist.novavm.battery.override 1")
        appendLine("dumpsys battery set level $level")
        appendLine("dumpsys battery set temp $temperatureTenths")
        appendLine("dumpsys battery set present 1")
        appendLine("dumpsys battery set ac ${if (sensors.batteryCharging) 1 else 0}")
        appendLine("dumpsys battery set usb ${if (sensors.batteryCharging) 1 else 0}")
        if (!sensors.batteryCharging) {
            appendLine("dumpsys battery unplug >/dev/null 2>&1 || true")
        }
        appendLine("dumpsys battery set status $status")
        appendLine("")
    }

    // ------------------------------------------------------------ keepalive

    private fun StringBuilder.appendKeepAlive(keepAlive: BackgroundKeepAliveConfig) {
        appendLine("# --- Background keep-alive ---")
        appendFlag("persist.novavm.keepalive.screen_off", keepAlive.screenOffKeepAlive)
        appendFlag("persist.novavm.clipboard.sync", keepAlive.clipboardSyncEnabled)
        appendFlag("persist.novavm.clipboard.bridge", keepAlive.clipboardSyncEnabled)
        appendFlag("persist.novavm.display.landscape_locked", keepAlive.landscapeLocked)
        appendFlag(
            "persist.novavm.touch.passthrough",
            keepAlive.floatingTouchPassthrough
        )
        appendLine("")
    }

    // --------------------------------------------------------------- helpers

    private fun StringBuilder.appendProp(key: String, value: String) {
        if (value.isBlank()) return
        appendLine("resetprop $key ${shellQuote(value)}")
    }

    private fun StringBuilder.appendFlag(key: String, enabled: Boolean) {
        appendLine("resetprop $key ${if (enabled) 1 else 0}")
    }

    /** Locale-independent battery temperature in tenths of a degree Celsius. */
    private fun batteryTempTenths(celsius: Float): Int {
        val safe = if (celsius.isFinite()) celsius.coerceIn(-40f, 100f) else 32.5f
        return (safe * 10f).roundToInt()
    }

    /** Quotes one value for a POSIX shell double-quoted string. */
    private fun shellQuote(value: String): String {
        val singleLine = value.replace('\r', ' ').replace('\n', ' ')
        val escaped = singleLine
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
            .replace("`", "\\`")
        return "\"$escaped\""
    }
}
