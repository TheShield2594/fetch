package com.fetch.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fetch.desktop.deeplink.DeepLinks
import com.fetch.desktop.deeplink.LinkRegistration
import com.fetch.desktop.deeplink.SingleInstance
import com.fetch.desktop.i18n.I18n
import com.fetch.desktop.ui.FetchApp
import com.fetch.desktop.ui.LocalWindowState
import org.jetbrains.skia.Image
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val links = args.filter(DeepLinks::isLink)
    // Fetch is already open: hand it the links (or just bring it forward) and stop here.
    if (SingleInstance.forward(links)) exitProcess(0)
    links.forEach(DeepLinks::offer)
    SingleInstance.listen(onLink = DeepLinks::offer, onPing = { DeepLinks.onArrive() })
    // Claim fetch:// (and stremio:// when free) for this install, in the background.
    thread(name = "fetch-link-registration", isDaemon = true) { LinkRegistration.register() }
    // Appearance › Language (or the system's), read before the first frame.
    I18n.init()

    application {
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
            // A link from another launch brings this window forward.
            DeepLinks.onArrive = {
                SwingUtilities.invokeLater {
                    if (windowState.isMinimized) windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            // Arabic and Hebrew lay the app out right to left, as Android does on TV.
            CompositionLocalProvider(
                LocalWindowState provides windowState,
                LocalLayoutDirection provides if (I18n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) { FetchApp() }
        }
    }
}
