package com.fetch.desktop.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Links handed to Fetch (`fetch://…`, `stremio://…`), from the command line or from another
 * launch while Fetch is already open, waiting for the open profile to act on them.
 */
object DeepLinks {
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    /** Called when a link arrives while the window is open, to bring it forward. */
    @Volatile var onArrive: () -> Unit = {}

    fun offer(url: String) {
        val clean = url.trim().takeIf { isLink(it) } ?: return
        _pending.update { it + clean }
        onArrive()
    }

    /** Takes the waiting links; each is handled once. */
    fun take(): List<String> {
        var taken = emptyList<String>()
        _pending.update { taken = it; emptyList() }
        return taken
    }

    fun isLink(text: String): Boolean = text.startsWith("fetch://", ignoreCase = true) || text.startsWith("stremio://", ignoreCase = true)
}

/**
 * One Fetch per user: a second launch (a link clicked in a browser) hands its links to the
 * running one over a Unix socket and exits, so the link opens in the window you already have.
 */
object SingleInstance {
    val socketFile: File by lazy {
        val dir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() && File(it).isDirectory }
            ?: System.getProperty("java.io.tmpdir")
        File(dir, "fetch-${System.getProperty("user.name")}.sock")
    }

    /** Sends [links] to a running Fetch; true when one took them (this launch should then exit). */
    fun forward(links: List<String>, socket: File = socketFile): Boolean {
        if (!socket.exists()) return false
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                // An empty message still tells the running one to come forward.
                val text = (links.joinToString("\n") + "\n")
                channel.write(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)))
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Listens for later launches' links. A socket left by a Fetch that crashed is replaced.
     * Returns false when listening isn't possible (Fetch still works, one window per launch).
     */
    fun listen(onLink: (String) -> Unit, onPing: () -> Unit = {}, socket: File = socketFile): Boolean = runCatching {
        if (socket.exists()) socket.delete()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket.toPath()))
        socket.setReadable(false, false)
        socket.setReadable(true, true)
        socket.setWritable(false, false)
        socket.setWritable(true, true)
        socket.deleteOnExit()
        thread(name = "fetch-links", isDaemon = true) {
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                runCatching {
                    client.use {
                        val lines = Channels.newReader(it, StandardCharsets.UTF_8).readLines().map(String::trim).filter(String::isNotEmpty)
                        if (lines.isEmpty()) onPing() else lines.forEach(onLink)
                    }
                }
            }
        }
        true
    }.getOrDefault(false)
}
