package com.fetch.tv

import com.fetch.tv.core.hidden.TitleVisibility
import com.fetch.tv.core.hidden.TitleVisibilityFilter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** A [TitleVisibilityFilter] that hides nothing: no "Not interested" titles, "Hide watched" off. */
fun unfilteredTitleVisibility(visibility: TitleVisibility = TitleVisibility.None): TitleVisibilityFilter = mockk {
    every { this@mockk.visibility } returns MutableStateFlow(visibility)
}
