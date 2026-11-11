package com.fetch.tv.data.local.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.fetch.tv.data.local.KeyedEntry
import com.fetch.tv.data.local.RoomLibraryEntryStore
import com.fetch.tv.data.local.RoomWatchProgressEntryStore
import com.fetch.tv.data.local.RoomWatchedItemStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A TV that ran the first database version (watched items only) upgrades to the current one
 * with its watched items intact, using the schema Room exported for version 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FetchDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `version 1 upgrades with its watched items, and the new tables work`() = runBlocking {
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createVersion1(file)

        val database = Room.databaseBuilder(context, FetchDatabase::class.java, file.absolutePath).build()
        try {
            val watched = RoomWatchedItemStore(database).all(1)
            assertEquals(listOf("tt1"), watched.map { it.contentId })
            assertEquals(null, watched.single().season)

            val progress = RoomWatchProgressEntryStore(database)
            progress.upsert(1, listOf(KeyedEntry("progress", "tt1", "tt1", 5L, "{}")))
            assertEquals(1, progress.observeContent(1, "tt1").first().size)

            val library = RoomLibraryEntryStore(database)
            library.upsert(1, listOf(KeyedEntry("item", "movie:tt1", "tt1", 5L, "{}")))
            assertEquals(1, library.all(1).size)
        } finally {
            database.close()
            file.delete()
        }
    }

    private fun createVersion1(file: File) {
        val schema = JSONObject(File(SCHEMA_DIR, "1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO watched_items (profileId, contentId, season, episode, contentType, title, watchedAt) " +
                    "VALUES (1, 'tt1', -1, -1, 'movie', 'Movie', 10)"
            )
            db.version = 1
        }
    }

    private companion object {
        // Unit tests run from the module directory.
        val SCHEMA_DIR = File("schemas/com.fetch.tv.data.local.db.FetchDatabase")
    }
}
