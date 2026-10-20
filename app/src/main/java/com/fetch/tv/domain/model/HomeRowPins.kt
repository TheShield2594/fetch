package com.fetch.tv.domain.model

/**
 * Moves the [pinned] keys that are in [order] to the front, in pin order, and keeps every other
 * key where it was. Pins outlive addon reorders and reinstalls: a pinned key that is not
 * available right now is skipped, not dropped, and comes back on top once it is.
 */
fun pinnedFirst(order: List<String>, pinned: List<String>): List<String> {
    if (pinned.isEmpty()) return order
    val available = order.toSet()
    val head = pinned.filter { it in available }.distinct()
    if (head.isEmpty()) return order
    val headSet = head.toSet()
    return head + order.filterNot { it in headSet }
}
