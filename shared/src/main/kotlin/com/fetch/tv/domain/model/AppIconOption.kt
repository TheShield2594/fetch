package com.fetch.tv.domain.model

/**
 * The launcher icon and TV banner styles, one per built-in theme family: Original (Fetch),
 * Arctic Blue (Ocean), Emerald, Copper (Amber) and Graphite (White).
 *
 * [retired] styles are no longer offered (#455) but keep their launcher entry in the manifest:
 * an installed app whose enabled launcher activity disappeared would vanish from the launcher,
 * so whoever already had one keeps it until they pick another style.
 */
enum class AppIconOption(val key: String, val retired: Boolean = false) {
    ORIGINAL("original"),
    ARCTIC_BLUE("arctic_blue"),
    EMERALD("emerald"),
    ROSE_GOLD("rose_gold", retired = true),
    COPPER("copper"),
    GRAPHITE("graphite");

    companion object {
        /** The styles the picker lists. */
        val offered: List<AppIconOption> = entries.filterNot { it.retired }
    }
}
