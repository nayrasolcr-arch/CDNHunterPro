package com.cdhunter.pro.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cdhunter.pro.R
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.screens.AboutScreen
import com.cdhunter.pro.ui.screens.AdvancedSettingsScreen
import com.cdhunter.pro.ui.screens.CdnSelectScreen
import com.cdhunter.pro.ui.screens.ChartsScreen
import com.cdhunter.pro.ui.screens.CompareScreen
import com.cdhunter.pro.ui.screens.DashboardScreen
import com.cdhunter.pro.ui.screens.ErrorLogScreen
import com.cdhunter.pro.ui.screens.ExportScreen
import com.cdhunter.pro.ui.screens.HistoryScreen
import com.cdhunter.pro.ui.screens.IpDetailScreen
import com.cdhunter.pro.ui.screens.NetworkSettingsScreen
import com.cdhunter.pro.ui.screens.RangeManagerScreen
import com.cdhunter.pro.ui.screens.ResultsScreen
import com.cdhunter.pro.ui.screens.ScanProgressScreen
import com.cdhunter.pro.ui.screens.ScanSetupScreen
import com.cdhunter.pro.ui.screens.SettingsScreen
import com.cdhunter.pro.ui.screens.SniScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val SCAN_SETUP = "scan_setup"
    const val SCAN_PROGRESS = "scan_progress"
    const val CDN_SELECT = "cdn_select"
    const val RANGE_MANAGER = "range_manager"
    const val RESULTS = "results?scanId={scanId}"
    const val IP_DETAIL = "ip_detail/{ip}"
    const val SNI = "sni"
    const val CHARTS = "charts"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    const val EXPORT = "export?scanId={scanId}"
    const val SETTINGS = "settings"
    const val NETWORK_SETTINGS = "network_settings"
    const val ADVANCED_SETTINGS = "advanced_settings"
    const val ERROR_LOG = "error_log"
    const val ABOUT = "about"

    fun results(scanId: Long) = "results?scanId=$scanId"
    fun export(scanId: Long) = "export?scanId=$scanId"
    fun ipDetail(ip: String) = "ip_detail/$ip"
}

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        Triple(Routes.DASHBOARD, stringResource(R.string.tab_dashboard), Icons.Default.Home),
        Triple(Routes.SCAN_SETUP, stringResource(R.string.tab_scan), Icons.Default.PlayArrow),
        Triple(Routes.RESULTS, stringResource(R.string.tab_results), Icons.Default.List),
        Triple(Routes.SETTINGS, stringResource(R.string.tab_tools), Icons.Default.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { (route, label, icon) ->
                    val selected = currentRoute == route || currentRoute?.startsWith(route.substringBefore('?')) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(container, navController)
            }
            composable(Routes.SCAN_SETUP) {
                ScanSetupScreen(container, navController)
            }
            composable(Routes.SCAN_PROGRESS) {
                ScanProgressScreen(container, navController)
            }
            composable(Routes.CDN_SELECT) {
                CdnSelectScreen(container, navController)
            }
            composable(Routes.RANGE_MANAGER) {
                RangeManagerScreen(container, navController)
            }
            composable(
                Routes.RESULTS,
                arguments = listOf(navArgument("scanId") { defaultValue = -1L }),
            ) { entry ->
                val scanId = entry.arguments?.getLong("scanId") ?: -1L
                ResultsScreen(container, navController, scanId)
            }
            composable(Routes.IP_DETAIL) { entry ->
                val ip = entry.arguments?.getString("ip") ?: ""
                IpDetailScreen(container, navController, java.net.URLDecoder.decode(ip, "UTF-8"))
            }
            composable(Routes.SNI) {
                SniScreen(container, navController)
            }
            composable(Routes.CHARTS) {
                ChartsScreen(container, navController)
            }
            composable(Routes.HISTORY) {
                HistoryScreen(container, navController)
            }
            composable(Routes.COMPARE) {
                CompareScreen(container, navController)
            }
            composable(
                Routes.EXPORT,
                arguments = listOf(navArgument("scanId") { defaultValue = -1L }),
            ) { entry ->
                val scanId = entry.arguments?.getLong("scanId") ?: -1L
                ExportScreen(container, navController, scanId)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container, navController)
            }
            composable(Routes.NETWORK_SETTINGS) {
                NetworkSettingsScreen(container, navController)
            }
            composable(Routes.ADVANCED_SETTINGS) {
                AdvancedSettingsScreen(container, navController)
            }
            composable(Routes.ERROR_LOG) {
                ErrorLogScreen(container, navController)
            }
            composable(Routes.ABOUT) {
                AboutScreen(container, navController)
            }
        }
    }
}
