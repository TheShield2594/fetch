package com.fetch.desktop.tracking

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

class TrackingStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `accounts round-trip and the file is owner-only`() {
        val file = File(temp.root, "fetch/tracking.json")
        val accounts = TrackingAccounts(
            trakt = TrackingAccount(TrackingTokens("a", "r", 123L), "me"),
            simkl = TrackingAccount(TrackingTokens("s", null, null), null),
        )
        TrackingStore(file).save(accounts)
        assertEquals(accounts, TrackingStore(file).load())
        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let {
            assertEquals(PosixFilePermissions.fromString("rw-------"), it)
        }
    }

    @Test
    fun `a missing or corrupt file loads as signed out`() {
        val file = File(temp.root, "tracking.json")
        assertEquals(TrackingAccounts(), TrackingStore(file).load())
        file.writeText("{not json")
        assertEquals(TrackingAccounts(), TrackingStore(file).load())
    }

    @Test
    fun `credentials prefer properties, fall back to env, and skip placeholders`() {
        val props = Properties().apply {
            setProperty("traktClientId", "from-props")
            setProperty("traktClientSecret", "\${traktClientSecret}")
            setProperty("simklClientId", " ")
        }
        val env = mapOf("TRAKT_CLIENT_SECRET" to "env-secret", "SIMKL_CLIENT_ID" to "env-simkl")
        val creds = TrackingCredentials.from(props, env::get)
        assertEquals(TrackingCredentials("from-props", "env-secret", "env-simkl", "fetch"), creds)
        assertEquals(true, creds.traktAvailable)
        assertEquals(false, TrackingCredentials.from(Properties()) { null }.simklAvailable)
    }
}
