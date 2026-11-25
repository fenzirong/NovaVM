package com.novavm.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Thrown for QMP protocol or transport failures. */
class QmpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One QMP response. [payload] is the parsed JSON object, or null when the
 * server returned unparsable text.
 */
data class QmpResponse(val raw: String, val payload: JsonObject?) {

    val isError: Boolean get() = payload?.containsKey("error") == true

    val errorMessage: String?
        get() = payload?.get("error")?.let { element ->
            runCatching { element.jsonObject["desc"]?.jsonPrimitive?.content }.getOrNull()
        }

    val returnValue: JsonElement?
        get() = payload?.get("return")

    val hasReturn: Boolean get() = payload?.containsKey("return") == true
}

/**
 * Pure JSON builders for the QEMU Monitor Protocol. Kept separate from the
 * socket layer so the wire format is unit-testable without a QEMU process.
 */
object QmpCommands {

    private val json = Json { encodeDefaults = true }

    fun capabilities(): String = "{\"execute\":\"qmp_capabilities\"}"

    fun queryStatus(): String = "{\"execute\":\"query-status\"}"

    fun stop(): String = "{\"execute\":\"stop\"}"

    fun cont(): String = "{\"execute\":\"cont\"}"

    fun systemPowerdown(): String = "{\"execute\":\"system_powerdown\"}"

    fun quit(): String = "{\"execute\":\"quit\"}"

    fun humanMonitor(commandLine: String): String = buildString {
        append("{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"")
        append(escapeJson(commandLine))
        append("\"}}")
    }

    fun savevm(snapshotName: String): String = humanMonitor("savevm ${sanitize(snapshotName)}")

    fun loadvm(snapshotName: String): String = humanMonitor("loadvm ${sanitize(snapshotName)}")

    fun delvm(snapshotName: String): String = humanMonitor("delvm ${sanitize(snapshotName)}")

    /** Escapes a string for embedding inside a JSON string literal. */
    fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /** QEMU snapshot tags allow alphanumerics plus `_`, `-`, `.`. */
    fun sanitize(name: String): String =
        name.trim().map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }
            .joinToString("")
            .ifBlank { "snapshot" }

    internal fun decode(raw: String): QmpResponse = QmpResponse(
        raw = raw,
        payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    )
}

/**
 * Blocking QMP client for a single VM.
 *
 * Usage:
 * ```
 * QmpClient(port = 4444).use { client ->
 *     client.connect()
 *     client.queryStatus()
 *     client.saveSnapshot("before_update")
 * }
 * ```
 */
class QmpClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000
) : Closeable {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val lock = Any()

    @Volatile
    var greeting: String? = null
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Opens the socket, consumes the greeting and negotiates capabilities. */
    fun connect(): QmpResponse {
        synchronized(lock) {
            if (isConnected) throw QmpException("QMP client already connected")
            try {
                val newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                newSocket.soTimeout = readTimeoutMs
                socket = newSocket
                reader = BufferedReader(
                    InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8)
                )
                writer = BufferedWriter(
                    OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8)
                )
            } catch (error: IOException) {
                closeQuietly()
                throw QmpException("Cannot connect to QMP at $host:$port", error)
            }

            val greetingLine = readLineOrThrow("QMP greeting")
            greeting = greetingLine
            if (!greetingLine.contains("\"QMP\"")) {
                closeQuietly()
                throw QmpException("Unexpected QMP greeting: $greetingLine")
            }
            val capabilities = sendRaw(QmpCommands.capabilities())
            if (capabilities.isError) {
                closeQuietly()
                throw QmpException("qmp_capabilities rejected: ${capabilities.errorMessage}")
            }
            isConnected = true
            return capabilities
        }
    }

    fun queryStatus(): String? {
        val response = execute(QmpCommands.queryStatus())
        return response.returnValue?.let { element ->
            runCatching { element.jsonObject["status"]?.jsonPrimitive?.content }.getOrNull()
        }
    }

    fun stop(): QmpResponse = execute(QmpCommands.stop())

    fun cont(): QmpResponse = execute(QmpCommands.cont())

    fun systemPowerdown(): QmpResponse = execute(QmpCommands.systemPowerdown())

    fun quit(): QmpResponse = execute(QmpCommands.quit())

    fun saveSnapshot(name: String): QmpResponse = execute(QmpCommands.savevm(name))

    fun loadSnapshot(name: String): QmpResponse = execute(QmpCommands.loadvm(name))

    fun deleteSnapshot(name: String): QmpResponse = execute(QmpCommands.delvm(name))

    fun humanMonitorCommand(commandLine: String): QmpResponse =
        execute(QmpCommands.humanMonitor(commandLine))

    /** Sends a pre-built command JSON document and waits for its reply. */
    fun execute(commandJson: String): QmpResponse {
        synchronized(lock) {
            ensureConnected()
            return sendRaw(commandJson)
        }
    }

    private fun sendRaw(commandJson: String): QmpResponse {
        val out = writer ?: throw QmpException("QMP socket is closed")
        try {
            out.write(commandJson)
            out.write("\n")
            out.flush()
        } catch (error: IOException) {
            closeQuietly()
            throw QmpException("Failed to write QMP command", error)
        }
        do {
            val line = readLineOrThrow("QMP response")
            val response = QmpCommands.decode(line)
            // Asynchronous events arrive between replies; skip them.
            if (response.payload?.containsKey("event") == true) continue
            return response
        } while (true)
    }

    private fun readLineOrThrow(context: String): String {
        val input = reader ?: throw QmpException("QMP socket is closed")
        val line = try {
            input.readLine()
        } catch (error: IOException) {
            closeQuietly()
            throw QmpException("Timed out reading $context", error)
        } ?: run {
            closeQuietly()
            throw QmpException("QMP connection closed while reading $context")
        }
        if (line.isBlank()) throw QmpException("Empty $context")
        return line
    }

    private fun ensureConnected() {
        if (!isConnected) throw QmpException("QMP client is not connected")
    }

    private fun closeQuietly() {
        isConnected = false
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    override fun close() {
        synchronized(lock) { closeQuietly() }
    }
}
