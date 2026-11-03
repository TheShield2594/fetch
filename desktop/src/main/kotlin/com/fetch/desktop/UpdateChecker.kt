package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer release on GitHub, with the Linux package that fits this install. */
data class AvailableUpdate(val version: String, val pageUrl: String, val packageUrl: String?, val notes: String?)

/**
 * The TV app's update check, for desktop: GitHub releases, stable only unless betas are wanted,
 * newer by semantic version. Installing stays with apt, dnf or the tarball; Fetch only says where.
 */
class UpdateChecker(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient(),
    private val current: () -> String = { AppInfo.version },
    private val releasesUrl: String = "https://api.github.com/repos/TheShield2594/fetch/releases?per_page=20"
) {
    var available: AvailableUpdate? by mutableStateOf(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** The last result, for Settings › About ("Fetch is up to date"). */
    var status: String? by mutableStateOf(null)
        private set
    /** Hidden for this run after "Later". */
    var dismissed by mutableStateOf(false)

    fun check(beta: Boolean) {
        if (checking) return
        scope.launch {
            checking = true
            try {
                val releases = withContext(Dispatchers.IO) { fetch() }
                available = pick(releases, current(), beta, packageKind())
                status = if (available == null) "Fetch ${current()} is up to date" else "Fetch ${available?.version} is available"
            } catch (e: Exception) {
                status = "Couldn't check for updates: ${e.message}"
            } finally {
                checking = false
            }
        }
    }

    private fun fetch(): List<Release> {
        val request = Request.Builder().url(releasesUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub answered ${response.code}" }
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    @Serializable
    data class Asset(val name: String = "", val browser_download_url: String = "")

    @Serializable
    data class Release(
        val tag_name: String = "",
        val html_url: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList()
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** ".deb", ".rpm" or ".tar.gz", by what this install came from (the package's files), else the tarball. */
        fun packageKind(): String {
            val home = System.getProperty("java.home").orEmpty()
            return when {
                !home.startsWith("/opt/") -> ".tar.gz"
                java.io.File("/usr/bin/dpkg").exists() -> ".deb"
                java.io.File("/usr/bin/rpm").exists() -> ".rpm"
                else -> ".tar.gz"
            }
        }

        /** The newest release above [current] (betas only when [beta]), or null. */
        fun pick(releases: List<Release>, current: String, beta: Boolean, kind: String): AvailableUpdate? {
            val local = Version.parse(current) ?: return null
            return releases
                .filter { !it.draft && (beta || (!it.prerelease && Version.parse(it.tag_name)?.prerelease?.isEmpty() == true)) }
                .mapNotNull { r -> Version.parse(r.tag_name)?.let { it to r } }
                .filter { it.first > local }
                .maxByOrNull { it.first }
                ?.let { (_, r) ->
                    AvailableUpdate(
                        version = r.tag_name.removePrefix("v"),
                        pageUrl = r.html_url,
                        packageUrl = r.assets.firstOrNull { it.name.contains("linux") && it.name.endsWith(kind) }?.browser_download_url,
                        notes = r.body?.lines()?.firstOrNull { it.isNotBlank() }
                    )
                }
        }
    }
}

/** A semantic version, compared as the TV app's updater does (a release outranks its betas). */
data class Version(val major: Long, val minor: Long, val patch: Long, val prerelease: List<String>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return compareValues(other.prerelease.size.coerceAtMost(1), prerelease.size.coerceAtMost(1))
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(raw: String?): Version? {
            val m = pattern.matchEntire(raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null) ?: return null
            return Version(
                m.groupValues[1].toLong(),
                m.groupValues[2].toLong(),
                m.groupValues[3].toLong(),
                m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            )
        }
    }
}
