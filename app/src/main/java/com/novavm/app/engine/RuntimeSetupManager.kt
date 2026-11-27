package com.novavm.app.engine

import com.novavm.app.data.VmArchitecture
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 运行时组件（QEMU 可执行文件 + UEFI 固件）的安装布局与导入。
 *
 * 目录结构：
 * ```
 * <rootDir>/runtime/
 *   bin/        qemu-system-aarch64 / qemu-system-x86_64 / qemu-img
 *   firmware/   edk2-aarch64-code.fd
 * ```
 *
 * 只接受白名单内的文件名，二进制必须真的是 ELF，写入一律走 `临时文件 + 重命名`，
 * 这样即便导入中途被杀掉也不会留下半个可执行文件污染运行时目录。
 * 与 [com.novavm.app.data.RomRepository] 一样完全不依赖 Android，可直接在 JVM 单元测试里跑。
 */
class RuntimeSetupManager(val rootDir: File) {

    /** 运行时总目录。 */
    val runtimeDir: File = File(rootDir, RUNTIME_DIR_NAME)

    /** QEMU 可执行文件目录。 */
    val binDir: File = File(runtimeDir, BIN_DIR_NAME)

    /** UEFI 固件目录。 */
    val firmwareDir: File = File(runtimeDir, FIRMWARE_DIR_NAME)

    companion object {
        const val RUNTIME_DIR_NAME = "runtime"
        const val BIN_DIR_NAME = "bin"
        const val FIRMWARE_DIR_NAME = "firmware"

        const val QEMU_AARCH64 = "qemu-system-aarch64"
        const val QEMU_X86_64 = "qemu-system-x86_64"
        const val QEMU_IMG = "qemu-img"
        const val UEFI_AARCH64 = "edk2-aarch64-code.fd"

        /** 需要有可执行位的 QEMU 程序。 */
        val EXECUTABLE_NAMES: Set<String> = setOf(QEMU_AARCH64, QEMU_X86_64, QEMU_IMG)

        /** 固件文件（不检查 ELF 头）。 */
        val FIRMWARE_NAMES: Set<String> = setOf(UEFI_AARCH64)

        /** 允许写入运行时目录的全部文件名。 */
        val ALLOWED_NAMES: Set<String> = EXECUTABLE_NAMES + FIRMWARE_NAMES

        /** 压缩包解压总大小上限：512 MiB。 */
        const val MAX_ARCHIVE_BYTES: Long = 512L * 1024L * 1024L

        /** 上限的中文展示文案。 */
        const val MAX_ARCHIVE_LABEL = "512 MiB"

        private const val COPY_BUFFER = 64 * 1024
        private const val TMP_SUFFIX = ".tmp"

        /** ELF 魔数：0x7F 'E' 'L' 'F'。 */
        private val ELF_MAGIC = byteArrayOf(
            0x7F,
            'E'.code.toByte(),
            'L'.code.toByte(),
            'F'.code.toByte()
        )
    }

    // ------------------------------------------------------------- 目录操作

