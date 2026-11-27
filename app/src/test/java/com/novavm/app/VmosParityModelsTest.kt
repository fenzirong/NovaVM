package com.novavm.app

import com.novavm.app.data.BackgroundKeepAliveConfig
import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofGenerator
import com.novavm.app.data.ExtendedDeviceIdentity
import com.novavm.app.data.GmsConfig
import com.novavm.app.data.OneKeyNewDeviceGenerator
import com.novavm.app.data.PinHashUtil
import com.novavm.app.data.VirtualSensorConfig
import com.novavm.app.data.VmPrivacyLockConfig
import com.novavm.app.root.VmosFeatureInjector
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit coverage for the VMOS-parity identity models, one-key generator,
 * PIN hashing, JSON compatibility and the generated extension script.
 */
class VmosParityModelsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ------------------------------------------------------- one-key generator

    @Test
    fun `imsi and iccid formats follow carrier rules`() {
        val random = Random(20261127)
        OneKeyNewDeviceGenerator.carrierPresets.forEach { carrier ->
            repeat(8) {
                val imsi = OneKeyNewDeviceGenerator.generateImsi(carrier, random)
                assertEquals("IMSI length for ${carrier.displayName}", 15, imsi.length)
                assertTrue("IMSI must keep the MCC/MNC prefix", imsi.startsWith(carrier.imsiPrefix))
                assertTrue("IMSI must be numeric", imsi.all { it.isDigit() })

                val iccid = OneKeyNewDeviceGenerator.generateIccid(carrier, random)
                assertEquals("ICCID length for ${carrier.displayName}", 20, iccid.length)
                assertTrue("ICCID must keep the carrier prefix", iccid.startsWith(carrier.iccidPrefix))
                assertTrue("ICCID must pass Luhn", isValidLuhn(iccid))
            }
        }
    }

    @Test
    fun `chinese carrier presets expose the expected mcc mnc values`() {
        assertEquals("46000", OneKeyNewDeviceGenerator.chinaMobile.mccMnc)
        assertEquals("46001", OneKeyNewDeviceGenerator.chinaUnicom.mccMnc)
        assertEquals("46003", OneKeyNewDeviceGenerator.chinaTelecom.mccMnc)
        assertEquals(
            listOf("中国移动", "中国联通", "中国电信"),
            OneKeyNewDeviceGenerator.carrierPresets.take(3).map { it.displayName }
        )
        assertEquals(
            OneKeyNewDeviceGenerator.chinaTelecom,
            OneKeyNewDeviceGenerator.byMccMnc("460-03")
        )
    }

    @Test
    fun `one key identity has realistic formats and fresh values`() {
        val random = Random(7)
        val (spoof, identity) = OneKeyNewDeviceGenerator.generateFullDeviceIdentity(
            DevicePresets.pixel8Pro,
            OneKeyNewDeviceGenerator.chinaMobile,
            random
        )

        assertEquals("中国移动", identity.carrierName)
        assertEquals("46000", identity.mccMnc)
        assertEquals(15, identity.imsi.length)
        assertEquals(20, identity.iccid.length)
        assertEquals(12, identity.serialNumber.length)
        assertTrue(identity.serialNumber.all { it.isLetterOrDigit() })
        assertTrue(identity.phoneNumber.matches(Regex("1\\d{10}")))
        assertTrue(identity.wifiSsid.startsWith("CMCC-"))
        assertTrue(DeviceSpoofGenerator.isValidMac(identity.wifiBssid))
        assertTrue(DeviceSpoofGenerator.isValidMac(identity.bluetoothMac))
        assertNotEquals(identity.wifiBssid, identity.bluetoothMac)
        UUID.fromString(identity.gaid)

        assertTrue(spoof.enabled)
        assertTrue(DeviceSpoofGenerator.isValidImei(spoof.imei))
        assertTrue(DeviceSpoofGenerator.isValidAndroidId(spoof.androidId))
        assertTrue(DeviceSpoofGenerator.isValidMac(spoof.wifiMac))
    }

    @Test
    fun `repeated generation produces different identifiers`() {
        val imsis = (1..40)
            .map { seed ->
                OneKeyNewDeviceGenerator.generateImsi(
                    OneKeyNewDeviceGenerator.chinaMobile,
                    Random(seed)
                )
            }
            .toSet()
        val serials = (1..40)
            .map { seed -> OneKeyNewDeviceGenerator.generateSerial(Random(seed + 1000)) }
            .toSet()
        val gaids = (1..20)
            .map { OneKeyNewDeviceGenerator.generateGaid() }
            .toSet()

        assertEquals(40, imsis.size)
        assertEquals(40, serials.size)
        assertEquals(20, gaids.size)
    }

    // --------------------------------------------------------------- PIN hash

    @Test
    fun `pin hash verifies the correct pin and rejects wrong or damaged hashes`() {
        val hash = PinHashUtil.hashPin("246810")

        assertTrue(hash.startsWith("sha256:"))
        assertTrue(PinHashUtil.verifyPin("246810", hash))
        assertFalse(PinHashUtil.verifyPin("246811", hash))
        assertFalse(PinHashUtil.verifyPin("246810", ""))
        assertFalse(PinHashUtil.verifyPin("246810", hash.dropLast(1)))
        assertFalse(PinHashUtil.verifyPin("246810", "not-a-hash"))
        assertNotEquals(hash, PinHashUtil.hashPin("246810"))
    }

    // ------------------------------------------------------------ JSON models

    @Test
    fun `all parity models round trip through json`() {
        assertJsonRoundTrip(
            ExtendedDeviceIdentity(
                imsi = "460001234567890",
                iccid = "89860012345678901234",
                gaid = "123e4567-e89b-12d3-a456-426614174000",
                serialNumber = "A1B2C3D4E5F6",
                phoneNumber = "13800138000",
                carrierName = "中国移动",
                mccMnc = "46000",
                wifiSsid = "CMCC-1A2B",
                wifiBssid = "02:11:22:33:44:55",
                bluetoothMac = "02:aa:bb:cc:dd:ee"
            )
        )
        assertJsonRoundTrip(
            GmsConfig(
                enabled = true,
                microGMode = true,
                playStoreEnabled = false,
                zygiskDenyList = listOf("com.google.android.gms", "com.tencent.mm")
            )
        )
        assertJsonRoundTrip(
            VirtualSensorConfig(
                batteryOverrideEnabled = true,
                batteryLevel = 42,
                batteryCharging = true,
                batteryTempC = 37.25f,
                stepCounterEnabled = true,
                dailySteps = 12345,
                virtualCameraEnabled = false,
                virtualMicEnabled = true
            )
        )
        assertJsonRoundTrip(
            BackgroundKeepAliveConfig(
                screenOffKeepAlive = true,
                clipboardSyncEnabled = false,
                landscapeLocked = true,
                floatingTouchPassthrough = true
            )
        )
        assertJsonRoundTrip(
            VmPrivacyLockConfig(
                enabled = true,
                pinHash = PinHashUtil.hashPin("1234")
            )
        )
        assertJsonRoundTrip(OneKeyNewDeviceGenerator.chinaMobile)
    }

    @Test
    fun `missing json fields fall back to safe defaults`() {
        assertEquals(GmsConfig(), json.decodeFromString<GmsConfig>("{}"))
        assertEquals(
            VirtualSensorConfig(),
            json.decodeFromString<VirtualSensorConfig>("{}")
        )
        assertEquals(
            BackgroundKeepAliveConfig(),
            json.decodeFromString<BackgroundKeepAliveConfig>("{}")
        )
        assertEquals(
            VmPrivacyLockConfig(),
            json.decodeFromString<VmPrivacyLockConfig>("{}")
        )

        val identity = json.decodeFromString<ExtendedDeviceIdentity>("{}")
        assertEquals("", identity.imsi)
        assertEquals("", identity.iccid)
        assertEquals("", identity.gaid)
        assertEquals("", identity.serialNumber)
        assertEquals("", identity.phoneNumber)
        assertEquals("", identity.carrierName)
        assertEquals("", identity.mccMnc)
        assertEquals("", identity.wifiSsid)
        assertEquals("", identity.wifiBssid)
        assertEquals("", identity.bluetoothMac)

        val gms = json.decodeFromString<GmsConfig>("{}")
        assertFalse(gms.enabled)
        assertFalse(gms.microGMode)
        assertTrue(gms.playStoreEnabled)
        assertEquals(
            listOf("com.google.android.gms", "com.android.vending"),
            gms.zygiskDenyList
        )
    }

    // ---------------------------------------------------------- script output

    @Test
    fun `extension script emits enabled parity directives`() {
        val identity = ExtendedDeviceIdentity(
            imsi = "460001234567890",
            iccid = "89860012345678901234",
            gaid = "123e4567-e89b-12d3-a456-426614174000",
            serialNumber = "A1B2C3D4E5F6",
            phoneNumber = "13800138000",
            carrierName = "中国移动",
            mccMnc = "46000",
            wifiSsid = "CMCC-1A2B",
            wifiBssid = "02:11:22:33:44:55",
            bluetoothMac = "02:aa:bb:cc:dd:ee"
        )

        val script = VmosFeatureInjector.buildExtensionScript(
            gms = GmsConfig(
                enabled = true,
                microGMode = true,
                playStoreEnabled = true,
                zygiskDenyList = listOf("com.google.android.gms", "com.tencent.mm")
            ),
            sensors = VirtualSensorConfig(
                batteryOverrideEnabled = true,
                batteryLevel = 72,
                batteryCharging = true,
                batteryTempC = 33.5f,
                stepCounterEnabled = true,
                dailySteps = 12345,
                virtualCameraEnabled = true,
                virtualMicEnabled = true
            ),
            identity = identity,
            keepAlive = BackgroundKeepAliveConfig(
                screenOffKeepAlive = true,
                clipboardSyncEnabled = true,
                landscapeLocked = true,
                floatingTouchPassthrough = true
            )
        )

        assertTrue(script.startsWith("#!/system/bin/sh"))

        // Extended identity.
        assertTrue(script.contains("resetprop persist.novavm.imsi \"460001234567890\""))
        assertTrue(script.contains("resetprop persist.novavm.iccid \"89860012345678901234\""))
        assertTrue(
            script.contains(
                "resetprop persist.novavm.gaid \"123e4567-e89b-12d3-a456-426614174000\""
            )
        )
        assertTrue(script.contains("resetprop ro.serialno \"A1B2C3D4E5F6\""))
        assertTrue(script.contains("resetprop gsm.sim.operator.alpha \"中国移动\""))
        assertTrue(script.contains("resetprop gsm.sim.operator.numeric \"46000\""))
        assertTrue(script.contains("resetprop persist.novavm.wifi_ssid \"CMCC-1A2B\""))
        assertTrue(
            script.contains(
                "resetprop persist.novavm.wifi_bssid \"02:11:22:33:44:55\""
            )
        )
        assertTrue(
            script.contains(
                "resetprop persist.novavm.bt_mac \"02:aa:bb:cc:dd:ee\""
            )
        )

        // GMS / MicroG / Zygisk.
        assertTrue(script.contains("persist.novavm.gms.enabled 1"))
        assertTrue(script.contains("persist.novavm.microg.enabled 1"))
        assertTrue(script.contains("persist.novavm.playstore.enabled 1"))
        assertTrue(script.contains("persist.novavm.gms.backend \"microg\""))
        assertTrue(script.contains("pm enable com.google.android.gms"))
        assertTrue(script.contains("com.tencent.mm"))
        assertTrue(
            script.contains(
                "persist.novavm.zygisk.denylist " +
                    "\"com.google.android.gms;com.tencent.mm\""
            )
        )

        // Virtual sensors.
        assertTrue(script.contains("dumpsys battery set level 72"))
        assertTrue(script.contains("dumpsys battery set temp 335"))
        assertTrue(script.contains("dumpsys battery set status 2"))
        assertTrue(script.contains("dumpsys battery set ac 1"))
        assertTrue(script.contains("persist.novavm.steps.daily 12345"))
        assertTrue(script.contains("persist.novavm.camera.virtual 1"))
        assertTrue(script.contains("persist.novavm.mic.virtual 1"))

        // Keep-alive / clipboard bridge.
        assertTrue(script.contains("persist.novavm.keepalive.screen_off 1"))
        assertTrue(script.contains("persist.novavm.clipboard.sync 1"))
        assertTrue(script.contains("persist.novavm.clipboard.bridge 1"))
        assertTrue(script.contains("persist.novavm.display.landscape_locked 1"))
        assertTrue(script.contains("persist.novavm.touch.passthrough 1"))
    }

    @Test
    fun `extension script marks disabled features`() {
        val script = VmosFeatureInjector.buildExtensionScript(
            gms = GmsConfig(
                enabled = false,
                microGMode = false,
                playStoreEnabled = false,
                zygiskDenyList = emptyList()
            ),
            sensors = VirtualSensorConfig(
                batteryOverrideEnabled = false,
                stepCounterEnabled = false,
                virtualCameraEnabled = false,
                virtualMicEnabled = false
            ),
            identity = ExtendedDeviceIdentity(),
            keepAlive = BackgroundKeepAliveConfig(clipboardSyncEnabled = false)
        )

        assertTrue(script.contains("persist.novavm.gms.enabled 0"))
        assertTrue(script.contains("persist.novavm.microg.enabled 0"))
        assertTrue(script.contains("persist.novavm.playstore.enabled 0"))
        assertTrue(script.contains("dumpsys battery reset"))
        assertTrue(script.contains("persist.novavm.steps.enabled 0"))
        assertTrue(script.contains("persist.novavm.camera.virtual 0"))
        assertTrue(script.contains("persist.novavm.mic.virtual 0"))
        assertTrue(script.contains("persist.novavm.clipboard.sync 0"))
        assertFalse(script.contains("resetprop persist.novavm.imsi "))
    }

    @Test
    fun `write extension script creates the shared script file`() {
        val vmDir = temp.newFolder("parity-vm")
        val gms = GmsConfig(enabled = true)
        val sensors = VirtualSensorConfig(batteryOverrideEnabled = true)
        val identity = ExtendedDeviceIdentity(imsi = "460001234567890")
        val keepAlive = BackgroundKeepAliveConfig()

        val script = VmosFeatureInjector.writeExtensionScript(
            vmDir,
            gms,
            sensors,
            identity,
            keepAlive
        )

        assertEquals(VmosFeatureInjector.SHARED_DIR_NAME, script.parentFile?.name)
        assertEquals(VmosFeatureInjector.EXTENSION_SCRIPT_NAME, script.name)
        assertTrue(script.isFile)
        assertEquals(
            VmosFeatureInjector.buildExtensionScript(gms, sensors, identity, keepAlive),
            script.readText()
        )
    }

    // --------------------------------------------------------------- helpers

    private inline fun <reified T> assertJsonRoundTrip(value: T) {
        val encoded = json.encodeToString(value)
        assertEquals(value, json.decodeFromString<T>(encoded))
    }

    private fun isValidLuhn(number: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (index in number.indices.reversed()) {
            var digit = number[index] - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }
}
