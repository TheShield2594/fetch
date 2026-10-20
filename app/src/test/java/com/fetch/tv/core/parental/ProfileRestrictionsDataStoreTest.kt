package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRestrictionsDataStoreTest {
    private val store = ProfileRestrictionsDataStore(TestPreferencesStore())

    @Test
    fun `round trips restrictions per profile`() = runTest {
        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = true)
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        store.setRestrictions(3, teen)

        val all = store.restrictions.first()
        assertEquals(ProfileRestrictions.KidsPreset, all[2])
        assertEquals(teen, all[3])
    }

    @Test
    fun `clearing to none removes the entry`() = runTest {
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        store.removeProfile(2)

        assertTrue(store.restrictions.first().isEmpty())
    }

    @Test
    fun `kids preset is restricted and hides unrated titles`() {
        val preset = ProfileRestrictions.KidsPreset
        assertTrue(preset.isRestricted)
        assertFalse(preset.contentRestriction.allows(null))
        assertTrue(preset.contentRestriction.allows(CertificationLevel.PARENTAL_GUIDANCE))
        assertFalse(preset.contentRestriction.allows(CertificationLevel.TEENS))
        assertFalse(ProfileRestrictions.None.isRestricted)
    }
}
