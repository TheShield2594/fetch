package com.fetch.tv.data.simkl

import android.content.Context
import android.content.SharedPreferences
import com.fetch.tv.data.local.ProfileDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidSimklAuthStorageTest {
    @Test
    fun `token that cannot be decrypted is kept for the next load`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val preferences = mockk<SharedPreferences>()
        every { preferences.getString("metadata.p1", null) } returns null
        // The Android keystore is not available on the JVM, so decrypting this value fails
        // the same way a transient keystore error does on a device.
        every { preferences.getString("access_token.p1", null) } returns "aXY=.Y2lwaGVy"
        every { preferences.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        val profileDataStore = mockk<ProfileDataStore>()
        every { profileDataStore.activeProfileId } returns MutableStateFlow(1)

        val storage = AndroidSimklAuthStorage(context, profileDataStore)

        assertFalse(storage.state.value.isAuthenticated)
        verify(exactly = 0) { editor.remove("access_token.p1") }
    }
}
