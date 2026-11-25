package com.novavm.app.transfer

import com.novavm.app.data.VmRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Entry of a VM's shared folder as shown in the transfer UI. */
data class SharedFileInfo(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val isDirectory: Boolean
)

/**
 * Handles the host side of the VirtIO-9p share.
 *
 * Everything lives under `<vmDir>/shared`, which QemuBackend exports as
 * `mount_tag=novavm_share`. Staged APKs land in `shared/apks` together with a
 * guest-side installer script and, optionally, host-side `adb install`
 * commands aimed at the VM's forwarded ADB port.
 */
class HostGuestTransferManager(private val vmDir: File) {

    constructor(repository: VmRepository, vmId: String) : this(repository.vmDir(vmId))

    val sharedDir: File = File(vmDir, "shared")
    val apkDir: File = File(sharedDir, "apks")
    val installScriptFile: File = File(sharedDir, INSTALL_SCRIPT_NAME)

    companion object {
        const val INSTALL_SCRIPT_NAME = "install_apks.sh"
        const val MOUNT_TAG = "novavm_share"
        private const val COPY_BUFFER = 32 * 1024

        fun adbSerial(adbPort: Int): String = "127.0.0.1:$adbPort"
    }

    fun ensureDirectories() {
        listOf(sharedDir, apkDir).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Unable to create ${dir.absolutePath}")
            }
        }
    }

    // ------------------------------------------------------------- listing

    fun listFiles(relativeDirectory: String = ""): List<SharedFileInfo> {
        ensureDirectories()
        val directory = resolveDirectory(relativeDirectory) ?: return emptyList()
        val base = sharedDir.canonicalFile
        return directory.listFiles()
            ?.map { file ->
                SharedFileInfo(
                    name = file.name,
                    relativePath = file.relativeTo(base).invariantSeparatorsPath,
                    sizeBytes = if (file.isDirectory) 0 else file.length(),
                    lastModifiedEpochMs = file.lastModified(),
                    isDirectory = file.isDirectory
                )
            }
            ?.sortedWith(compareByDescending<SharedFileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
    }

    fun apks(): List<SharedFileInfo> = listFiles("apks").filterNot { it.isDirectory }

    // -------------------------------------------------------------- import

    /**
     * Copies [input] into the share. Returns the created file.
     *
     * @param subdirectory optional folder inside `shared`, e.g. "apks".
     * @param overwrite when false a `-1`, `-2` suffix is appended on collision.
     */
    fun importFile(
        input: InputStream,
        fileName: String,
        subdirectory: String = "",
        overwrite: Boolean = true
    ): File {
        ensureDirectories()
        val targetDir = resolveDirectory(subdirectory)
            ?: throw IOException("Invalid shared subdirectory: $subdirectory")
        val safeName = sanitizeFileName(fileName)
        require(safeName.isNotBlank()) { "fileName must not be blank" }

        val baseTarget = File(targetDir, safeName)
        val target = if (overwrite) baseTarget else uniqueTarget(baseTarget)
        if (target.exists() && target.isDirectory) {
            throw IOException("${target.name} is a directory")
        }

        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink, COPY_BUFFER) }
        }
        return target
    }

    fun importBytes(
        bytes: ByteArray,
        fileName: String,
        subdirectory: String = "",
        overwrite: Boolean = true
    ): File = importFile(bytes.inputStream(), fileName, subdirectory, overwrite)

    fun exportFile(relativePath: String, output: OutputStream) {
        val file = resolveFile(relativePath) ?: throw IOException("No such shared file: $relativePath")
        file.inputStream().use { it.copyTo(output, COPY_BUFFER) }
    }

    fun readText(relativePath: String): String {
        val file = resolveFile(relativePath) ?: throw IOException("No such shared file: $relativePath")
        return file.readText(StandardCharsets.UTF_8)
    }

    fun deleteFile(relativePath: String): Boolean {
        val file = resolveFile(relativePath) ?: return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    // ----------------------------------------------------------- APK stage

    fun stageApk(input: InputStream, fileName: String, overwrite: Boolean = true): File {
        val name = if (fileName.lowercase().endsWith(".apk")) fileName else "$fileName.apk"
        val staged = importFile(input, name, subdirectory = "apks", overwrite = overwrite)
        writeInstallScript()
        return staged
    }

    fun stageApks(files: List<File>, overwrite: Boolean = true): List<File> {
        val staged = files.map { file ->
            file.inputStream().use { stageApk(it, file.name, overwrite) }
        }
        writeInstallScript()
        return staged
    }

    /** Removes a staged APK and regenerates the installer script. */
    fun removeApk(fileName: String): Boolean {
        val removed = deleteFile("apks/$fileName")
        if (removed) writeInstallScript()
        return removed
    }

    // ------------------------------------------------------------- scripts

    /** Guest-side installer script (POSIX sh, runs as root inside the VM). */
    fun buildInstallScript(apkNames: List<String>? = null): String {
        val names = apkNames?.sorted() ?: stagedApkNames().sorted()
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Generated by NovaVM. Installs APKs staged in the VirtIO-9p share.")
            appendLine("# Usage: sh /sdcard/novavm/install_apks.sh")
            appendLine("set -u")
            appendLine("SCRIPT_DIR=\$(cd \"\$(dirname \"\$0\")\" && pwd)")
            appendLine("APK_DIR=\"\$SCRIPT_DIR/apks\"")
            appendLine("")
            appendLine("if [ ! -d \"\$APK_DIR\" ]; then")
            appendLine("  echo \"[novavm] no apk directory at \$APK_DIR\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("")
            appendLine("install_one() {")
            appendLine("  apk=\"\$1\"")
            appendLine("  echo \"[novavm] installing \$apk\"")
            appendLine("  pm install -r -g \"\$apk\" || echo \"[novavm] failed: \$apk\"")
            appendLine("}")
            appendLine("")
            if (names.isEmpty()) {
                appendLine("for apk in \"\$APK_DIR\"/*.apk; do")
                appendLine("  [ -e \"\$apk\" ] || continue")
                appendLine("  install_one \"\$apk\"")
                appendLine("done")
            } else {
                names.forEach { name ->
                    appendLine("if [ -f \"\$APK_DIR/${escapeShell(name)}\" ]; then")
                    appendLine("  install_one \"\$APK_DIR/${escapeShell(name)}\"")
                    appendLine("fi")
                }
            }
            appendLine("")
            appendLine("echo \"[novavm] apk staging complete\"")
        }
    }

    /** Names of APKs currently staged in `shared/apks`. */
    fun stagedApkNames(): List<String> = apkDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    /** Writes `shared/install_apks.sh` and marks it executable. */
    fun writeInstallScript(): File {
        ensureDirectories()
        installScriptFile.writeText(buildInstallScript(), StandardCharsets.UTF_8)
        installScriptFile.setExecutable(true, false)
        return installScriptFile
    }

    /**
     * Host-side commands that install every staged APK over ADB-over-TCP:
     * ```
     * adb connect 127.0.0.1:15555
     * adb -s 127.0.0.1:15555 install -r /.../shared/apks/app.apk
     * ```
     */
    fun buildAdbInstallCommands(
        adbPort: Int,
        apkFiles: List<File>? = null,
        connectFirst: Boolean = true
    ): List<String> {
        val serial = adbSerial(adbPort)
        val files = apkFiles ?: apkDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val commands = mutableListOf<String>()
        if (connectFirst) commands += "adb connect $serial"
        files.forEach { apk ->
            commands += "adb -s $serial install -r ${quote(apk.absolutePath)}"
        }
        return commands
    }

    fun buildAdbPushCommand(adbPort: Int, localFile: File, guestPath: String = "/sdcard/"): String =
        "adb -s ${adbSerial(adbPort)} push ${quote(localFile.absolutePath)} ${quote(guestPath)}"

    // ------------------------------------------------------------- helpers

    /** Resolves a path inside the share, rejecting traversal and absolutes. */
    fun resolveFile(relativePath: String): File? {
        val canonicalBase = sharedDir.canonicalFile
        val candidate = File(canonicalBase, relativePath).canonicalFile
        return if (candidate.path == canonicalBase.path || candidate.path.startsWith(canonicalBase.path + File.separator)) {
            candidate.takeIf { it.exists() && it.isFile }
        } else {
            null
        }
    }

    fun resolveDirectory(relativeDirectory: String): File? {
        val canonicalBase = sharedDir.canonicalFile
        val candidate = File(canonicalBase, relativeDirectory).canonicalFile
        val allowed = candidate.path == canonicalBase.path ||
            candidate.path.startsWith(canonicalBase.path + File.separator)
        if (!allowed) return null
        if (!candidate.exists() && !candidate.mkdirs()) return null
        return candidate.takeIf { it.isDirectory }
    }

    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        return base.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch in "-_. " -> ch
                else -> '_'
            }
        }.joinToString("").trim().take(120)
    }

    private fun uniqueTarget(base: File): File {
        if (!base.exists()) return base
        val name = base.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        var candidate = File(base.parentFile, "$stem-$counter$ext")
        while (candidate.exists()) {
            counter++
            candidate = File(base.parentFile, "$stem-$counter$ext")
        }
        return candidate
    }

    private fun escapeShell(value: String): String = value.replace("\"", "\\\"")

    private fun quote(value: String): String =
        if (value.any { it.isWhitespace() }) "\"$value\"" else value
}
