package com.novavm.app

import com.novavm.app.engine.HostCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCapabilitiesTest {

    private val payload = """
        {
          "kvmAvailable": true,
          "vhostVsockAvailable": true,
          "avfSupported": true,
          "cpuCores": 8,
          "totalRamMb": 12000,
          "primaryAbi": "arm64-v8a",
          "qemuBinaryPresent": true
        }
    """.trimIndent()

    @Test
    fun `parses native capability JSON`() {
        val capabilities = HostCapabilities.fromNative(payload, sdkInt = 34)

        assertTrue(capabilities.kvmAvailable)
        assertTrue(capabilities.vhostVsockAvailable)
        assertTrue(capabilities.avfSupported)
        assertTrue(capabilities.avfUsable)
        assertTrue(capabilities.hardwareAccelerated)
        assertEquals("kvm", capabilities.acceleration)
        assertEquals(8, capabilities.cpuCores)
        assertEquals(12000L, capabilities.totalRamMb)
        assertEquals("arm64-v8a", capabilities.primaryAbi)
        assertTrue(capabilities.qemuBinaryPresent)
        assertEquals(34, capabilities.sdkInt)
        assertEquals(6000, capabilities.recommendedRamMb)
        assertEquals("AVF", capabilities.preferredBackend)
    }

    @Test
    fun `AVF requires API 33 or newer`() {
        val old = HostCapabilities.fromNative(payload, sdkInt = 32)
        assertFalse(old.avfUsable)
        assertEquals("QEMU", old.preferredBackend)

        val new = HostCapabilities.fromNative(payload, sdkInt = 33)
        assertTrue(new.avfUsable)
    }

    @Test
    fun `TCG is selected without KVM`() {
        val noKvm = HostCapabilities.fromNative(
            """{"kvmAvailable":false,"avfSupported":false,"cpuCores":4,"totalRamMb":4000,"primaryAbi":"x86_64"}""",
            sdkInt = 34
        )
        assertEquals("tcg,thread=multi", noKvm.acceleration)
        assertEquals("QEMU", noKvm.preferredBackend)
        assertFalse(noKvm.avfUsable)
        assertEquals(2000, noKvm.recommendedRamMb)
    }

    @Test
    fun `malformed payload degrades to safe defaults`() {
        val capabilities = HostCapabilities.fromNative("not json", sdkInt = 34)
        assertFalse(capabilities.kvmAvailable)
        assertFalse(capabilities.avfUsable)
        assertEquals(1, capabilities.cpuCores)
        assertEquals("unknown", capabilities.primaryAbi)
        assertEquals(2048, capabilities.recommendedRamMb)
    }

    @Test
    fun `recommended RAM is clamped`() {
        val tiny = HostCapabilities.conservative(sdkInt = 34).copy(totalRamMb = 256)
        assertEquals(512, tiny.recommendedRamMb)

        val huge = HostCapabilities.conservative(sdkInt = 34).copy(totalRamMb = 64000)
        assertEquals(8192, huge.recommendedRamMb)

        val unknown = HostCapabilities.conservative(sdkInt = 34).copy(totalRamMb = -1)
        assertEquals(2048, unknown.recommendedRamMb)
    }

    @Test
    fun `summary mentions the key facts`() {
        val summary = HostCapabilities.fromNative(payload, sdkInt = 34).summary()
        assertTrue(summary.contains("ABI=arm64-v8a"))
        assertTrue(summary.contains("cores=8"))
        assertTrue(summary.contains("KVM=yes"))
        assertTrue(summary.contains("AVF=yes"))
    }
}
