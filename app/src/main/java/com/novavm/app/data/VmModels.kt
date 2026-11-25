package com.novavm.app.data

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

/** CPU architecture the guest payload is compiled for. */
@Serializable
enum class VmArchitecture {
    ARM64,
    X86_64;

    val qemuSystemBinary: String
        get() = when (this) {
            ARM64 -> "qemu-system-aarch64"
            X86_64 -> "qemu-system-x86_64"
        }

    val qemuMachine: String
        get() = when (this) {
            ARM64 -> "virt"
            X86_64 -> "q35"
        }

    val abiLabel: String
        get() = when (this) {
            ARM64 -> "arm64-v8a"
            X86_64 -> "x86_64"
        }
}

/** Which hypervisor implementation should own the VM. */
@Serializable
enum class VmBackendType {
    AUTO,
    QEMU,
    AVF
}

@Serializable
enum class VmStatus {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    ERROR
}

@Serializable
enum class GpuMode {
    SOFTWARE_RAMFB,
    VIRTIO_GPU,
    VIRGL_OPENGL
}

@Serializable
data class VmHardwareConfig(
    val cpuCores: Int = 4,
    val ramMb: Int = 2048,
    val diskSizeGb: Int = 16,
    val width: Int = 720,
    val height: Int = 1280,
    val dpi: Int = 320,
    val targetFps: Int = 60,
    val gpuMode: GpuMode = GpuMode.VIRTIO_GPU
) {
    init {
        require(cpuCores in 1..32) { "cpuCores must be in 1..32" }
        require(ramMb in 256..32768) { "ramMb must be in 256..32768" }
        require(diskSizeGb in 1..512) { "diskSizeGb must be in 1..512" }
        require(width >= 320 && height >= 320) { "resolution must be at least 320x320" }
        require(dpi in 120..640) { "dpi must be in 120..640" }
        require(targetFps in 15..240) { "targetFps must be in 15..240" }
    }
}

/**
 * Identity that the guest sees. Every value is written into the guest boot
 * environment by [com.novavm.app.root.RootXposedConfigurator] and mirrored into
 * the QEMU SMBIOS table.
 */
@Serializable
data class DeviceSpoofConfig(
    val enabled: Boolean = true,
    val presetName: String = "Pixel 8 Pro",
    val brand: String = "google",
    val manufacturer: String = "Google",
    val model: String = "Pixel 8 Pro",
    val device: String = "husky",
    val board: String = "husky",
    val imei: String = "",
    val androidId: String = "",
    val wifiMac: String = "",
    val buildFingerprint: String =
        "google/husky/husky:14/UQ1A.240205.004/11230369:user/release-keys"
) {
    val smbiosManufacturer: String get() = manufacturer
    val smbiosProduct: String get() = model
    val smbiosVersion: String get() = buildFingerprint.substringAfter(':').substringBefore('/')
}

/** Realistic device profiles used by the "spoof device" UI. */
object DevicePresets {

    val pixel8Pro: DeviceSpoofConfig = DeviceSpoofConfig(
        presetName = "Pixel 8 Pro",
        brand = "google",
        manufacturer = "Google",
        model = "Pixel 8 Pro",
        device = "husky",
        board = "husky",
        buildFingerprint = "google/husky/husky:14/UQ1A.240205.004/11230369:user/release-keys"
    )

    val galaxyS24Ultra: DeviceSpoofConfig = DeviceSpoofConfig(
        presetName = "Galaxy S24 Ultra",
        brand = "samsung",
        manufacturer = "samsung",
        model = "SM-S928B",
        device = "e3q",
        board = "e3q",
        buildFingerprint =
            "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AWLM:user/release-keys"
    )

    val rogPhone8: DeviceSpoofConfig = DeviceSpoofConfig(
        presetName = "ROG Phone 8",
        brand = "asus",
        manufacturer = "asus",
        model = "ASUS_AI2401_D",
        device = "AI2401",
        board = "kalama",
        buildFingerprint =
            "asus/WW_AI2401_D/AI2401:14/UP1A.231005.007/34.1420.1420.309:user/release-keys"
    )

    val xiaomi14: DeviceSpoofConfig = DeviceSpoofConfig(
        presetName = "Xiaomi 14",
        brand = "Xiaomi",
        manufacturer = "Xiaomi",
        model = "23127PN0CC",
        device = "houji",
        board = "houji",
        buildFingerprint =
            "Xiaomi/houji_global/houji:14/UKQ1.240116.001/OS1.0.4.0.UNCMIXM:user/release-keys"
    )

    val all: List<DeviceSpoofConfig> = listOf(pixel8Pro, galaxyS24Ultra, rogPhone8, xiaomi14)

    val names: List<String> = all.map { it.presetName }

    fun byName(name: String): DeviceSpoofConfig =
        all.firstOrNull { it.presetName.equals(name, ignoreCase = true) } ?: pixel8Pro
}

/**
 * Deterministic-capable generator for guest identifiers. Everything is derived
 * from an injectable [Random] so unit tests can pin the values.
 */
