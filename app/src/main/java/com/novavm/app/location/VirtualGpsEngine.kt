package com.novavm.app.location

import com.novavm.app.data.GeoPoint
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VmProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * NMEA-0183 sentence generation for the virtual GPS fix.
 *
 * Sentences are byte-for-byte deterministic for a given timestamp so they can be
 * asserted in unit tests and replayed into a guest GNSS daemon.
 */
object NmeaGenerator {

    private const val HEX_RADIX = 16

    /**
     * XOR of every ASCII character of [body] (the payload between `$` and `*`),
     * rendered as two uppercase hex digits.
     */
    fun computeChecksum(body: String): String {
        var checksum = 0
        for (char in body) checksum = checksum xor char.code
        return (checksum and 0xFF).toString(HEX_RADIX).uppercase(Locale.US).padStart(2, '0')
    }

    /** Wraps a body into a complete `$body*XX` sentence. */
    fun withChecksum(body: String): String = "$" + body + "*" + computeChecksum(body)

    /**
     * `$GPGGA` fix sentence: UTC time, position, fix quality 1 (GPS),
     * satellites, HDOP derived from [accuracy], altitude and geoid separation.
     */
    fun formatGpgga(
        lat: Double,
        lon: Double,
        alt: Double,
        accuracy: Float,
        utcEpochMs: Long
    ): String {
        val latitude = latitudeField(lat)
        val longitude = longitudeField(lon)
        val hdop = (accuracy / 5.0f).coerceIn(0.5f, 9.9f)
        val body = buildString {
            append("GPGGA,")
            append(formatUtcTime(utcEpochMs))
            append(',').append(latitude.first)
            append(',').append(latitude.second)
            append(',').append(longitude.first)
            append(',').append(longitude.second)
            append(",1,08,")
            append(String.format(Locale.US, "%.1f", hdop))
            append(',')
            append(String.format(Locale.US, "%.1f", alt))
            append(",M,0.0,M,,")
        }
        return withChecksum(body)
    }

    /**
     * `$GPRMC` recommended-minimum sentence. Speed is converted from m/s to
     * knots (`* 1.943844`) and [bearingDeg] is emitted as true track.
     */
    fun formatGprmc(
        lat: Double,
        lon: Double,
        speedMps: Float,
        bearingDeg: Float,
        utcEpochMs: Long
    ): String {
        val latitude = latitudeField(lat)
        val longitude = longitudeField(lon)
        val knots = speedMps * 1.943844f
        val body = buildString {
            append("GPRMC,")
            append(formatUtcTime(utcEpochMs))
            append(",A,")
            append(latitude.first).append(',').append(latitude.second)
            append(',').append(longitude.first).append(',').append(longitude.second)
            append(',')
            append(String.format(Locale.US, "%.2f", knots))
            append(',')
            append(String.format(Locale.US, "%.1f", bearingDeg))
            append(',')
            append(formatUtcDate(utcEpochMs))
            append(",,,A")
        }
        return withChecksum(body)
    }

    /** `DDMM.MMMM` plus hemisphere, e.g. `3954.2520` / `N`. */
    fun latitudeField(lat: Double): Pair<String, String> {
        val value = GeoMath.clampLat(lat)
        return degreesMinutes(abs(value), 2) to if (value < 0.0) "S" else "N"
    }

    /** `DDDMM.MMMM` plus hemisphere, e.g. `11624.4440` / `E`. */
    fun longitudeField(lon: Double): Pair<String, String> {
        val value = GeoMath.normalizeLon(lon)
        return degreesMinutes(abs(value), 3) to if (value < 0.0) "W" else "E"
    }

    /** `hhmmss.cc` in UTC, as required by the NMEA time field. */
    fun formatUtcTime(utcEpochMs: Long): String {
        val time = Instant.ofEpochMilli(utcEpochMs).atZone(ZoneOffset.UTC)
        return String.format(
            Locale.US,
            "%02d%02d%02d.%02d",
            time.hour,
            time.minute,
            time.second,
            time.nano / 10_000_000
        )
    }

