package com.fetch.desktop.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.JsonFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The TV's `app_locale` preferences: [localeTag] is a tag from [Languages.pickerTags], or empty
 * to follow the system. Kept on this computer for everyone, as on TV, where it isn't synced.
 */
@Serializable
data class AppLocaleSettings(
    @SerialName("locale_tag") val localeTag: String = ""
)

fun appLocaleFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "app_locale.json"), AppLocaleSettings.serializer()) { AppLocaleSettings() }

/**
 * The UI language. [chosenTag] is Appearance › Language (null: System default, which follows
 * [Locale.getDefault]); [language] is what's shown. Both are snapshot state, so every [tr] read
 * during composition recomposes when the language changes, without a restart.
 */
object I18n {
    private val store by lazy { appLocaleFile() }
    private val catalog by lazy { StringCatalog.fromClasspath() }

    var chosenTag: String? by mutableStateOf(null)
        private set

    /** The resolved language every [tr] translates into. */
    var language: String by mutableStateOf(Languages.ENGLISH)
        private set

    private val systemTag: String get() = Locale.getDefault().toLanguageTag()

    /** Reads the saved choice and the strings for it in the background. Call once at start-up. */
    fun init() {
        val saved = runCatching { store.load().localeTag }.getOrDefault("").takeIf { it.isNotBlank() }
        apply(saved)
        val tag = language
        thread(name = "fetch-i18n-preload", isDaemon = true) { runCatching { catalog.preload(tag) } }
    }

    /** Appearance › Language: a TV tag, or null for System default. Saved and applied at once. */
    fun choose(tag: String?) {
        val value = tag?.takeIf { it.isNotBlank() }
        runCatching { store.save(AppLocaleSettings(value ?: "")) }
        apply(value)
    }

    private fun apply(tag: String?) {
        chosenTag = tag
        language = Languages.resolve(tag ?: systemTag)
    }

    val isRtl: Boolean get() = Languages.isRtl(language)

    fun translate(text: String): String {
        val tag = language
        return if (tag == Languages.ENGLISH) text else catalog.translate(text, tag)
    }

    fun format(template: String, vararg args: Any?): String = catalog.format(template, language, *args)
}

/**
 * [text], the desktop's English, in the UI language: the TV's translation when it has the same
 * English, else [text]. Not a composable, so it works in click handlers and state code too; read
 * during composition, it recomposes when the language changes.
 */
fun tr(text: String): String = I18n.translate(text)

/** [template] (`%1$s`, `%s`, `%d`) translated, then formatted with [args]. */
fun tr(template: String, vararg args: Any?): String = I18n.format(template, *args)
