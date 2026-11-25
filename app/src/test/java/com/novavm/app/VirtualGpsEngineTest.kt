package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.GeoPoint
import com.novavm.app.data.LocationPresets
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VirtualLocationMode
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.engine.HostCapabilities
import com.novavm.app.engine.QemuBackend
import com.novavm.app.engine.VmInstanceManager
import com.novavm.app.location.GeoMath
import com.novavm.app.location.NmeaGenerator
import com.novavm.app.location.VirtualGpsEngine
import com.novavm.app.root.RootXposedConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.random.Random

class VirtualGpsEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = VirtualGpsEngine(clock = { FIXED_EPOCH_MS })

    private val hardware = VmHardwareConfig(width = 1080, height = 2400, dpi = 420)

    private val capabilities = HostCapabilities(
        kvmAvailable = true,
        vhostVsockAvailable = true,
        avfSupported = false,
        cpuCores = 8,
        totalRamMb = 8192,
        primaryAbi = "arm64-v8a",
        qemuBinaryPresent = true,
        sdkInt = 34
    )

    @Before
    fun setUp() {
        VmInstanceManager.resetForTests()
    }

    // ------------------------------------------------------------- fixtures

    private fun profile(
        gps: VirtualLocationConfig = gpsConfig(),
        romPath: String = "",
        architecture: VmArchitecture = VmArchitecture.ARM64
    ): VmProfile = VmProfile.create(
        id = "vm-gps",
        name = "GPS VM",
        architecture = architecture,
        backendType = VmBackendType.QEMU,
        romPath = romPath,
        diskImagePath = "/data/vms/vm-gps/disk.qcow2",
        hardware = hardware,
        deviceSpoof = DevicePresets.pixel8Pro,
        rootXposed = RootXposedConfig(rootEnabled = true),
        virtualLocation = gps,
        vncPort = 5901,
        qmpPort = 4444,
        adbForwardPort = 15555
    )

    private fun gpsConfig(
        latitude: Double = 39.9042,
        longitude: Double = 116.4074,
        altitude: Double = 43.5,
        enabled: Boolean = true,
        jitter: Boolean = false
    ): VirtualLocationConfig = VirtualLocationConfig(
        enabled = enabled,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracyMeters = 5.0f,
        speedMps = 1.4f,
        bearingDegrees = 0.0f,
        mode = VirtualLocationMode.STATIC,
        jitterEnabled = jitter
    )

    /** Independent XOR implementation used to validate generated sentences. */
    private fun xorChecksum(sentence: String): String {
        val body = sentence.substringAfter('$').substringBefore('*')
        var checksum = 0
        for (char in body) checksum = checksum xor char.code
        return String.format(Locale.US, "%02X", checksum and 0xFF)
    }

    private fun assertValidSentence(sentence: String) {
        assertTrue("sentence must start with \$: $sentence", sentence.startsWith("\$"))
        val star = sentence.lastIndexOf('*')
        assertTrue("sentence must carry a checksum: $sentence", star > 1)
        assertEquals(
            "checksum length: $sentence",
            2,
            sentence.length - star - 1
        )
        assertEquals(
            "checksum mismatch: $sentence",
            xorChecksum(sentence),
            sentence.substring(star + 1)
        )
        assertTrue(
            "not a GP sentence: $sentence",
            sentence.matches(Regex("^\\\$GP[A-Z]{3},.*\\*[0-9A-F]{2}$"))
        )
    }

    // ---------------------------------------------------------------- NMEA

    @Test
    fun `checksum matches published NMEA reference sentences`() {
        // Reference sentences from the NMEA-0183 specification appendix.
        assertEquals(
            "1D",
            NmeaGenerator.computeChecksum("GPGLL,4916.45,N,12311.12,W,225444,A,")
        )
        assertEquals(
            "47",
            NmeaGenerator.computeChecksum(
                "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
            )
        )
        assertEquals(
            "6A",
            NmeaGenerator.computeChecksum(
                "GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"
            )
        )
        assertEquals("41", NmeaGenerator.computeChecksum("A"))
        assertEquals(
            "\$GPGGA,1*" + NmeaGenerator.computeChecksum("GPGGA,1"),
            NmeaGenerator.withChecksum("GPGGA,1")
        )
    }

    @Test
    fun `gpgga encodes north east coordinates for Beijing`() {
        val sentence = NmeaGenerator.formatGpgga(
            lat = 39.9042,
            lon = 116.4074,
            alt = 43.5,
            accuracy = 5.0f,
            utcEpochMs = FIXED_EPOCH_MS
        )

        assertValidSentence(sentence)
        assertTrue(sentence.startsWith("\$GPGGA,221320.00,3954.2520,N,11624.4440,E,"))
        assertTrue("fix quality + satellites: $sentence", sentence.contains(",1,08,"))
        assertTrue("hdop from accuracy: $sentence", sentence.contains(",1.0,"))
        assertTrue("altitude: $sentence", sentence.contains(",43.5,M,0.0,M,,"))
        assertEquals("3954.2520" to "N", NmeaGenerator.latitudeField(39.9042))
        assertEquals("11624.4440" to "E", NmeaGenerator.longitudeField(116.4074))
    }

    @Test
    fun `gpgga encodes western and southern hemisphere coordinates`() {
        val sanFrancisco = NmeaGenerator.formatGpgga(
            lat = 37.3346,
            lon = -122.0090,
            alt = 30.0,
            accuracy = 3.0f,
            utcEpochMs = FIXED_EPOCH_MS
        )
        assertValidSentence(sanFrancisco)
        assertTrue(
            "north-west fix: $sanFrancisco",
            sanFrancisco.contains(",3720.0760,N,12200.5400,W,")
        )

        val sydney = NmeaGenerator.formatGpgga(
            lat = -33.8688,
            lon = 151.2093,
            alt = 12.0,
            accuracy = 8.0f,
            utcEpochMs = FIXED_EPOCH_MS
        )
        assertValidSentence(sydney)
        assertTrue(
            "south-east fix: $sydney",
            sydney.contains(",3352.1280,S,15112.5580,E,")
        )
        assertEquals("3352.1280" to "S", NmeaGenerator.latitudeField(-33.8688))
        assertEquals("12200.5400" to "W", NmeaGenerator.longitudeField(-122.0090))
    }

    @Test
    fun `gprmc converts meters per second to knots`() {
        val sentence = NmeaGenerator.formatGprmc(
            lat = 39.9042,
            lon = 116.4074,
            speedMps = 1.4f,
            bearingDeg = 84.4f,
            utcEpochMs = FIXED_EPOCH_MS
        )

        assertValidSentence(sentence)
        assertTrue(sentence.startsWith("\$GPRMC,221320.00,A,3954.2520,N,11624.4440,E,"))
        // 1.4 m/s == 2.72 knots
        assertTrue("knots: $sentence", sentence.contains(",2.72,84.4,141123,"))
        assertTrue("mode indicator: $sentence", sentence.contains(",141123,,,A*"))

        val knots = 1.4f * 1.943844f
        assertEquals(2.7213817f, knots, 0.0001f)
    }

    @Test
    fun `utc fields are derived from the epoch`() {
        assertEquals("000000.00", NmeaGenerator.formatUtcTime(0L))
        assertEquals("010170", NmeaGenerator.formatUtcDate(0L))
        assertEquals("221320.00", NmeaGenerator.formatUtcTime(FIXED_EPOCH_MS))
        assertEquals("141123", NmeaGenerator.formatUtcDate(FIXED_EPOCH_MS))

        val midnight = NmeaGenerator.formatGpgga(0.0, 0.0, 0.0, 5.0f, 0L)
        assertTrue(midnight.startsWith("\$GPGGA,000000.00,0000.0000,N,00000.0000,E,"))
        assertValidSentence(midnight)
    }

    // ------------------------------------------------------------ GeoMath

    @Test
    fun `coordinate math clamps and wraps`() {
        assertEquals(90.0, GeoMath.clampLat(120.0), 0.0)
        assertEquals(-90.0, GeoMath.clampLat(-120.0), 0.0)
        assertEquals(45.5, GeoMath.clampLat(45.5), 0.0)

        assertEquals(-179.0, GeoMath.normalizeLon(181.0), 1e-9)
        assertEquals(179.0, GeoMath.normalizeLon(-181.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeLon(360.0), 1e-9)
        assertEquals(-170.0, GeoMath.normalizeLon(190.0), 1e-9)
        assertTrue(GeoMath.normalizeLon(1000.0) in -180.0..180.0)
    }

    @Test
    fun `step position moves in the four cardinal directions`() {
        val north = GeoMath.stepPosition(0.0, 0.0, 0.0, 1000.0)
        assertEquals(0.008993, north.latitude, 1e-5)
        assertEquals(0.0, north.longitude, 1e-9)

        val south = GeoMath.stepPosition(0.0, 0.0, 180.0, 1000.0)
        assertEquals(-0.008993, south.latitude, 1e-5)

        val east = GeoMath.stepPosition(0.0, 0.0, 90.0, 1000.0)
        assertEquals(0.008993, east.longitude, 1e-5)
        assertEquals(0.0, east.latitude, 1e-9)

        val west = GeoMath.stepPosition(0.0, 0.0, 270.0, 1000.0)
        assertEquals(-0.008993, west.longitude, 1e-5)

        // Every cardinal leg really is ~1 km on the sphere.
        listOf(north, south, east, west).forEach { point ->
            val meters = GeoMath.distanceMeters(0.0, 0.0, point.latitude, point.longitude)
            assertEquals(1000.0, meters, 1.0)
        }
    }

    @Test
    fun `step position wraps across the antimeridian and clamps at the pole`() {
        val wrapped = GeoMath.stepPosition(0.0, 179.995, 90.0, 1000.0)
        assertTrue("longitude must wrap west: ${wrapped.longitude}", wrapped.longitude < -179.0)
        assertTrue(wrapped.longitude in -180.0..180.0)

        // A 10 km northward step from 89.999° folds over the pole and stays legal.
        val overPole = GeoMath.stepPosition(89.999, 10.0, 0.0, 10_000.0)
        assertTrue("pole crossing must stay valid: ${overPole.latitude}", overPole.latitude in 89.8..90.0)

        // Out-of-range inputs are clamped/normalised before the step.
        val clampedOrigin = GeoMath.stepPosition(120.0, 400.0, 0.0, 0.0)
        assertEquals(90.0, clampedOrigin.latitude, 1e-9)
        assertEquals(40.0, clampedOrigin.longitude, 1e-9)

        val fromClamped = GeoMath.stepPosition(120.0, 400.0, 45.0, 100.0)
        assertTrue(fromClamped.latitude in -90.0..90.0)
        assertTrue(fromClamped.longitude in -180.0..180.0)
    }

    @Test
    fun `jitter stays inside the radius and is seed stable`() {
        val first = GeoMath.applyJitter(39.9042, 116.4074, 2.0, Random(2024))
        val second = GeoMath.applyJitter(39.9042, 116.4074, 2.0, Random(2024))
        assertEquals(first, second)

        val moved = GeoMath.distanceMeters(39.9042, 116.4074, first.latitude, first.longitude)
        assertTrue("jitter must stay under 2m, was $moved", moved <= 2.0 + 1e-6)
        assertNotEquals(GeoPoint(39.9042, 116.4074), first)

        val pinned = GeoMath.applyJitter(39.9042, 116.4074, 0.0, Random(7))
        assertEquals(39.9042, pinned.latitude, 0.0)
        assertEquals(116.4074, pinned.longitude, 0.0)
    }

    // ---------------------------------------------------------- syncToVm

    @Test
    fun `syncToVm writes nmea json and injector script`() {
        val shared = temp.newFolder("shared")
        val vmProfile = profile()

        val result = engine.syncToVm(vmProfile, shared)

        assertTrue(result.nmeaFile.isFile)
        assertTrue(result.scriptFile.isFile)
        assertTrue(File(shared, VirtualGpsEngine.JSON_FILE_NAME).isFile)
        assertEquals(VirtualGpsEngine.NMEA_FILE_NAME, result.nmeaFile.name)
        assertEquals(VirtualGpsEngine.SCRIPT_FILE_NAME, result.scriptFile.name)

        assertValidSentence(result.gpgga)
        assertValidSentence(result.gprmc)
        assertTrue(result.gpgga.startsWith("\$GPGGA,"))
        assertTrue(result.gprmc.startsWith("\$GPRMC,"))

        val nmea = result.nmeaFile.readText()
        assertTrue(nmea.contains(result.gpgga))
        assertTrue(nmea.contains(result.gprmc))
        assertEquals(2, nmea.split("\r\n").filter { it.isNotBlank() }.size)

        val json = File(shared, VirtualGpsEngine.JSON_FILE_NAME).readText()
        assertTrue(json.contains("\"timestampEpochMs\": $FIXED_EPOCH_MS"))
        assertTrue(json.contains("\"latitude\": 39.9042"))
        assertTrue(json.contains("\"longitude\": 116.4074"))
        assertTrue(json.contains(result.gpgga))
        assertTrue(json.contains("\"adbGeoCommand\""))

        val script = result.scriptFile.readText()
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("resetprop persist.novavm.gps.enabled 1"))
        assertTrue(script.contains("resetprop persist.novavm.gps.lat \"39.904200\""))
        assertTrue(script.contains("resetprop persist.novavm.gps.lon \"116.407400\""))
        assertTrue(script.contains("resetprop persist.novavm.gps.alt \"43.5\""))
        assertTrue(script.contains("resetprop persist.novavm.gps.mode \"static\""))
        assertTrue(script.contains(VirtualGpsEngine.NMEA_FILE_NAME))
        assertTrue(script.contains("adb -s 127.0.0.1:15555 emu geo fix 116.407400 39.904200 43.5"))

        assertEquals(
            "adb -s 127.0.0.1:15555 emu geo fix 116.407400 39.904200 43.5",
            result.adbGeoCommand
        )
        assertTrue(script.contains(result.adbGeoCommand))
    }

    @Test
    fun `syncToVm creates nested shared folders and honors jitter switch`() {
        val nested = File(temp.root, "vms/vm-gps/shared")
        assertFalse(nested.exists())

        val fixed = engine.syncToVm(profile(gps = gpsConfig(jitter = false)), nested)
        assertTrue(nested.isDirectory)
        assertTrue(fixed.gpgga.contains(",3954.2520,N,11624.4440,E,"))

        val jittered = engine.syncToVm(
            profile(gps = gpsConfig(jitter = true)),
            nested,
            Random(99)
        )
        assertValidSentence(jittered.gpgga)
        assertValidSentence(jittered.gprmc)
        assertTrue(jittered.gpgga.contains("\$GPGGA,"))
    }

    @Test
    fun `location presets expose seven curated teleport targets`() {
        assertEquals(7, LocationPresets.all.size)
        assertEquals(7, LocationPresets.names.toSet().size)
        assertEquals("北京 · 天安门", LocationPresets.beijingTiananmen.presetName)
        assertEquals(39.9042, LocationPresets.beijingTiananmen.latitude, 0.0)
        assertEquals(116.4074, LocationPresets.beijingTiananmen.longitude, 0.0)
        assertEquals(31.2397, LocationPresets.shanghaiLujiazui.latitude, 0.0)
        assertEquals(121.4998, LocationPresets.shanghaiLujiazui.longitude, 0.0)
        assertEquals(22.5404, LocationPresets.shenzhenNanshan.latitude, 0.0)
        assertEquals(113.9445, LocationPresets.shenzhenNanshan.longitude, 0.0)
        assertEquals(35.6595, LocationPresets.tokyoShibuya.latitude, 0.0)
        assertEquals(139.7004, LocationPresets.tokyoShibuya.longitude, 0.0)
        assertEquals(37.3346, LocationPresets.sanFranciscoApplePark.latitude, 0.0)
        assertEquals(-122.0090, LocationPresets.sanFranciscoApplePark.longitude, 0.0)
        assertEquals(40.7580, LocationPresets.newYorkTimesSquare.latitude, 0.0)
        assertEquals(-73.9855, LocationPresets.newYorkTimesSquare.longitude, 0.0)
        assertEquals(51.5007, LocationPresets.londonBigBen.latitude, 0.0)
        assertEquals(-0.1246, LocationPresets.londonBigBen.longitude, 0.0)
        assertEquals(
            LocationPresets.londonBigBen,
            LocationPresets.byName("伦敦 · 大本钟")
        )
        assertEquals(LocationPresets.beijingTiananmen, LocationPresets.byName("unknown"))
        assertEquals(7, LocationPresets.points.size)
    }

    // ------------------------------------------------------ JSON contracts

    @Test
    fun `profile json without virtualLocation decodes with defaults`() {
        val root = temp.newFolder("legacy")
        val vmsDir = File(root, "vms")
        assertTrue(vmsDir.mkdirs())
        File(vmsDir, "profiles.json").writeText(
            """
            {
              "version": 1,
              "profiles": [
                {
                  "id": "legacy-vm",
                  "name": "Legacy",
                  "architecture": "ARM64",
                  "backendType": "QEMU",
                  "romPath": "",
                  "diskImagePath": "/data/vms/legacy/disk.qcow2"
                }
              ]
            }
            """.trimIndent()
        )

        val loaded = VmRepository(root).loadAll().single()

        assertEquals("legacy-vm", loaded.id)
        assertEquals(VirtualLocationConfig(), loaded.virtualLocation)
        assertFalse(loaded.virtualLocation.enabled)
        assertEquals(39.9042, loaded.virtualLocation.latitude, 0.0)
        assertEquals(116.4074, loaded.virtualLocation.longitude, 0.0)
        assertEquals(VirtualLocationMode.STATIC, loaded.virtualLocation.mode)
        assertTrue(loaded.virtualLocation.routeWaypoints.isEmpty())
    }

    @Test
    fun `profile json round trips the virtual location config`() {
        val root = temp.newFolder("round-trip")
        val repository = VmRepository(root)
        val config = gpsConfig(latitude = 35.6595, longitude = 139.7004).copy(
            presetName = "东京 · 涩谷",
            mode = VirtualLocationMode.ROUTE_WALK,
            routeWaypoints = listOf(
                GeoPoint(35.6595, 139.7004, 40.0, "start"),
                GeoPoint(35.6600, 139.7010, 40.0, "end")
            )
        )
        repository.create(profile(gps = config))

        val raw = repository.profilesFile.readText()
        assertTrue(raw.contains("\"virtualLocation\""))
        assertTrue(raw.contains("\"ROUTE_WALK\""))

        val reloaded = VmRepository(root).get("vm-gps")!!
        assertEquals(config, reloaded.virtualLocation)
        assertEquals(2, reloaded.virtualLocation.routeWaypoints.size)
        assertEquals("start", reloaded.virtualLocation.routeWaypoints.first().label)
    }

    // -------------------------------------------------- engine integration

    @Test
    fun `root configurator emits gps props only when enabled`() {
        val configurator = RootXposedConfigurator(temp.newFolder("vm-root"))
        val enabled = gpsConfig(latitude = 31.2397, longitude = 121.4998, altitude = 4.0)

        val script = configurator.buildBootScript(
            RootXposedConfig(rootEnabled = true),
            DevicePresets.pixel8Pro,
            hardware,
            "GPS VM",
            enabled
        )

        assertTrue(script.contains("resetprop persist.novavm.gps.enabled 1"))
        assertTrue(script.contains("resetprop persist.novavm.gps.lat \"31.239700\""))
        assertTrue(script.contains("resetprop persist.novavm.gps.lon \"121.499800\""))
        assertTrue(script.contains("resetprop persist.novavm.gps.alt \"4.000000\""))
        assertTrue(script.contains("apply_location.sh"))
        assertTrue(script.contains("/data/local/tmp/novavm/apply_location.sh"))

        val initRc = configurator.buildInitRc(
            RootXposedConfig(rootEnabled = true),
            DevicePresets.pixel8Pro,
            hardware,
            "GPS VM",
            enabled
        )
        assertTrue(initRc.contains("setprop persist.novavm.gps.enabled 1"))
        assertTrue(initRc.contains("setprop persist.novavm.gps.lat 31.239700"))

        val disabled = configurator.buildBootScript(
            RootXposedConfig(rootEnabled = true),
            DevicePresets.pixel8Pro,
            hardware,
            "GPS VM"
        )
        assertFalse(disabled.contains("persist.novavm.gps"))
        assertFalse(disabled.contains("apply_location.sh"))
        assertTrue(disabled.contains("Virtual location disabled"))

        val disabledInit = configurator.buildInitRc(
            RootXposedConfig(rootEnabled = true),
            DevicePresets.pixel8Pro,
            hardware,
            "GPS VM"
        )
        assertFalse(disabledInit.contains("persist.novavm.gps"))
    }

    @Test
    fun `qemu backend emits gps kernel args only when enabled`() {
        val backend = QemuBackend()
        val enabled = backend.buildKernelCmdline(
            profile(romPath = "/data/vms/vm-gps/kernel")
        )

        assertTrue(enabled.contains("androidboot.novavm.gps=1"))
        assertTrue(enabled.contains("novavm.gps.lat=39.904200"))
        assertTrue(enabled.contains("novavm.gps.lon=116.407400"))
        assertTrue(enabled.contains("androidboot.novavm.root=1"))

        val command = backend.buildLaunchCommand(
            profile(romPath = "/data/vms/vm-gps/kernel"),
            capabilities
        )
        val append = command[command.indexOf("-append") + 1]
        assertTrue(append.contains("androidboot.novavm.gps=1"))

        val disabled = backend.buildKernelCmdline(
            profile(gps = gpsConfig(enabled = false), romPath = "/data/vms/vm-gps/kernel")
        )
        assertFalse(disabled.contains("androidboot.novavm.gps"))
        assertFalse(disabled.contains("novavm.gps."))

        // Coordinates are locale-stable and never contain the QEMU list comma.
        val western = backend.buildKernelCmdline(
            profile(gps = gpsConfig(latitude = -33.8688, longitude = -151.2093))
        )
        assertTrue(western.contains("novavm.gps.lat=-33.868800"))
        assertTrue(western.contains("novavm.gps.lon=-151.209300"))
        assertFalse(western.contains("novavm.gps.lat=-33,868800"))
    }

    @Test
    fun `qemu backend logs virtual gps status for both switches`() {
        val messages = mutableListOf<String>()
        val backend = QemuBackend(logger = { messages += it })

        backend.buildLaunchCommand(profile(), capabilities)
        assertTrue(messages.any { it.contains("virtual GPS enabled") })
        assertTrue(messages.any { it.contains("北京 · 天安门") })

        messages.clear()
        backend.buildLaunchCommand(profile(gps = gpsConfig(enabled = false)), capabilities)
        assertTrue(messages.any { it.contains("virtual GPS disabled") })
        assertFalse(messages.any { it.contains("virtual GPS enabled") })
    }

    @Test
    fun `instance manager syncs gps files on start and logs coordinate changes`() {
        val root = temp.newFolder("manager")
        val repository = VmRepository(root)
        val manager = VmInstanceManager(
            repository,
            capabilitiesProvider = { capabilities },
            virtualGps = engine,
            clock = { FIXED_EPOCH_MS }
        )
        val created = manager.createProfile(
            name = "GPS VM",
            architecture = VmArchitecture.ARM64,
            hardware = hardware,
            virtualLocation = gpsConfig(jitter = false)
        )

        assertEquals(39.9042, created.virtualLocation.latitude, 0.0)
        val shared = repository.sharedDir(created.id)
        assertTrue(File(shared, VirtualGpsEngine.NMEA_FILE_NAME).isFile)
        assertTrue(File(shared, VirtualGpsEngine.SCRIPT_FILE_NAME).isFile)
        assertTrue(File(shared, VirtualGpsEngine.JSON_FILE_NAME).isFile)
        assertTrue(manager.consoleLog(created.id).any { it.contains("[GPS]") })

        assertTrue(manager.start(created.id))
        assertEquals("39.904200,116.407400", manager.lastVirtualFix(created.id))
        val afterStart = manager.consoleLog(created.id).count { it.contains("[GPS] fix") }
        assertEquals(1, afterStart)

        // Re-syncing unchanged coordinates must not spam the console.
        manager.syncVirtualLocation(created.id, "repeat")
        assertEquals(afterStart, manager.consoleLog(created.id).count { it.contains("[GPS] fix") })

        val moved = GeoMath.stepPosition(39.9042, 116.4074, 90.0, 100.0)
        repository.update(
            created.copy(
                virtualLocation = created.virtualLocation.copy(
                    latitude = moved.latitude,
                    longitude = moved.longitude
                )
            )
        )
        val result = manager.syncVirtualLocation(created.id, "nudge")
        assertNotNull(result)
        assertEquals(
            afterStart + 1,
            manager.consoleLog(created.id).count { it.contains("[GPS] fix") }
        )
        assertTrue(manager.consoleLog(created.id).any { it.contains("emu geo fix") })

        val nmea = File(shared, VirtualGpsEngine.NMEA_FILE_NAME).readText()
        assertTrue(nmea.contains("GPGGA"))
        assertTrue(nmea.contains("GPRMC"))
        assertNotEquals("39.904200,116.407400", manager.lastVirtualFix(created.id))
        assertTrue(
            GeoMath.distanceMeters(
                39.9042,
                116.4074,
                moved.latitude,
                moved.longitude
            ) in 99.0..101.0
        )
    }

    @Test
    fun `virtual location config exposes a geo point helper`() {
        val config = gpsConfig(latitude = 51.5007, longitude = -0.1246)
        assertEquals(GeoPoint(51.5007, -0.1246, 43.5, config.presetName), config.geoPoint)
        assertEquals("51.500700, -0.124600", config.geoPoint.toString())

        val moved = config.withCoordinates(40.7580, -73.9855, "纽约 · 时代广场")
        assertEquals(40.7580, moved.latitude, 0.0)
        assertEquals("纽约 · 时代广场", moved.presetName)
        assertEquals(config.altitude, moved.altitude, 0.0)
    }

    companion object {
        /** 2023-11-14T22:13:20Z — pins NMEA time/date fields in assertions. */
        private const val FIXED_EPOCH_MS = 1_700_000_000_000L
    }
}