    /** `ddmmyy` in UTC, as required by the NMEA date field. */
    fun formatUtcDate(utcEpochMs: Long): String {
        val date = Instant.ofEpochMilli(utcEpochMs).atZone(ZoneOffset.UTC)
        return String.format(
            Locale.US,
            "%02d%02d%02d",
            date.dayOfMonth,
            date.monthValue,
            date.year % 100
        )
    }

    private fun degreesMinutes(value: Double, degreeDigits: Int): String {
        var degrees = floor(value).toInt()
        var minutes = (value - degrees) * 60.0
        // Keep the minute field below 60 after rounding to 4 decimals.
        if (round(minutes * 10_000.0) / 10_000.0 >= 60.0) {
            degrees += 1
            minutes = 0.0
        }
        return String.format(Locale.US, "%0${degreeDigits}d%07.4f", degrees, minutes)
    }
}

/**
 * Spherical coordinate math shared by the radar, the D-Pad and the route walker.
 */
object GeoMath {

    /** IUGG mean Earth radius in meters. */
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    fun clampLat(lat: Double): Double = when {
        lat.isNaN() -> 0.0
        lat > 90.0 -> 90.0
        lat < -90.0 -> -90.0
        else -> lat
    }

    /** Wraps [lon] into `-180.0..180.0`. */
    fun normalizeLon(lon: Double): Double {
        if (lon.isNaN() || lon.isInfinite()) return 0.0
        var wrapped = (lon + 180.0) % 360.0
        if (wrapped < 0.0) wrapped += 360.0
        return wrapped - 180.0
    }

    /**
     * Moves [distanceMeters] along [bearingDegrees] (0 = north, clockwise) from
     * the current fix using the great-circle destination formula.
     */
    fun stepPosition(
        lat: Double,
        lon: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ): GeoPoint {
        val startLat = Math.toRadians(clampLat(lat))
        val startLon = Math.toRadians(normalizeLon(lon))
        val bearing = Math.toRadians(bearingDegrees)
        val angular = distanceMeters / EARTH_RADIUS_METERS

        val sinLat2 = sin(startLat) * cos(angular) +
            cos(startLat) * sin(angular) * cos(bearing)
        val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
        val lon2 = startLon + atan2(
            sin(bearing) * sin(angular) * cos(startLat),
            cos(angular) - sin(startLat) * sinLat2
        )
        return GeoPoint(
            latitude = clampLat(Math.toDegrees(lat2)),
            longitude = normalizeLon(Math.toDegrees(lon2))
        )
    }

