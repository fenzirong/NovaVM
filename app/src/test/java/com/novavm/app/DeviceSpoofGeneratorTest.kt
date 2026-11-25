package com.novavm.app

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeviceSpoofGeneratorTest {

    private val imeiPattern = Regex("^\\d{15}$")
    private val androidIdPattern = Regex("^[0-9a-f]{16}$")
    private val macPattern = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

    @Test
    fun `generated IMEI is 15 digits and Luhn valid`() {
        repeat(50) { seed ->
            val imei = DeviceSpoofGenerator.generateImei(Random(seed))
            assertTrue("bad shape: $imei", imeiPattern.matches(imei))
            assertTrue("bad Luhn: $imei", DeviceSpoofGenerator.isValidImei(imei))
            assertTrue("IMEI should start with the 35 TAC prefix", imei.startsWith("35"))
        }
    }

    @Test
    fun `IMEI generation is deterministic for a fixed seed`() {
        val first = DeviceSpoofGenerator.generateImei(Random(1234))
        val second = DeviceSpoofGenerator.generateImei(Random(1234))
        assertEquals(first, second)
        assertNotEquals(first, DeviceSpoofGenerator.generateImei(Random(99)))
    }

    @Test
    fun `Luhn validation accepts known good IMEI and rejects tampering`() {
        val knownGood = "490154203237518"
        assertTrue(DeviceSpoofGenerator.isValidImei(knownGood))
        assertFalse(DeviceSpoofGenerator.isValidImei("490154203237519"))
        assertFalse(DeviceSpoofGenerator.isValidImei("49015420323751"))
        assertFalse(DeviceSpoofGenerator.isValidImei("49015420323751x"))
    }

    @Test
    fun `check digit produces a Luhn valid sequence`() {
        for (seed in 0 until 25) {
            val random = Random(seed * 31)
            val body = buildString { repeat(14) { append(random.nextInt(10)) } }
            val complete = body + DeviceSpoofGenerator.luhnCheckDigit(body)
            assertTrue("invalid Luhn for $complete", DeviceSpoofGenerator.isValidImei(complete))
        }
    }

    @Test
    fun `generated Android ID is 16 lowercase hex characters`() {
        val ids = mutableSetOf<String>()
        repeat(40) { seed ->
            val id = DeviceSpoofGenerator.generateAndroidId(Random(seed))
            assertTrue("bad android id: $id", androidIdPattern.matches(id))
            assertTrue(DeviceSpoofGenerator.isValidAndroidId(id))
            ids += id
        }
        assertTrue("Android IDs should not collide across seeds", ids.size > 35)
    }

    @Test
    fun `generated MAC is locally administered unicast`() {
        repeat(40) { seed ->
            val mac = DeviceSpoofGenerator.generateMac(Random(seed))
            assertTrue("bad mac shape: $mac", macPattern.matches(mac))
            assertTrue("mac not unicast/local: $mac", DeviceSpoofGenerator.isValidMac(mac))

            val firstOctet = mac.substringBefore(':').toInt(16)
            assertEquals("multicast bit must be clear", 0, firstOctet and 0x01)
            assertEquals("locally administered bit must be set", 0x02, firstOctet and 0x02)
        }
    }

    @Test
    fun `MAC validation rejects multicast and globally administered addresses`() {
        assertFalse(DeviceSpoofGenerator.isValidMac("01:23:45:67:89:ab"))
        assertFalse(DeviceSpoofGenerator.isValidMac("00:11:22:33:44:55"))
        assertFalse(DeviceSpoofGenerator.isValidMac("02:11:22:33:44"))
        assertTrue(DeviceSpoofGenerator.isValidMac("02:11:22:33:44:55"))
    }

    @Test
    fun `all four device presets are complete and distinct`() {
        val presets = DevicePresets.all
        assertEquals(4, presets.size)
        assertEquals(4, presets.map { it.presetName }.toSet().size)
        assertEquals(4, presets.map { it.model }.toSet().size)

        presets.forEach { preset ->
            assertTrue("brand missing for ${preset.presetName}", preset.brand.isNotBlank())
            assertTrue("manufacturer missing", preset.manufacturer.isNotBlank())
            assertTrue("model missing", preset.model.isNotBlank())
            assertTrue("device missing", preset.device.isNotBlank())
            assertTrue("board missing", preset.board.isNotBlank())
            assertTrue("fingerprint missing", preset.buildFingerprint.contains("release-keys"))
            assertTrue("enabled by default", preset.enabled)
        }
        assertEquals(DevicePresets.pixel8Pro, DevicePresets.byName("Pixel 8 Pro"))
        assertEquals(DevicePresets.galaxyS24Ultra, DevicePresets.byName("galaxy s24 ultra"))
        assertEquals(DevicePresets.rogPhone8, DevicePresets.byName("ROG Phone 8"))
        assertEquals(DevicePresets.xiaomi14, DevicePresets.byName("Xiaomi 14"))
        assertEquals(DevicePresets.pixel8Pro, DevicePresets.byName("unknown device"))
    }

    @Test
    fun `forPreset keeps identity and fills generated identifiers`() {
        val generated = DeviceSpoofGenerator.forPreset(DevicePresets.xiaomi14, Random(7))
        assertEquals(DevicePresets.xiaomi14.model, generated.model)
        assertEquals(DevicePresets.xiaomi14.brand, generated.brand)
        assertTrue(generated.enabled)
        assertTrue(DeviceSpoofGenerator.isValidImei(generated.imei))
        assertTrue(DeviceSpoofGenerator.isValidAndroidId(generated.androidId))
        assertTrue(DeviceSpoofGenerator.isValidMac(generated.wifiMac))
    }

    @Test
    fun `randomIdentity returns one of the curated presets`() {
        repeat(20) { seed ->
            val identity = DeviceSpoofGenerator.randomIdentity(Random(seed))
            assertNotNull(DevicePresets.all.firstOrNull { it.model == identity.model })
            assertTrue(DeviceSpoofGenerator.isValidImei(identity.imei))
        }
    }
}
