package com.fetch.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.SavedWindow
import com.fetch.desktop.data.savedWindowFile
import com.fetch.desktop.deeplink.DeepLinks
import com.fetch.desktop.deeplink.LinkRegistration
import com.fetch.desktop.deeplink.SingleInstance
import com.fetch.desktop.i18n.I18n
import com.fetch.desktop.ui.FetchApp
import com.fetch.desktop.ui.LocalWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * What runs before Fetch exits: the open profile's [AppState.shutdown] (playback's last save,
 * scrobble stop and sync), set while a profile is open.
 */
object AppShutdown {
    /** The longest closing waits for that work before exiting anyway. */
    const val TIMEOUT_MS = 4_000L

    @Volatile var onClose: (suspend () -> Unit)? = null
}

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
        val windowFile = remember { savedWindowFile() }
        val windowState = remember { initialWindowState(windowFile.load()) }
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("fetch-icon.png")?.use { stream ->
                BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
            }
        }
        val closeScope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        Window(
            // The window goes at once; playback's last save, scrobble and sync get a few seconds.
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    // Should the UI thread itself hang (a stuck libmpv call), exit anyway.
                    thread(name = "fetch-exit-watchdog", isDaemon = true) {
                        Thread.sleep(AppShutdown.TIMEOUT_MS + 2_000)
                        exitProcess(0)
                    }
                    closeScope.launch {
                        withTimeoutOrNull(AppShutdown.TIMEOUT_MS) { runCatching { AppShutdown.onClose?.invoke() } }
                        exitApplication()
                    }
                }
            },
            visible = !closing,
            title = "Fetch",
            icon = icon,
            state = windowState
        ) {
            remember { window.minimumSize = Dimension(SavedWindow.MIN_WIDTH.toInt(), SavedWindow.MIN_HEIGHT.toInt()) }
            RememberWindowPlacement(windowState, windowFile)
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

/** The window as it was last closed: size clamped to the screen, position if still on one. */
private fun initialWindowState(saved: SavedWindow): WindowState {
    val (screenWidth, screenHeight) = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.let { it.width.toFloat() to it.height.toFloat() }
    }.getOrNull() ?: (null to null)
    val screens = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            device.defaultConfiguration.bounds.let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.width.toFloat(), it.height.toFloat()) }
        }
    }.getOrDefault(emptyList())
    val size = saved.sizeFor(screenWidth, screenHeight)
    val position = saved.positionOn(screens, size)
    return WindowState(
        placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = position?.let { (x, y) -> WindowPosition(x.dp, y.dp) } ?: WindowPosition.PlatformDefault,
        size = DpSize(size.first.dp, size.second.dp)
    )
}

/**
 * Saves the window's size, place and maximised state as they change (half a second after the
 * last change). The floating size and place are kept while maximised; fullscreen isn't saved.
 */
@Composable
private fun RememberWindowPlacement(state: WindowState, file: JsonFile<SavedWindow>) {
    LaunchedEffect(state) {
        var last = file.load()
        snapshotFlow { Triple(state.placement, state.size, state.position) }.collectLatest { (placement, size, position) ->
            val next = when (placement) {
                WindowPlacement.Fullscreen -> return@collectLatest
                WindowPlacement.Maximized -> last.copy(maximized = true)
                WindowPlacement.Floating -> last.copy(
                    width = size.width.value.takeIf { it.isFinite() } ?: last.width,
                    height = size.height.value.takeIf { it.isFinite() } ?: last.height,
                    x = position.takeIf { it.isSpecified }?.x?.value ?: last.x,
                    y = position.takeIf { it.isSpecified }?.y?.value ?: last.y,
                    maximized = false
                )
            }
            if (next == last) return@collectLatest
            delay(500)
            withContext(Dispatchers.IO) { runCatching { file.save(next) } }
            last = next
        }
    }
}
