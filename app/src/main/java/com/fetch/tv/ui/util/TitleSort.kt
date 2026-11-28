package com.fetch.tv.ui.util

import java.text.Collator
import java.util.Locale

// Leading English articles ("The", "A", "An") are dropped when sorting by title, so
// "The Walking Dead" sorts under W and not T, as streaming services and most media libraries
// order titles.
private val LEADING_ARTICLE_REGEX = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

/** [title] as the title sort reads it: trimmed, without a leading article. */
fun titleSortKey(title: String): String = title.trim().replace(LEADING_ARTICLE_REGEX, "")

/**
 * The one title order the Library and Discover share (#849): articles dropped, then compared by a
 * [Collator], so "Écoute" sorts beside "Ecoute" rather than after Z, and case is ignored.
 * A new collator per call, since a Collator is not safe to share between threads.
 */
fun <T> titleSortComparator(title: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(titleSortKey(title(a)), titleSortKey(title(b))) }
}
