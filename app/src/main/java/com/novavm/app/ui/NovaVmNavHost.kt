package com.novavm.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novavm.app.overlay.FloatingVmService
import com.novavm.app.overlay.VmDisplayActivity
import com.novavm.app.ui.screens.CreateVmScreen
import com.novavm.app.ui.screens.HomeScreen
import com.novavm.app.ui.screens.RomManagerScreen
import com.novavm.app.ui.screens.TransferScreen
import com.novavm.app.ui.screens.VmDetailScreen
import com.novavm.app.ui.theme.NovaPalette

/** Cross-activity navigation hint (tab switch or direct VM deep link). */
data class NavRequest(
    val tab: String? = null,
    val vmId: String? = null,
    val stamp: Long = System.currentTimeMillis()
)

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("instances", "虚拟机", Icons.Default.PhoneAndroid),
    BottomTab("roms", "镜像库", Icons.Default.Storage),
    BottomTab("transfer", "文件互传", Icons.Default.SwapHoriz)
)

private val topLevelRoutes = bottomTabs.map { it.route }.toSet()

@Composable
fun NovaVmNavHost(request: NavRequest? = null) {
    val vm: NovaVmViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refreshOverlayPermission()
    }

    fun openDisplay(vmId: String) {
        context.startActivity(
            Intent(context, VmDisplayActivity::class.java)
                .putExtra(VmDisplayActivity.EXTRA_VM_ID, vmId)
        )
    }

    fun openFloating(vmId: String) {
        if (Settings.canDrawOverlays(context)) {
            FloatingVmService.start(context, vmId)
        } else {
            overlayPermissionLauncher.launch(vm.overlaySettingsIntent())
        }
    }

    LaunchedEffect(state.messages.firstOrNull()?.id) {
        val message = state.messages.firstOrNull() ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        vm.dismissMessage(message.id)
    }

    LaunchedEffect(request?.stamp) {
        val target = request ?: return@LaunchedEffect
        when {
            target.tab != null -> {
                navController.navigate(target.tab) {
                    launchSingleTop = true
                    restoreState = true
                }
                target.vmId?.let { id -> vm.selectVm(id) }
            }
            target.vmId != null -> navController.navigate("vm/${target.vmId}") {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = NovaPalette.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (route != null && route in topLevelRoutes) {
                NavigationBar(
                    containerColor = NovaPalette.Surface,
                    tonalElevation = 0.dp
                ) {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NovaPalette.Cyan,
                                selectedTextColor = NovaPalette.Cyan,
                                indicatorColor = NovaPalette.CyanSoft,
                                unselectedIconColor = NovaPalette.TextMuted,
                                unselectedTextColor = NovaPalette.TextMuted
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "instances",
            modifier = Modifier.padding(padding)
        ) {
            composable("instances") {
                HomeScreen(
                    state = state,
                    vm = vm,
                    onOpenCreate = { navController.navigate("create") },
                    onOpenDisplay = { id -> openDisplay(id) },
                    onOpenSettings = { id -> navController.navigate("vm/$id") },
                    onOpenFloating = { id -> openFloating(id) }
                )
            }
            composable("roms") {
                RomManagerScreen(state = state, vm = vm)
            }
            composable("transfer") {
                TransferScreen(state = state, vm = vm)
            }
            composable("create") {
                CreateVmScreen(
                    state = state,
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onCreated = { id ->
                        navController.popBackStack()
                        navController.navigate("vm/$id")
                    }
                )
            }
            composable("vm/{vmId}") { entry ->
                val vmId = entry.arguments?.getString("vmId").orEmpty()
                VmDetailScreen(
                    state = state,
                    profileId = vmId,
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenDisplay = { id -> openDisplay(id) },
                    onOpenFloating = { id -> openFloating(id) }
                )
            }
        }
    }
}
