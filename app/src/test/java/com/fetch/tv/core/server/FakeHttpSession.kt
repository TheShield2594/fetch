package com.fetch.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal class FakeHttpSession(
    private val uri: String,
    private val method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
    body: String = "",
    private val remoteIp: String = "192.168.1.20",
    private val cookie: String? = null,
    private val parameters: Map<String, List<String>> = emptyMap()
) : NanoHTTPD.IHTTPSession {
    private val bytes = body.toByteArray(StandardCharsets.UTF_8)

    override fun execute() = Unit
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = buildMap {
        put("content-length", bytes.size.toString())
        put("content-type", "application/json; charset=utf-8")
        put("remote-addr", remoteIp)
        cookie?.let { put("cookie", it) }
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getMethod(): NanoHTTPD.Method = method
    override fun getParms(): Map<String, String> = parameters.mapValues { it.value.first() }
    override fun getParameters(): Map<String, List<String>> = parameters
    override fun getQueryParameterString(): String? = null
    override fun getUri(): String = uri
    override fun parseBody(files: MutableMap<String, String>) {
        files["postData"] = String(bytes, StandardCharsets.UTF_8)
    }
    override fun getRemoteIpAddress(): String = remoteIp
    override fun getRemoteHostName(): String = remoteIp
}
