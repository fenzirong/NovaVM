package com.novavm.app

import com.novavm.app.engine.BootConsoleRenderer
import com.novavm.app.engine.BootProgression
import com.novavm.app.engine.BootStage
import com.novavm.app.engine.BootStageRunner
import com.novavm.app.engine.BootStep
import com.novavm.app.engine.advanceProgress
import com.novavm.app.engine.applyTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Regression cover for the "permanently stuck at STAGE 1 / HOST PROBE" bug:
 * the renderer must advance through every stage and the extracted boot-stage
 * controller must always finish at 100%.
 */
class BootStageProgressionTest {

    private fun renderer() = BootConsoleRenderer(360, 640)

    private fun framebuffer(width: Int = 360, height: Int = 640): ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4)

    @Test
    fun `renderer starts at stage one with its default progress`() {
        val console = renderer()
        assertEquals("STAGE 1 / HOST PROBE", console.bootStage)
        assertEquals(BootStage.STAGE_1_HOST_PROBE, console.currentBootStage)
        assertEquals(0.18f, console.progress, 0.0001f)
    }

    @Test
    fun `setBootStage advances label and progress across all five stages`() {
        val console = renderer()
        val expected = listOf(
            BootStage.STAGE_1_HOST_PROBE to 0.18f,
            BootStage.STAGE_2_STORAGE_SHARE to 0.42f,
            BootStage.STAGE_3_HYPERVISOR to 0.68f,
            BootStage.STAGE_4_DISPLAY_BRIDGE to 0.88f,
            BootStage.STAGE_5_READY to 1.0f
        )

        expected.forEach { (stage, progress) ->
            console.setBootStage(stage)
            assertEquals(stage.label, console.bootStage)
            assertEquals(stage, console.currentBootStage)
            assertEquals(progress, console.progress, 0.0001f)
        }

        assertEquals("STAGE 5 / SYSTEM READY", console.bootStage)
        assertEquals(1.0f, console.progress, 0.0001f)

        console.setBootStage(BootStage.STAGE_5_RFB_LIVE, detail = "Connected to QEMU VNC")
        assertEquals("STAGE 5 / RFB LIVE", console.bootStage)
        assertEquals(1.0f, console.progress, 0.0001f)
        assertEquals("Connected to QEMU VNC", console.statusDetail)
        assertTrue(BootStage.STAGE_5_RFB_LIVE.isTerminal)
    }

    @Test
    fun `setBootStage honours detail and progress override`() {
        val console = renderer()
        console.setBootStage(
            BootStage.STAGE_3_HYPERVISOR,
            detail = "QEMU backend present; VNC 127.0.0.1:5901",
            progressOverride = 0.5f
        )

        assertEquals("STAGE 3 / HYPERVISOR INIT", console.bootStage)
        assertEquals(0.5f, console.progress, 0.0001f)
        assertEquals("QEMU backend present; VNC 127.0.0.1:5901", console.statusDetail)

        console.setBootStage(BootStage.STAGE_4_DISPLAY_BRIDGE, progressOverride = 2f)
        assertEquals(1.0f, console.progress, 0.0001f)
    }

    @Test
    fun `setFooterHint shows the vms real vnc port and still renders`() {
        val console = renderer()
        console.setFooterHint("Tap to interact  |  VNC 127.0.0.1:5907")

        assertEquals("Tap to interact  |  VNC 127.0.0.1:5907", console.footerHint)
        console.setBootStage(BootStage.STAGE_5_READY)
        assertTrue(console.render(framebuffer(), timeMs = 0))
    }

    @Test
    fun `boot progression advances stage one to system ready at 100 percent`() {
        val console = renderer()
        val progression = BootProgression(
            vncPort = 5901,
            qemuBinaryPresent = false,
            hostSummary = "ABI=arm64-v8a, cores=8, RAM=8192MB, KVM=no",
            vmName = "Pixel 8 Pro",
            displayWidth = 360,
            displayHeight = 640
        )

        val applied = mutableListOf<BootStep>()
        val runner = BootStageRunner(
            progression = progression,
            apply = { step ->
                applied += step
                step.applyTo(console)
            },
            sleep = { /* no waiting in tests */ },
            cancelled = { false }
        )

        assertTrue("boot sequence must complete", runner.run())

        assertEquals(
            listOf(
                BootStage.STAGE_1_HOST_PROBE,
                BootStage.STAGE_2_STORAGE_SHARE,
                BootStage.STAGE_3_HYPERVISOR,
                BootStage.STAGE_4_DISPLAY_BRIDGE,
                BootStage.STAGE_5_READY
            ),
            applied.map { it.stage }
        )
        assertEquals(listOf(250L, 300L, 350L, 300L, 0L), applied.map { it.holdMs })
        assertEquals(1200L, BootProgression.TOTAL_HOLD_MS)

        // The bug: the console used to stay parked at Stage 1 / 24%.
        assertEquals("STAGE 5 / SYSTEM READY", console.bootStage)
        assertEquals(BootStage.STAGE_5_READY, console.currentBootStage)
        assertEquals(1.0f, console.progress, 0.0001f)
        assertEquals("SYSTEM READY", console.status)
        assertTrue(console.statusDetail.contains("5901"))

        val lines = console.visibleLines()
        assertTrue(lines.any { it.contains("host probe") })
        assertTrue(lines.any { it.contains("virtio-9p") })
        assertTrue(lines.any { it.contains("ANativeWindow") })
        assertTrue(lines.any { it.contains("boot sequence complete") })
    }

    @Test
    fun `qemu backed progression reports the vnc endpoint in stage three`() {
        val console = renderer()
        val progression = BootProgression(
            vncPort = 5912,
            qemuBinaryPresent = true,
            hostSummary = "ABI=arm64-v8a, cores=8, RAM=8192MB, KVM=yes"
        )
        val runner = BootStageRunner(
            progression = progression,
            apply = { step -> step.applyTo(console) },
            sleep = { },
            cancelled = { false }
        )

        assertTrue(runner.run())
        assertEquals(1.0f, console.progress, 0.0001f)
        assertTrue(console.visibleLines().any { it.contains("guest VNC at 127.0.0.1:5912") })
    }

    @Test
    fun `runner upgrades to rfb live as soon as the guest is connected`() {
        val console = renderer()
        val progression = BootProgression(vncPort = 5901, qemuBinaryPresent = true)
        val applied = mutableListOf<BootStage>()
        val runner = BootStageRunner(
            progression = progression,
            apply = { step ->
                applied += step.stage
                step.applyTo(console)
            },
            sleep = { },
            cancelled = { false },
            rfbServerLabel = { "QEMU VNC 720x1280" }
        )

        assertTrue(runner.run())
        assertEquals(BootStage.STAGE_5_RFB_LIVE, applied.last())
        assertEquals("STAGE 5 / RFB LIVE", console.bootStage)
        assertEquals("RFB LIVE", console.status)
        assertEquals("Connected to QEMU VNC 720x1280", console.statusDetail)
        assertEquals(1.0f, console.progress, 0.0001f)
    }

    @Test
    fun `runner stops immediately when the pipeline is closed`() {
        var cancelled = false
        val applied = mutableListOf<BootStage>()
        val runner = BootStageRunner(
            progression = BootProgression(vncPort = 5901),
            apply = { step ->
                applied += step.stage
                cancelled = applied.size >= 2
            },
            sleep = { },
            cancelled = { cancelled }
        )

        assertFalse("cancelled run must not report completion", runner.run())
        assertEquals(2, applied.size)
        assertEquals(BootStage.STAGE_2_STORAGE_SHARE, applied.last())
    }

    @Test
    fun `progress never rewinds once the console is ready`() {
        val console = renderer()
        console.setBootStage(BootStage.STAGE_5_READY)
        assertEquals(1.0f, console.progress, 0.0001f)

        repeat(5) { console.advanceProgress(0.04f) }
        assertEquals(1.0f, console.progress, 0.0001f)
        assertEquals("STAGE 5 / SYSTEM READY", console.bootStage)

        // Before ready, the same helper still moves forward.
        val early = renderer()
        early.advanceProgress(0.04f)
        assertEquals(0.22f, early.progress, 0.0001f)
    }
}
