package com.fetch.tv.core.server

import com.fetch.tv.core.addon.normalizeAddonInstallUrl
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD

/** Localized text for the phone page; resolved by the caller so this server needs no Context. */
data class AddonConfigureWebText(
    val title: String,
    val step1: String,
    val openConfigure: String,
    val step2: String,
    val placeholder: String,
    val send: String,
    val sent: String,
    val invalid: String,
    val failed: String
)

/**
 * Phone side of "Configure addon from TV": a page that links to the addon's own `/configure`
 * page and takes back the configured manifest link ("Send to TV"). [onUrlReceived] gets the
 * normalized http(s) base URL; the TV installs it and shows the result itself.
 */
class AddonConfigureServer(
    private val addonName: String,
    private val configureUrl: String,
    private val text: AddonConfigureWebText,
    private val onUrlReceived: (String) -> Unit,
    port: Int = 8100,
    private val pairingGuard: PairingGuard? = null
) : NanoHTTPD(port) {
    private val gson = Gson()

    fun pairingUrl(host: String): String =
        pairingGuard?.pairingUrl(host, listeningPort) ?: "http://$host:$listeningPort"

    override fun serve(session: IHTTPSession): Response {
        pairingGuard?.check(session)?.let { return it }
        return when {
            session.method == Method.GET && session.uri == "/" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
            session.method == Method.POST && session.uri == "/api/configured" -> receive(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun receive(session: IHTTPSession): Response {
        val url = try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val parsed = gson.fromJson<Map<String, Any>>(
                bodyMap["postData"].orEmpty(),
                object : TypeToken<Map<String, Any>>() {}.type
            )
            (parsed?.get("url") as? String)?.let(::normalizeAddonInstallUrl)
        } catch (e: Exception) {
            null
        } ?: return json(Response.Status.BAD_REQUEST, mapOf("error" to text.invalid))
        onUrlReceived(url)
        return json(Response.Status.OK, mapOf("status" to "received"))
    }

    private fun json(status: Response.Status, body: Map<String, String>): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body))

    internal fun page(): String = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${html(text.title)}</title>
<style>
body{margin:0;padding:24px 16px;font-family:system-ui,sans-serif;background:#111;color:#eee;max-width:560px;margin:auto}
h1{font-size:22px;margin:0 0 20px}p{color:#bbb;line-height:1.45}
a.btn,button{display:block;width:100%;box-sizing:border-box;padding:14px;border:0;border-radius:10px;
background:#e8e8e8;color:#111;font-size:16px;font-weight:600;text-align:center;text-decoration:none;margin:8px 0 24px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #444;background:#1c1c1c;color:#eee;font-size:15px}
#status{min-height:1.4em}
</style></head><body>
<h1>${html(addonName)}</h1>
<p>${html(text.step1)}</p>
<a class="btn" href="${html(configureUrl)}" target="_blank" rel="noopener">${html(text.openConfigure)}</a>
<p>${html(text.step2)}</p>
<input id="url" type="url" autocomplete="off" placeholder="${html(text.placeholder)}">
<button id="send">${html(text.send)}</button>
<p id="status"></p>
<script>
const sent=${js(text.sent)},failed=${js(text.failed)},invalid=${js(text.invalid)};
document.getElementById('send').onclick=async()=>{
  const status=document.getElementById('status');
  const url=document.getElementById('url').value.trim();
  if(!/^(https?|stremio):\/\//i.test(url)){status.textContent=invalid;return;}
  try{
    const r=await fetch('/api/configured',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url})});
    status.textContent=r.ok?sent:((await r.json()).error||failed);
  }catch(e){status.textContent=failed;}
};
</script>
</body></html>
""".trimIndent()

    private fun html(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun js(value: String): String = gson.toJson(value).replace("</", "<\\/")

    companion object {
        fun startOnAvailablePort(
            addonName: String,
            configureUrl: String,
            text: AddonConfigureWebText,
            onUrlReceived: (String) -> Unit,
            startPort: Int = 8100,
            maxAttempts: Int = 10
        ): AddonConfigureServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AddonConfigureServer(
                        addonName = addonName,
                        configureUrl = configureUrl,
                        text = text,
                        onUrlReceived = onUrlReceived,
                        port = port,
                        pairingGuard = PairingGuard()
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
