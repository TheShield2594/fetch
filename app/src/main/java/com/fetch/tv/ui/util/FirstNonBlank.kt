package com.fetch.tv.ui.util

/** The first candidate that has text, trimmed, or null when none has. */
fun firstNonBlank(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
