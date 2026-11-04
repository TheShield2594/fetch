package com.fetch.tv.core.profile

import com.fetch.tv.domain.model.UserProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBackupTest {
    private val main = UserProfile(id = 1, name = "Profile 1", avatarColorHex = "#1E88E5")
    private val sam = UserProfile(id = 2, name = "Sam ", avatarColorHex = "#1E88E5")
    private val kids = UserProfile(id = 3, name = "Kids", avatarColorHex = "#1E88E5")
    private val profiles = listOf(main, sam, kids)

    @Test
    fun `the backed-up main profile goes to this TV's main profile whatever it is called`() {
        assertEquals(main, TvBackup.matchProfile("Alex", isPrimary = true, profiles = profiles))
    }

    @Test
    fun `other profiles match by name, ignoring case and outer spaces`() {
        assertEquals(sam, TvBackup.matchProfile(" sam", isPrimary = false, profiles = profiles))
        assertEquals(kids, TvBackup.matchProfile("KIDS", isPrimary = false, profiles = profiles))
        assertNull(TvBackup.matchProfile("Guest", isPrimary = false, profiles = profiles))
        assertNull(TvBackup.matchProfile("  ", isPrimary = false, profiles = profiles))
    }

    @Test
    fun `a main profile already taken falls back to its name`() {
        val withoutMain = profiles - main
        assertEquals(sam, TvBackup.matchProfile("Sam", isPrimary = true, profiles = withoutMain))
        assertNull(TvBackup.matchProfile("Alex", isPrimary = true, profiles = withoutMain))
    }

    @Test
    fun `only a fetch-backup document is a backup`() {
        assertTrue(TvBackup.isBackup(buildJsonObject { put("format", JsonPrimitive(TvBackup.FORMAT)) }))
        assertFalse(TvBackup.isBackup(buildJsonObject { put("format", JsonPrimitive(ProfileTransfer.FORMAT)) }))
        assertFalse(TvBackup.isBackup(buildJsonObject { }))
    }
}
