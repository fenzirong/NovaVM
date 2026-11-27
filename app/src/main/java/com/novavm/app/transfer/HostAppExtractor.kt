package com.novavm.app.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * A host application the user may push into a guest VM as an APK.
 *
 * [apkPath] is the base APK on the host filesystem (`ApplicationInfo.sourceDir`).
 * Split APKs are intentionally not covered: `pm install` on a single base APK is
 * exactly what the guest-side installer script in [HostGuestTransferManager] runs.
 */
data class HostAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val isSystemApp: Boolean
) {
    /** True when the recorded base APK looks usable for extraction. */
    val isExtractable: Boolean
        get() = apkPath.isNotBlank() && apkSizeBytes > 0L
}

/**
 * Lists host apps and hands their APKs to the VirtIO-9p share.
 *
 * ## Android 11+ package visibility
 *
 * Since API 30 `PackageManager` only reports packages the manifest opts into
 * seeing. Nothing here fails when the list comes back partial — it returns
 * whatever is visible. To see more, declare the usual queries:
 *
 * ```xml
 * <queries>
 *     <intent>
 *         <action android:name="android.intent.action.MAIN" />
 *         <category android:name="android.intent.category.LAUNCHER" />
 *     </intent>
 * </queries>
 * ```
 *
 * Both the launchable-activity query and `getInstalledPackages` are best-effort:
 * every entry is resolved individually, and any failure (hidden package,
 * unreadable `sourceDir`, race with an uninstall) drops that one entry instead
 * of failing the whole listing.
 */
object HostAppExtractor {

    /** Longest package-derived file stem, leaving room for the `.apk` suffix. */
    private const val MAX_STEM_LENGTH = 100

    // ------------------------------------------------------------ listing

    /**
     * Installed host apps, sorted by [HostAppInfo.appName] (case-insensitive,
     * package name as tie-breaker).
     *
     * @param includeSystem when false (default) system and updated-system
     *   packages are filtered out.
     */
    fun listInstalledApps(context: Context, includeSystem: Boolean = false): List<HostAppInfo> {
        val packageManager = runCatching { context.packageManager }.getOrNull() ?: return emptyList()
        val byPackage = LinkedHashMap<String, HostAppInfo>()

        // Launchable apps first: they answer "what can I install?" and stay
        // visible under Android 11+ visibility rules.
        launchablePackages(packageManager).forEach { packageName ->
            // `putIfAbsent(packageName, describe(...))` evaluates describe()
            // even when the entry already exists, doubling the binder calls
            // for every launchable app on a cold list load.
            if (byPackage.containsKey(packageName)) return@forEach
            describe(packageManager, packageName)?.let { byPackage[packageName] = it }
        }
        // Then anything else the platform is willing to report (non-launcher
        // apps, plus system packages when asked for). Duplicates are ignored.
        installedPackages(packageManager).forEach { packageName ->
            if (byPackage.containsKey(packageName)) return@forEach
            describe(packageManager, packageName)?.let { byPackage[packageName] = it }
        }

        return byPackage.values
            .filter { includeSystem || !it.isSystemApp }
            .filter { it.apkPath.isNotBlank() }
            .sortedWith(compareBy({ it.appName.lowercase(Locale.ROOT) }, { it.packageName }))
    }

    /** Convenience wrapper: launchable user apps only. */
    fun listUserApps(context: Context): List<HostAppInfo> =
        listInstalledApps(context, includeSystem = false)

    // --------------------------------------------------------- extraction

    /**
     * Copies `app.apkPath` into the VM share as `shared/apks/<packageName>.apk`
     * and refreshes the guest installer script.
     *
     * @throws IOException when the host APK cannot be read.
     */
    fun extractApkToVm(app: HostAppInfo, transferManager: HostGuestTransferManager): File {
        val source = File(app.apkPath)
        if (app.apkPath.isBlank() || !source.isFile || !source.canRead()) {
            throw IOException("Host APK is not readable: ${app.apkPath}")
        }
        val stem = transferManager.sanitizeFileName(app.packageName)
            .ifBlank { transferManager.sanitizeFileName(source.nameWithoutExtension) }
            .ifBlank { "host_app" }
            .take(MAX_STEM_LENGTH)
        val stageName = if (stem.endsWith(".apk", ignoreCase = true)) stem else "$stem.apk"
        return source.inputStream().use { transferManager.stageApk(it, stageName) }
    }

    // ------------------------------------------------------------ helpers

    @Suppress("DEPRECATION") // no pre-API-33 replacement for queryIntentActivities(Intent, Int) here
    private fun launchablePackages(packageManager: PackageManager): List<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, 0)
            .orEmpty()
            .mapNotNull { activity -> activity.activityInfo?.packageName }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION") // returns only the visible set on API 30+; it never throws for that reason
    private fun installedPackages(packageManager: PackageManager): List<String> = runCatching {
        packageManager.getInstalledPackages(0)
            .orEmpty()
            .map { it.packageName }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * Resolves one package into a [HostAppInfo], or null when it is hidden,
     * gone, or its base APK cannot be read.
     */
    @Suppress("DEPRECATION") // avoids an SDK_INT branch for the API 33 PackageInfoFlags overload
    private fun describe(packageManager: PackageManager, packageName: String): HostAppInfo? = runCatching {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        // No explicit `ApplicationInfo?` type here: the elvis-return makes the
        // value non-null, and an explicit nullable type would defeat smart casts.
        val applicationInfo = packageInfo.applicationInfo ?: return@runCatching null
        val apkPath = applicationInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val apkFile = File(apkPath)
        // Missing or unreadable sourceDir (moved app, split-only install, race
        // with an uninstall) is skipped instead of producing a broken entry.
        if (!apkFile.isFile || !apkFile.canRead()) return@runCatching null

        val resolvedLabel = runCatching {
            packageManager.getApplicationLabel(applicationInfo)?.toString()
        }.getOrNull()
        val label = resolvedLabel?.takeIf { it.isNotBlank() } ?: packageName
        val versionName = packageInfo.versionName.orEmpty()
        val flags = applicationInfo.flags
        val isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        HostAppInfo(
            packageName = packageName,
            appName = label,
            versionName = versionName,
            apkPath = apkFile.absolutePath,
            apkSizeBytes = apkFile.length(),
            isSystemApp = isSystemApp
        )
    }.getOrNull()
}
