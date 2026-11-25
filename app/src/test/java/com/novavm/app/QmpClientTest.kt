package com.novavm.app

import com.novavm.app.engine.QmpCommands
import com.novavm.app.engine.QmpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmpClientTest {

    private val json = Json { isLenient = true }

    private fun executeOf(payload: String): String =
        json.parseToJsonElement(payload).jsonObject["execute"]!!.jsonPrimitive.content

    private fun argumentsOf(payload: String) =
        json.parseToJsonElement(payload).jsonObject["arguments"]!!.jsonObject

    @Test
    fun `capabilities handshake is well formed JSON`() {
        val payload = QmpCommands.capabilities()
        assertEquals("qmp_capabilities", executeOf(payload))
        assertTrue(payload.startsWith("{"))
        assertTrue(payload.endsWith("}"))
    }

    @Test
    fun `simple control commands serialize correctly`() {
        assertEquals("query-status", executeOf(QmpCommands.queryStatus()))
        assertEquals("stop", executeOf(QmpCommands.stop()))
        assertEquals("cont", executeOf(QmpCommands.cont()))
        assertEquals("system_powerdown", executeOf(QmpCommands.systemPowerdown()))
        assertEquals("quit", executeOf(QmpCommands.quit()))
    }

    @Test
    fun `snapshot commands use human-monitor-command`() {
        val save = QmpCommands.savevm("before_update")
        assertEquals("human-monitor-command", executeOf(save))
        assertEquals("savevm before_update", argumentsOf(save)["command-line"]!!.jsonPrimitive.content)

        val load = QmpCommands.loadvm("before_update")
        assertEquals("loadvm before_update", argumentsOf(load)["command-line"]!!.jsonPrimitive.content)

        val delete = QmpCommands.delvm("before_update")
        assertEquals("delvm before_update", argumentsOf(delete)["command-line"]!!.jsonPrimitive.content)
    }

    @Test
    fun `snapshot names are sanitized for the HMP shell`() {
        val payload = QmpCommands.savevm("my snapshot / 1")
        assertEquals(
            "savevm my_snapshot___1",
            argumentsOf(payload)["command-line"]!!.jsonPrimitive.content
        )
        assertEquals("snapshot", QmpCommands.sanitize("   "))
        assertEquals("a-b_c.d", QmpCommands.sanitize("a-b_c.d"))
    }

    @Test
    fun `JSON string escaping survives quotes backslashes and newlines`() {
        val payload = QmpCommands.humanMonitor("info \"status\" \\ path\nnext")
        val decoded = argumentsOf(payload)["command-line"]!!.jsonPrimitive.content
        assertEquals("info \"status\" \\ path\nnext", decoded)

        val escaped = QmpCommands.escapeJson("a\"b\\c\nd\te")
        assertEquals("a\\\"b\\\\c\\nd\\te", escaped)
    }

    @Test
    fun `QmpResponse detects success and error payloads`() {
        val success = QmpResponse(
            raw = "{\"return\":{}}",
            payload = json.parseToJsonElement("{\"return\":{}}").jsonObject
        )
        assertTrue(success.hasReturn)
        assertFalse(success.isError)
        assertNull(success.errorMessage)

        val failure = QmpResponse(
            raw = "{\"error\":{\"class\":\"GenericError\",\"desc\":\"Snapshot not found\"}}",
            payload = json.parseToJsonElement(
                "{\"error\":{\"class\":\"GenericError\",\"desc\":\"Snapshot not found\"}}"
            ).jsonObject
        )
        assertTrue(failure.isError)
        assertEquals("Snapshot not found", failure.errorMessage)
        assertFalse(failure.hasReturn)
    }

    @Test
    fun `builder output parses as strict JSON`() {
        val strict = Json
        listOf(
            QmpCommands.capabilities(),
            QmpCommands.queryStatus(),
            QmpCommands.stop(),
            QmpCommands.cont(),
            QmpCommands.systemPowerdown(),
            QmpCommands.quit(),
            QmpCommands.savevm("snap-1"),
            QmpCommands.loadvm("snap-1"),
            QmpCommands.delvm("snap-1"),
            QmpCommands.humanMonitor("info block")
        ).forEach { payload ->
            val element = strict.parseToJsonElement(payload)
            assertTrue(payload, element.jsonObject.containsKey("execute"))
        }
    }
}
