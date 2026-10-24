package com.fetch.tv.ui.screens.profile

import com.fetch.tv.data.local.BundledAvatars
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.profileInitial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCustomizationTest {
    private fun profile(id: Int, color: String) = UserProfile(id = id, name = "P$id", avatarColorHex = color)

    @Test
    fun `new profiles get the first colour nobody uses`() {
        val profiles = listOf(profile(1, PROFILE_AVATAR_COLORS[0]), profile(2, PROFILE_AVATAR_COLORS[1].lowercase()))

        assertEquals(PROFILE_AVATAR_COLORS[2], nextProfileColor(profiles))
    }

    @Test
    fun `colours rotate once every one is taken`() {
        val profiles = PROFILE_AVATAR_COLORS.mapIndexed { index, color -> profile(index + 1, color) }

        assertEquals(PROFILE_AVATAR_COLORS[0], nextProfileColor(profiles))
    }

    @Test
    fun `initials keep emoji whole and ignore leading spaces`() {
        assertEquals("A", profileInitial("alex"))
        assertEquals("É", profileInitial("  émile"))
        assertEquals("🦊", profileInitial("🦊 fox"))
        assertEquals("?", profileInitial("   "))
    }

    @Test
    fun `bundled avatar ids resolve to their packaged image`() {
        assertEquals("file:///android_asset/avatars/fox.webp", BundledAvatars.imageUrl("fetch:fox"))
        assertNull(BundledAvatars.imageUrl("fox"))
        assertNull(BundledAvatars.imageUrl("fetch:not-an-avatar"))
    }
}
