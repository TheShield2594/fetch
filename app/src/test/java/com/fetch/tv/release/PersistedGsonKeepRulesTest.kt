package com.fetch.tv.release

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The keep rules for classes Gson persists by field name (see proguard-rules.pro). A rule that
 * names a class which was renamed or moved keeps nothing and fails silently in release builds,
 * so every class listed between the markers has to exist.
 */
class PersistedGsonKeepRulesTest {

    private val keptClasses: List<String> by lazy {
        val rules = sequenceOf("proguard-rules.pro", "app/proguard-rules.pro")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("proguard-rules.pro not found from ${File(".").absolutePath}")
        rules.readLines()
            .dropWhile { it.trim() != "# gson-persisted-begin" }
            .drop(1)
            .takeWhile { it.trim() != "# gson-persisted-end" }
            .mapNotNull { KEEP_RULE.find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `every class with a gson keep rule exists`() {
        assertTrue("No keep rules found between the gson-persisted markers", keptClasses.isNotEmpty())
        val missing = keptClasses.filter { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.isFailure
        }
        if (missing.isNotEmpty()) {
            fail("proguard-rules.pro keeps classes that no longer exist:\n" + missing.joinToString("\n"))
        }
    }

    @Test
    fun `the offline sync queue and the Continue Watching cache are kept`() {
        val required = listOf(
            "com.fetch.tv.core.sync.PendingProgressUpsert",
            "com.fetch.tv.core.sync.PendingWatchedUpsert",
            "com.fetch.tv.data.local.CachedNextUpItem",
            "com.fetch.tv.data.local.CachedInProgressItem"
        )
        val absent = required - keptClasses.toSet()
        assertTrue("Missing keep rules for: $absent", absent.isEmpty())
    }

    private companion object {
        val KEEP_RULE = Regex("""^-keep class ([\w.$]+) \{ \*; \}""")
    }
}
