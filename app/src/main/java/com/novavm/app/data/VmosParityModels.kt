package com.novavm.app.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Extended device identifiers used by the VMOS-parity feature set.
 *
 * Every field defaults to an empty string so profiles written by older app
 * versions still decode when a new identifier is introduced.
 */
@Serializable
data class ExtendedDeviceIdentity(
    val imsi: String = "",
    val iccid: String = "",
    val gaid: String = "",
    val serialNumber: String = "",
    val phoneNumber: String = "",
    val carrierName: String = "",
    val mccMnc: String = "",
    val wifiSsid: String = "",
    val wifiBssid: String = "",
    val bluetoothMac: String = ""
)

/**
 * A carrier profile used by [OneKeyNewDeviceGenerator]. Prefixes are stored
 * separately so both Chinese and global operators can share the same
 * generation path.
 */
@Serializable
data class CarrierPreset(
    val displayName: String = "",
    val mccMnc: String = "",
    val imsiPrefix: String = "",
    val iccidPrefix: String = "89",
    val phonePrefixes: List<String> = emptyList(),
    val phoneNumberLength: Int = 11,
    val wifiSsidPrefix: String = "NovaVM"
)

/**
 * One-tap generator for a complete "new device" identity.
 *
 * All randomness is injectable so tests (and reproducible profiles) can pin a
 * seed. Chinese carriers are listed first because the VMOS-style UI presents
 * them as the primary presets, followed by a small global set.
 */
object OneKeyNewDeviceGenerator {

    private const val IMSI_LENGTH = 15
    private const val ICCID_LENGTH = 20
    private const val SERIAL_LENGTH = 12
    private const val SERIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val WIFI_SUFFIX_ALPHABET = "0123456789ABCDEF"

    val chinaMobile = CarrierPreset(
        displayName = "中国移动",
        mccMnc = "46000",
        imsiPrefix = "46000",
        iccidPrefix = "898600",
        phonePrefixes = listOf(
            "134", "135", "136", "137", "138", "139", "147", "150", "151",
            "152", "157", "158", "159", "178", "182", "183", "184", "187",
            "188", "195", "197", "198"
        ),
        phoneNumberLength = 11,
        wifiSsidPrefix = "CMCC"
    )

    val chinaUnicom = CarrierPreset(
        displayName = "中国联通",
        mccMnc = "46001",
        imsiPrefix = "46001",
        iccidPrefix = "898601",
        phonePrefixes = listOf(
            "130", "131", "132", "145", "155", "156", "166", "167", "171",
            "175", "176", "185", "186", "196"
        ),
        phoneNumberLength = 11,
        wifiSsidPrefix = "ChinaUnicom"
    )

    val chinaTelecom = CarrierPreset(
        displayName = "中国电信",
        mccMnc = "46003",
        imsiPrefix = "46003",
        iccidPrefix = "898603",
        phonePrefixes = listOf(
            "133", "149", "153", "173", "177", "180", "181", "189", "190",
            "191", "193", "199"
        ),
        phoneNumberLength = 11,
        wifiSsidPrefix = "ChinaNet"
    )

    val verizon = CarrierPreset(
        displayName = "Verizon",
        mccMnc = "311480",
        imsiPrefix = "311480",
        iccidPrefix = "891480",
        phonePrefixes = listOf("201", "202", "212", "646", "917"),
        phoneNumberLength = 10,
        wifiSsidPrefix = "Verizon"
    )

    val tMobileUs = CarrierPreset(
        displayName = "T-Mobile US",
        mccMnc = "310260",
        imsiPrefix = "310260",
        iccidPrefix = "890126",
        phonePrefixes = listOf("202", "213", "310", "424", "626"),
        phoneNumberLength = 10,
        wifiSsidPrefix = "T-Mobile"
    )

    val vodafoneUk = CarrierPreset(
        displayName = "Vodafone UK",
        mccMnc = "23415",
        imsiPrefix = "23415",
        iccidPrefix = "894415",
        phonePrefixes = listOf("7400", "7700", "7711"),
        phoneNumberLength = 10,
        wifiSsidPrefix = "Vodafone"
    )

    val nttDocomo = CarrierPreset(
        displayName = "NTT docomo",
        mccMnc = "44010",
        imsiPrefix = "44010",
        iccidPrefix = "894410",
        phonePrefixes = listOf("090", "080", "070"),
        phoneNumberLength = 11,
        wifiSsidPrefix = "docomo"
    )

