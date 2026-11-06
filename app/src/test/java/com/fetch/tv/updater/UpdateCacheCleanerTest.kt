package com.fetch.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCacheCleanerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `removes every old APK`() {
        val dir = temp.newFolder(UpdateCacheCleaner.DIRECTORY)
        val old = dir.resolve("fetch-v1.4.0-arm64-v8a.apk").apply { writeText("a") }
        val older = dir.resolve("fetch-v1.3.0-arm64-v8a.apk").apply { writeText("b") }

        assertEquals(2, UpdateCacheCleaner.clean(dir))
        assertFalse(old.exists())
        assertFalse(older.exists())
    }

    @Test
    fun `keeps the current download`() {
        val dir = temp.newFolder(UpdateCacheCleaner.DIRECTORY)
        val old = dir.resolve("fetch-v1.4.0-arm64-v8a.apk").apply { writeText("a") }
        val current = dir.resolve("fetch-v1.5.0-arm64-v8a.apk").apply { writeText("b") }

        assertEquals(1, UpdateCacheCleaner.clean(dir, keep = current))
        assertFalse(old.exists())
        assertTrue(current.exists())
    }

    @Test
    fun `missing directory is a no-op`() {
        assertEquals(0, UpdateCacheCleaner.clean(temp.root.resolve("absent")))
    }
}