object DeviceSpoofGenerator {

    private const val IMEI_TAC_PREFIX = "35"
    private const val IMEI_LENGTH = 15
    private const val ANDROID_ID_LENGTH = 16
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Returns a 15 digit IMEI that satisfies the Luhn checksum. */
    fun generateImei(random: Random = Random.Default): String {
        val body = StringBuilder(IMEI_LENGTH)
        body.append(IMEI_TAC_PREFIX)
        // 6 digit Type Allocation Code remainder + 6 digit serial.
        repeat(IMEI_LENGTH - 1 - IMEI_TAC_PREFIX.length) {
            body.append(random.nextInt(10))
        }
        val checkDigit = luhnCheckDigit(body.toString())
        return body.append(checkDigit).toString()
    }

    /** 16 lowercase hex characters, matching Android's ANDROID_ID shape. */
    fun generateAndroidId(random: Random = Random.Default): String {
        val out = StringBuilder(ANDROID_ID_LENGTH)
        repeat(ANDROID_ID_LENGTH) { out.append(HEX_CHARS[random.nextInt(HEX_CHARS.size)]) }
        return out.toString()
    }

    /**
     * Locally administered unicast MAC: bit 0 of the first octet is cleared
     * (unicast) and bit 1 is set (locally administered).
     */
    fun generateMac(random: Random = Random.Default): String {
        val bytes = ByteArray(6) { random.nextInt(256).toByte() }
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Luhn check digit for a numeric body (no check digit yet). */
    fun luhnCheckDigit(body: String): Int {
        require(body.isNotEmpty()) { "body must not be empty" }
        require(body.all { it.isDigit() }) { "body must be numeric" }
        var sum = 0
        var double = true // rightmost body digit is doubled when the check digit follows it
        for (index in body.indices.reversed()) {
            var digit = body[index] - '0'
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return (10 - (sum % 10)) % 10
    }

    /** Validates a complete IMEI string with the Luhn checksum. */
    fun isValidImei(imei: String): Boolean {
        if (imei.length != IMEI_LENGTH || imei.any { !it.isDigit() }) return false
        var sum = 0
        for (index in imei.indices) {
            var digit = imei[index] - '0'
            // For a 15 digit IMEI the odd left-hand indexes are doubled.
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return sum % 10 == 0
    }

    fun isValidMac(mac: String): Boolean {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return false
        val octets = parts.map { part ->
            if (part.length != 2) return false
            part.toIntOrNull(16) ?: return false
        }
        val first = octets[0]
        val unicast = first and 0x01 == 0
        val locallyAdministered = first and 0x02 != 0
        return unicast && locallyAdministered && octets.all { it in 0..255 }
    }

    fun isValidAndroidId(id: String): Boolean =
        id.length == ANDROID_ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    /** Clones a preset and fills in freshly generated identifiers. */
    fun forPreset(
        preset: DeviceSpoofConfig = DevicePresets.pixel8Pro,
        random: Random = Random.Default
    ): DeviceSpoofConfig = preset.copy(
        enabled = true,
        imei = generateImei(random),
        androidId = generateAndroidId(random),
        wifiMac = generateMac(random)
    )

    fun randomIdentity(random: Random = Random.Default): DeviceSpoofConfig =
        forPreset(DevicePresets.all[random.nextInt(DevicePresets.all.size)], random)
}

@Serializable
data class RootXposedConfig(
    val rootEnabled: Boolean = false,
    val xposedEnabled: Boolean = false,
    val selinuxPermissive: Boolean = false,
    val magiskHideEnabled: Boolean = false,
    val stagedModules: List<String> = emptyList()
)

/** How the mock GPS fix is driven from the UI. */
@Serializable
enum class VirtualLocationMode {
    /** One fixed point, optionally with micro jitter. */
    STATIC,

    /** Walk along [VirtualLocationConfig.routeWaypoints]. */
    ROUTE_WALK,

    /** Manual D-Pad / joystick stepping. */
    JOYSTICK
}

/** A single WGS-84 coordinate, optionally labelled for route waypoints. */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 35.0,
    val label: String = ""
) {
    override fun toString(): String =
        String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude)
}

/**
 * Mock GPS configuration handed to the guest through NMEA sentences, the
 * `persist.novavm.gps.*` properties and the ADB `emu geo fix` command.
 */
@Serializable
data class VirtualLocationConfig(
    val enabled: Boolean = false,
    val presetName: String = "北京 · 天安门",
    val latitude: Double = 39.9042,
    val longitude: Double = 116.4074,
    val altitude: Double = 43.5,
    val accuracyMeters: Float = 5.0f,
    val speedMps: Float = 1.4f,
    val bearingDegrees: Float = 0.0f,
    val mode: VirtualLocationMode = VirtualLocationMode.STATIC,
    val jitterEnabled: Boolean = true,
    val routeWaypoints: List<GeoPoint> = emptyList()
) {
    val geoPoint: GeoPoint get() = GeoPoint(latitude, longitude, altitude, presetName)

    fun withCoordinates(
        latitude: Double,
        longitude: Double,
        presetName: String = this.presetName
    ): VirtualLocationConfig = copy(
        latitude = latitude,
        longitude = longitude,
        presetName = presetName
    )
}

/** Curated one-tap teleport targets for the virtual location radar. */
object LocationPresets {

