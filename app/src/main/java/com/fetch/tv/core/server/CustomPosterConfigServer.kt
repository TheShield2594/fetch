package com.fetch.tv.core.server

import android.content.Context
import com.fetch.tv.R
import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class CustomPosterConfigServer(
    private val currentPatternProvider: () -> String,
    private val onPatternChanged: (String) -> Unit,
    private val context: Context? = null,
    port: Int = 8092,
    private val pairingGuard: PairingGuard? = null
) : NanoHTTPD(port) {
    /** Address for the QR code, carrying the one-time pairing token. */
    fun pairingUrl(host: String): String =
        pairingGuard?.pairingUrl(host, listeningPort) ?: "http://$host:$listeningPort"

    override fun serve(session: IHTTPSession): Response {
        pairingGuard?.check(session)?.let { return it }
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/pattern" -> serveCurrentPattern()
            session.method == Method.POST && session.uri == "/api/pattern" -> handlePatternUpdate(session)
            session.method == Method.POST && session.uri == "/api/clear" -> handleClear()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            CustomPosterWebPage.html(context)
        )

    private fun serveCurrentPattern(): Response {
        val pattern = currentPatternProvider()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            patternJson(pattern)
        )
    }

    private fun handlePatternUpdate(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val pattern = extractJsonString(body, "pattern")
        if (pattern == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                """{"error":"Missing pattern field"}"""
            )
        }
        onPatternChanged(pattern.trim())
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"saved"}"""
        )
    }

    private fun handleClear(): Response {
        onPatternChanged("")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"cleared"}"""
        )
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    companion object {
        /** The current pattern as JSON; the pattern may hold any character, backslashes included. */
        internal fun patternJson(pattern: String): String =
            buildJsonObject { put("pattern", pattern) }.toString()

        /**
         * The string (or bare number/boolean) at [key] in the JSON object [json], decoded, or null
         * when the body is not a JSON object or has no such value.
         */
        internal fun extractJsonString(json: String, key: String): String? {
            val element = try {
                Json.parseToJsonElement(json)
            } catch (_: IllegalArgumentException) {
                // SerializationException, which malformed JSON throws, is one.
                return null
            }
            val value = (element as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
            return value.contentOrNull
        }

        fun startOnAvailablePort(
            currentPatternProvider: () -> String,
            onPatternChanged: (String) -> Unit,
            context: Context? = null,
            startPort: Int = 8092,
            maxAttempts: Int = 10
        ): CustomPosterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = CustomPosterConfigServer(
                        currentPatternProvider = currentPatternProvider,
                        onPatternChanged = onPatternChanged,
                        context = context,
                        port = port,
                        pairingGuard = PairingGuard()
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
