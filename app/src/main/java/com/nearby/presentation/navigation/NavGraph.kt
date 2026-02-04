package com.nearby.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nearby.R
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.nearby.presentation.screens.chat.ChatScreen
import com.nearby.presentation.screens.connected.ConnectedScreen
import com.nearby.presentation.screens.discover.DiscoverScreen
import com.nearby.presentation.screens.home.HomeScreen
import com.nearby.presentation.screens.onboarding.OnboardingScreen
import com.nearby.presentation.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object Connected : Screen("connected")
    object Discover : Screen("discover")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    object Settings : Screen("settings")
}

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val labelResId: Int
) {
    object Chats : BottomNavItem(Screen.Home.route, Icons.Default.Forum, R.string.nav_chat)
    object NearYou : BottomNavItem(Screen.Connected.route, Icons.Default.WifiTethering, R.string.nav_near_you)
    object Settings : BottomNavItem(Screen.Settings.route, Icons.Default.Settings, R.string.nav_settings)
}

private val bottomNavItems = listOf(
    BottomNavItem.Chats,
    BottomNavItem.NearYou,
    BottomNavItem.Settings
)

private val bottomNavRoutes = bottomNavItems.map { it.route }

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomNav = currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val label = stringResource(item.labelResId)
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(Screen.Home.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onOnboardingComplete = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToDiscover = {
                        navController.navigate(Screen.Discover.route)
                    },
                    onNavigateToChat = { conversationId ->
                        navController.navigate(Screen.Chat.createRoute(conversationId))
                    },
                    onNavigateToSettings = {
                        // Navigate like a bottom nav tap to ensure consistent behavior
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Screen.Connected.route) {
                ConnectedScreen(
                    onNavigateToChat = { conversationId ->
                        navController.navigate(Screen.Chat.createRoute(conversationId))
                    }
                )
            }

            composable(Screen.Discover.route) {
                DiscoverScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToChat = { conversationId ->
                        navController.navigate(Screen.Chat.createRoute(conversationId)) {
                            popUpTo(Screen.Home.route)
                        }
                    }
                )
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                ChatScreen(
                    conversationId = conversationId,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