    /** Chinese presets first, followed by international profiles. */
    val carrierPresets: List<CarrierPreset> = listOf(
        chinaMobile,
        chinaUnicom,
        chinaTelecom,
        verizon,
        tMobileUs,
        vodafoneUk,
        nttDocomo
    )

    fun randomCarrier(random: Random = Random.Default): CarrierPreset =
        carrierPresets[random.nextInt(carrierPresets.size)]

    fun byMccMnc(mccMnc: String): CarrierPreset? {
        val normalized = mccMnc.filter { it.isDigit() }
        return carrierPresets.firstOrNull { it.mccMnc == normalized }
    }

    /** A 15 digit IMSI carrying the carrier's MCC/MNC prefix. */
    fun generateImsi(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): String = numericBody(carrier.imsiPrefix, IMSI_LENGTH, random)

    /**
     * A 20 digit ICCID with a valid Luhn check digit. The first 19 characters
     * are the carrier prefix plus random subscriber digits.
     */
    fun generateIccid(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): String {
        val body = numericBody(carrier.iccidPrefix.ifBlank { "89" }, ICCID_LENGTH - 1, random)
        return body + DeviceSpoofGenerator.luhnCheckDigit(body)
    }

    /** RFC 4122 UUID in the canonical Google Advertising ID shape. */
    fun generateGaid(): String = UUID.randomUUID().toString()

    /** 12 character alphanumeric serial, matching legacy Build.SERIAL. */
    fun generateSerial(random: Random = Random.Default): String = buildString(SERIAL_LENGTH) {
        repeat(SERIAL_LENGTH) {
            append(SERIAL_ALPHABET[random.nextInt(SERIAL_ALPHABET.length)])
        }
    }

    /** A plausible mobile number for the selected carrier. */
    fun generatePhoneNumber(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): String {
        require(carrier.phonePrefixes.isNotEmpty()) { "carrier has no phone prefixes" }
        val prefix = carrier.phonePrefixes[random.nextInt(carrier.phonePrefixes.size)]
        val length = carrier.phoneNumberLength.coerceAtLeast(prefix.length + 1)
        return numericBody(prefix, length, random)
    }

    /** A realistic-looking SSID, e.g. `CMCC-7F2A`. */
    fun generateWifiSsid(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): String = buildString {
        append(carrier.wifiSsidPrefix.ifBlank { "NovaVM" })
        append('-')
        repeat(4) {
            append(WIFI_SUFFIX_ALPHABET[random.nextInt(WIFI_SUFFIX_ALPHABET.length)])
        }
    }

    /** A locally administered unicast Wi-Fi BSSID. */
    fun generateBssid(random: Random = Random.Default): String =
        DeviceSpoofGenerator.generateMac(random)

    /** A locally administered unicast Bluetooth MAC address. */
    fun generateBluetoothMac(random: Random = Random.Default): String =
        DeviceSpoofGenerator.generateMac(random)

    /** Builds every extended identifier for one carrier. */
    fun generateIdentity(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): ExtendedDeviceIdentity {
        val bssid = generateBssid(random)
        var bluetoothMac = generateBluetoothMac(random)
        while (bluetoothMac == bssid) {
            bluetoothMac = generateBluetoothMac(random)
        }
        return ExtendedDeviceIdentity(
            imsi = generateImsi(carrier, random),
            iccid = generateIccid(carrier, random),
            gaid = generateGaid(),
            serialNumber = generateSerial(random),
            phoneNumber = generatePhoneNumber(carrier, random),
            carrierName = carrier.displayName,
            mccMnc = carrier.mccMnc,
            wifiSsid = generateWifiSsid(carrier, random),
            wifiBssid = bssid,
            bluetoothMac = bluetoothMac
        )
    }

    /**
     * Alias for [generateIdentity], used by the [VmProfile] default and by the
     * "one-key new device" action so both call sites read the same way.
     */
    fun generateExtendedIdentity(
        carrier: CarrierPreset = chinaMobile,
        random: Random = Random.Default
    ): ExtendedDeviceIdentity = generateIdentity(carrier, random)

    /**
     * Clones [preset] with fresh IMEI/Android ID/Wi-Fi MAC values and returns
     * the matching extended identity. The carrier is selected randomly from
     * [carrierPresets].
     */
    fun generateFullDeviceIdentity(
        preset: DeviceSpoofConfig,
        random: Random = Random.Default
    ): Pair<DeviceSpoofConfig, ExtendedDeviceIdentity> =
        generateFullDeviceIdentity(preset, randomCarrier(random), random)

