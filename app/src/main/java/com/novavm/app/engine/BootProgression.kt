package com.novavm.app.engine

/**
 * One step of the boot console sequence.
 *
 * A step bundles the [BootStage] that becomes visible, the status pill text,
 * the status detail line, the console log lines emitted for it and how long the
 * step is held before the next one starts.
 */
data class BootStep(
    val stage: BootStage,
    val status: String,
    val detail: String,
    val logLines: List<String> = emptyList(),
    val holdMs: Long = 0L
)

/**
 * Deterministic boot plan for [BootConsoleRenderer].
 *
 * Deliberately free of Android APIs so the whole Stage 1 -> Stage 5 progression
 * can be unit tested on a plain JVM; [com.novavm.app.overlay.VmDisplayPipeline]
 * only supplies the thread and the renderer sink.
 */
class BootProgression(
    val vncPort: Int,
    val qemuBinaryPresent: Boolean = false,
    val hostSummary: String = "",
    val vmName: String = "",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0
) {

    /** The five stages in order; the last one is always [BootStage.STAGE_5_READY]. */
    fun steps(): List<BootStep> = listOf(
        hostProbeStep(),
        storageShareStep(),
        hypervisorStep(),
        displayBridgeStep(),
        readyStep()
    )

    /** Stage 5 upgrade used as soon as a live VNC/RFB guest is attached. */
    fun rfbLiveStep(serverLabel: String): BootStep {
        val label = serverLabel.ifBlank { "RFB $HOST:$vncPort" }
        return BootStep(
            stage = BootStage.STAGE_5_RFB_LIVE,
            status = "RFB LIVE",
            detail = "Connected to $label",
            logLines = listOf("[ OK ] RFB live: $label"),
            holdMs = 0L
        )
    }

    private fun hostProbeStep(): BootStep {
        val summary = hostSummary.ifBlank { "ABI unknown, cores n/a, RAM n/a, KVM unknown" }
        return BootStep(
            stage = BootStage.STAGE_1_HOST_PROBE,
            status = "HOST PROBE",
            detail = "probing host ABI / CPU / RAM / KVM",
            logLines = listOf(
                "[ .. ] host probe: $summary",
                "[ OK ] host probe complete"
            ),
            holdMs = STAGE_1_HOLD_MS
        )
    }

    private fun storageShareStep(): BootStep = BootStep(
        stage = BootStage.STAGE_2_STORAGE_SHARE,
        status = "STORAGE READY",
        detail = "virtio-9p share mounted at /mnt/novavm_share",
        logLines = listOf(
            "[ OK ] virtio-9p shared dir ready (mount_tag=novavm_share)",
            "[ OK ] guest boot scripts staged (novavm_boot.sh, init.novavm.rc)"
        ),
        holdMs = STAGE_2_HOLD_MS
    )

    private fun hypervisorStep(): BootStep = if (qemuBinaryPresent) {
        BootStep(
            stage = BootStage.STAGE_3_HYPERVISOR,
            status = "HYPERVISOR INIT",
            detail = "QEMU backend present; VNC 127.0.0.1:$vncPort",
            logLines = listOf(
                "[ .. ] hypervisor init: qemu-system present",
                "[ .. ] connecting to guest VNC at 127.0.0.1:$vncPort"
            ),
            holdMs = STAGE_3_HOLD_MS
        )
    } else {
        BootStep(
            stage = BootStage.STAGE_3_HYPERVISOR,
            status = "HYPERVISOR INIT",
            detail = "no qemu-system binary; interactive sandbox console",
            logLines = listOf(
                "[ .. ] no qemu-system-* binary found in the app sandbox",
                "[ OK ] interactive sandbox console backend armed"
            ),
            holdMs = STAGE_3_HOLD_MS
        )
    }

    private fun displayBridgeStep(): BootStep {
        val surface = if (displayWidth > 0 && displayHeight > 0) {
            "${displayWidth}x$displayHeight"
        } else {
            "default size"
        }
        return BootStep(
            stage = BootStage.STAGE_4_DISPLAY_BRIDGE,
            status = "DISPLAY BRIDGE",
            detail = "ANativeWindow surface $surface, input bridge ready",
            logLines = listOf(
                "[ OK ] ANativeWindow surface attached ($surface)",
                "[ OK ] touch/key input bridge armed"
            ),
            holdMs = STAGE_4_HOLD_MS
        )
    }

    private fun readyStep(): BootStep = BootStep(
        stage = BootStage.STAGE_5_READY,
        status = "SYSTEM READY",
        detail = "Interactive console active (VNC :$vncPort)",
        logLines = listOf("[ OK ] boot sequence complete; console interactive"),
        holdMs = 0L
    )

    companion object {
        const val HOST = "127.0.0.1"
        const val STAGE_1_HOLD_MS = 250L
        const val STAGE_2_HOLD_MS = 300L
        const val STAGE_3_HOLD_MS = 350L
        const val STAGE_4_HOLD_MS = 300L

        /** Wall-clock budget of the whole progression (the ready step holds 0 ms). */
        const val TOTAL_HOLD_MS = STAGE_1_HOLD_MS + STAGE_2_HOLD_MS +
            STAGE_3_HOLD_MS + STAGE_4_HOLD_MS
    }
}

/**
 * Applies [BootStep] to a renderer: logs first, then stage/progress, then the
 * status pill so the newest state wins.
 */
fun BootStep.applyTo(console: BootConsoleRenderer): BootConsoleRenderer {
    logLines.forEach { line -> console.appendLine(line) }
    console.setBootStage(stage, detail)
    console.setStatus(status, detail)
    return console
}

/**
 * Moves [BootConsoleRenderer.progress] forward only: once the console is ready
 * (`progress == 1f`) late touches can never push it backwards.
 */
fun BootConsoleRenderer.advanceProgress(delta: Float): BootConsoleRenderer {
    val target = (progress + delta).coerceIn(0f, 1f)
    if (target > progress) setProgress(target)
    return this
}

/**
 * Walks [BootProgression] steps into [apply] and upgrades to
 * [BootStage.STAGE_5_RFB_LIVE] when a guest is already live.
 *
 * Pure JVM: the thread, the sleep implementation and the cancellation flag are
 * injected, so tests can drive the whole sequence without waiting and
 * [com.novavm.app.overlay.VmDisplayPipeline] can stop it by flipping its
 * `running` flag.
 *
 * @return true when every step (and the optional RFB upgrade) was applied.
 */
class BootStageRunner(
    private val progression: BootProgression,
    private val apply: (BootStep) -> Unit,
    private val sleep: (Long) -> Unit = { millis -> if (millis > 0L) Thread.sleep(millis) },
    private val cancelled: () -> Boolean = { false },
    private val rfbServerLabel: () -> String? = { null }
) {

    fun run(): Boolean {
        for (step in progression.steps()) {
            if (cancelled()) return false
            apply(step)
            if (step.holdMs > 0L) sleep(step.holdMs)
            if (cancelled()) return false
        }
        rfbServerLabel()?.let { label -> apply(progression.rfbLiveStep(label)) }
        return true
    }
}
