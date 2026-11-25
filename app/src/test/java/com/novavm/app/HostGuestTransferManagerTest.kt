package com.novavm.app

import com.novavm.app.transfer.HostGuestTransferManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class HostGuestTransferManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var vmDir: File
    private lateinit var manager: HostGuestTransferManager

    @Before
    fun setUp() {
        vmDir = temp.newFolder("vm")
        manager = HostGuestTransferManager(vmDir)
        manager.ensureDirectories()
    }

    @Test
    fun `import lists and exports a file`() {
        val imported = manager.importFile("hello nova".byteInputStream(), "notes.txt")
        assertEquals("notes.txt", imported.name)
        assertTrue(File(vmDir, "shared/notes.txt").isFile)
        assertEquals("hello nova", imported.readText())

        val listing = manager.listFiles()
        assertEquals(listOf("apks", "notes.txt"), listing.map { it.name }.sorted())
        assertTrue(listing.first { it.name == "notes.txt" }.sizeBytes > 0)

        val out = ByteArrayOutputStream()
        manager.exportFile("notes.txt", out)
        assertEquals("hello nova", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `overwrite false produces unique names`() {
        manager.importFile("one".byteInputStream(), "same.txt")
        val second = manager.importFile("two".byteInputStream(), "same.txt", overwrite = false)
        val third = manager.importFile("three".byteInputStream(), "same.txt", overwrite = false)

        assertEquals("same-1.txt", second.name)
        assertEquals("same-2.txt", third.name)
        assertEquals(3, manager.listFiles().count { !it.isDirectory })
    }

    @Test
    fun `path traversal is rejected and sanitised`() {
        val imported = manager.importFile("x".byteInputStream(), "../../evil.txt")
        assertEquals("evil.txt", imported.name)
        assertTrue(imported.canonicalPath.startsWith(manager.sharedDir.canonicalPath))
        assertNull(manager.resolveFile("../../profiles.json"))
        assertNull(manager.resolveFile("does-not-exist.txt"))
        assertFalse(File(vmDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun `export missing file throws`() {
        assertThrows(IOException::class.java) {
            manager.exportFile("missing.bin", ByteArrayOutputStream())
        }
    }

    @Test
    fun `delete removes file but keeps directories`() {
        manager.importFile("x".byteInputStream(), "temp.txt")
        assertTrue(manager.deleteFile("temp.txt"))
        assertFalse(File(vmDir, "shared/temp.txt").exists())
        assertFalse(manager.deleteFile("temp.txt"))
        assertTrue(File(vmDir, "shared").isDirectory)
    }

    @Test
    fun `staging APKs writes installer script`() {
        val staged = manager.stageApk("apk-bytes".byteInputStream(), "app")
        assertEquals("app.apk", staged.name)
        assertTrue(staged.parentFile == manager.apkDir)
        assertEquals(listOf("app.apk"), manager.stagedApkNames())

        val script = manager.installScriptFile
        assertTrue(script.isFile)
        val content = script.readText()
        assertTrue(content.startsWith("#!/system/bin/sh"))
        assertTrue(content.contains("pm install -r -g"))
        assertTrue(content.contains("app.apk"))
        assertTrue(content.contains("APK_DIR"))

        val apks = manager.apks()
        assertEquals(1, apks.size)
        assertEquals("apks/app.apk", apks.single().relativePath)
    }

    @Test
    fun `installer script falls back to glob when no APK list given`() {
        val script = manager.buildInstallScript(emptyList())
        assertTrue(script.contains("for apk in \"\$APK_DIR\"/*.apk; do"))
    }

    @Test
    fun `ADB over TCP install commands target the forwarded port`() {
        manager.stageApk("a".byteInputStream(), "first.apk")
        manager.stageApk("b".byteInputStream(), "second.apk")

        val commands = manager.buildAdbInstallCommands(15555)
        assertEquals(3, commands.size)
        assertEquals("adb connect 127.0.0.1:15555", commands.first())
        assertTrue(commands[1].startsWith("adb -s 127.0.0.1:15555 install -r"))
        assertTrue(commands[1].contains("first.apk"))
        assertTrue(commands[2].contains("second.apk"))

        val withoutConnect = manager.buildAdbInstallCommands(15556, connectFirst = false)
        assertEquals(2, withoutConnect.size)
        assertTrue(withoutConnect.first().contains("127.0.0.1:15556"))
    }

    @Test
    fun `adb push command quotes the local path`() {
        val file = File(vmDir, "shared/some file.txt")
        file.writeText("x")
        val command = manager.buildAdbPushCommand(15555, file, "/sdcard/")
        assertTrue(command.startsWith("adb -s 127.0.0.1:15555 push"))
        assertTrue(command.contains("\"${file.absolutePath}\""))
    }

    @Test
    fun `remove APK refreshes the installer script`() {
        manager.stageApk("a".byteInputStream(), "gone.apk")
        assertTrue(manager.installScriptFile.readText().contains("gone.apk"))
        assertTrue(manager.removeApk("gone.apk"))
        assertFalse(manager.installScriptFile.readText().contains("gone.apk"))
        assertTrue(manager.stagedApkNames().isEmpty())
    }

    @Test
    fun `constructor from repository targets the VM directory`() {
        val repository = com.novavm.app.data.VmRepository(temp.newFolder("files"))
        val profile = repository.create(
            com.novavm.app.data.VmProfile.create(id = "vm-x", name = "X")
        )
        val fromRepo = HostGuestTransferManager(repository, profile.id)
        assertNotNull(fromRepo.listFiles())
        assertEquals(repository.sharedDir(profile.id).canonicalPath, fromRepo.sharedDir.canonicalPath)
    }

    private fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue(
                "expected ${type.name} but got ${error::class.java.name}",
                type.isInstance(error)
            )
            return
        }
        throw AssertionError("expected ${type.name} to be thrown")
    }
}
