package com.noscroll.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.noscroll.R
import com.noscroll.ui.extension.ExtensionScreen
import com.noscroll.ui.onboarding.OnboardingScreen
import com.noscroll.ui.screens.CalendarScreen
import com.noscroll.ui.screens.InsightsScreen
import com.noscroll.ui.screens.ProtectScreen
import com.noscroll.ui.screens.SettingsScreen
import com.noscroll.ui.screens.TodayScreen
import com.noscroll.ui.theme.NoScrollTheme

class MainActivity : ComponentActivity() {

    private val viewModel: NoScrollViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val openExtension = intent?.getBooleanExtra(EXTRA_OPEN_EXTENSION, false) ?: false

        setContent {
            NoScrollTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    when {
                        state.loading -> Box(Modifier.fillMaxSize())
                        !state.preferences.onboardingComplete ->
                            OnboardingScreen(viewModel, state) { viewModel.completeOnboarding() }
                        else -> NoScrollApp(viewModel, startAtExtension = openExtension)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and package state can change while the app is in the background, so health is
        // re-read rather than cached from launch.
        viewModel.refreshWellbeing()
        viewModel.resumeCountdown()
    }

    override fun onPause() {
        super.onPause()
        // The extension countdown must not run down while the app is not in front of the user.
        viewModel.pauseCountdown()
    }

    companion object {
        const val EXTRA_OPEN_EXTENSION = "com.noscroll.extra.OPEN_EXTENSION"
    }
}

private enum class Destination(val route: String, val labelRes: Int, val iconRes: Int) {
    TODAY("today", R.string.nav_today, R.drawable.ic_nav_today),
    INSIGHTS("insights", R.string.nav_insights, R.drawable.ic_nav_insights),
    PROTECT("protect", R.string.nav_protect, R.drawable.ic_nav_protect),
    CALENDAR("calendar", R.string.nav_calendar, R.drawable.ic_nav_calendar),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_nav_settings),
}

private const val ROUTE_EXTENSION = "extension"

@Composable
private fun NoScrollApp(viewModel: NoScrollViewModel, startAtExtension: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            // The extension flow is a focused task; the tab bar would only invite escape from it.
            if (currentDestination?.route != ROUTE_EXTENSION) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startAtExtension) ROUTE_EXTENSION else Destination.TODAY.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.TODAY.route) {
                TodayScreen(state) { navController.navigate(ROUTE_EXTENSION) }
            }
            composable(Destination.INSIGHTS.route) { InsightsScreen(state) }
            composable(Destination.PROTECT.route) { ProtectScreen(viewModel, state) }
            composable(Destination.CALENDAR.route) { CalendarScreen(state) }
            composable(Destination.SETTINGS.route) { SettingsScreen(viewModel, state) }
            composable(ROUTE_EXTENSION) {
                ExtensionScreen(viewModel, state) { navController.popBackStack() }
            }
        }
    }
}
