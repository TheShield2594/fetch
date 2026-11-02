package com.fetch.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.CompositionLocalProvider
import com.fetch.desktop.ui.FetchApp
import com.fetch.desktop.ui.LocalWindowState
import org.jetbrains.skia.Image

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    val icon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("fetch-icon.png")?.use { stream ->
            BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Fetch",
        icon = icon,
        state = windowState
    ) {
        CompositionLocalProvider(LocalWindowState provides windowState) { FetchApp() }
    }
}
