package com.fetch.tv.core.util

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.fetch.tv.LocaleCache
import com.fetch.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The application context's strings follow the in-app language, not the device's (#324). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppLocaleResourcesTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val resolver = AppLocaleResources { configuration -> app.createConfigurationContext(configuration) }

    @Test
    fun `strings come in the app language when it differs from the device's`() {
        val english = app.resources.getString(R.string.error_generic)
        val german = resolver.resolve(app.resources, "de").getString(R.string.error_generic)
        assertEquals("An error occurred", english)
        assertEquals("Ein Fehler ist aufgetreten", german)
    }

    @Test
    fun `no app language keeps the device's resources`() {
        assertSame(app.resources, resolver.resolve(app.resources, ""))
        assertSame(app.resources, resolver.resolve(app.resources, LocaleCache.UNSET))
        assertSame(app.resources, resolver.resolve(app.resources, null))
    }

    @Test
    fun `a language change takes effect on the next lookup`() {
        val german = resolver.resolve(app.resources, "de")
        assertSame(german, resolver.resolve(app.resources, "de"))
        val french = resolver.resolve(app.resources, "fr")
        assertEquals(
            app.createConfigurationContext(
                android.content.res.Configuration(app.resources.configuration).apply {
                    setLocale(java.util.Locale.FRENCH)
                }
            ).resources.getString(R.string.error_generic),
            french.getString(R.string.error_generic)
        )
    }
}
