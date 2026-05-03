package hr.fer.studentzkp.holder.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import hr.fer.studentzkp.holder.data.local.CredentialStore
import hr.fer.studentzkp.holder.domain.CredentialRepository
import hr.fer.studentzkp.holder.ui.detail.CredentialDetailScreen
import hr.fer.studentzkp.holder.ui.scan.ScanScreen
import hr.fer.studentzkp.holder.ui.settings.SettingsScreen
import hr.fer.studentzkp.holder.ui.wallet.WalletScreen

private const val NAV_ANIM_DURATION = 300

@Composable
fun AppNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val repo = remember(context) { CredentialRepository(CredentialStore(context)) }

    NavHost(
        navController = navController,
        startDestination = Screen.Wallet.route,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(NAV_ANIM_DURATION),
            ) + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(NAV_ANIM_DURATION),
            ) + fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(NAV_ANIM_DURATION),
            ) + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(NAV_ANIM_DURATION),
            ) + fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
        },
    ) {
        composable(Screen.Wallet.route) {
            WalletScreen(
                repository = repo,
                onOpenDetail = { credentialId ->
                    navController.navigate(Screen.CredentialDetail.createRoute(credentialId))
                },
            )
        }
        composable(
            route = Screen.CredentialDetail.route,
            arguments = listOf(navArgument("credentialId") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("credentialId") ?: return@composable
            CredentialDetailScreen(
                credentialId = id,
                repository = repo,
                onBack = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Screen.Wallet.route, inclusive = false)
                },
            )
        }
        composable(Screen.Scan.route) {
            ScanScreen(repository = repo)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                repository = repo,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items = listOf(
        Triple(Screen.Wallet, "Wallet", Icons.Default.AccountBalanceWallet),
        Triple(Screen.Scan, "Scan", Icons.Default.QrCodeScanner),
        Triple(Screen.Settings, "Settings", Icons.Default.Settings),
    )

    NavigationBar {
        items.forEach { (screen, label, icon) ->
            NavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = {
                    if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}
