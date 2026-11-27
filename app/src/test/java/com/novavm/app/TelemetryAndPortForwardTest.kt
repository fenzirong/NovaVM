package com.novavm.app

import com.novavm.app.data.ForwardProtocol
import com.novavm.app.data.PortForwardPresets
import com.novavm.app.data.PortForwardRule
import com.novavm.app.engine.QmpCommands
import com.novavm.app.engine.QmpTerminalController
import com.novavm.app.engine.VmTelemetryMonitor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the three telemetry/console/port-forward building blocks:
 * `PortForwardRule` formatting and validation, `VmTelemetryMonitor` byte
 * accounting on a synthetic VM tree, and `QmpTerminalController` in both the
 * offline fallback path and against a local fake QMP peer.
 */
class TelemetryAndPortForwardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var vmDir: File
    private lateinit var disk: File

    @Before
    fun setUp() {
        vmDir = temp.newFolder("vm")

        val shared = File(vmDir, "shared").apply { mkdirs() }
        File(shared, "notes.txt").writeBytes(ByteArray(5) { 'a'.code.toByte() })
        File(shared, "nested").mkdirs()
        File(File(shared, "nested"), "track.bin").writeBytes(ByteArray(10))

        val modules = File(vmDir, "modules").apply { mkdirs() }
        File(modules, "m.txt").writeBytes(ByteArray(1))

        File(vmDir, "logs").mkdirs()
        File(File(vmDir, "logs"), "boot.log").writeBytes(ByteArray(7))

        disk = File(vmDir, "disk.qcow2")
        disk.writeBytes(ByteArray(2048))
    }

    // ---------------------------------------------------------------------
    // Port forwarding
    // ---------------------------------------------------------------------

    @Test
    fun `tcp rule renders the qemu hostfwd fragment`() {
        val rule = PortForwardRule(id = "r1", label = "SSH", hostPort = 2222, guestPort = 22)

        assertEquals("hostfwd=tcp:127.0.0.1:2222-:22", rule.toQemuHostFwdArg())
        assertEquals(ForwardProtocol.TCP, rule.protocol)
        assertTrue(rule.enabled)
        assertTrue(rule.isValid())
        assertNull(rule.validationMessage())
    }

    @Test
    fun `udp rule renders the udp token`() {
        val rule = PortForwardRule(
            id = "r2",
            label = "HTTP",
            protocol = ForwardProtocol.UDP,
            hostPort = 8080,
            guestPort = 80
        )

        assertEquals("hostfwd=udp:127.0.0.1:8080-:80", rule.toQemuHostFwdArg())
        assertEquals("udp", ForwardProtocol.UDP.qemuTag)
        assertEquals("tcp", ForwardProtocol.TCP.qemuTag)
    }

    @Test
    fun `validation accepts boundary ports and rejects reserved ones`() {
        assertTrue(PortForwardRule("a", "l", hostPort = 1024, guestPort = 1).isValid())
        assertTrue(PortForwardRule("a", "l", hostPort = 65535, guestPort = 65535).isValid())

        val lowHost = PortForwardRule("a", "l", hostPort = 1023, guestPort = 22)
        assertFalse(lowHost.isValid())
        assertNotNull(lowHost.validationMessage())

        assertFalse(PortForwardRule("a", "l", hostPort = 65536, guestPort = 22).isValid())
        assertFalse(PortForwardRule("a", "l", hostPort = 2222, guestPort = 0).isValid())
        assertFalse(PortForwardRule("a", "l", hostPort = 2222, guestPort = 65536).isValid())
    }

    @Test
    fun `summary marks disabled rules and withId keeps the ports`() {
        val rule = PortForwardRule(
            id = "r3",
            label = "SSH 调试",
            hostPort = 2222,
            guestPort = 22,
            enabled = false
        )

        assertTrue(rule.summary().contains("127.0.0.1:2222 → 22"))
        assertTrue(rule.summary().contains("已停用"))

        val copy = rule.withId("r3-copy")
        assertEquals("r3-copy", copy.id)
        assertEquals(rule.hostPort, copy.hostPort)
        assertEquals(rule.guestPort, copy.guestPort)
        assertEquals("hostfwd=tcp:127.0.0.1:2222-:22", copy.toQemuHostFwdArg())
    }

    @Test
    fun `presets cover ssh http https and frida`() {
        assertEquals("hostfwd=tcp:127.0.0.1:2222-:22", PortForwardPresets.ssh().toQemuHostFwdArg())
        assertEquals("hostfwd=tcp:127.0.0.1:18080-:8080", PortForwardPresets.http().toQemuHostFwdArg())
        assertEquals("hostfwd=tcp:127.0.0.1:18443-:8443", PortForwardPresets.https().toQemuHostFwdArg())

        val frida = PortForwardPresets.frida()
        assertEquals(27042, frida.hostPort)
        assertEquals(27042, frida.guestPort)
        assertEquals("hostfwd=tcp:127.0.0.1:27042-:27042", frida.toQemuHostFwdArg())

        assertEquals(4, PortForwardPresets.all.size)
        assertEquals(4, PortForwardPresets.ids.distinct().size)
        assertTrue(PortForwardPresets.all.all { it.isValid() })
        assertNotNull(PortForwardPresets.byId(PortForwardPresets.SSH_ID))
        assertNull(PortForwardPresets.byId("nope"))
    }

    // ---------------------------------------------------------------------
    // Storage / memory telemetry
    // ---------------------------------------------------------------------

    @Test
    fun `inspectVmStorage measures each directory exactly`() {
        val stats = VmTelemetryMonitor.inspectVmStorage(vmDir, disk.absolutePath, snapshotCount = 3)

        // 5 + 10 (shared) + 1 (modules) + 7 (logs) + 2048 (disk) = 2071
        assertEquals(2071L, stats.vmDirBytes)
        assertEquals(2048L, stats.diskImageBytes)
        assertEquals(15L, stats.sharedDirBytes)
        assertEquals(1L, stats.modulesDirBytes)
        assertEquals(3, stats.snapshotCount)
        assertFalse(stats.diskImageIsExternal)
        assertEquals(0L, stats.vmDirMb)
        assertEquals(5, stats.summaryLines().size)
    }

    @Test
    fun `relative disk image path resolves against the vm directory`() {
        val stats = VmTelemetryMonitor.inspectVmStorage(vmDir, "disk.qcow2", snapshotCount = 0)
        assertEquals(2048L, stats.diskImageBytes)
    }

    @Test
    fun `missing directories and a blank disk path report zero without throwing`() {
        val missing = File(vmDir, "does-not-exist")
        val stats = VmTelemetryMonitor.inspectVmStorage(missing, "", snapshotCount = -5)

        assertEquals(0L, stats.vmDirBytes)
        assertEquals(0L, stats.diskImageBytes)
        assertEquals(0L, stats.sharedDirBytes)
        assertEquals(0L, stats.modulesDirBytes)
        assertEquals(0, stats.snapshotCount)
        assertEquals(0L, VmTelemetryMonitor.directorySize(missing))
        assertEquals(0L, VmTelemetryMonitor.directorySize(disk))
    }

    @Test
    fun `symlinked content is never followed`() {
        val outside = temp.newFolder("outside")
        File(outside, "big.bin").writeBytes(ByteArray(4096))

        val link = File(File(vmDir, "shared"), "linked-outside")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("该文件系统不支持创建符号链接，跳过", created)

        // Still only the two regular files (5 + 10 bytes): the link adds nothing.
        assertEquals(15L, VmTelemetryMonitor.directorySize(File(vmDir, "shared")))
        assertEquals(0L, VmTelemetryMonitor.directorySize(link))
    }

    @Test
    fun `inspectHostMemory reports jvm and volume capacity`() {
        val stats = VmTelemetryMonitor.inspectHostMemory(vmDir)

        assertTrue(stats.jvmMaxMb > 0L)
        assertTrue(stats.jvmUsedMb >= 0L)
        assertTrue(stats.jvmUsedMb <= stats.jvmMaxMb)
        assertTrue(stats.totalDiskMb > 0L)
        assertTrue(stats.freeDiskMb <= stats.totalDiskMb)
        assertTrue(stats.jvmUsagePercent in 0..100)
        assertTrue(stats.freeDiskPercent in 0..100)
    }

    @Test
    fun `formatBytes switches units`() {
        assertEquals("512 B", VmTelemetryMonitor.formatBytes(512))
        assertEquals("1.0 KB", VmTelemetryMonitor.formatBytes(1024))
        assertEquals("1.0 MB", VmTelemetryMonitor.formatBytes(1024L * 1024L))
        assertEquals("0 B", VmTelemetryMonitor.formatBytes(-1))
    }

    // ---------------------------------------------------------------------
    // QMP terminal
    // ---------------------------------------------------------------------

    @Test
    fun `quick commands carry chinese labels and hmp commands`() {
        val quick = QmpTerminalController.quickCommands

        assertEquals(6, quick.size)
        assertEquals("运行状态" to "info status", quick.first())
        assertEquals(
            listOf(
                "info status",
                "info kvm",
                "info block",
                "info network",
                "system_reset",
                "system_powerdown"
            ),
            quick.map { it.second }
        )
        assertTrue(quick.none { it.first.isBlank() || it.second.isBlank() })
    }

    @Test
    fun `empty command is rejected without touching qmp`() {
        val entry = QmpTerminalController.executeCommand(
            qmpPort = 4444,
            rawCommand = "   \n\t ",
            isVmRunningWithQemu = true
        )

        assertTrue(entry.isError)
        assertEquals("", entry.command)
        assertEquals(QmpTerminalController.EMPTY_COMMAND_HINT, entry.response)
        assertEquals("（空命令）", entry.echoLine())
        assertTrue(entry.timestampMs > 0L)
    }

    @Test
    fun `offline fallback explains the pending qmp command and shows synthetic status`() {
        val entry = QmpTerminalController.executeCommand(4444, "info status", isVmRunningWithQemu = false)

        assertFalse(entry.isError)
        assertEquals("info status", entry.command)
        assertEquals("(qemu) info status", entry.echoLine())

        val text = entry.response
        assertTrue(text.contains(QmpTerminalController.OFFLINE_MARKER))
        assertTrue(text.contains("127.0.0.1:4444"))
        assertTrue(text.contains("human-monitor-command"))
        assertTrue(text.contains(QmpCommands.humanMonitor("info status")))
        assertTrue(text.contains("VM status: paused"))
        assertTrue(text.contains("合成输出"))
    }

    @Test
    fun `offline fallback synthesises network info`() {
        val entry = QmpTerminalController.executeCommand(15555, "info network", isVmRunningWithQemu = false)

        assertFalse(entry.isError)
        assertTrue(entry.response.contains("net0: type=user"))
        assertTrue(entry.response.contains("127.0.0.1:15555"))
    }

    @Test
    fun `offline fallback for an unknown command has no synthetic body`() {
        val entry = QmpTerminalController.executeCommand(4444, "info registers", isVmRunningWithQemu = false)

        assertFalse(entry.isError)
        assertTrue(entry.response.contains("待发送命令"))
        assertFalse(entry.response.contains("合成输出"))
    }

    @Test
    fun `live qmp channel returns the human monitor output`() {
        FakeQmpServer().use { server ->
            val entry = QmpTerminalController.executeCommand(
                qmpPort = server.port,
                rawCommand = "info status",
                isVmRunningWithQemu = true
            )

            assertFalse(entry.response, entry.isError)
            assertEquals("VM status: running", entry.response)
            assertEquals("info status", entry.command)
        }
    }

    @Test
    fun `unreachable live channel degrades to the offline diagnostic`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val entry = QmpTerminalController.executeCommand(port, "info status", isVmRunningWithQemu = true)

        assertTrue(entry.response.contains("127.0.0.1:$port"))
        assertTrue(entry.response.contains("VM status: paused"))
        assertTrue(entry.isError)
    }

    /**
     * Minimal QMP peer: greeting, `qmp_capabilities` handshake, one canned
     * `human-monitor-command` reply. Served on loopback from a daemon thread so
     * a failed expectation can never wedge the JVM.
     */
    private class FakeQmpServer : Closeable {

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val port: Int = serverSocket.localPort

        private val worker = thread(isDaemon = true, name = "fake-qmp-peer") { serve() }

        private fun serve() {
            runCatching {
                serverSocket.accept().use { socket ->
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                    )
                    val writer = BufferedWriter(
                        OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    )

                    writer.write(GREETING)
                    writer.write("\r\n")
                    writer.flush()

                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val execute = runCatching {
                            Json.parseToJsonElement(line)
                                .jsonObject["execute"]!!
                                .jsonPrimitive.content
                        }.getOrNull() ?: continue

                        val reply = when (execute) {
                            "qmp_capabilities" -> "{\"return\": {}}"
                            // Raw string: the JSON escape \r\n is intentional.
                            "human-monitor-command" -> """{"return": "VM status: running\r\n"}"""
                            else -> """{"error": {"class": "CommandNotFound", "desc": "unknown"}}"""
                        }
                        writer.write(reply)
                        writer.write("\r\n")
                        writer.flush()
                    }
                }
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { worker.join(1_000) }
        }

        private companion object {
            const val GREETING: String =
                """{"QMP": {"version": {"qemu": {"major": 8, "minor": 0, "micro": 0}, "package": ""}, "capabilities": []}}"""
        }
    }
}
