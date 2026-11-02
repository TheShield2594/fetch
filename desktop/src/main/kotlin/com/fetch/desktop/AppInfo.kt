package com.fetch.desktop

import java.util.Properties

/** Facts about this build, for Settings › About. */
object AppInfo {
    /** The app version, written into `fetch-desktop.properties` at build time. */
    val version: String by lazy {
        val props = Properties()
        AppInfo::class.java.getResourceAsStream("/fetch-desktop.properties")?.use(props::load)
        props.getProperty("version")?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "dev"
    }

    const val REPO_URL = "https://github.com/TheShield2594/fetch"
    const val PRIVACY_URL = "$REPO_URL/blob/main/PRIVACY.md"
    const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"
}