    /** 创建 `runtime/bin` 与 `runtime/firmware`。 */
    fun ensureDirs() {
        listOf(runtimeDir, binDir, firmwareDir).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("无法创建运行时目录：${dir.absolutePath}")
            }
        }
    }

    // --------------------------------------------------------------- 检查

    /**
     * 扫描运行时目录，返回各组件现状。
     * 每个组件都以“存在且非空”为准，长度 0 的空壳文件视为未安装。
     */
    fun inspect(): RuntimeStatus {
        val aarch64 = installedBinary(QEMU_AARCH64)
        val x86 = installedBinary(QEMU_X86_64)
        val qemuImg = installedBinary(QEMU_IMG)
        val firmware = installedFirmware(UEFI_AARCH64)

        return RuntimeStatus(
            qemuAarch64Path = aarch64?.absolutePath,
            qemuX86_64Path = x86?.absolutePath,
            qemuImgPath = qemuImg?.absolutePath,
            uefiArm64Path = firmware?.absolutePath,
            totalRuntimeBytes = directorySize(runtimeDir)
        )
    }

    /** 校验文件是否为 ELF 二进制（读取前 4 字节魔数）。 */
    fun isValidElfBinary(file: File): Boolean {
        if (!file.isFile || file.length() < ELF_MAGIC.size) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(ELF_MAGIC.size)
                stream.read(header) == ELF_MAGIC.size && header.contentEquals(ELF_MAGIC)
            }
        } catch (io: IOException) {
            false
        }
    }

    /** 返回某架构对应的 QEMU 可执行文件路径，未安装则为 null。 */
    fun resolveQemuBinary(arch: VmArchitecture): String? = when (arch) {
        VmArchitecture.ARM64 -> installedBinary(QEMU_AARCH64)?.absolutePath
        VmArchitecture.X86_64 -> installedBinary(QEMU_X86_64)?.absolutePath
    }

    // --------------------------------------------------------------- 导入

    /**
     * 把单个文件导入运行时目录。
     *
     * - [targetName] 只保留文件名部分，且必须命中白名单，否则抛 [IllegalArgumentException]；
     * - QEMU 程序必须通过 ELF 头校验，否则同样抛 [IllegalArgumentException]；
     * - 先写 `<name>.tmp`，校验通过后再重命名覆盖，最后补齐可执行位。
     *
     * @return 落盘后的目标文件。
     */
    fun importBinary(input: InputStream, targetName: String): File {
        val name = fileNameOf(targetName)
        if (name !in ALLOWED_NAMES) {
            throw IllegalArgumentException("不支持的运行时文件：$targetName")
        }
        val isExecutable = name in EXECUTABLE_NAMES
        val target = File(if (isExecutable) binDir else firmwareDir, name)
        val parent = target.parentFile ?: throw IOException("运行时目录无效：${target.absolutePath}")
        ensureDir(parent)

        val temp = File(parent, name + TMP_SUFFIX)
        if (temp.exists() && !temp.delete()) {
            throw IOException("无法清理临时文件：${temp.absolutePath}")
        }

        try {
            input.use { stream ->
                temp.outputStream().use { output -> stream.copyTo(output, COPY_BUFFER) }
            }
            val written = temp.length()
            if (written <= 0L) {
                throw IOException("$name 内容为空，已放弃导入")
            }
            if (isExecutable && !isValidElfBinary(temp)) {
                throw IllegalArgumentException("$name 不是有效的 ELF 可执行文件，已放弃导入")
            }
            if (target.exists() && !target.delete()) {
                throw IOException("无法覆盖旧文件：${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                // 跨分区时 renameTo 会失败，退化成复制 + 删除。
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (isExecutable) {
                target.setExecutable(true, false)
            }
            return target
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /**
     * 从 `.zip` 运行时包中解出白名单组件。
     *
     * 安全措施：
     * - 逐条目校验名称，出现 `..`、绝对路径或驱动器前缀直接拒绝（Zip-Slip）；
     * - 目标路径再用 canonical path 复核一次，必须仍在 `runtime/` 之内；
     * - 解压总量超过 [MAX_ARCHIVE_BYTES] 立即中止；
     * - 非白名单条目跳过，ELF 校验不过的 QEMU 程序跳过；
     * - 每个条目同样走 `临时文件 + 重命名`，QEMU 程序补可执行位。
     *
     * @return 实际写入的文件列表（按压缩包内顺序）。
     */
    fun importRuntimeArchive(input: InputStream): List<File> {
        val created = mutableListOf<File>()
        var extractedBytes = 0L
        val canonicalRoot = runtimeDir.canonicalFile

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val rawName = entry.name
                val name = fileNameOf(rawName)

                if (name !in ALLOWED_NAMES) {
                    zip.closeEntry()
                    continue
                }

                val isExecutable = name in EXECUTABLE_NAMES
                val target = File(if (isExecutable) binDir else firmwareDir, name)
                val parent = target.parentFile ?: throw IOException("运行时目录无效：${target.absolutePath}")
                ensureDir(parent)

                val canonicalTarget = target.absoluteFile.canonicalFile
                if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw IOException("压缩包包含非法路径：$rawName")
                }

                val temp = File(parent, name + TMP_SUFFIX)
                temp.delete()
                var entryBytes = 0L
                try {
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            extractedBytes += read
                            if (extractedBytes > MAX_ARCHIVE_BYTES) {
                                throw IOException("压缩包超过 $MAX_ARCHIVE_LABEL 上限，已中止解压")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } catch (error: Throwable) {
                    temp.delete()
                    runCatching { zip.closeEntry() }
                    throw error
                }
                zip.closeEntry()

                if (entryBytes <= 0L) {
                    temp.delete()
                    continue
                }
                if (isExecutable && !isValidElfBinary(temp)) {
                    temp.delete()
                    continue
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                if (isExecutable) {
                    target.setExecutable(true, false)
                }
                created += target
            }
        }
        return created
    }

    // --------------------------------------------------------------- helpers

    private fun installedBinary(name: String): File? =
        File(binDir, name).takeIf { it.isFile && it.length() > 0L }

    private fun installedFirmware(name: String): File? =
        File(firmwareDir, name).takeIf { it.isFile && it.length() > 0L }

    /**
     * 只取文件名部分，并拒绝 `..` / 绝对路径 / 驱动器前缀。
     *
     * 越界路径在这里就抛 [IOException]，白名单校验随后再进行。
     */
    private fun fileNameOf(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.contains(':')) {
            throw IOException("非法路径：$raw")
        }
        return normalized.trim('/').substringAfterLast('/')
    }

    private fun ensureDir(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建目录：${dir.absolutePath}")
        }
    }

    private fun directorySize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

/**
 * 运行时安装状态快照，所有路径均为绝对路径，未安装则为 null。
 */
data class RuntimeStatus(
    val qemuAarch64Path: String?,
    val qemuX86_64Path: String?,
    val qemuImgPath: String?,
    val uefiArm64Path: String?,
    val totalRuntimeBytes: Long
) {

    /** 至少有一个可用的 QEMU 系统模拟器。 */
    val hasAnyQemu: Boolean
        get() = !qemuAarch64Path.isNullOrBlank() || !qemuX86_64Path.isNullOrBlank()

    /** ARM64 固件是否就位。 */
    val hasFirmware: Boolean
        get() = !uefiArm64Path.isNullOrBlank()

    /** 是否具备启动虚拟机的最低条件：QEMU + 固件。 */
    val isReady: Boolean
        get() = hasAnyQemu && hasFirmware

    /** 是否可以从容创建镜像（qcow2 需要 qemu-img）。 */
    val canManageImages: Boolean
        get() = !qemuImgPath.isNullOrBlank()
}
