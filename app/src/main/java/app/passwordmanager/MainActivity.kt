package app.passwordmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.passwordmanager.settings.SettingsScreen
import app.passwordmanager.settings.SettingsViewModel
import app.passwordmanager.translator.HistoryScreen
import app.passwordmanager.translator.TranslatorScreen
import app.passwordmanager.translator.TranslatorViewModel
import app.passwordmanager.ui.theme.PasswordManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The gradient header fills the area behind the status bar, so use light bar icons.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val speechRate by settingsViewModel.speechRate.collectAsState()
            val speechPitch by settingsViewModel.speechPitch.collectAsState()

            PasswordManagerTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val translatorViewModel: TranslatorViewModel = viewModel()

                // Standard bottom-nav switching: single top + state save/restore so tapping tabs
                // doesn't pile destinations on the back stack (which caused the recomposition lag).
                fun switchTab(route: String) {
                    if (currentRoute == route) return
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                label = { Text("Translator") },
                                selected = currentRoute == "translator",
                                onClick = { switchTab("translator") }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = { Text("History") },
                                selected = currentRoute == "history",
                                onClick = { switchTab("history") }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                                selected = currentRoute == "settings",
                                onClick = { switchTab("settings") }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "translator",
                        // Only inset the bottom (nav bar); screens draw their gradient header
                        // behind the status bar at the top.
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                        enterTransition = { fadeIn(tween(250)) },
                        exitTransition = { fadeOut(tween(250)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { fadeOut(tween(250)) }
                    ) {
                        composable("translator") {
                            TranslatorScreen(translatorViewModel, speechRate, speechPitch)
                        }
                        composable("history") {
                            HistoryScreen(translatorViewModel, speechRate, speechPitch)
                        }
                        composable("settings") {
                            SettingsScreen(viewModel = settingsViewModel)
                        }
                    }
                }
            }
        }
    }
}
