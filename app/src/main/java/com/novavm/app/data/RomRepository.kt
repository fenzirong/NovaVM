package com.novavm.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@Serializable
internal data class ImportedRomFile(
    val version: Int = 1,
    val roms: List<ImportedRom> = emptyList()
)

/**
 * Curated open-source guest images plus user-imported disk/kernel images.
 *
 * Like [VmRepository] this is Android-free: everything lives under a plain
 * directory, so the catalogue and import flow are unit-testable.
 */
class RomRepository(
    val rootDir: File,
    private val json: Json = VmRepository.defaultJson,
    private val clock: () -> Long = System::currentTimeMillis
) {

    val romsDir: File = File(rootDir, "roms")
    val imagesDir: File = File(romsDir, "images")
    val indexFile: File = File(romsDir, "roms.json")

    /** Static catalogue of freely redistributable images. */
    val catalog: List<RomCatalogItem> = builtInCatalog()

    companion object {
        const val FORMAT_QCOW2 = "qcow2"
        const val FORMAT_RAW = "raw"
        private const val COPY_BUFFER = 64 * 1024

        /** Four byte QCOW2 magic "QFI\xfb". */
        val QCOW2_MAGIC = byteArrayOf(0x51, 0x46, 0x49, 0xFB.toByte())

        fun builtInCatalog(): List<RomCatalogItem> = listOf(
            RomCatalogItem(
                id = "android-x86-9",
                name = "Android-x86",
                version = "9.0-r2",
                architecture = VmArchitecture.X86_64,
                category = "Android",
                description = "Mainline Android-x86 desktop build with VirtIO GPU support.",
                downloadUrl = "https://sourceforge.net/projects/android-x86/files/Release%209.0/",
                sourceUrl = "https://www.android-x86.org/",
                license = "GPL-2.0 / Apache-2.0",
                approxSizeMb = 900
            ),
            RomCatalogItem(
                id = "blissos-16",
                name = "BlissOS",
                version = "16.9.6",
                architecture = VmArchitecture.X86_64,
                category = "Android",
                description = "Android 13 based BlissOS with desktop UI and wide VirtIO support.",
                downloadUrl = "https://sourceforge.net/projects/blissos-x86/files/Official/",
                sourceUrl = "https://blissos.org/",
                license = "GPL-3.0 / Apache-2.0",
                approxSizeMb = 1600
            ),
            RomCatalogItem(
                id = "lineage-gsi-21",
                name = "LineageOS GSI",
                version = "21.0 (Android 14)",
                architecture = VmArchitecture.ARM64,
                category = "Android",
                description = "LineageOS generic system image, bootable on ARM64 virt boards.",
                downloadUrl = "https://download.lineageos.org/gsi",
                sourceUrl = "https://github.com/lineageos",
                license = "Apache-2.0",
                approxSizeMb = 1200
            ),
            RomCatalogItem(
                id = "cuttlefish-aosp",
                name = "Cuttlefish (AOSP)",
                version = "14",
                architecture = VmArchitecture.ARM64,
                category = "Android",
                description = "Official AOSP Cuttlefish virtual device image for pKVM/AVF.",
                downloadUrl = "https://ci.android.com/builds/branches/aosp-main/grid",
                sourceUrl = "https://source.android.com/docs/devices/cuttlefish",
                license = "Apache-2.0",
                approxSizeMb = 2400,
                requiresKvm = true
            ),
            RomCatalogItem(
                id = "alpine-arm64",
                name = "Alpine Linux",
                version = "3.20",
                architecture = VmArchitecture.ARM64,
                category = "Linux",
                description = "Minimal musl Linux cloud image, ideal for fast console-mode VMs.",
                downloadUrl = "https://alpinelinux.org/downloads/",
                sourceUrl = "https://gitlab.alpinelinux.org/alpine/aports",
                license = "MIT",
                approxSizeMb = 60
            ),
            RomCatalogItem(
                id = "debian-x86_64",
                name = "Debian GNU/Linux",
                version = "12 (bookworm)",
                architecture = VmArchitecture.X86_64,
                category = "Linux",
                description = "Generic cloud image with kernel and initrd pre-installed.",
                downloadUrl = "https://cloud.debian.org/images/cloud/bookworm/latest/",
                sourceUrl = "https://www.debian.org/",
                license = "DFSG / GPL",
                approxSizeMb = 350
            )
        )
    }

    // ---------------------------------------------------------------- index

    private fun ensureDirs() {
        listOf(romsDir, imagesDir).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Unable to create ${dir.absolutePath}")
            }
        }
    }

    @Synchronized
    fun loadAll(): List<ImportedRom> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<ImportedRomFile>(indexFile.readText(Charsets.UTF_8)).roms
        }.getOrElse {
            runCatching {
                json.decodeFromString<List<ImportedRom>>(indexFile.readText(Charsets.UTF_8))
            }.getOrDefault(emptyList())
        }
    }

    @Synchronized
    fun saveAll(roms: List<ImportedRom>) {
        ensureDirs()
        indexFile.writeText(
            json.encodeToString(ImportedRomFile(roms = roms)),
            Charsets.UTF_8
        )
    }

    fun imported(): List<ImportedRom> = loadAll()

    fun find(id: String): ImportedRom? = loadAll().firstOrNull { it.id == id }

    fun catalogItem(id: String): RomCatalogItem? = catalog.firstOrNull { it.id == id }

    // --------------------------------------------------------------- import

    /**
     * Copies [input] into `roms/images/<name>` and registers it. The file
     * extension is preserved, and collisions get a `-1`, `-2`, ... suffix.
     */
    @Synchronized
    fun importRom(
        input: InputStream,
        fileName: String,
        displayName: String = fileName,
        architecture: VmArchitecture = VmArchitecture.ARM64,
        performDetect: Boolean = true
    ): ImportedRom {
        ensureDirs()
        val safeName = sanitizeFileName(fileName)
        require(safeName.isNotBlank()) { "fileName must not be blank" }
        val target = uniqueTarget(safeName)

        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output, COPY_BUFFER) }
        }

        val detected = if (performDetect) detectFormat(target) else FORMAT_RAW
        val rom = ImportedRom(
            id = UUID.randomUUID().toString(),
            displayName = displayName.ifBlank { safeName },
            fileName = target.name,
            absolutePath = target.absolutePath,
            format = detected,
            sizeBytes = target.length(),
            architecture = architecture,
            importedAtEpochMs = clock()
        )
        saveAll(loadAll() + rom)
        return rom
    }

    /** Registers an image that the user already placed in `roms/images`. */
    @Synchronized
    fun registerExistingImage(
        file: File,
        displayName: String = file.name,
        architecture: VmArchitecture = VmArchitecture.ARM64
    ): ImportedRom {
        require(file.isFile) { "Not a file: ${file.absolutePath}" }
        ensureDirs()
        val target = if (file.parentFile == imagesDir) file else {
            file.copyTo(uniqueTarget(file.name), overwrite = false)
        }
        val rom = ImportedRom(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            fileName = target.name,
            absolutePath = target.absolutePath,
            format = detectFormat(target),
            sizeBytes = target.length(),
            architecture = architecture,
            importedAtEpochMs = clock()
        )
        saveAll(loadAll() + rom)
        return rom
    }

    @Synchronized
    fun delete(id: String, deleteFile: Boolean = true): Boolean {
        val existing = loadAll()
        val rom = existing.firstOrNull { it.id == id } ?: return false
        saveAll(existing.filterNot { it.id == id })
        if (deleteFile) {
            val file = File(rom.absolutePath)
            if (file.isFile && file.parentFile?.canonicalFile == imagesDir.canonicalFile) {
                file.delete()
            }
        }
        return true
    }

    // --------------------------------------------------------------- helpers

    /**
     * Reads the first four bytes: QCOW2 magic means "qcow2", anything else is
     * treated as a flat raw image.
     */
    fun detectFormat(file: File): String {
        if (!file.isFile || file.length() < 4) return FORMAT_RAW
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                val read = stream.read(header)
                if (read == 4 && header.contentEquals(QCOW2_MAGIC)) FORMAT_QCOW2 else FORMAT_RAW
            }
        } catch (io: IOException) {
            FORMAT_RAW
        }
    }

    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch in "-_. " -> ch
                else -> '_'
            }
        }.joinToString("").trim()
        return cleaned.take(120)
    }

    private fun uniqueTarget(fileName: String): File {
        var candidate = File(imagesDir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(imagesDir, "$stem-$counter$ext")
            counter++
        }
        return candidate
    }
}
