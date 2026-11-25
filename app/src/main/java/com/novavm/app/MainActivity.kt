package com.novavm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.novavm.app.ui.NavRequest
import com.novavm.app.ui.NovaVmNavHost
import com.novavm.app.ui.theme.NovaVmTheme

/**
 * Compose host. Bottom navigation exposes the three top-level tabs; deep links
 * from the fullscreen display (file transfer) arrive through [onNewIntent].
 */
class MainActivity : ComponentActivity() {

    private val navRequest = mutableStateOf<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        navRequest.value = requestFromIntent(intent)
        ensureNotificationPermission()
        runCatching { NovaVmApp.from(this).vmInstanceManager.refresh() }
        setContent {
            NovaVmTheme {
                NovaVmNavHost(request = navRequest.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navRequest.value = requestFromIntent(intent)
    }

    private fun requestFromIntent(intent: Intent?): NavRequest? {
        if (intent == null) return null
        val tab = intent.getStringExtra(EXTRA_TAB)
        val vmId = intent.getStringExtra(EXTRA_VM_ID)
        if (tab == null && vmId == null) return null
        return NavRequest(tab = tab, vmId = vmId)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    companion object {
        const val EXTRA_TAB = "com.novavm.app.extra.TAB"
        const val EXTRA_VM_ID = "com.novavm.app.extra.VM_ID"
        private const val NOTIFICATION_REQUEST = 1101
    }
}
