package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen

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

    FetchTheme {
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        data,
                        containerColor = FetchPalette.SurfaceHigh,
                        contentColor = FetchPalette.TextPrimary
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    // Ctrl+F (or Ctrl+K) opens search from anywhere.
                    event.isCtrlPressed && (event.key == Key.F || event.key == Key.K) -> {
                        state.openSection(Screen.Search)
                        true
                    }
                    // Escape goes back, like Back on the TV remote. Backspace too on a title page,
                    // where there is no text field to type in.
                    (event.key == Key.Escape || (event.key == Key.Backspace && state.screen is Screen.Detail)) &&
                        state.canGoBack -> {
                        state.back()
                        true
                    }
                    else -> false
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                AnimatedContent(
                    targetState = state.screen,
                    // Detail pages share one key, so going from one title to another doesn't fade.
                    contentKey = { if (it is Screen.Detail) "detail" else it.toString() },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.fillMaxSize().padding(start = SidebarWidth)
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(state)
                        Screen.Search -> SearchScreen(state)
                        Screen.Addons -> AddonsScreen(state)
                        is Screen.Detail -> DetailScreen(state, screen.preview)
                    }
                }
                Sidebar(
                    items = listOf(
                        SidebarItem("Home", FetchIcons.Home, state.screen == Screen.Home || state.screen is Screen.Detail) {
                            state.openSection(Screen.Home)
                        },
                        SidebarItem("Search", FetchIcons.Search, state.screen == Screen.Search) {
                            state.openSection(Screen.Search)
                        },
                        SidebarItem("Addons", FetchIcons.Addons, state.screen == Screen.Addons) {
                            state.openSection(Screen.Addons)
                        }
                    ),
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
    }
}
