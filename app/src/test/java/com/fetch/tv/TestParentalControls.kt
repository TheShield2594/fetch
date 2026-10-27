package com.fetch.tv

import com.fetch.tv.core.parental.ContentRestriction
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.parental.ParentalFilterRevision
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.domain.model.CatalogRow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** ParentalControls for an unrestricted profile: everything passes through. */
fun unrestrictedParentalControls(): ParentalControls = mockk(relaxed = true) {
    every { activeRestrictions } returns MutableStateFlow(ProfileRestrictions.None)
    every { allRestrictions } returns MutableStateFlow(emptyMap())
    every { filterRevision } returns MutableStateFlow(
        ParentalFilterRevision(ContentRestriction.Unrestricted, ProfileTitleLists.Empty, 0L)
    )
    every { filtersContent() } returns false
    every { filterRow(any()) } answers { firstArg<CatalogRow>() }
    every { filterAllowed(any<List<Any>>(), any()) } answers { firstArg() }
    every { isAllowed(any<com.fetch.tv.domain.model.MetaPreview>()) } returns true
    every { guardPinProfileId() } returns null
    every { needsGate() } returns false
}
