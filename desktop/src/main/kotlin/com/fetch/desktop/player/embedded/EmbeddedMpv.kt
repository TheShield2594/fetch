package com.fetch.desktop.player.embedded

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.player.MpvProgress
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** An audio or subtitle track of the playing file. */
data class MpvTrack(
    val id: String,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val external: Boolean,
    val selected: Boolean,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
    /** Audio: "5.1(side)" and the like. */
    val channels: String? = null,
    /** An added file's path or URL (mpv's external-filename), so an addon subtitle can be found again. */
    val externalFilename: String? = null,
    /** The track's number in its file (mpv's src-id: a Matroska TrackNumber). */
    val srcId: Int? = null
) {
    val label: String
        get() = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            lang?.uppercase(),
            codec?.uppercase(),
            channels,
            "External".takeIf { external }
        ).distinct().joinToString(" · ").ifEmpty { "Track $id" }
}

/**
 * The built-in player: libmpv rendering into Skia bitmaps that Compose draws, so Fetch's own
 * controls sit on top of the picture. mpv's software renderer draws subtitles into the frame.
 *
 * Two threads: one reads mpv's events into Compose state, one renders frames when mpv says a new
 * one is ready. Frames go into one of three bitmaps in turn, so the one on screen is never
 * written to.
 */
