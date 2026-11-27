package com.novavm.app.engine

import com.novavm.app.data.DevicePresets
import com.novavm.app.data.DeviceSpoofConfig
import com.novavm.app.data.RootXposedConfig
import com.novavm.app.data.VirtualLocationConfig
import com.novavm.app.data.VmArchitecture
import com.novavm.app.data.VmBackendType
import com.novavm.app.data.VmHardwareConfig
import com.novavm.app.data.VmProfile
import com.novavm.app.data.VmRepository
import com.novavm.app.data.VmSnapshot
import com.novavm.app.data.VmStatus
import com.novavm.app.location.GpsSyncResult
import com.novavm.app.location.VirtualGpsEngine
import com.novavm.app.root.RootXposedConfigurator
import com.novavm.app.root.VmosFeatureInjector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Immutable view of a VM's live state. */
data class VmRuntimeState(
    val vmId: String,
    val name: String,
    val status: VmStatus = VmStatus.STOPPED,
    val backendName: String = "QEMU",
    val vncPort: Int = VmRepository.DEFAULT_VNC_PORT,
    val qmpPort: Int = VmRepository.DEFAULT_QMP_PORT,
    val adbPort: Int = VmRepository.DEFAULT_ADB_PORT,
    val pid: Int = -1,
    val startedAtEpochMs: Long? = null,
    val errorMessage: String? = null,
    val consoleTail: List<String> = emptyList()
) {
    val isRunning: Boolean get() = status == VmStatus.RUNNING || status == VmStatus.PAUSED
}

/**
 * Thread-safe controller for every VM known to the app.
 *
 * [start] builds the exact argv, records it in the console log, spawns the
 * `qemu-system-*` process when the host exposes an executable binary and
 * transitions the runtime state. While a real process is alive, pause/resume and
 * snapshot restore are driven over QMP; without one the same calls degrade to
 * console-only feedback so the state machine stays usable on every host.
 */
