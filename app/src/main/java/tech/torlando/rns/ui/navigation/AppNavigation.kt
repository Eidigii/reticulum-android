package tech.torlando.rns.ui.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.torlando.rns.data.InterfaceConfig
import tech.torlando.rns.rnode.data.RNodeWizardResult
import tech.torlando.rns.rnode.ui.RNodeWizardScreen
import tech.torlando.rns.rnode.viewmodel.RNodeWizardViewModel
import tech.torlando.rns.tcpclient.data.TcpClientWizardResult
import tech.torlando.rns.tcpclient.ui.TcpClientWizardScreen
import tech.torlando.rns.tcpclient.viewmodel.TcpClientWizardViewModel
import tech.torlando.rns.ui.screens.ConversationScreen
import tech.torlando.rns.ui.screens.DiscoveredInterfacesScreen
import tech.torlando.rns.ui.screens.HomeScreen
import tech.torlando.rns.ui.screens.IdentityScreen
import tech.torlando.rns.ui.screens.InterfaceStatsScreen
import tech.torlando.rns.ui.screens.InterfacesScreen
import tech.torlando.rns.ui.screens.MessagesScreen
import tech.torlando.rns.ui.screens.MonitorScreen
import tech.torlando.rns.ui.screens.NodesScreen
import tech.torlando.rns.ui.screens.PropagationScreen
import tech.torlando.rns.ui.screens.SettingsScreen
import tech.torlando.rns.viewmodel.TransportViewModel

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    Messages("messages", "Messages", Icons.AutoMirrored.Filled.Chat),
    Nodes("nodes", "Nodes", Icons.Default.Hub),
    Interfaces("interfaces", "Interfaces", Icons.AutoMirrored.Filled.List),
    Settings("settings", "Settings", Icons.Default.Settings),
}

private fun conversationRoute(hash: String) = "conversation/$hash"

