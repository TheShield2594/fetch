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

    private fun layout(vararg order: String) = buildJsonObject {
        put("home_catalog_order_keys", JsonPrimitive(order.joinToString(",", "[", "]") { "\"$it\"" }))
    }

    @Test
    fun `a backed-up Home layout replaces one that names only catalogs this profile has`() {
        val here = layout("addon.a_movie_top", "addon.b_series_new", "collection_x")
        assertTrue(TvBackup.homeLayoutFits(layout("addon.b_series_new", "addon.a_movie_top"), here, includesAddons = false))
        // Collections aren't addons; one the TV lacks doesn't keep the layout out.
        assertTrue(TvBackup.homeLayoutFits(layout("addon.a_movie_top", "collection_y"), here, includesAddons = false))
    }

    @Test
    fun `a backed-up Home layout naming catalogs this TV lacks keeps the TV's own`() {
        val here = layout("addon.a_movie_top")
        assertFalse(TvBackup.homeLayoutFits(layout("addon.a_movie_top", "addon.z_movie_x"), here, includesAddons = false))
        // Unless the backup brings the addons along, or the profile has no layout to keep.
        assertTrue(TvBackup.homeLayoutFits(layout("addon.z_movie_x"), here, includesAddons = true))
        assertTrue(TvBackup.homeLayoutFits(layout("addon.z_movie_x"), current = null, includesAddons = false))
    }
}
