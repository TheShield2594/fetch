package com.fetch.desktop.home

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.MetaPreview

/**
 * The addons to ask for a title's details, in order. With "Prefer meta from external addon" on
 * (the TV default) that's addon order, so a dedicated meta addon answers first; off, the addon
 * whose catalog listed the title goes first, as the TV's MetaDetailsViewModel does.
 */
fun metaAddonOrder(addons: List<Addon>, preview: MetaPreview, preferExternal: Boolean): List<Addon> {
    if (preferExternal) return addons
    val source = preview.sourceAddonBaseUrl?.trimEnd('/')?.lowercase() ?: return addons
    val (own, others) = addons.partition { it.baseUrl.trimEnd('/').lowercase() == source }
    return own + others
}
