package com.fetch.tv.updater

import android.os.Build
import com.fetch.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    // Longest first, so "x86_64" is taken out of a name before "x86" is looked for.
    private val abiPatterns = knownAbis.sortedByDescending { it.length }.map { abi ->
        abi to Regex("(?<![a-z0-9])${Regex.escape(abi)}(?![a-z0-9])")
    }

    private val universalTokens = setOf("universal", "all")

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
    ): GitHubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        // Prefer exact ABI match (in device preference order)
        for (abi in supportedAbis) {
            val wanted = abi.lowercase()
            val candidate = apkAssets.firstOrNull { wanted in abisIn(it.name) }
            if (candidate != null) return candidate
        }

        // Fallback to a universal APK if present
        val universal = apkAssets.firstOrNull { asset -> tokensOf(asset.name).any { it in universalTokens } }
        if (universal != null) return universal

        // If we can at least avoid wrong-ABI picks, prefer APKs that don't mention a known ABI.
        val noAbiMention = apkAssets.firstOrNull { asset -> abisIn(asset.name).isEmpty() }
        return noAbiMention ?: apkAssets.first()
    }

    /**
     * The known ABIs [name] names as whole tokens, so "x86" doesn't match inside "x86_64" and
     * "arm64-v8a" doesn't match inside a longer word.
     */
    internal fun abisIn(name: String): Set<String> {
        var rest = name.lowercase()
        val found = mutableSetOf<String>()
        for ((abi, pattern) in abiPatterns) {
            if (pattern.containsMatchIn(rest)) {
                found += abi
                rest = pattern.replace(rest, " ")
            }
        }
        return found
    }

    /** [name] without its extension, split into lowercase words ("fetch-all.apk" -> fetch, all). */
    private fun tokensOf(name: String): List<String> =
        name.lowercase()
            .removeSuffix(".apk")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
}