class EmbeddedMpv private constructor(
    private val mpv: LibMpv,
    private val ctx: Pointer,
    private val preferGpu: Boolean,
    private val allowSoftwareGl: Boolean
) : AutoCloseable {

    /** True when frames are drawn on the GPU (OpenGL); false for mpv's software renderer. */
    var usingGpu = false
        private set
    private var gl: GlRenderer? = null

    // --- State for the controls (written on the UI thread) ---
    var position by mutableStateOf(0.0)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var paused by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var volume by mutableStateOf(100.0)
        private set
    var muted by mutableStateOf(false)
        private set
    var tracks by mutableStateOf<List<MpvTrack>>(emptyList())
        private set
    /** Playback speed, 1.0 normal. */
    var speed by mutableStateOf(1.0)
        private set
    /** Seconds; positive delays the sound. */
    var audioDelay by mutableStateOf(0.0)
        private set
    /** Seconds; positive shows subtitles later. */
    var subtitleDelay by mutableStateOf(0.0)
        private set
    /** Bumped for every new frame, so the picture redraws. */
    var frameVersion by mutableStateOf(0L)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var renderCtx: Pointer? = null
    private val frameReady = Semaphore(0)
    @Volatile private var running = true
    private val updateCallback = LibMpv.UpdateCallback { frameReady.release() }

    // Frames: rendered into `back`, shown from `front`, the third one waits.
    private val frameLock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var spare: Bitmap? = null
    /** The view's size in pixels, set as one value so the render thread never sees half a change. */
    @Volatile private var viewSize = 1280 to 720
    /** Render at the view's size even in software, so crop and stretch can fill it. */
    @Volatile private var fillView = false
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    // The file being followed: position listener and how it ended.
    @Volatile private var onPosition: ((Double) -> Unit)? = null
    @Volatile private var ending: CompletableDeferred<Boolean>? = null
    /** Set by [load] until mpv starts the new file, so the old file's end isn't taken for it. */
    @Volatile private var awaitingStart = false
    private var pendingSubtitles: List<String> = emptyList()

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var eventThread: Thread
    private lateinit var renderThread: Thread

    private fun start() {

        observe("time-pos", LibMpv.FORMAT_DOUBLE)
        observe("duration", LibMpv.FORMAT_DOUBLE)
        observe("pause", LibMpv.FORMAT_FLAG)
        observe("paused-for-cache", LibMpv.FORMAT_FLAG)
        observe("volume", LibMpv.FORMAT_DOUBLE)
        observe("mute", LibMpv.FORMAT_FLAG)
        observe("track-list", LibMpv.FORMAT_NONE)
        observe("speed", LibMpv.FORMAT_DOUBLE)
        observe("audio-delay", LibMpv.FORMAT_DOUBLE)
        observe("sub-delay", LibMpv.FORMAT_DOUBLE)
        observe("dwidth", LibMpv.FORMAT_INT64)
        observe("dheight", LibMpv.FORMAT_INT64)

        eventThread = thread(name = "mpv-events", isDaemon = true) { eventLoop() }
        // The renderer is made on its own thread: an OpenGL context belongs to the thread that
        // made it current.
        val ready = java.util.concurrent.CompletableFuture<Unit>()
        renderThread = thread(name = "mpv-render", isDaemon = true) {
            try {
                createRenderContext()
                ready.complete(Unit)
            } catch (e: Throwable) {
                ready.completeExceptionally(e)
                return@thread
            }
            try {
                renderLoop()
            } catch (e: Throwable) {
                if (!System.getenv("FETCH_MPV_DEBUG").isNullOrBlank()) e.printStackTrace()
                ui { error = "The player stopped drawing (${e.message})" }
            } finally {
                renderCtx?.let { mpv.mpv_render_context_free(it) }
                renderCtx = null
                gl?.close()
                gl = null
            }
        }
        try {
            ready.get(15, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** OpenGL when it's available and wanted, else the software renderer. */
    private fun createRenderContext() {
        if (preferGpu) {
            val renderer = GlRenderer.create(allowSoftware = allowSoftwareGl)
            if (renderer != null) {
                val params = renderParams(renderer.createParams())
                val res = PointerByReference()
                val created = mpv.mpv_render_context_create(res, ctx, params)
                java.lang.ref.Reference.reachabilityFence(params)
                if (created >= 0) {
                    gl = renderer
                    usingGpu = true
                    renderCtx = res.value
                    mpv.mpv_render_context_set_update_callback(res.value, updateCallback, null)
                    return
                }
                renderer.close()
            }
        }
        val apiType = stringPointer("sw")
        val params = renderParams(listOf(LibMpv.RENDER_PARAM_API_TYPE to apiType))
        val res = PointerByReference()
        val rc = mpv.mpv_render_context_create(res, ctx, params)
        java.lang.ref.Reference.reachabilityFence(apiType)
        java.lang.ref.Reference.reachabilityFence(params)
        check(rc >= 0) { "libmpv couldn't start its renderer: ${mpv.mpv_error_string(rc)}" }
        renderCtx = res.value
        mpv.mpv_render_context_set_update_callback(res.value, updateCallback, null)
    }

    private fun observe(name: String, format: Int) {
        mpv.mpv_observe_property(ctx, 0, name, format)
    }

    // --- Playback ---

    /**
     * Plays [url]. [options] are the same mpv options the external player gets
     * ("--alang=en,eng", "--sub-file=…"); [start] is mpv's --start ("754" or "42%").
     */
    fun load(url: String, title: String, start: String?, options: List<String>, headers: Map<String, String>) {
        ending?.complete(false)
        ending = CompletableDeferred()
        awaitingStart = true
        error = null
        buffering = true
        val subtitles = mutableListOf<String>()
        options.forEach { option ->
            val body = option.removePrefix("--")
            val name = body.substringBefore('=')
            val value = if ('=' in body) body.substringAfter('=') else "yes"
            when (name) {
                "fs" -> Unit // the window, not mpv, goes fullscreen
                "sub-file" -> subtitles += value
                // Decoded frames go straight to the GPU only with an X11 display for VA-API;
                // otherwise (and for the software renderer) they come back to memory.
                "hwdec" -> set(
                    "hwdec",
                    when {
                        value == "no" -> "no"
                        gl?.x11Display != null -> value
                        else -> "auto-copy-safe"
                    }
                )
                else -> set(name, value)
            }
        }
        pendingSubtitles = subtitles
        set("force-media-title", title)
        set("start", start ?: "none")
        command("change-list", "http-header-fields", "clr", "")
        headers.forEach { (name, value) -> command("change-list", "http-header-fields", "append", "$name: $value") }
        command("loadfile", url, "replace")
    }

    fun togglePause() = set("pause", if (paused) "no" else "yes")
    fun pause(value: Boolean) = set("pause", if (value) "yes" else "no")
    /** A frame-exact seek, for keeping a watch party in step. */
    fun seekExact(seconds: Double) = command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute+exact")
    fun seekTo(seconds: Double) = command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")
    fun seekBy(seconds: Double) = command("seek", seconds.toString(), "relative")
    fun changeVolume(value: Double) = set("volume", value.coerceIn(0.0, 130.0).toString())
    fun toggleMute() = set("mute", if (muted) "no" else "yes")
    fun selectAudio(id: String) = set("aid", id)
    /** A subtitle track id, or null for off. */
    fun selectSubtitle(id: String?) = set("sid", id ?: "no")
    fun showText(text: String) = command("show-text", text, "2000")
    fun changeSpeed(value: Double) = set("speed", value.coerceIn(0.25, 4.0).toString())
    fun changeAudioDelay(seconds: Double) = set("audio-delay", "%.3f".format(java.util.Locale.ROOT, seconds))
    fun changeSubtitleDelay(seconds: Double) = set("sub-delay", "%.3f".format(java.util.Locale.ROOT, seconds))

    /** Adds a subtitle file (an addon's) and shows it. */
    fun addSubtitle(url: String, title: String?, lang: String?) {
        command("sub-add", url, "select", title ?: lang ?: "Addon", lang ?: "")
    }

    /** How the picture fills the view: properties from [com.fetch.desktop.player.MpvArgs.fitProperties]. */
    fun setFit(properties: List<Pair<String, String>>, fill: Boolean) {
        properties.forEach { (k, v) -> set(k, v) }
        fillView = fill
        frameReady.release()
    }

    /** Sets an mpv property while playing (the subtitle style panel, audio extras). */
    fun setProperty(name: String, value: String) = set(name, value)

    /** Runs an mpv command ("af", "add", …). */
    fun runCommand(vararg args: String) = command(*args)

    /** A property as text, for the stream info overlay; null when mpv has none. */
    fun property(name: String): String? = getString(name)?.takeIf { it.isNotBlank() }
    fun stop() = command("stop")

    /** Where the picture should fit, in pixels; frames are rendered no bigger than this. */
    fun setViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && viewSize != (width to height)) {
            viewSize = width to height
            frameReady.release()
        }
    }

    /** The newest frame, or null before the first one. */
    fun frame(): Bitmap? = synchronized(frameLock) { front }

    /**
     * Follows the current file like [MpvProgress.followToEnd] follows an mpv window: [onPosition]
     * can ask for a seek (Skip Segments), [onSample] gets the position every [intervalMs].
     */
    suspend fun follow(
        intervalMs: Long = 5_000,
        onPosition: (Double) -> MpvProgress.Seek? = { null },
        onSample: (MpvProgress.Sample) -> Unit
    ): MpvProgress.Ending {
        val done = ending ?: return MpvProgress.Ending(null, false)
        var lastEmit = 0L
        var last: MpvProgress.Sample? = null
        this.onPosition = { pos ->
            onPosition(pos)?.let { seek ->
                seekTo(seek.toSec)
                showText(seek.message)
            }
            val d = currentDuration
            if (d > 0) {
                val sample = MpvProgress.Sample(pos, d)
                last = sample
                val now = System.currentTimeMillis()
                if (now - lastEmit >= intervalMs) {
                    lastEmit = now
                    onSample(sample)
                }
            }
        }
        val reachedEnd = try {
            done.await()
        } finally {
            this.onPosition = null
        }
        return MpvProgress.Ending(last?.also(onSample), reachedEnd)
    }

    @Volatile private var currentDuration = 0.0

    // --- Threads ---

    private fun eventLoop() {
        while (running) {
            val pointer = mpv.mpv_wait_event(ctx, 0.5)
            val event = MpvEvent(pointer)
            when (event.event_id) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> {
                    running = false
                    ending?.complete(false)
                }
                LibMpv.EVENT_START_FILE -> awaitingStart = false
                LibMpv.EVENT_FILE_LOADED -> {
                    pendingSubtitles.forEach { command("sub-add", it, "auto") }
                    pendingSubtitles = emptyList()
                }
                LibMpv.EVENT_PLAYBACK_RESTART -> ui { buffering = false }
                LibMpv.EVENT_END_FILE -> {
                    if (awaitingStart) continue
                    val end = event.data?.let { MpvEventEndFile(it) }
                    if (end?.reason == LibMpv.END_FILE_ERROR) {
                        val message = mpv.mpv_error_string(end.error)
                        ui { error = "Couldn't play this source ($message)" }
                    }
                    ending?.complete(end?.reason == LibMpv.END_FILE_EOF)
                }
                LibMpv.EVENT_PROPERTY_CHANGE -> event.data?.let { onProperty(MpvEventProperty(it)) }
            }
        }
    }

    private fun onProperty(p: MpvEventProperty) {
        val data = p.data
        when (p.name) {
            "time-pos" -> data?.getDouble(0)?.let { pos ->
                onPosition?.invoke(pos)
                ui { position = pos }
            }
            "duration" -> data?.getDouble(0)?.let { d ->
                currentDuration = d
                ui { duration = d }
            }
            "pause" -> data?.getInt(0)?.let { v -> ui { paused = v != 0 } }
            "paused-for-cache" -> data?.getInt(0)?.let { v -> ui { buffering = v != 0 } }
            "volume" -> data?.getDouble(0)?.let { v -> ui { volume = v } }
            "mute" -> data?.getInt(0)?.let { v -> ui { muted = v != 0 } }
            "dwidth" -> data?.getLong(0)?.let { videoWidth = it.toInt(); frameReady.release() }
            "dheight" -> data?.getLong(0)?.let { videoHeight = it.toInt(); frameReady.release() }
            "speed" -> data?.getDouble(0)?.let { v -> ui { speed = v } }
            "audio-delay" -> data?.getDouble(0)?.let { v -> ui { audioDelay = v } }
            "sub-delay" -> data?.getDouble(0)?.let { v -> ui { subtitleDelay = v } }
            "track-list" -> {
                val list = parseTracks(getString("track-list"))
                ui { tracks = list }
            }
        }
    }

    private fun renderLoop() {
        while (running) {
            if (!frameReady.tryAcquire(250, TimeUnit.MILLISECONDS)) continue
            frameReady.drainPermits()
            val render = renderCtx ?: continue
            val flags = mpv.mpv_render_context_update(render)
            // Software: only new frames. GPU: every update mpv signals, which also covers the first
            // frame of a paused file and redraws after a seek while paused.
            if (gl == null && flags and LibMpv.RENDER_UPDATE_FRAME == 0L && videoWidth > 0) continue
            val (w, h) = frameSize() ?: continue
            val target = synchronized(frameLock) {
                back?.takeIf { it.width == w && it.height == h } ?: newBitmap(w, h).also { back = it }
            }
            val pixels = target.peekPixels() ?: continue
            val glRenderer = gl
            if (glRenderer != null) {
                if (!glRenderer.render(mpv, render, w, h, Pointer(pixels.addr), target.rowBytes)) continue
                publish(target)
                continue
            }
            val stride = Memory(Native.SIZE_T_SIZE.toLong()).apply {
                if (Native.SIZE_T_SIZE == 8) setLong(0, target.rowBytes.toLong()) else setInt(0, target.rowBytes)
            }
            val size = Memory(8).apply { setInt(0, w); setInt(4, h) }
            val format = stringPointer("bgr0")
            val params = renderParams(
                listOf(
                    LibMpv.RENDER_PARAM_SW_SIZE to size,
                    LibMpv.RENDER_PARAM_SW_FORMAT to format,
                    LibMpv.RENDER_PARAM_SW_STRIDE to stride,
                    LibMpv.RENDER_PARAM_SW_POINTER to Pointer(pixels.addr)
                )
            )
            val rendered = mpv.mpv_render_context_render(render, params)
            // The parameter buffers are only referenced by address; keep them alive until here.
            java.lang.ref.Reference.reachabilityFence(size)
            java.lang.ref.Reference.reachabilityFence(stride)
            java.lang.ref.Reference.reachabilityFence(format)
            java.lang.ref.Reference.reachabilityFence(params)
            if (rendered < 0) continue
            publish(target)
        }
    }

    /** Puts [target] on screen and makes the oldest frame the next one to draw into. */
    private fun publish(target: Bitmap) {
        target.notifyPixelsChanged()
        synchronized(frameLock) {
            back = spare
            spare = front
            front = target
        }
        ui { frameVersion++ }
    }

    /**
     * The size to render at. On the GPU, the whole view: mpv scales and letterboxes the picture
     * itself, and needs a render as soon as it reconfigures. In software, the picture's size
     * scaled to fit the view, never larger than the video.
     */
    private fun frameSize(): Pair<Int, Int>? {
        val (viewWidth, viewHeight) = viewSize
        if (gl != null || fillView) return (viewWidth.coerceAtLeast(2) and 1.inv()) to (viewHeight.coerceAtLeast(2) and 1.inv())
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0) return null
        val scale = minOf(1.0, viewWidth.toDouble() / vw, viewHeight.toDouble() / vh)
        return ((vw * scale).toInt().coerceAtLeast(2) and 1.inv()) to ((vh * scale).toInt().coerceAtLeast(2) and 1.inv())
    }

    private fun newBitmap(w: Int, h: Int) = Bitmap().apply {
        allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
    }

    // --- Helpers ---

    private fun set(name: String, value: String) {
        mpv.mpv_set_property_string(ctx, name, value)
    }

    private fun command(vararg args: String) {
        mpv.mpv_command(ctx, StringArray(args))
    }

    private fun getString(name: String): String? {
        val p = mpv.mpv_get_property_string(ctx, name) ?: return null
        return try {
            p.getString(0, "UTF-8")
        } finally {
            mpv.mpv_free(p)
        }
    }

    private fun parseTracks(raw: String?): List<MpvTrack> = runCatching {
        json.parseToJsonElement(raw ?: return emptyList()).jsonArray.mapNotNull { element ->
            val o: JsonObject = element.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            val type = str("type") ?: return@mapNotNull null
            if (type != "audio" && type != "sub") return@mapNotNull null
            MpvTrack(
                id = o["id"]?.jsonPrimitive?.intOrNull?.toString() ?: return@mapNotNull null,
                type = type,
                title = str("title"),
                lang = str("lang"),
                codec = str("codec"),
                external = o["external"]?.jsonPrimitive?.booleanOrNull == true,
                selected = o["selected"]?.jsonPrimitive?.booleanOrNull == true,
                forced = o["forced"]?.jsonPrimitive?.booleanOrNull == true,
                isDefault = o["default"]?.jsonPrimitive?.booleanOrNull == true,
                channels = str("demux-channels") ?: o["demux-channel-count"]?.jsonPrimitive?.intOrNull?.let { "${it}ch" },
                externalFilename = str("external-filename"),
                srcId = o["src-id"]?.jsonPrimitive?.intOrNull
            )
        }
    }.getOrDefault(emptyList())

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    override fun close() {
        if (!running && renderCtx == null) return
        running = false
        ending?.complete(false)
        mpv.mpv_wakeup(ctx)
        runCatching { eventThread.join(1_000) }
        frameReady.release()
        // The render thread frees its renderer as it ends; libmpv goes after.
        runCatching { renderThread.join(2_000) }
        mpv.mpv_terminate_destroy(ctx)
        synchronized(frameLock) {
            listOfNotNull(front, back, spare).distinct().forEach { it.close() }
            front = null
            back = null
            spare = null
        }
    }

    companion object {
        /**
         * [create] on the IO pool, for a coroutine that may be cancelled while it runs. Starting
         * libmpv can't be interrupted, so a player that finishes starting after the caller was
         * cancelled is closed (off this thread, as closing waits for mpv's threads) rather than
         * dropped with its threads and GL context still running; the cancellation is then rethrown.
         * Failures to start are thrown as from [create].
         */
        suspend fun createOffThread(preferGpu: Boolean = true): EmbeddedMpv {
            // Held outside withContext: switching back to the caller's dispatcher is itself
            // cancellable, and would drop the result even though the block ran to the end.
            var created: EmbeddedMpv? = null
            try {
                withContext(NonCancellable + Dispatchers.IO) { created = create(preferGpu = preferGpu) }
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                created?.let { player -> thread(name = "embedded-mpv-close", isDaemon = true) { runCatching { player.close() } } }
                throw e
            }
            return created!!
        }

        /**
         * Starts libmpv with Fetch's defaults: its own controls and keys off, staying open between
         * files. Throws when libmpv is missing or won't start.
         *
         * [preferGpu] draws with OpenGL when a graphics card is available; [allowSoftwareGl] also
         * accepts a CPU-only OpenGL driver, for tests on machines without a GPU.
         */
        fun create(preferGpu: Boolean = true, allowSoftwareGl: Boolean = false): EmbeddedMpv {
            val lib = LibMpv.instance ?: error("libmpv isn't installed (on Debian and Ubuntu: sudo apt install libmpv2)")
            // libmpv refuses to start unless numbers are formatted the C way.
            runCatching { LibC.instance.setlocale(LibC.LC_NUMERIC, "C") }
            val ctx = lib.mpv_create() ?: error("libmpv couldn't start")
            listOf(
                "vo" to "libmpv",
                "idle" to "yes",
                "keep-open" to "no",
                "osc" to "no",
                "input-default-bindings" to "no",
                "input-vo-keyboard" to "no",
                "terminal" to "no",
                "hwdec" to "auto-copy-safe",
                "osd-level" to "1"
            ).forEach { (k, v) -> lib.mpv_set_option_string(ctx, k, v) }
            // FETCH_MPV_DEBUG=1 prints mpv's log, for reporting playback problems.
            if (!System.getenv("FETCH_MPV_DEBUG").isNullOrBlank()) {
                lib.mpv_set_option_string(ctx, "terminal", "yes")
                lib.mpv_set_option_string(ctx, "msg-level", "all=v")
            }
            val rc = lib.mpv_initialize(ctx)
            if (rc < 0) {
                lib.mpv_terminate_destroy(ctx)
                error("libmpv couldn't start: ${lib.mpv_error_string(rc)}")
            }
            val player = EmbeddedMpv(lib, ctx, preferGpu, allowSoftwareGl)
            try {
                player.start()
            } catch (e: Throwable) {
                player.running = false
                lib.mpv_wakeup(ctx)
                runCatching { player.eventThread.join(1_000) }
                lib.mpv_terminate_destroy(ctx)
                throw e
            }
            return player
        }

        private fun stringPointer(value: String): Pointer = Memory(value.length + 1L).apply { setString(0, value) }

        /** An `mpv_render_param` array ({int type; void *data}), ended by an empty one. */
        private fun renderParams(params: List<Pair<Int, Pointer>>): Pointer {
            val entry = 2L * Native.POINTER_SIZE
            val memory = Memory(entry * (params.size + 1))
            memory.clear()
            params.forEachIndexed { i, (type, data) ->
                memory.setInt(i * entry, type)
                memory.setPointer(i * entry + Native.POINTER_SIZE, data)
            }
            return memory
        }
    }
}
