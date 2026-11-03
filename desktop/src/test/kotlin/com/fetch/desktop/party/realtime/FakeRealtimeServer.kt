package com.fetch.desktop.party.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

/** A stand-in for the Realtime server: records what the client sends and lets a test answer. */
class FakeRealtimeServer : RealtimeTransport {
    inner class Connection(val url: String, val listener: RealtimeTransport.Listener) : RealtimeTransport.Connection {
        val sent: MutableList<PhoenixMessage> = Collections.synchronizedList(mutableListOf())
        @Volatile var closed = false

        override fun send(text: String): Boolean {
            if (closed) return false
            sent += RealtimeProtocol.decode(text)!!
            return true
        }

        override fun close(code: Int, reason: String?) {
            closed = true
        }

        fun open() = listener.onOpen()
        fun drop() {
            closed = true
            listener.onClosed(RuntimeException("dropped"))
        }

        fun receive(message: PhoenixMessage) = listener.onMessage(RealtimeProtocol.encode(message))

        fun sentEvents(event: String) = synchronized(sent) { sent.filter { it.event == event } }

        /** Answers the last join with [status]. */
        fun replyToJoin(topic: String, status: String = "ok") {
            val join = sentEvents(PhoenixEvents.JOIN).last { it.topic == topic }
            receive(PhoenixMessage(topic, PhoenixEvents.REPLY, buildJsonObject {
                put("status", status)
                put("response", JsonObject(emptyMap()))
            }, join.ref))
        }
    }

    val connections: MutableList<Connection> = Collections.synchronizedList(mutableListOf())
    val last: Connection get() = connections.last()

    override fun open(url: String, listener: RealtimeTransport.Listener): RealtimeTransport.Connection =
        Connection(url, listener).also { connections += it }
}

/** Polls [condition] for up to [timeoutMs]. */
fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting")
        Thread.sleep(5)
    }
}
