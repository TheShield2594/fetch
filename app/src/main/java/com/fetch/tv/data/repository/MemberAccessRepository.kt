package com.fetch.tv.data.repository

import com.fetch.tv.domain.model.CosmeticEntitlements
import com.fetch.tv.domain.model.MemberAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetch has no paid tiers: every cosmetic (themes, gradients, backgrounds) is unlocked
 * locally and no membership check ever leaves the device.
 */
@Singleton
class MemberAccessRepository @Inject constructor() {
    val access: StateFlow<MemberAccess> = MutableStateFlow(
        MemberAccess(tier = null, entitlements = CosmeticEntitlements.SupporterPreview)
    ).asStateFlow()

    fun refresh() = Unit

    fun refreshIfStale() = Unit
}
