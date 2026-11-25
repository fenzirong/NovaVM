package com.novavm.app.engine

import android.os.Build
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Host capabilities relevant to running guests, merged from the native probe
 * and the Android platform level.
 */
data class HostCapabilities(
    val kvmAvailable: Boolean,
    val vhostVsockAvailable: Boolean,
    val avfSupported: Boolean,
    val cpuCores: Int,
    val totalRamMb: Long,
    val primaryAbi: String,
    val qemuBinaryPresent: Boolean = false,
    val sdkInt: Int = 0
) {

    /** AVF is only exposed from Android 13 (API 33) upwards. */
    val avfUsable: Boolean get() = avfSupported && sdkInt >= 33

    /** True when `/dev/kvm` is usable for hardware acceleration. */
    val hardwareAccelerated: Boolean get() = kvmAvailable

    val acceleration: String get() = if (kvmAvailable) "kvm" else "tcg,thread=multi"

    val effectiveCores: Int get() = cpuCores.coerceIn(1, 16)

    /** Half of host RAM, clamped to a sane guest window. */
    val recommendedRamMb: Int
        get() {
            if (totalRamMb <= 0) return 2048
            return ((totalRamMb / 2).coerceIn(512, 8192)).toInt()
        }

    val preferredBackend: String get() = if (avfUsable) "AVF" else "QEMU"

    fun summary(): String = buildString {
        append("ABI=").append(primaryAbi)
        append(", cores=").append(cpuCores)
        append(", RAM=").append(if (totalRamMb > 0) "${totalRamMb}MB" else "unknown")
        append(", KVM=").append(if (kvmAvailable) "yes" else "no")
        append(", vhost-vsock=").append(if (vhostVsockAvailable) "yes" else "no")
        append(", AVF=").append(if (avfUsable) "yes" else "no")
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromNative(
            payload: String,
            sdkInt: Int = if (Build.VERSION.SDK_INT > 0) Build.VERSION.SDK_INT else 33
        ): HostCapabilities {
            val native = runCatching {
                json.decodeFromString<NativeCapabilities>(payload)
            }.getOrElse {
                NativeCapabilities()
            }
            return HostCapabilities(
                kvmAvailable = native.kvmAvailable,
                vhostVsockAvailable = native.vhostVsockAvailable,
                avfSupported = native.avfSupported,
                cpuCores = native.cpuCores.coerceAtLeast(1),
                totalRamMb = native.totalRamMb,
                primaryAbi = native.primaryAbi,
                qemuBinaryPresent = native.qemuBinaryPresent,
                sdkInt = sdkInt
            )
        }

        fun detect(sdkInt: Int = Build.VERSION.SDK_INT): HostCapabilities =
            fromNative(NativeEngine.detectCapabilities(), sdkInt)

        /** Conservative baseline used on unsupported hosts and in tests. */
        fun conservative(sdkInt: Int = 0): HostCapabilities = HostCapabilities(
            kvmAvailable = false,
            vhostVsockAvailable = false,
            avfSupported = false,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalRamMb = 2048,
            primaryAbi = "arm64-v8a",
            qemuBinaryPresent = false,
            sdkInt = sdkInt
        )
    }
}
