package com.fetch.desktop.player.embedded

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/**
 * The parts of libmpv's C API (client.h and render.h, API 2.x) the built-in player uses, bound
 * through JNA. libmpv2 ships with mpv on most distributions.
 */
@Suppress("FunctionName", "LocalVariableName")
interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(ctx: Pointer, args: com.sun.jna.StringArray): Int
    fun mpv_command_async(ctx: Pointer, replyUserdata: Long, args: com.sun.jna.StringArray): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, data: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    /** Called by mpv on its own thread when a new frame can be rendered. */
    fun interface UpdateCallback : Callback {
        fun invoke(data: Pointer?)
    }

    companion object {
        // mpv_format
        const val FORMAT_NONE = 0
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        // mpv_event_id
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        // mpv_end_file_reason
        const val END_FILE_EOF = 0
        const val END_FILE_ERROR = 4

        // mpv_render_param_type
        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20

        // mpv_render_update_flag
        const val RENDER_UPDATE_FRAME = 1L

        /** libmpv, or null when it isn't installed (the player then falls back to mpv's window). */
        val instance: LibMpv? by lazy {
            listOf("mpv", "libmpv.so.2", "libmpv.so.1").firstNotNullOfOrNull { name ->
                runCatching { Native.load(name, LibMpv::class.java) }.getOrNull()
            }
        }

        val available: Boolean get() = instance != null
    }
}

/** `mpv_event`: what happened, and its payload. */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEvent(p: Pointer) : Structure(p) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    init {
        read()
    }
}

/** `mpv_event_property`: a changed property, with its value in [format]. */
@Structure.FieldOrder("name", "format", "data")
class MpvEventProperty(p: Pointer) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    init {
        read()
    }
}

/** `mpv_event_end_file`: why a file stopped. */
@Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
class MpvEventEndFile(p: Pointer) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0

    init {
        read()
    }
}

/** For setlocale: libmpv needs LC_NUMERIC to be "C". */
interface LibC : Library {
    fun setlocale(category: Int, locale: String): String?

    companion object {
        const val LC_NUMERIC = 1
        val instance: LibC by lazy { Native.load("c", LibC::class.java) }
    }
}
