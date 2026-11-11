package com.fetch.tv.updater

import com.fetch.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiSelectorTest {

    private fun apk(name: String) = GitHubAssetDto(name = name, browserDownloadUrl = "https://example.invalid/$name")

    private fun pick(names: List<String>, abis: List<String>): String? =
        AbiSelector.chooseBestApkAsset(names.map(::apk), supportedAbis = abis)?.name

    @Test
    fun `an x86 device does not take the x86_64 apk`() {
        val names = listOf("fetch-1.2.0-x86_64.apk", "fetch-1.2.0-x86.apk")
        assertEquals("fetch-1.2.0-x86.apk", pick(names, listOf("x86")))
    }

    @Test
    fun `an x86_64 device takes the x86_64 apk`() {
        val names = listOf("fetch-1.2.0-x86.apk", "fetch-1.2.0-x86_64.apk")
        assertEquals("fetch-1.2.0-x86_64.apk", pick(names, listOf("x86_64", "x86")))
    }

    @Test
    fun `abis are matched in device preference order`() {
        val names = listOf("fetch-armeabi-v7a.apk", "fetch-arm64-v8a.apk")
        assertEquals("fetch-arm64-v8a.apk", pick(names, listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `a word that merely contains all is not a universal apk`() {
        val names = listOf("fetch-install-helper.apk", "fetch-universal.apk")
        assertEquals("fetch-universal.apk", pick(names, listOf("mips")))
    }

    @Test
    fun `an apk named all is universal`() {
        val names = listOf("fetch-x86_64.apk", "fetch-all.apk")
        assertEquals("fetch-all.apk", pick(names, listOf("arm64-v8a")))
    }

    @Test
    fun `without a match or universal apk, one naming no abi is preferred`() {
        val names = listOf("fetch-x86_64.apk", "fetch-release.apk")
        assertEquals("fetch-release.apk", pick(names, listOf("arm64-v8a")))
    }

    @Test
    fun `abisIn reads whole tokens only`() {
        assertEquals(setOf("x86_64"), AbiSelector.abisIn("Fetch-X86_64-release.apk"))
        assertEquals(setOf("x86"), AbiSelector.abisIn("fetch_x86_release.apk"))
        assertEquals(emptySet<String>(), AbiSelector.abisIn("fetch-x86abc.apk"))
    }

    @Test
    fun `no apk assets gives null`() {
        assertNull(pick(listOf("checksums.txt"), listOf("arm64-v8a")))
    }
}