class VmInstanceManager(
    val repository: VmRepository,
    private val capabilitiesProvider: () -> HostCapabilities = { HostCapabilities.conservative() },
    private val qemuBackend: QemuBackend = QemuBackend(),
    private val avfBackend: AvfBackend = AvfBackend(qemuBackend),
    private val virtualGps: VirtualGpsEngine = VirtualGpsEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val consoleLimit: Int = 400
) {

    private val lock = Any()
    private val runtime = LinkedHashMap<String, VmRuntimeState>()
    private val logs = LinkedHashMap<String, ArrayDeque<String>>()
    private val launchCommands = LinkedHashMap<String, List<String>>()

    /** Live qemu-system processes keyed by VM id. */
    private val processes = LinkedHashMap<String, Process>()


    /** Last logged GPS fix per VM, so unchanged coordinates do not spam logs. */
    private val gpsFixes = LinkedHashMap<String, String>()

    private val _states = MutableStateFlow<List<VmRuntimeState>>(emptyList())
    val states: StateFlow<List<VmRuntimeState>> = _states.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    companion object {
        @Volatile
        private var instance: VmInstanceManager? = null

        /** Process-wide controller bound to the first repository it sees. */
        fun getInstance(repository: VmRepository): VmInstanceManager {
            val existing = instance
            if (existing != null && existing.repository.rootDir == repository.rootDir) return existing
            return synchronized(this) {
                instance?.takeIf { it.repository.rootDir == repository.rootDir }
                    ?: VmInstanceManager(repository).also { instance = it }
            }
        }

        /** Test/DI hook: drops the cached singleton. */
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }

    // ---------------------------------------------------------------- state

    fun refresh(): List<VmRuntimeState> {
        synchronized(lock) {
            val profiles = repository.loadAll()
            val known = profiles.map { profile ->
                val previous = runtime[profile.id]
                (previous ?: VmRuntimeState(vmId = profile.id, name = profile.name)).copy(
                    name = profile.name,
                    vncPort = profile.vncPort,
                    qmpPort = profile.qmpPort,
                    adbPort = profile.adbForwardPort,
                    consoleTail = logs[profile.id]?.toList()?.takeLast(40).orEmpty()
                )
            }
            runtime.clear()
            known.forEach { runtime[it.vmId] = it }
            val liveIds = profiles.map { it.id }.toSet()
            logs.keys.retainAll(liveIds)
            launchCommands.keys.retainAll(liveIds)
            gpsFixes.keys.retainAll(liveIds)
            processes.keys.retainAll(liveIds)
            _states.value = known
            return known
        }
    }

    fun state(vmId: String): VmRuntimeState? = synchronized(lock) {
        runtime[vmId] ?: refresh().firstOrNull { it.vmId == vmId }
    }

    fun consoleLog(vmId: String): List<String> = synchronized(lock) {
        logs[vmId]?.toList().orEmpty()
    }

    fun appendConsole(vmId: String, line: String) {
        synchronized(lock) {
            val buffer = logs.getOrPut(vmId) { ArrayDeque() }
            buffer.addLast("[${timeFormat.format(Date(clock()))}] $line")
            while (buffer.size > consoleLimit) buffer.removeFirst()
            val current = runtime[vmId]
            if (current != null) {
                runtime[vmId] = current.copy(consoleTail = buffer.toList().takeLast(40))
                publish()
            }
        }
    }

    fun lastLaunchCommand(vmId: String): List<String>? = synchronized(lock) { launchCommands[vmId] }

    private fun publish() {
        _states.value = runtime.values.toList()
    }

    private fun update(
        vmId: String,
        transform: (VmRuntimeState) -> VmRuntimeState
    ): VmRuntimeState? {
        synchronized(lock) {
            val current = runtime[vmId] ?: return null
            val updated = transform(current)
            runtime[vmId] = updated
            publish()
            return updated
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun createProfile(
        name: String,
        architecture: VmArchitecture = VmArchitecture.ARM64,
        backendType: VmBackendType = VmBackendType.AUTO,
        hardware: VmHardwareConfig = VmHardwareConfig(),
        deviceSpoof: DeviceSpoofConfig = DevicePresets.pixel8Pro,
        rootXposed: RootXposedConfig = RootXposedConfig(),
        virtualLocation: VirtualLocationConfig = VirtualLocationConfig()
    ): VmProfile {
        val id = UUID.randomUUID().toString()
        repository.ensureVmDirectories(id)
        val disk = File(repository.vmDir(id), "disk.qcow2")
        val profile = VmProfile.create(
            id = id,
            name = name,
            architecture = architecture,
            backendType = backendType,
            romPath = "",
            diskImagePath = disk.absolutePath,
            hardware = hardware,
            deviceSpoof = deviceSpoof,
            rootXposed = rootXposed,
            virtualLocation = virtualLocation,
            createdAtEpochMs = clock()
        )
        return synchronized(lock) {
            val stored = repository.create(profile)
            repository.ensureVmDirectories(stored.id)
            runtime[stored.id] = VmRuntimeState(
                vmId = stored.id,
                name = stored.name,
                vncPort = stored.vncPort,
                qmpPort = stored.qmpPort,
                adbPort = stored.adbForwardPort
            )
            logs[stored.id] = ArrayDeque()
            appendConsole(stored.id, "VM profile created (${stored.architecture}, ${stored.hardware.cpuCores} vCPU, ${stored.hardware.ramMb} MB)")
            if (stored.virtualLocation.enabled) {
                appendConsole(stored.id, "[GPS] virtual location armed: ${stored.virtualLocation.presetName}")
                syncVirtualLocation(stored.id, reason = "create")
            }
            publish()
            stored
        }
    }

    fun cloneProfile(vmId: String, newName: String? = null): VmProfile? {
        val clone = synchronized(lock) { repository.clone(vmId, newName) } ?: return null
        refresh()
        appendConsole(clone.id, "Cloned from $vmId")
        return clone
    }

    fun deleteProfile(vmId: String, deleteFiles: Boolean = true): Boolean {
        synchronized(lock) {
            if (runtime[vmId]?.isRunning == true) stop(vmId)
            processes.remove(vmId)?.let { runCatching { it.destroy() } }
            val removed = repository.delete(vmId, deleteFiles)
            if (removed) {
                runtime.remove(vmId)
                logs.remove(vmId)
                launchCommands.remove(vmId)
                gpsFixes.remove(vmId)
                publish()
            }
            return removed
        }
    }

    /** Builds argv for a VM without changing its state. */
    fun buildLaunchCommand(vmId: String): List<String>? {
        val profile = repository.get(vmId) ?: return null
        val capabilities = capabilitiesProvider()
        val backend = resolveBackend(profile, capabilities)
        return backend.buildLaunchCommand(
            profile = profile,
            capabilities = capabilities,
            sharedDir = repository.sharedDir(vmId),
            logDir = repository.logsDir(vmId)
        )
    }

    fun start(vmId: String): Boolean {
        val profile = repository.get(vmId) ?: return false
        synchronized(lock) {
            val current = state(vmId) ?: return false
            if (current.status == VmStatus.RUNNING || current.status == VmStatus.STARTING) return false

            val capabilities = capabilitiesProvider()
            val backend = resolveBackend(profile, capabilities)
            val command = backend.buildLaunchCommand(
                profile = profile,
                capabilities = capabilities,
                sharedDir = repository.sharedDir(vmId),
                logDir = repository.logsDir(vmId)
            )
            launchCommands[vmId] = command
            writeLaunchLog(vmId, command)
            // Stage 2 must report real files on disk, so the guest bootstrap is
            // regenerated for every launch before the hypervisor starts.
            writeBootScripts(profile)

            update(vmId) {
                it.copy(
                    status = VmStatus.STARTING,
                    backendName = backend.backendName,
                    errorMessage = null
                )
            }
            appendConsole(vmId, "Launching ${backend.backendName}: ${command.joinToString(" ")}")

            val process = launchQemuProcess(vmId, command, capabilities)
            val pid = process?.let { pidOf(it) } ?: -1

            val effectiveBackend = buildString {
                append(backend.backendName)
                if (process == null) append(" (console fallback)")
            }
            update(vmId) {
                it.copy(
                    status = VmStatus.RUNNING,
                    backendName = effectiveBackend,
                    pid = pid,
                    startedAtEpochMs = clock(),
                    errorMessage = null
                )
            }
            when {
                process != null -> appendConsole(
                    vmId,
                    "[ OK ] guest process running (pid=$pid) on VNC ${profile.vncPort}"
                )

                capabilities.qemuBinaryPresent -> appendConsole(
                    vmId,
                    "[WARN] qemu-system present but not launchable; boot console renderer active on VNC ${profile.vncPort}"
                )

                else -> appendConsole(
                    vmId,
                    "[ .. ] no qemu-system binary in sandbox; boot console renderer active on VNC ${profile.vncPort}"
                )
            }
            syncVirtualLocation(vmId, reason = "start")
            return true
        }
    }

    fun stop(vmId: String): Boolean {
        val process = synchronized(lock) {
            val current = state(vmId) ?: return false
            if (current.status == VmStatus.STOPPED) return false
            processes.remove(vmId)
        }
        if (process != null) {
            val pid = pidOf(process)
            destroyQuietly(process)
            appendConsole(vmId, "[ OK ] qemu process terminated (pid=$pid)")
        }
        synchronized(lock) {
            update(vmId) {
                it.copy(status = VmStatus.STOPPED, pid = -1, startedAtEpochMs = null)
            }
            appendConsole(vmId, "[ OK ] guest stopped")
            return true
        }
    }

    fun pause(vmId: String): Boolean {
        val runtimeState = synchronized(lock) {
            val current = state(vmId) ?: return false
            if (current.status != VmStatus.RUNNING) return false
            current
        }
        val port = repository.get(vmId)?.qmpPort ?: runtimeState.qmpPort
        // null => no live qemu process, keep the console-only fallback path.
        val applied = runQmpCommand(vmId, port, "stop") { it.stop() }
        if (applied == false) return false
        if (applied == null) appendConsole(vmId, "VM paused (QMP stop requested)")
        synchronized(lock) {
            update(vmId) { it.copy(status = VmStatus.PAUSED) }
        }
        return true
    }

    fun resume(vmId: String): Boolean {
        val runtimeState = synchronized(lock) {
            val current = state(vmId) ?: return false
            if (current.status != VmStatus.PAUSED) return false
            current
        }
        val port = repository.get(vmId)?.qmpPort ?: runtimeState.qmpPort
        val applied = runQmpCommand(vmId, port, "cont") { it.cont() }
        if (applied == false) return false
        if (applied == null) appendConsole(vmId, "VM resumed (QMP cont requested)")
        synchronized(lock) {
            update(vmId) { it.copy(status = VmStatus.RUNNING) }
        }
        return true
    }

    fun markError(vmId: String, message: String): Boolean {
        synchronized(lock) {
            val updated = update(vmId) {
                it.copy(status = VmStatus.ERROR, errorMessage = message)
            } ?: return false
            appendConsole(vmId, "[FAIL] $message")
            return updated.status == VmStatus.ERROR
        }
    }

    // ------------------------------------------------------------ snapshots

    fun takeSnapshot(vmId: String, name: String, description: String = ""): VmSnapshot? {
        val snapshot = repository.addSnapshot(
            vmId = vmId,
            name = name,
            description = description,
            createdAtEpochMs = clock()
        ) ?: return null
        appendConsole(vmId, "Snapshot created: ${snapshot.name} (${snapshot.id})")
        return snapshot
    }

    fun restoreSnapshot(vmId: String, snapshotId: String): Boolean {
        val snapshot = repository.findSnapshot(vmId, snapshotId) ?: return false
        appendConsole(vmId, "Restoring snapshot ${snapshot.name} via QMP loadvm")
        val port = repository.get(vmId)?.qmpPort ?: state(vmId)?.qmpPort
        // No live guest => the console line above stays the whole story.
        if (port != null) {
            runQmpCommand(vmId, port, "loadvm ${snapshot.name}") {
                it.loadSnapshot(snapshot.name)
            }
        }
        return true
    }

    fun deleteSnapshot(vmId: String, snapshotId: String): Boolean {
        val removed = repository.removeSnapshot(vmId, snapshotId)
        if (removed) appendConsole(vmId, "Snapshot $snapshotId deleted")
        return removed
    }

    // -------------------------------------------------------- virtual GPS

    /**
     * Renders the VM's virtual location into its shared folder and appends a
     * console line whenever the fix actually changed, so the boot console shows
     * live GPS feedback.
     */
    fun syncVirtualLocation(vmId: String, reason: String = "update"): GpsSyncResult? {
        val profile = repository.get(vmId) ?: return null
        val gps = profile.virtualLocation
        val result = runCatching {
            virtualGps.syncToVm(profile, repository.sharedDir(vmId))
        }.onFailure { error ->
            appendConsole(vmId, "[GPS] sync failed: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull() ?: return null

        val signature = fixSignature(gps.latitude, gps.longitude)
        val previous = synchronized(lock) { gpsFixes.put(vmId, signature) }
        if (previous != signature) {
            if (gps.enabled) {
                appendConsole(
                    vmId,
                    "[GPS] fix ($reason) ${coordinate(gps.latitude)}, ${coordinate(gps.longitude)} " +
                        "alt ${coordinate(gps.altitude)}m · ${gps.mode.name.lowercase(Locale.US)} · " +
                        "${gps.presetName} · NMEA ${result.nmeaFile.name}"
                )
                appendConsole(vmId, "[GPS] inject: ${result.adbGeoCommand}")
            } else {
                appendConsole(vmId, "[GPS] virtual location disabled; real provider reported")
            }
        }
        return result
    }

    /** Last fix logged for [vmId], e.g. `39.904200,116.407400`. */
    fun lastVirtualFix(vmId: String): String? = synchronized(lock) { gpsFixes[vmId] }

    private fun fixSignature(latitude: Double, longitude: Double): String =
        coordinate(latitude) + "," + coordinate(longitude)

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    // --------------------------------------------------------------- helpers

    private fun resolveBackend(profile: VmProfile, capabilities: HostCapabilities): VmBackend =
        when (profile.backendType) {
            VmBackendType.QEMU -> qemuBackend
            VmBackendType.AVF -> if (avfBackend.isSupported(capabilities)) avfBackend else qemuBackend
            // AVF does not execute a real guest process yet, so an automatic
            // choice must stay on QEMU; AVF remains available for profiles that
            // explicitly ask for it.
            VmBackendType.AUTO -> qemuBackend
        }

    /**
     * Sends one QMP command to the VM's monitor socket when a real qemu-system
     * process is alive for it.
     *
     * @return `null` when no live guest process owns [vmId] — callers then keep
     * their console-only fallback wording; `true` when the monitor accepted the
     * command; `false` when the transport or the command itself failed.
     */
    private fun runQmpCommand(
        vmId: String,
        port: Int,
        label: String,
        action: (QmpClient) -> QmpResponse
    ): Boolean? {
        val alive = synchronized(lock) { processes[vmId]?.isAlive == true }
        if (!alive) return null
        return runCatching {
            QmpClient(port = port).use { client ->
                client.connect()
                action(client)
            }
        }.fold(
            onSuccess = { response ->
                val rejection = if (response.isError) {
                    response.errorMessage ?: response.raw
                } else {
                    null
                }
                if (rejection != null) {
                    appendConsole(vmId, "[FAIL] QMP $label rejected: $rejection")
                    false
                } else {
                    appendConsole(vmId, "[ OK ] QMP $label on 127.0.0.1:$port")
                    true
                }
            },
            onFailure = { error ->
                appendConsole(vmId, "[FAIL] QMP $label failed: ${reasonOf(error)}")
                false
            }
        )
    }

    private fun reasonOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun writeLaunchLog(vmId: String, command: List<String>) {
        runCatching {
            val dir = repository.logsDir(vmId)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "launch.log").appendText(
                "${timeFormat.format(Date(clock()))} ${command.joinToString(" ")}\n"
            )
        }
    }

    /**
     * Regenerates `shared/novavm_boot.sh` + `shared/init.novavm.rc` and syncs
     * `shared/novavm_vmos_ext.sh`, so the `STAGE 2 / STORAGE & SHARE` step always
     * reports up-to-date guest scripts.
     */
    private fun writeBootScripts(profile: VmProfile) {
        try {
            repository.ensureVmDirectories(profile.id)
            val vmDir = repository.vmDir(profile.id)
            val files = RootXposedConfigurator(repository, profile.id).writeAll(
                config = profile.rootXposed,
                spoof = profile.deviceSpoof,
                hardware = profile.hardware,
                profileName = profile.displayName,
                virtualLocation = profile.virtualLocation,
                vmos = profile.vmosBundle()
            )
            // writeAll already invoked the injector through the bundle; this call
            // guarantees the extension script exists even for a bare profile.
            runCatching {
                VmosFeatureInjector.writeExtensionScript(
                    vmDir = vmDir,
                    gms = profile.gms,
                    sensors = profile.sensors,
                    identity = profile.extendedIdentity,
                    keepAlive = profile.keepAlive
                )
            }
            appendConsole(
                profile.id,
                "[ OK ] boot scripts staged: ${files.joinToString(", ") { it.name }}"
            )
        } catch (error: Throwable) {
            appendConsole(
                profile.id,
                "[WARN] boot script generation failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    /**
     * Spawns the real QEMU process when the host exposes an executable binary.
     * Any failure (I/O, exec denial, missing ELF loader) is logged as `[WARN]`
     * and degrades to console mode: [start] must never crash or hang.
     */
    private fun launchQemuProcess(
        vmId: String,
        command: List<String>,
        capabilities: HostCapabilities
    ): Process? {
        if (!capabilities.qemuBinaryPresent || command.isEmpty()) return null
        val binary = command.first()
        if (!File(binary).canExecute()) {
            appendConsole(vmId, "[WARN] $binary is not executable; falling back to console mode")
            return null
        }
        return try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            val workingDir = repository.vmDir(vmId)
            if (workingDir.isDirectory) builder.directory(workingDir)
            val process = builder.start()
            processes[vmId] = process
            startOutputPump(vmId, process)
            process
        } catch (error: Throwable) {
            appendConsole(
                vmId,
                "[WARN] qemu spawn failed: ${error.message ?: error.javaClass.simpleName}; " +
                    "falling back to console mode"
            )
            null
        }
    }

    /** Pumps merged stdout/stderr of [process] into the VM console log. */
    private fun startOutputPump(vmId: String, process: Process) {
        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) appendConsole(vmId, line)
                        line = reader.readLine()
                    }
                }
            } catch (error: Throwable) {
                appendConsole(
                    vmId,
                    "[WARN] qemu output pump stopped: ${error.message ?: error.javaClass.simpleName}"
                )
            }
            synchronized(lock) { if (processes[vmId] === process) processes.remove(vmId) }
            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            appendConsole(vmId, "[ .. ] qemu process exited (code=$exit)")
        }, "NovaVM-Qemu-$vmId").apply {
            isDaemon = true
            start()
        }
    }

    /** `destroy()`, escalating to `destroyForcibly()` when it does not exit. */
    private fun destroyQuietly(process: Process) {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /**
     * Guest process id, or -1 when the runtime cannot report one.
     *
     * Resolved reflectively because `java.lang.Process.pid()` is a Java 9 API
     * that is not part of every Android platform stub, and spawning must never
     * fail just because a PID is unavailable.
     */
    private fun pidOf(process: Process): Int = runCatching {
        val value = process.javaClass.methods
            .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
            ?.invoke(process)
        (value as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)
}
