package com.fetch.desktop.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the screen on while the built-in player plays (#755). It draws into the app's own window
 * (`vo=libmpv`), so mpv has no window of its own through which to inhibit, as mpv's window does.
 *
 * Asks the session bus's `org.freedesktop.ScreenSaver.Inhibit` (GNOME, KDE, Xfce, Cinnamon, …)
 * over libdbus, holding the connection, since the desktop drops an inhibition when its client
 * disconnects. Without libdbus or that service, `systemd-inhibit --what=idle` holds one with logind.
 *
 * [hold] and [release] return at once; the D-Bus calls run on a thread of their own, in order.
 */
object IdleInhibitor {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "idle-inhibitor").apply { isDaemon = true } }

    // Written only on the worker thread.
    @Volatile private var held: Held? = null

    init {
        // A systemd-inhibit child would outlive the app and keep the screen on.
        Runtime.getRuntime().addShutdownHook(Thread {
            (held as? SystemdInhibit)?.let { runCatching { it.release() } }
            // One still being started (not yet in held) is stopped too.
            SystemdInhibit.starting?.let { runCatching { it.destroy() } }
        })
    }

    private sealed interface Held {
        fun release()
    }

    private val isLinux: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    /** Keeps the display from blanking or locking until [release]; a second call while held does nothing. */
    fun hold(reason: String = "Playing a video") {
        if (!isLinux) return
        worker.execute {
            if (held == null) {
                held = runCatching { ScreenSaverBus.inhibit(reason) }.getOrNull() ?: SystemdInhibit.start(reason)
                SystemdInhibit.starting = null
            }
        }
    }

    /** Lets the display blank again. */
    fun release() {
        if (!isLinux) return
        worker.execute {
            held?.let { runCatching { it.release() } }
            held = null
        }
    }

    /** `org.freedesktop.ScreenSaver.Inhibit` on a private session-bus connection kept open while held. */
    private class ScreenSaverBus(private val connection: Pointer, private val cookie: Int) : Held {
        override fun release() {
            runCatching { call(connection, "UnInhibit", uint = cookie) }
            dbus.dbus_connection_close(connection)
            dbus.dbus_connection_unref(connection)
        }

        companion object {
            fun inhibit(reason: String): Held? {
                val lib = dbusOrNull ?: return null
                val busError = newError()
                val connection = lib.dbus_bus_get_private(DBUS_BUS_SESSION, busError) ?: run {
                    lib.dbus_error_free(busError)
                    return null
                }
                // libdbus's default calls _exit() when the bus goes away: never for this app.
                lib.dbus_connection_set_exit_on_disconnect(connection, 0)
                val cookie = runCatching { call(connection, "Inhibit", strings = listOf("Fetch", reason)) }.getOrNull()
                if (cookie == null) {
                    lib.dbus_connection_close(connection)
                    lib.dbus_connection_unref(connection)
                    return null
                }
                return ScreenSaverBus(connection, cookie)
            }

            /** A method call on the screensaver; the uint32 it answers (Inhibit's cookie), or null. */
            private fun call(connection: Pointer, method: String, strings: List<String> = emptyList(), uint: Int? = null): Int? {
                val lib = dbus
                val message = lib.dbus_message_new_method_call(
                    "org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver", method
                ) ?: return null
                try {
                    val args = mutableListOf<Any>()
                    // The char** below holds only the text's address: keep each text buffer itself
                    // reachable until append_args has copied it, or GC could free it first.
                    val texts = mutableListOf<Memory>()
                    // Each argument is passed by address: a char** for a string, a dbus_uint32_t* for a uint32.
                    strings.forEach { value ->
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        val text = Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
                        texts += text
                        val ref = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, text) }
                        args += DBUS_TYPE_STRING
                        args += ref
                    }
                    uint?.let { value ->
                        args += DBUS_TYPE_UINT32
                        args += Memory(4).apply { setInt(0, value) }
                    }
                    if (args.isNotEmpty()) {
                        val first = args.removeAt(0) as Int
                        val appended = lib.dbus_message_append_args(message, first, *args.toTypedArray(), DBUS_TYPE_INVALID)
                        java.lang.ref.Reference.reachabilityFence(texts)
                        java.lang.ref.Reference.reachabilityFence(args)
                        if (appended == 0) return null
                    }
                    val callError = newError()
                    val reply = lib.dbus_connection_send_with_reply_and_block(connection, message, CALL_TIMEOUT_MS, callError)
                    if (reply == null) {
                        lib.dbus_error_free(callError)
                        error("D-Bus $method failed")
                    }
                    try {
                        if (method != "Inhibit") return 0
                        val cookie = IntByReference()
                        val readError = newError()
                        if (lib.dbus_message_get_args(reply, readError, DBUS_TYPE_UINT32, cookie, DBUS_TYPE_INVALID) == 0) {
                            lib.dbus_error_free(readError)
                            error("D-Bus Inhibit sent no cookie")
                        }
                        return cookie.value
                    } finally {
                        lib.dbus_message_unref(reply)
                    }
                } finally {
                    lib.dbus_message_unref(message)
                }
            }

            /** A zeroed DBusError, larger than the struct (two pointers, a uint and a pointer). */
            private fun newError(): Memory = Memory(64).apply { clear() }.also { dbus.dbus_error_init(it) }
        }
    }

    /** `systemd-inhibit --what=idle … sleep infinity`, for as long as that process lives. */
    private class SystemdInhibit(private val process: Process) : Held {
        override fun release() {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        companion object {
            /** The process between its start and [held], for the shutdown hook. */
            @Volatile var starting: Process? = null

            fun start(reason: String): Held? = runCatching {
                val process = ProcessBuilder(
                    "systemd-inhibit", "--what=idle", "--who=Fetch", "--why=$reason", "--mode=block",
                    "sleep", "infinity"
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                starting = process
                // One that can't inhibit (no logind) exits at once.
                if (process.waitFor(300, TimeUnit.MILLISECONDS)) null else SystemdInhibit(process)
            }.getOrNull()
        }
    }

    private interface LibDBus : Library {
        fun dbus_threads_init_default(): Int
        fun dbus_error_init(error: Pointer)
        fun dbus_error_free(error: Pointer)
        fun dbus_bus_get_private(type: Int, error: Pointer): Pointer?
        fun dbus_connection_set_exit_on_disconnect(connection: Pointer, exitOnDisconnect: Int)
        fun dbus_connection_close(connection: Pointer)
        fun dbus_connection_unref(connection: Pointer)
        fun dbus_message_new_method_call(destination: String, path: String, iface: String, method: String): Pointer?
        fun dbus_message_append_args(message: Pointer, firstArgType: Int, vararg args: Any): Int
        fun dbus_connection_send_with_reply_and_block(connection: Pointer, message: Pointer, timeoutMs: Int, error: Pointer): Pointer?
        fun dbus_message_get_args(message: Pointer, error: Pointer, firstArgType: Int, vararg args: Any): Int
        fun dbus_message_unref(message: Pointer)
    }

    private const val DBUS_BUS_SESSION = 0
    private const val DBUS_TYPE_INVALID = 0
    private const val DBUS_TYPE_STRING = 's'.code
    private const val DBUS_TYPE_UINT32 = 'u'.code
    private const val CALL_TIMEOUT_MS = 2_000

    private val dbusOrNull: LibDBus? by lazy {
        listOf("dbus-1", "libdbus-1.so.3").firstNotNullOfOrNull { name ->
            runCatching { Native.load(name, LibDBus::class.java) }.getOrNull()
        }?.also { it.dbus_threads_init_default() }
    }
    private val dbus: LibDBus get() = dbusOrNull!!
}
