package com.fetch.tv.core.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.fetch.tv.LocaleCache
import java.util.Locale

/**
 * Resolves this context against the app's own language setting.
 *
 * The injected `@ApplicationContext` (FetchApplication) already returns resources in the app
 * language (#324); this is for any other context that keeps the system configuration. Returns
 * the receiver unchanged when no app language is set.
 */
fun Context.withAppLocale(): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return this
    val locale = Locale.forLanguageTag(tag)
    if (locale.toLanguageTag().isEmpty()) return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    return createConfigurationContext(configuration)
}

/**
 * Resources in the app's own language, derived from a base set and cached until the language
 * or the base configuration changes. FetchApplication hands these out from getResources(), so
 * the injected `@ApplicationContext` follows the in-app language (#324).
 *
 * [createConfigurationContext] builds a context for a configuration; it must not call back into
 * the getResources() this serves.
 */
class AppLocaleResources(
    private val createConfigurationContext: (Configuration) -> Context,
) {
    private class Entry(val tag: String, val baseConfiguration: Configuration, val resources: Resources)

    @Volatile
    private var cached: Entry? = null

    /**
     * [base] in the language [tag] names; [base] itself when [tag] is unset, empty or invalid.
     * Unsynchronized on purpose: two concurrent misses build equivalent Resources and the
     * later one wins, which costs one extra context creation and nothing else.
     */
    fun resolve(base: Resources, tag: String?): Resources {
        val languageTag = tag?.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() } ?: return base
        val baseConfiguration = base.configuration
        cached?.let { entry ->
            if (entry.tag == languageTag && entry.baseConfiguration == baseConfiguration) return entry.resources
        }
        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isEmpty()) return base
        val configuration = Configuration(baseConfiguration).apply { setLocale(locale) }
        val resources = createConfigurationContext(configuration).resources
        cached = Entry(languageTag, Configuration(baseConfiguration), resources)
        return resources
    }
}
