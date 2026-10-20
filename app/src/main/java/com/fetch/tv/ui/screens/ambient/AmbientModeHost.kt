package com.fetch.tv.ui.screens.ambient

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AmbientModeHost(
    controller: AmbientIdleController,
    allowed: Boolean,
    onShown: () -> Unit,
    viewModel: AmbientModeViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isShowing by controller.isShowing.collectAsStateWithLifecycle()
    val slides by viewModel.slides.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = settings.enabled && allowed && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val timeoutMs = settings.timeoutMinutes * 60_000L

    LaunchedEffect(active, timeoutMs) {
        if (!active) {
            if (controller.isShowing.value) controller.dismiss()
            return@LaunchedEffect
        }
        controller.lastInteractionAt.collectLatest { interactedAt ->
            delay(interactedAt + timeoutMs - SystemClock.uptimeMillis())
            controller.show()
        }
    }

    LaunchedEffect(isShowing) {
        if (isShowing) {
            onShown()
            viewModel.onShown()
        }
    }

    // No keep-screen-on here: ambient mode runs until the system screensaver's own timeout, as
    // other apps do, instead of holding the screen and blocking the screensaver for good.

    AnimatedVisibility(
        visible = isShowing,
        enter = fadeIn(tween(900)),
        exit = fadeOut(tween(400))
    ) {
        AmbientSlideshow(
            slides = slides,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { controller.dismiss() } }
        )
    }
}
