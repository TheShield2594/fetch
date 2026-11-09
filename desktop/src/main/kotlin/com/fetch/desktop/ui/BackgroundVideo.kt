package com.fetch.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fetch.desktop.player.embedded.EmbeddedMpv
import com.fetch.desktop.player.embedded.LibMpv
import kotlinx.coroutines.delay

/** A YouTube trailer's watch page, which mpv opens through yt-dlp. */
fun youTubeWatchUrl(ytId: String) = "https://www.youtube.com/watch?v=$ytId"

/**
 * A video playing behind a page, looping and fading in once it has a picture: a trailer on Home's
 * hero (Layout › Focused Poster › Autoplay Trailer) or a collection folder's hero video. It plays
 * in libmpv, like the built-in player; without libmpv it shows nothing and the backdrop stays.
 * [crop] fills the frame ("Crop trailers to fill"), else the whole picture is letterboxed.
 */
@Composable
fun BackgroundVideo(
    url: String,
    muted: Boolean,
    crop: Boolean,
    modifier: Modifier = Modifier,
    startDelayMs: Long = 0,
    gpu: Boolean = true,
    onEnded: () -> Unit = {},
    /** Drawn over the picture and faded in with it (a page's scrim). */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    if (LibMpv.instance == null) return
    var player by remember(url) { mutableStateOf<EmbeddedMpv?>(null) }
    LaunchedEffect(url) {
        if (startDelayMs > 0) delay(startDelayMs)
        // Cancelled while libmpv starts (hover moved on), the new player is closed, not leaked.
        val created = try {
            EmbeddedMpv.createOffThread(preferGpu = gpu)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@LaunchedEffect
        }
        created.load(
            url,
            title = "",
            start = null,
            options = listOf(
                "--mute=${if (muted) "yes" else "no"}",
                "--loop-file=inf",
                "--ytdl-format=bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
                "--sub-auto=no",
                "--sid=no"
            ),
            headers = emptyMap()
        )
        player = created
    }
    DisposableEffect(url) {
        onDispose {
            val p = player
            player = null
            // Closing waits for mpv's threads; keep that off the UI thread.
            if (p != null) Thread({ runCatching { p.close() } }, "background-video-close").start()
        }
    }
    val mpv = player ?: return
    LaunchedEffect(mpv, muted) { mpv.pauseMute(muted) }
    if (mpv.error != null) {
        LaunchedEffect(mpv.error) { onEnded() }
        return
    }
    val shown = mpv.frame() != null && !mpv.buffering
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(600))
    androidx.compose.foundation.layout.Box(modifier.alpha(alpha)) {
    Canvas(Modifier.matchParentSize().onSizeChanged { mpv.setViewSize(it.width, it.height) }) {
        @Suppress("UNUSED_EXPRESSION") mpv.frameVersion // redraw on every new frame
        val frame = mpv.frame() ?: return@Canvas
        val scale = if (crop) maxOf(size.width / frame.width, size.height / frame.height)
        else minOf(size.width / frame.width, size.height / frame.height)
        val w = (frame.width * scale).toInt()
        val h = (frame.height * scale).toInt()
        drawImage(
            frame.asComposeImageBitmap(),
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Medium
        )
    }
    overlay()
    }
}

private fun EmbeddedMpv.pauseMute(muted: Boolean) {
    if (this.muted != muted) toggleMute()
}