@Composable
fun AppNavigation(
    viewModel: TransportViewModel = viewModel(),
    openConversation: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val messengerStatus by viewModel.messenger.status.collectAsState()
    val pendingConversation by openConversation.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.messenger.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(pendingConversation) {
        pendingConversation?.let {
            openConversation.value = null
            navController.navigate(conversationRoute(it)) { launchSingleTop = true }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in Screen.entries.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination

                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                if (screen == Screen.Messages && messengerStatus.unread > 0) {
                                    BadgedBox(badge = { Badge { Text("${messengerStatus.unread}") } }) {
                                        Icon(screen.icon, contentDescription = screen.label)
                                    }
                                } else {
                                    Icon(screen.icon, contentDescription = screen.label)
                                }
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenIdentity = { navController.navigate("identity") },
                    onOpenMonitor = { navController.navigate("monitor") },
                )
            }
            composable(Screen.Messages.route) {
                MessagesScreen(
                    viewModel = viewModel,
                    onOpenConversation = { navController.navigate(conversationRoute(it)) },
                    onOpenPropagation = { navController.navigate("propagation") },
                )
            }
            composable("propagation") {
                PropagationScreen(viewModel, onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Nodes.route) {
                NodesScreen(
                    viewModel = viewModel,
                    onOpenConversation = { navController.navigate(conversationRoute(it)) },
                    onOpenIdentity = { navController.navigate("identity") },
                )
            }
            composable("identity") {
                IdentityScreen(viewModel, onNavigateBack = { navController.popBackStack() })
            }
            composable("conversation/{hash}") { backStackEntry ->
                ConversationScreen(
                    viewModel = viewModel,
                    peerHash = backStackEntry.arguments?.getString("hash") ?: "",
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Screen.Interfaces.route) {
                InterfacesScreen(
                    viewModel = viewModel,
                    onNavigateToDiscovery = { navController.navigate("discovered_interfaces") },
                    onNavigateToRnodeWizard = { editIndex ->
                        navController.navigate(if (editIndex != null) "rnode_wizard?edit=$editIndex" else "rnode_wizard")
                    },
                    onNavigateToTcpClientWizard = { navController.navigate("tcp_client_wizard") },
                    onNavigateToInterfaceStats = { name ->
                        navController.navigate("interface_stats/${java.net.URLEncoder.encode(name, "UTF-8")}")
                    },
                )
            }
            composable("monitor") { MonitorScreen(viewModel, onNavigateBack = { navController.popBackStack() }) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            composable("discovered_interfaces") {
                DiscoveredInterfacesScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable("interface_stats/{name}") { backStackEntry ->
                val name = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("name") ?: "",
                    "UTF-8",
                )
                InterfaceStatsScreen(
                    interfaceName = name,
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable("tcp_client_wizard") {
                val wizardViewModel: TcpClientWizardViewModel = viewModel()
                TcpClientWizardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onComplete = {
                        wizardViewModel.state.value.savedResult?.let { result ->
                            viewModel.addInterface(result.toInterfaceConfig())
                        }
                        navController.popBackStack()
                    },
                    viewModel = wizardViewModel,
                )
            }
            composable(
                "rnode_wizard?edit={edit}",
                arguments = listOf(navArgument("edit") { type = NavType.IntType; defaultValue = -1 }),
            ) { backStackEntry ->
                val wizardViewModel: RNodeWizardViewModel = viewModel()
                val editIndex = backStackEntry.arguments?.getInt("edit")?.takeIf { it >= 0 }
                val existing = editIndex?.let { viewModel.interfaces.value.getOrNull(it) as? InterfaceConfig.RNodeInterface }
                LaunchedEffect(existing) {
                    existing?.let { wizardViewModel.startEditing(it.toWizardResult()) }
                }
                RNodeWizardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onComplete = {
                        wizardViewModel.state.value.savedResult?.let { result ->
                            if (editIndex != null && existing != null) {
                                viewModel.updateInterface(editIndex, result.toInterfaceConfig().copy(enabled = existing.enabled))
                            } else {
                                viewModel.addInterface(result.toInterfaceConfig())
                            }
                        }
                        navController.popBackStack()
                    },
                    viewModel = wizardViewModel,
                )
            }
        }
    }
}

private fun TcpClientWizardResult.toInterfaceConfig() =
    InterfaceConfig.TcpClient(
        name = name,
        enabled = true,
        targetHost = targetHost,
        targetPort = targetPort,
        bootstrapOnly = bootstrapOnly,
        socksProxyEnabled = socksProxyEnabled,
        socksProxyHost = socksProxyHost,
        socksProxyPort = socksProxyPort,
        networkName = networkName,
        passphrase = passphrase,
        ifacSize = ifacSize,
    )

private fun RNodeWizardResult.toInterfaceConfig() =
    InterfaceConfig.RNodeInterface(
        name = name,
        enabled = true,
        interfaceMode = interfaceMode,
        connectionMode = connectionMode,
        targetDevice = targetDevice,
        frequency = frequency,
        bandwidth = bandwidth,
        spreadingFactor = spreadingFactor,
        codingRate = codingRate,
        txPower = txPower,
        networkName = networkName,
        passphrase = passphrase,
        ifacSize = ifacSize,
        stAlock = stAlock,
        ltAlock = ltAlock,
        customRadio = customRadio,
        enableFramebuffer = enableFramebuffer,
    )

private fun InterfaceConfig.RNodeInterface.toWizardResult() =
    RNodeWizardResult(
        name = name,
        connectionMode = connectionMode,
        targetDevice = targetDevice,
        frequency = frequency,
        bandwidth = bandwidth,
        spreadingFactor = spreadingFactor,
        codingRate = codingRate,
        txPower = txPower,
        interfaceMode = interfaceMode,
        stAlock = stAlock,
        ltAlock = ltAlock,
        networkName = networkName,
        passphrase = passphrase,
        ifacSize = ifacSize,
        customRadio = customRadio,
        enableFramebuffer = enableFramebuffer,
    )
