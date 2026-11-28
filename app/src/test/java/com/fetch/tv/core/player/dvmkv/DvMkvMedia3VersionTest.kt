package com.fetch.tv.core.player.dvmkv

import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendored Matroska extractor in this package is a copy of Media3's, so it silently drifts
 * from the library it runs against. This fails on a Media3 upgrade: re-check the copy against
 * the new upstream extractor, then update the record in `package-info.java` and this version.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DvMkvMedia3VersionTest {

    @Test
    fun `vendored extractor is checked against the Media3 version it was forked for`() {
        assertEquals(
            "The Media3 version changed: re-check core/player/dvmkv against the upstream extractor",
            "1.8.0",
            MediaLibraryInfo.VERSION
        )
    }
}
