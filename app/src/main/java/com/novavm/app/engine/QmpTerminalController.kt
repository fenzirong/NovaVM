package com.novavm.app.engine

import java.io.IOException
import kotlinx.serialization.json.jsonPrimitive

/**
 * One line of the QMP/HMP console: the command the user asked for and the
 * rendered reply.
 *
 * [timestampMs] is epoch millis so the UI can format it with the user's
 * locale. [isError] is true for rejected/empty commands and for QMP `error`
 * payloads — never for the offline-console diagnostic, which is a normal reply.
 */
data class QmpConsoleEntry(
    val timestampMs: Long,
    val command: String,
    val response: String,
    val isError: Boolean
) {

    /** Echo line suitable for a monospace console pane. */
    fun echoLine(): String = if (command.isEmpty()) "（空命令）" else "(qemu) $command"
}

/**
 * Bridge between the VM detail console UI and [QmpClient].
 *
 * Two modes share one entry point:
 *  - **live**: the VM is running under QEMU and a QMP listener answers on
 *    `127.0.0.1:qmpPort`; the command goes out as `human-monitor-command`.
 *  - **offline**: no QEMU process is listening yet. Instead of failing, the
 *    controller returns a diagnostic that names the exact JSON that *would* be
 *    sent plus a clearly-labelled synthetic reply, so the terminal is usable
 *    (and testable) before the first boot.
 */
object QmpTerminalController {

    /** Marks every offline reply so a synthetic payload is never mistaken for real. */
    const val OFFLINE_MARKER: String = "【控制台模式】"

    /** Shown when the user submits an empty line. */
    const val EMPTY_COMMAND_HINT: String =
        "命令为空：请输入一条 HMP 命令（例如 info status / info block / info network），" +
            "或点击上方的快捷命令按钮。未向 QMP 发送任何内容。"

    /** One-tap HMP commands, ordered for the console chip row. */
    val quickCommands: List<Pair<String, String>> = listOf(
        "运行状态" to "info status",
        "KVM 状态" to "info kvm",
        "块设备" to "info block",
        "网络信息" to "info network",
        "系统重启" to "system_reset",
        "安全关机" to "system_powerdown"
    )

    /**
     * Synthetic replies for the offline console. Deliberately generic and
     * labelled in the output — a stale or invented guest state would be worse
     * than an obvious placeholder.
     */
    private val syntheticReplies: Map<String, String> = mapOf(
        "info status" to "VM status: paused",
        "info kvm" to "kvm support: enabled",
        "info block" to "drive0 (#block0): qcow2 (disk.qcow2)",
        "info network" to "net0: type=user"
    )

    /**
     * Runs [rawCommand] as an HMP command.
     *
     * Empty/blank input is rejected without touching the socket. When
     * [isVmRunningWithQemu] is true the command is sent for real; if the socket
     * is unreachable or the command is rejected the reply still explains what
     * happened on `127.0.0.1:[qmpPort]`.
     */
    fun executeCommand(
        qmpPort: Int,
        rawCommand: String,
        isVmRunningWithQemu: Boolean
    ): QmpConsoleEntry {
        val command = rawCommand.trim()
        val now = System.currentTimeMillis()

        if (command.isEmpty()) {
            return QmpConsoleEntry(
                timestampMs = now,
                command = "",
                response = EMPTY_COMMAND_HINT,
                isError = true
            )
        }

        if (!isVmRunningWithQemu) {
            return QmpConsoleEntry(
                timestampMs = now,
                command = command,
                response = offlineResponse(qmpPort, command, null),
                isError = false
            )
        }

        return runCatching { executeLive(qmpPort, command) }.getOrElse { error ->
            QmpConsoleEntry(
                timestampMs = now,
                command = command,
                response = offlineResponse(qmpPort, command, error),
                isError = true
            )
        }
    }

    /** Sends the command over a real QMP socket and formats the HMP text. */
    private fun executeLive(qmpPort: Int, command: String): QmpConsoleEntry =
        QmpClient(port = qmpPort).use { client ->
            // connect() performs the greeting + qmp_capabilities handshake and
            // throws QmpException when either step fails.
            client.connect()
            val response = client.humanMonitorCommand(command)
            val now = System.currentTimeMillis()

            if (response.isError) {
                val detail = response.errorMessage ?: response.raw
                return@use QmpConsoleEntry(
                    timestampMs = now,
                    command = command,
                    response = "QMP 错误：$detail",
                    isError = true
                )
            }

            QmpConsoleEntry(
                timestampMs = now,
                command = command,
                response = humanMonitorText(response),
                isError = false
            )
        }

    /**
     * `human-monitor-command` returns the HMP output as a JSON string, so the
     * primitive's content already has the CR/LF escapes decoded.
     */
    private fun humanMonitorText(response: QmpResponse): String {
        val element = response.returnValue
            ?: return "(命令已执行，QEMU 未返回输出)"
        val text = runCatching { element.jsonPrimitive.content }.getOrNull()
            ?: element.toString()
        return text.trim().ifEmpty { "(命令已执行，无输出)" }
    }

    /**
     * Builds the offline diagnostic: what would be sent, where, why it was not,
     * and a synthetic body for the four informational queries.
     */
    private fun offlineResponse(qmpPort: Int, command: String, error: Throwable?): String =
        buildString {
            appendLine("$OFFLINE_MARKER 当前未检测到运行中的 QEMU 进程，已切换到离线控制台。")
            appendLine("目标通道：127.0.0.1:$qmpPort（QMP over TCP）")
            appendLine("待发送命令：${QmpCommands.humanMonitor(command)}")
            if (error != null) {
                appendLine("连接/执行失败：${describeFailure(error)}")
            }
            appendLine("说明：启动该虚拟机后，本终端会自动改用真实 QMP 通道并返回 QEMU 的实际输出。")

            val synthetic = syntheticReplies[command]
            if (synthetic != null) {
                appendLine()
                appendLine("—— 合成输出（离线示例，非真实 QEMU 数据）——")
                appendLine(synthetic)
            }
        }

    private fun describeFailure(error: Throwable): String = when (error) {
        is QmpException -> error.message ?: error.javaClass.simpleName
        is IOException -> error.message ?: "网络层 I/O 异常"
        else -> error.message ?: error.javaClass.simpleName
    }
}
