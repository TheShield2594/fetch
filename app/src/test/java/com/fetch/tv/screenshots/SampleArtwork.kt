package com.fetch.tv.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import java.net.URLDecoder

/**
 * Stand-in artwork for the README screenshots. Image URLs look like
 * `sample://poster/The%20Long%20Field?hue=24`; this draws a poster, backdrop, thumbnail or title
 * logo for them instead of going to the network, so no real show's art is used.
 */
internal object SampleArtwork {
    fun poster(title: String, hue: Int) = "sample://poster/${enc(title)}?hue=$hue"
    fun backdrop(title: String, hue: Int) = "sample://backdrop/${enc(title)}?hue=$hue"
    fun thumb(title: String, hue: Int) = "sample://thumb/${enc(title)}?hue=$hue"
    fun logo(title: String) = "sample://logo/${enc(title)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @OptIn(ExperimentalCoilApi::class)
    fun install(context: Context) {
        val loader = ImageLoader.Builder(context)
            .components { add(SampleInterceptor) }
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    private object SampleInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data.toString()
            // Only sample artwork is faked; the app's own icons and logos load as usual.
            val bitmap = draw(url) ?: return chain.proceed()
            return SuccessResult(
                image = bitmap.asImage(),
                // No cross-fade: a capture mid-fade shows the image half transparent.
                request = chain.request.newBuilder().crossfade(false).build(),
                dataSource = DataSource.MEMORY
            )
        }
    }

    private fun draw(url: String): Bitmap? {
        if (!url.startsWith("sample://")) return null
        val rest = url.removePrefix("sample://")
        val kind = rest.substringBefore('/')
        val title = URLDecoder.decode(rest.substringAfter('/').substringBefore('?'), "UTF-8")
        val hue = rest.substringAfter("hue=", "200").toIntOrNull() ?: 200
        return when (kind) {
            "poster" -> scene(400, 600, title, hue, withTitle = true)
            "backdrop" -> scene(1920, 1080, title, hue, withTitle = false)
            "thumb" -> scene(640, 360, title, hue, withTitle = false)
            "logo" -> drawLogo(title)
            else -> null
        }
    }

    private fun color(hue: Int, sat: Float, value: Float) =
        Color.HSVToColor(floatArrayOf(((hue % 360) + 360) % 360f, sat, value))

    /** A dusk landscape: sky gradient, a sun and two hill layers, tinted by [hue]. */
    private fun scene(w: Int, h: Int, title: String, hue: Int, withTitle: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            color(hue + 20, 0.55f, 0.55f), color(hue - 15, 0.65f, 0.22f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val sunX = w * (0.25f + (title.length % 5) * 0.12f)
        val sunR = minOf(w, h) * 0.16f
        p.shader = RadialGradient(sunX, h * 0.42f, sunR * 2.2f, color(hue + 40, 0.35f, 1f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(sunX, h * 0.42f, sunR * 2.2f, p)
        p.shader = null
        p.color = color(hue + 40, 0.25f, 0.98f)
        c.drawCircle(sunX, h * 0.42f, sunR * 0.55f, p)
        fun hill(base: Float, amp: Float, col: Int, phase: Float) {
            val path = android.graphics.Path()
            path.moveTo(0f, h.toFloat())
            var x = 0f
            while (x <= w) {
                val y = h * base - amp * h * kotlin.math.sin((x / w) * 6.2f + phase).toFloat()
                path.lineTo(x, y)
                x += w / 60f
            }
            path.lineTo(w.toFloat(), h.toFloat())
            path.close()
            p.color = col
            c.drawPath(path, p)
        }
        hill(0.68f, 0.05f, color(hue - 30, 0.55f, 0.18f), title.length.toFloat())
        hill(0.80f, 0.04f, color(hue - 40, 0.6f, 0.10f), title.length * 2f)
        if (withTitle) {
            p.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(220, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
            p.shader = null
            p.color = Color.WHITE
            p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            p.textSize = w * 0.11f
            wrap(title, w * 0.84f, p).asReversed().forEachIndexed { i, line ->
                c.drawText(line, w * 0.08f, h * 0.93f - i * p.textSize * 1.08f, p)
            }
        }
        return bmp
    }

    private fun drawLogo(title: String): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 120f
            letterSpacing = -0.02f
        }
        val lines = wrap(title.uppercase(), 900f, p)
        val width = (lines.maxOf { p.measureText(it) } + 20).toInt()
        val height = (lines.size * p.textSize * 1.05f + 30).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, line -> c.drawText(line, 10f, p.textSize * (i + 1), p) }
        return bmp
    }

    private fun wrap(text: String, maxWidth: Float, p: Paint): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}
