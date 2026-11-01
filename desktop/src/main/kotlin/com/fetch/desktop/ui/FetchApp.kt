package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen

private val FetchColors = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF0D0D0D),
    secondary = Color(0xFFFB8C00),
    onSecondary = Color(0xFF0D0D0D),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF111111),
    outline = Color(0xFF4D4D4D),
    error = Color(0xFFFF6B6B)
)

@Composable
fun FetchApp() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    LaunchedEffect(Unit) { state.start() }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            state.message = null
        }
    }

    MaterialTheme(colorScheme = FetchColors) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.onPreviewKeyEvent { event ->
                // Escape and Backspace go back, like Back on the TV remote.
                val isBack = event.key == Key.Escape || (event.key == Key.Backspace && state.screen is Screen.Detail)
                if (event.type == KeyEventType.KeyDown && isBack && state.canGoBack) {
                    state.back()
                    true
                } else {
                    false
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxHeight().width(96.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        NavigationRailItem(
                            selected = state.screen == Screen.Home || state.screen is Screen.Detail,
                            onClick = { state.openSection(Screen.Home) },
                            icon = { Text("⌂", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("Home") }
                        )
                        NavigationRailItem(
                            selected = state.screen == Screen.Addons,
                            onClick = { state.openSection(Screen.Addons) },
                            icon = { Text("⧉", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("Addons") }
                        )
                    }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (val screen = state.screen) {
                        Screen.Home -> HomeScreen(state)
                        Screen.Addons -> AddonsScreen(state)
                        is Screen.Detail -> DetailScreen(state, screen.preview)
                    }
                }
            }
        }
    }
}