    /** Great-circle distance in meters, used by the radar scale and tests. */
    fun distanceMeters(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Double {
        val lat1 = Math.toRadians(clampLat(fromLat))
        val lat2 = Math.toRadians(clampLat(toLat))
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(normalizeLon(toLon) - normalizeLon(fromLon))
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    /**
     * Adds a random offset of at most [maxMeters] so a static mock fix still
     * looks like a live GNSS solution.
     */
    fun applyJitter(
        lat: Double,
        lon: Double,
        maxMeters: Double = 2.0,
        random: Random = Random
    ): GeoPoint {
        if (maxMeters <= 0.0) return GeoPoint(clampLat(lat), normalizeLon(lon))
        val bearing = random.nextDouble(0.0, 360.0)
        val distance = maxMeters * sqrt(random.nextDouble(0.0, 1.0))
        return stepPosition(lat, lon, bearing, distance)
    }
}

/** Artifacts produced by a virtual GPS sync into a VM's shared folder. */
data class GpsSyncResult(
    val gpgga: String,
    val gprmc: String,
    val adbGeoCommand: String,
    val nmeaFile: File,
    val scriptFile: File
)

/**
 * Renders a [VirtualLocationConfig] into guest-consumable artifacts.
 *
 * Everything is plain `java.io` so the engine runs identically in unit tests:
 * ```
 * <shared>/novavm_gps.nmea      latest $GPGGA + $GPRMC pair
 * <shared>/novavm_location.json serialized config + timestamp
 * <shared>/apply_location.sh    guest injector (resetprop + fifo + ADB hint)
 * ```
 */
class VirtualGpsEngine(private val clock: () -> Long = System::currentTimeMillis) {

    /** JSON envelope for `novavm_location.json`. */
    @Serializable
    data class LocationSnapshot(
        val config: VirtualLocationConfig,
        val timestampEpochMs: Long,
        val utcIso: String,
        val gpgga: String,
        val gprmc: String,
        val adbGeoCommand: String
    )

    companion object {
        const val NMEA_FILE_NAME = "novavm_gps.nmea"
        const val JSON_FILE_NAME = "novavm_location.json"
        const val SCRIPT_FILE_NAME = "apply_location.sh"

        /** Default jitter radius in meters for a static fix. */
        const val DEFAULT_JITTER_METERS = 2.0

        /** Guest ADB port QEMU forwards to the host. */
        const val GUEST_ADB_PORT = 5555

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }

    /** Coordinate actually reported to the guest (config + optional jitter). */
    fun effectivePoint(
        config: VirtualLocationConfig,
        random: Random = Random.Default
    ): GeoPoint {
        val lat = GeoMath.clampLat(config.latitude)
        val lon = GeoMath.normalizeLon(config.longitude)
        return if (config.jitterEnabled) {
            GeoMath.applyJitter(lat, lon, DEFAULT_JITTER_METERS, random)
                .copy(altitude = config.altitude, label = config.presetName)
        } else {
            GeoPoint(lat, lon, config.altitude, config.presetName)
        }
    }

    /** `$GPGGA` + `$GPRMC` for UI previews, without touching the disk. */
    fun previewNmea(
        config: VirtualLocationConfig,
        utcEpochMs: Long = clock(),
        random: Random = Random.Default
    ): List<String> {
        val point = effectivePoint(config, random)
        return listOf(
            NmeaGenerator.formatGpgga(
                point.latitude,
                point.longitude,
                config.altitude,
                config.accuracyMeters,
                utcEpochMs
            ),
            NmeaGenerator.formatGprmc(
                point.latitude,
                point.longitude,
                config.speedMps,
                config.bearingDegrees,
                utcEpochMs
            )
        )
    }

    /** `adb -s 127.0.0.1:<adbPort> emu geo fix <lon> <lat> <alt>`. */
    fun adbGeoCommand(
        config: VirtualLocationConfig,
        adbPort: Int = GUEST_ADB_PORT
    ): String = String.format(
        Locale.US,
        "adb -s 127.0.0.1:%d emu geo fix %.6f %.6f %.1f",
        adbPort,
        GeoMath.normalizeLon(config.longitude),
        GeoMath.clampLat(config.latitude),
        config.altitude
    )

    /**
     * Writes the NMEA stream, the JSON snapshot and the guest injector script
     * into [vmSharedDir], creating it when missing.
     */
    fun syncToVm(
        profile: VmProfile,
        vmSharedDir: File,
        random: Random = Random.Default
    ): GpsSyncResult {
        if (!vmSharedDir.exists() && !vmSharedDir.mkdirs() && !vmSharedDir.isDirectory) {
            throw IOException("Unable to create ${vmSharedDir.absolutePath}")
        }
        val config = profile.virtualLocation
        val point = effectivePoint(config, random)
        val now = clock()

        val gpgga = NmeaGenerator.formatGpgga(
            point.latitude,
            point.longitude,
            config.altitude,
            config.accuracyMeters,
            now
        )
        val gprmc = NmeaGenerator.formatGprmc(
            point.latitude,
            point.longitude,
            config.speedMps,
            config.bearingDegrees,
            now
        )
        val adbGeoCommand = adbGeoCommand(config, profile.adbForwardPort)

        val nmeaFile = File(vmSharedDir, NMEA_FILE_NAME)
        nmeaFile.writeText(gpgga + "\r\n" + gprmc + "\r\n", StandardCharsets.UTF_8)

        val snapshot = LocationSnapshot(
            config = config,
            timestampEpochMs = now,
            utcIso = Instant.ofEpochMilli(now).toString(),
            gpgga = gpgga,
            gprmc = gprmc,
            adbGeoCommand = adbGeoCommand
        )
        File(vmSharedDir, JSON_FILE_NAME).writeText(
            json.encodeToString(snapshot),
            StandardCharsets.UTF_8
        )

        val scriptFile = File(vmSharedDir, SCRIPT_FILE_NAME)
        scriptFile.writeText(
            buildApplyScript(profile, point, adbGeoCommand),
            StandardCharsets.UTF_8
        )
        scriptFile.setExecutable(true, false)

        return GpsSyncResult(
            gpgga = gpgga,
            gprmc = gprmc,
            adbGeoCommand = adbGeoCommand,
            nmeaFile = nmeaFile,
            scriptFile = scriptFile
        )
    }

    /** Guest script that applies the fix and prints the ADB injection hint. */
    fun buildApplyScript(
        profile: VmProfile,
        point: GeoPoint = profile.virtualLocation.geoPoint,
        adbGeoCommand: String = adbGeoCommand(profile.virtualLocation, profile.adbForwardPort)
    ): String {
        val config = profile.virtualLocation
        val lat = String.format(Locale.US, "%.6f", point.latitude)
        val lon = String.format(Locale.US, "%.6f", point.longitude)
        val alt = String.format(Locale.US, "%.1f", config.altitude)
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# ------------------------------------------------------------------")
            appendLine("# NovaVM virtual location injector (generated, do not edit)")
            appendLine("# Profile: ${profile.displayName}")
            appendLine("# Preset:  ${config.presetName}")
            appendLine("# Mode:    ${config.mode.name.lowercase(Locale.US)}")
            appendLine("# Fix:     $lat, $lon  alt ${alt}m  accuracy ${config.accuracyMeters}m")
            appendLine("# ------------------------------------------------------------------")
            appendLine("resetprop persist.novavm.gps.enabled 1")
            appendLine("resetprop persist.novavm.gps.lat \"$lat\"")
            appendLine("resetprop persist.novavm.gps.lon \"$lon\"")
            appendLine("resetprop persist.novavm.gps.alt \"$alt\"")
            appendLine("resetprop persist.novavm.gps.accuracy \"${config.accuracyMeters}\"")
            appendLine("resetprop persist.novavm.gps.speed \"${config.speedMps}\"")
            appendLine("resetprop persist.novavm.gps.bearing \"${config.bearingDegrees}\"")
            appendLine("resetprop persist.novavm.gps.mode \"${config.mode.name.lowercase(Locale.US)}\"")
            appendLine("resetprop persist.novavm.gps.preset \"${escape(config.presetName)}\"")
            appendLine("")
            appendLine("NMEA_DIR=\"\${NOVAVM_SHARE:-/data/local/tmp/novavm}\"")
            appendLine("NMEA_FILE=\"\$NMEA_DIR/$NMEA_FILE_NAME\"")
            appendLine("log -t novavm \"novavm virtual GPS $lat, $lon\"")
            appendLine("")
            appendLine("# Feed the NMEA stream into a guest GNSS serial/fifo when one exists.")
            appendLine("for node in /dev/novavm_gps /dev/ttyGPS0 /data/vendor/gnss/novavm_gps; do")
            appendLine("  if [ -e \"\$NMEA_FILE\" ] && [ -e \"\$node\" ]; then")
            appendLine("    cat \"\$NMEA_FILE\" > \"\$node\" 2>/dev/null && log -t novavm \"NMEA injected into \$node\"")
            appendLine("  fi")
            appendLine("done")
            appendLine("")
            appendLine("# Host-side injection hint (run from the NovaVM device):")
            appendLine("echo \"$adbGeoCommand\"")
            appendLine("log -t novavm \"$adbGeoCommand\"")
            appendLine("exit 0")
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