    /**
     * Deterministic-carrier variant used by callers that let the user pick a
     * preset explicitly.
     */
    fun generateFullDeviceIdentity(
        preset: DeviceSpoofConfig,
        carrier: CarrierPreset,
        random: Random = Random.Default
    ): Pair<DeviceSpoofConfig, ExtendedDeviceIdentity> {
        val identity = generateIdentity(carrier, random)
        val spoof = preset.copy(
            enabled = true,
            imei = DeviceSpoofGenerator.generateImei(random),
            androidId = DeviceSpoofGenerator.generateAndroidId(random),
            wifiMac = DeviceSpoofGenerator.generateMac(random)
        )
        return spoof to identity
    }

    /** Prefix + random digits, padded to exactly [totalLength]. */
    private fun numericBody(prefix: String, totalLength: Int, random: Random): String {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
        require(prefix.all { it.isDigit() }) { "prefix must be numeric" }
        require(totalLength > prefix.length) {
            "totalLength ($totalLength) must exceed prefix length (${prefix.length})"
        }
        return buildString(totalLength) {
            append(prefix)
            repeat(totalLength - prefix.length) {
                append(random.nextInt(10))
            }
        }
    }
}

/** GMS / MicroG / Play Store switches for the guest image. */
@Serializable
data class GmsConfig(
    val enabled: Boolean = false,
    val microGMode: Boolean = false,
    val playStoreEnabled: Boolean = true,
    val zygiskDenyList: List<String> = listOf(
        "com.google.android.gms",
        "com.android.vending"
    )
)

/** Virtual battery, step counter, camera and microphone overrides. */
@Serializable
data class VirtualSensorConfig(
    val batteryOverrideEnabled: Boolean = false,
    val batteryLevel: Int = 85,
    val batteryCharging: Boolean = false,
    val batteryTempC: Float = 32.5f,
    val stepCounterEnabled: Boolean = false,
    val dailySteps: Int = 6800,
    val virtualCameraEnabled: Boolean = true,
    val virtualMicEnabled: Boolean = true
)

/** VMOS-style background survival / interaction preferences. */
@Serializable
data class BackgroundKeepAliveConfig(
    val screenOffKeepAlive: Boolean = false,
    val clipboardSyncEnabled: Boolean = true,
    val landscapeLocked: Boolean = false,
    val floatingTouchPassthrough: Boolean = false
)

/** Optional PIN gate protecting the VM list. */
@Serializable
data class VmPrivacyLockConfig(
    val enabled: Boolean = false,
    val pinHash: String = ""
)

/**
 * The four VMOS-parity feature configs in one value.
 *
 * Boot-script writers pass this to
 * [com.novavm.app.root.VmosFeatureInjector.writeExtensionScript], so the
 * generated `shared/novavm_vmos_ext.sh` always matches the profile.
 */
data class VmosFeatureBundle(
    val gms: GmsConfig = GmsConfig(),
    val sensors: VirtualSensorConfig = VirtualSensorConfig(),
    val identity: ExtendedDeviceIdentity = ExtendedDeviceIdentity(),
    val keepAlive: BackgroundKeepAliveConfig = BackgroundKeepAliveConfig()
)

/**
 * Salted SHA-256 PIN hashing.
 *
 * Stored format: `sha256:<salt-hex>:<digest-hex>`. A fresh 16 byte salt is
 * generated for every hash, so hashing the same PIN twice produces different
 * records.
 */

object PinHashUtil {

    private const val ALGORITHM = "SHA-256"
    private const val FORMAT_PREFIX = "sha256"
    private const val SALT_BYTES = 16
    private const val DIGEST_BYTES = 32
    private const val HEX_LOWER = "0123456789abcdef"

    private val secureRandom = SecureRandom()

    fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val digest = digest(salt, pin)
        return "$FORMAT_PREFIX:${salt.toHex()}:${digest.toHex()}"
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split(':')
        if (parts.size != 3 || parts[0] != FORMAT_PREFIX) return false
        val salt = parts[1].hexToBytesOrNull() ?: return false
        val expected = parts[2].hexToBytesOrNull() ?: return false
        if (salt.size != SALT_BYTES || expected.size != DIGEST_BYTES) return false
        return MessageDigest.isEqual(expected, digest(salt, pin))
    }

    private fun digest(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance(ALGORITHM).run {
            update(salt)
            update(pin.toByteArray(StandardCharsets.UTF_8))
            digest()
        }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_LOWER[value ushr 4])
            append(HEX_LOWER[value and 0x0F])
        }
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        val out = ByteArray(length / 2)
        for (index in out.indices) {
            val high = Character.digit(this[index * 2], 16)
            val low = Character.digit(this[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