    private fun preset(name: String, lat: Double, lon: Double, alt: Double) =
        VirtualLocationConfig(
            enabled = true,
            presetName = name,
            latitude = lat,
            longitude = lon,
            altitude = alt
        )

    val beijingTiananmen: VirtualLocationConfig = preset("北京 · 天安门", 39.9042, 116.4074, 43.5)
    val shanghaiLujiazui: VirtualLocationConfig = preset("上海 · 陆家嘴", 31.2397, 121.4998, 4.0)
    val shenzhenNanshan: VirtualLocationConfig = preset("深圳 · 南山科技园", 22.5404, 113.9445, 12.0)
    val tokyoShibuya: VirtualLocationConfig = preset("东京 · 涩谷", 35.6595, 139.7004, 40.0)
    val sanFranciscoApplePark: VirtualLocationConfig = preset("旧金山 · Apple Park", 37.3346, -122.0090, 30.0)
    val newYorkTimesSquare: VirtualLocationConfig = preset("纽约 · 时代广场", 40.7580, -73.9855, 10.0)
    val londonBigBen: VirtualLocationConfig = preset("伦敦 · 大本钟", 51.5007, -0.1246, 15.0)

    val all: List<VirtualLocationConfig> = listOf(
        beijingTiananmen,
        shanghaiLujiazui,
        shenzhenNanshan,
        tokyoShibuya,
        sanFranciscoApplePark,
        newYorkTimesSquare,
        londonBigBen
    )

    val names: List<String> = all.map { it.presetName }

    val points: List<GeoPoint> = all.map { it.geoPoint }

    fun byName(name: String): VirtualLocationConfig =
        all.firstOrNull { it.presetName.equals(name, ignoreCase = true) } ?: beijingTiananmen
}

@Serializable
data class VmSnapshot(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val description: String = ""
)

@Serializable
data class VmProfile(
    val id: String,
    val name: String,
    val architecture: VmArchitecture,
    val backendType: VmBackendType,
    val romPath: String,
    val diskImagePath: String,
    val hardware: VmHardwareConfig = VmHardwareConfig(),
    val deviceSpoof: DeviceSpoofConfig = DevicePresets.pixel8Pro,
    val rootXposed: RootXposedConfig = RootXposedConfig(),
    val virtualLocation: VirtualLocationConfig = VirtualLocationConfig(),
    val vncPort: Int = 5901,
    val qmpPort: Int = 4444,
    val adbForwardPort: Int = 15555,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val snapshots: List<VmSnapshot> = emptyList()
) {
    val displayName: String get() = name.ifBlank { id }

    fun withSnapshot(snapshot: VmSnapshot): VmProfile =
        copy(snapshots = snapshots + snapshot)

    fun withoutSnapshot(snapshotId: String): VmProfile =
        copy(snapshots = snapshots.filterNot { it.id == snapshotId })

    companion object {
        fun create(
            name: String,
            architecture: VmArchitecture = VmArchitecture.ARM64,
            backendType: VmBackendType = VmBackendType.AUTO,
            romPath: String = "",
            diskImagePath: String = "",
            hardware: VmHardwareConfig = VmHardwareConfig(),
            deviceSpoof: DeviceSpoofConfig = DevicePresets.pixel8Pro,
            rootXposed: RootXposedConfig = RootXposedConfig(),
            virtualLocation: VirtualLocationConfig = VirtualLocationConfig(),
            vncPort: Int = 5901,
            qmpPort: Int = 4444,
            adbForwardPort: Int = 15555,
            createdAtEpochMs: Long = System.currentTimeMillis(),
            id: String = UUID.randomUUID().toString()
        ): VmProfile = VmProfile(
            id = id,
            name = name,
            architecture = architecture,
            backendType = backendType,
            romPath = romPath,
            diskImagePath = diskImagePath,
            hardware = hardware,
            deviceSpoof = deviceSpoof,
            rootXposed = rootXposed,
            virtualLocation = virtualLocation,
            vncPort = vncPort,
            qmpPort = qmpPort,
            adbForwardPort = adbForwardPort,
            createdAtEpochMs = createdAtEpochMs
        )
    }
}

/** Curated, freely redistributable guest images. */
@Serializable
data class RomCatalogItem(
    val id: String,
    val name: String,
    val version: String,
    val architecture: VmArchitecture,
    val category: String,
    val description: String,
    val downloadUrl: String,
    val sourceUrl: String,
    val license: String,
    val approxSizeMb: Long,
    val requiresKvm: Boolean = false
)

@Serializable
data class ImportedRom(
    val id: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val format: String,
    val sizeBytes: Long,
    val architecture: VmArchitecture = VmArchitecture.ARM64,
    val importedAtEpochMs: Long = System.currentTimeMillis()
)
