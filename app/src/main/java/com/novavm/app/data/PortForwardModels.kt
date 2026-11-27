package com.novavm.app.data

import kotlinx.serialization.Serializable

/**
 * Transport used by one forwarded guest port.
 *
 * [qemuTag] is the lowercase token QEMU expects inside a `hostfwd=` clause,
 * so the wire format never has to be spelled out at the call site.
 */
@Serializable
enum class ForwardProtocol {
    TCP,
    UDP;

    /** `tcp` / `udp` — the token used by `-netdev user,hostfwd=...`. */
    val qemuTag: String get() = if (this == UDP) "udp" else "tcp"

    /** Short label for the rule list in the VM detail screen. */
    val displayName: String get() = if (this == UDP) "UDP" else "TCP"
}

/**
 * One host → guest port mapping for a VM.
 *
 * Rules are serialized with the profile, so every field stays stable across
 * app versions. Host ports start at 1024 because the lower range is reserved
 * on Android/Linux and would make the VM unbootable on most devices.
 *
 * [toQemuHostFwdArg] is deliberately side-effect free: it formats an argument
 * even for an out-of-range rule so the UI can still show what *would* be
 * emitted; callers gate on [isValid] before handing it to the engine.
 */
@Serializable
data class PortForwardRule(
    val id: String,
    val label: String,
    val protocol: ForwardProtocol = ForwardProtocol.TCP,
    val hostPort: Int,
    val guestPort: Int,
    val enabled: Boolean = true
) {

    companion object {
        /** Lowest host port we allow: everything below is privileged. */
        const val MIN_HOST_PORT: Int = 1024
        const val MIN_GUEST_PORT: Int = 1
        const val MAX_PORT: Int = 65535

        /** Loopback-only binding; the VM never exposes a port to the LAN. */
        const val HOST_ADDRESS: String = "127.0.0.1"
    }

    /** True when both ports sit inside the ranges the engine can bind. */
    fun isValid(): Boolean =
        hostPort in MIN_HOST_PORT..MAX_PORT && guestPort in MIN_GUEST_PORT..MAX_PORT

    /**
     * Chinese explanation of the first validation failure, or null when the
     * rule is usable. Keeps the UI from inventing its own wording.
     */
    fun validationMessage(): String? = when {
        hostPort !in MIN_HOST_PORT..MAX_PORT ->
            "主机端口需在 $MIN_HOST_PORT-$MAX_PORT 之间（低端口为系统保留）"
        guestPort !in MIN_GUEST_PORT..MAX_PORT ->
            "虚拟机端口需在 $MIN_GUEST_PORT-$MAX_PORT 之间"
        else -> null
    }

    /**
     * QEMU `-netdev user` fragment, e.g. `hostfwd=tcp:127.0.0.1:2222-:22`.
     *
     * The guest side is left blank (`-:22`) so QEMU forwards to the guest's own
     * address rather than a pinned one.
     */
    fun toQemuHostFwdArg(): String =
        "hostfwd=${protocol.qemuTag}:$HOST_ADDRESS:$hostPort-:$guestPort"

    /** Human readable one-liner, e.g. `SSH 调试 · TCP 127.0.0.1:2222 → 22`. */
    fun summary(): String =
        "$label · ${protocol.displayName} $HOST_ADDRESS:$hostPort → $guestPort" +
            if (enabled) "" else "（已停用）"

    /** Copy with a fresh identity — used when a preset is added twice. */
    fun withId(newId: String): PortForwardRule = copy(id = newId)
}

/**
 * Ready-made rules for the ports people actually forward out of a NovaVM guest.
 *
 * Every accessor returns a fresh instance, so ids stay stable per preset while
 * the caller is free to [PortForwardRule.withId] the copy before storing it.
 */
object PortForwardPresets {

    const val SSH_ID: String = "preset-ssh"
    const val HTTP_ID: String = "preset-http"
    const val HTTPS_ID: String = "preset-https"
    const val FRIDA_ID: String = "preset-frida"

    /** SSH/adb shell into the guest. */
    fun ssh(id: String = SSH_ID, enabled: Boolean = true): PortForwardRule = PortForwardRule(
        id = id,
        label = "SSH 调试",
        protocol = ForwardProtocol.TCP,
        hostPort = 2222,
        guestPort = 22,
        enabled = enabled
    )

    /** Plain HTTP service running inside the guest. */
    fun http(id: String = HTTP_ID, enabled: Boolean = true): PortForwardRule = PortForwardRule(
        id = id,
        label = "HTTP 服务",
        protocol = ForwardProtocol.TCP,
        hostPort = 18080,
        guestPort = 8080,
        enabled = enabled
    )

    /** TLS endpoint (8443 on the guest side so it never collides with 443). */
    fun https(id: String = HTTPS_ID, enabled: Boolean = true): PortForwardRule = PortForwardRule(
        id = id,
        label = "HTTPS 服务",
        protocol = ForwardProtocol.TCP,
        hostPort = 18443,
        guestPort = 8443,
        enabled = enabled
    )

    /** Frida server: same port on both sides so tooling defaults keep working. */
    fun frida(id: String = FRIDA_ID, enabled: Boolean = true): PortForwardRule = PortForwardRule(
        id = id,
        label = "Frida 注入",
        protocol = ForwardProtocol.TCP,
        hostPort = 27042,
        guestPort = 27042,
        enabled = enabled
    )

    /** The four presets in display order. */
    val all: List<PortForwardRule> get() = listOf(ssh(), http(), https(), frida())

    /** Every preset id, in the same order as [all]. */
    val ids: List<String> get() = all.map { it.id }

    fun byId(id: String): PortForwardRule? = all.firstOrNull { it.id == id }
}
