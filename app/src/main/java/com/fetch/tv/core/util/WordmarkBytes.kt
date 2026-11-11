package com.fetch.tv.core.util

import android.annotation.SuppressLint
import android.content.Context
import com.fetch.tv.R

/**
 * The Fetch wordmark's PNG bytes, which the phone hub and the local settings servers send to the
 * browser, or null if the resource can't be read.
 *
 * openRawResource is typed for raw resources, but it opens any file-backed resource, and
 * app_logo_wordmark is a plain PNG in drawable/ (also drawn on screen through ThemeBranding), so
 * the ResourceType warning does not apply. Keep this file a PNG: an XML drawable would come back
 * as compiled binary XML.
 */
@SuppressLint("ResourceType")
fun Context.readWordmarkPngBytes(): ByteArray? =
    runCatching { resources.openRawResource(R.drawable.app_logo_wordmark).use { it.readBytes() } }.getOrNull()
