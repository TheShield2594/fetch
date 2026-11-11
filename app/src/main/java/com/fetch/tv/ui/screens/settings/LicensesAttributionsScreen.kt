@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fetch.tv.R
import com.fetch.tv.ui.util.rememberRawSvgPainter

private const val FetchRepositoryUrl = "https://github.com/TheShield2594/fetch"
private const val TmdbUrl = "https://www.themoviedb.org"
private const val TraktUrl = "https://trakt.tv"
private const val SimklUrl = "https://simkl.com"
private const val MdbListUrl = "https://mdblist.com"
private const val IntroDbUrl = "https://introdb.app/"
private const val ImdbDatasetsUrl = "https://developer.imdb.com/non-commercial-datasets/"
private const val ApacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"
private const val LibMpvAndroidUrl = "https://github.com/abdallahmehiz/mpv-android"
private const val FfmpegLegalUrl = "https://ffmpeg.org/legal.html"
private const val LgplUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"

private const val Apache2 = "Apache License 2.0"
private const val Bsd3 = "BSD 3-Clause License"

/**
 * A bundled third-party library: name, licence and where to read it. Names aren't translated.
 * [notices] are the licence texts (res/raw) whose terms require the copyright notice and
 * conditions to ship with the app; Apache-2.0 and LGPL texts are shown once, under
 * "Full license texts".
 */
private data class LibraryNotice(
    val name: String,
    val license: String,
    val url: String,
    val notices: List<Int> = emptyList()
)

// Keep in step with app/build.gradle.kts and app/libs. Media3, mpv and FFmpeg have their own
// entries under playback.
private val playbackLibraryNotices = listOf(
    LibraryNotice("libgav1 (AV1 decoder)", Apache2, "https://chromium.googlesource.com/codecs/libgav1"),
    LibraryNotice("libiamf (IAMF decoder)", "BSD 3-Clause Clear License", "https://github.com/AOMediaCodec/libiamf", listOf(R.raw.license_libiamf)),
    LibraryNotice("Fraunhofer MPEG-H decoder (mpeghdec)", "Fraunhofer FDK MPEG-H software license", "https://github.com/Fraunhofer-IIS/mpeghdec", listOf(R.raw.license_mpeghdec)),
    LibraryNotice(
        "libass / libass-android (ass-media)",
        "ISC License (libass); MIT License (libass-android)",
        "https://github.com/peerless2012/libass-android",
        listOf(R.raw.license_libass, R.raw.license_libass_android)
    ),
    LibraryNotice("NextLib MediaInfo", "GNU General Public License v3.0", "https://github.com/anilbeesetti/nextlib")
)

private val libraryNotices = listOf(
    LibraryNotice("Android Jetpack (AndroidX, Compose, Compose for TV)", Apache2, "https://developer.android.com/jetpack/androidx"),
    LibraryNotice("Kotlin, kotlinx.coroutines, kotlinx.serialization", Apache2, "https://github.com/JetBrains/kotlin"),
    LibraryNotice("Dagger / Hilt", Apache2, "https://github.com/google/dagger"),
    LibraryNotice("Guava", Apache2, "https://github.com/google/guava"),
    LibraryNotice("Gson", Apache2, "https://github.com/google/gson"),
    LibraryNotice("ZXing", Apache2, "https://github.com/zxing/zxing"),
    LibraryNotice("OkHttp", Apache2, "https://github.com/square/okhttp"),
    LibraryNotice("Retrofit", Apache2, "https://github.com/square/retrofit"),
    LibraryNotice("Moshi", Apache2, "https://github.com/square/moshi"),
    LibraryNotice("Coil", Apache2, "https://github.com/coil-kt/coil"),
    LibraryNotice("Multiplatform Markdown Renderer", Apache2, "https://github.com/mikepenz/multiplatform-markdown-renderer"),
    LibraryNotice("NanoHTTPD", Bsd3, "https://github.com/NanoHttpd/nanohttpd", listOf(R.raw.license_nanohttpd))
)

private sealed interface LicenseLogo {
    data class Drawable(@param:DrawableRes val resId: Int) : LicenseLogo
    data class Raw(@param:RawRes val resId: Int) : LicenseLogo
    data class Url(val url: String) : LicenseLogo
}

private data class LicenseAttributionItem(
    val title: String,
    val body: String,
    val url: String,
    val logo: LicenseLogo? = null,
    val notices: List<Int> = emptyList()
)

@Composable
fun LicensesAttributionsScreen(
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.screen.horizontal, vertical = FetchTheme.spacing.screen.vertical),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        LicensesAttributionsBrandPanel(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
        )

        LicensesAttributionsDetailsPanel(
            firstFocusRequester = firstFocusRequester,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun LicensesAttributionsBrandPanel(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.licenses_attributions_title),
            style = MaterialTheme.typography.headlineLarge,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
        Text(
            text = stringResource(R.string.licenses_attributions_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun LicensesAttributionsDetailsPanel(
    firstFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .border(FetchTheme.spacing.hairline, FetchTheme.colors.Border.copy(alpha = 0.5f), RoundedCornerShape(FetchTheme.radii.xl))
            .background(FetchTheme.colors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(FetchTheme.radii.xl))
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_app)
                ) {
                    AttributionDetailRow(
                        item = appLicenseItem(),
                        modifier = Modifier.focusRequester(firstFocusRequester)
                    )
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_data)
                ) {
                    dataAttributionItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_playback)
                ) {
                    playbackLicenseItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_libraries)
                ) {
                    libraryNotices.forEach { notice ->
                        AttributionDetailRow(item = notice.toItem())
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_texts)
                ) {
                    licenseTextItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }
            }
            SettingsVerticalScrollIndicators(state = scrollState)
        }
    }
}

@Composable
private fun AttributionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = FetchTheme.colors.TextSecondary,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun AttributionDetailRow(
    item: LicenseAttributionItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Card(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            },
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp),
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.Background,
                focusedContainerColor = FetchTheme.colors.Background
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(SettingsSecondaryCardRadius)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
            scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
                verticalAlignment = Alignment.Top
            ) {
                item.logo?.let { logo ->
                    AttributionLogo(logo = logo)
                    Spacer(modifier = Modifier.width(14.dp))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = FetchTheme.colors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = FetchTheme.colors.Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = FetchTheme.colors.TextTertiary,
                    modifier = Modifier
                        .padding(top = FetchTheme.spacing.xxs)
                        .size(18.dp)
                )
            }
        }
        item.notices.forEach { resId -> LicenseText(resId = resId) }
    }
}

/**
 * A bundled licence text, one focusable block per paragraph so a remote can step through
 * texts longer than the screen.
 */
@Composable
private fun LicenseText(@RawRes resId: Int) {
    val context = LocalContext.current
    val paragraphs = remember(resId) {
        runCatching {
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        }.getOrDefault("")
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        paragraphs.forEach { paragraph ->
            var focused by remember { mutableStateOf(false) }
            Text(
                text = paragraph,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
            )
        }
    }
}

@Composable
private fun AttributionLogo(
    logo: LicenseLogo
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(FetchTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = when (logo) {
                is LicenseLogo.Drawable -> painterResource(id = logo.resId)
                is LicenseLogo.Raw -> rememberRawSvgPainter(rawIconRes = logo.resId, targetSize = FetchTheme.spacing.xxxl)
                is LicenseLogo.Url -> rememberUrlSvgPainter(url = logo.url)
            },
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun rememberUrlSvgPainter(url: String): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { FetchTheme.spacing.xxxl.roundToPx() }
    val request = remember(url, context, sizePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun appLicenseItem() = LicenseAttributionItem(
    title = stringResource(R.string.licenses_attributions_fetch_title),
    body = stringResource(R.string.licenses_attributions_fetch_body),
    url = FetchRepositoryUrl
)

@Composable
private fun dataAttributionItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_tmdb_title),
        body = stringResource(R.string.licenses_attributions_tmdb_body),
        url = TmdbUrl,
        logo = LicenseLogo.Drawable(R.drawable.rating_tmdb)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_trakt_title),
        body = stringResource(R.string.licenses_attributions_trakt_body),
        url = TraktUrl,
        logo = LicenseLogo.Raw(R.raw.trakt_tv_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_simkl_title),
        body = stringResource(R.string.licenses_attributions_simkl_body),
        url = SimklUrl,
        logo = LicenseLogo.Raw(R.raw.simkl_tv_glyph)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_mdblist_title),
        body = stringResource(R.string.licenses_attributions_mdblist_body),
        url = MdbListUrl,
        logo = LicenseLogo.Raw(R.raw.mdblist_logo)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_introdb_title),
        body = stringResource(R.string.licenses_attributions_introdb_body),
        url = IntroDbUrl,
        logo = LicenseLogo.Drawable(R.drawable.introdb_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_imdb_title),
        body = stringResource(R.string.licenses_attributions_imdb_body),
        url = ImdbDatasetsUrl
    )
)

@Composable
private fun playbackLicenseItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_exoplayer_title),
        body = stringResource(R.string.licenses_attributions_exoplayer_body),
        url = ApacheLicenseUrl
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_libmpv_title),
        body = stringResource(R.string.licenses_attributions_libmpv_body),
        url = LibMpvAndroidUrl,
        notices = listOf(R.raw.license_mpv_android)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_ffmpeg_title),
        body = stringResource(R.string.licenses_attributions_ffmpeg_body),
        url = FfmpegLegalUrl
    )
) + playbackLibraryNotices.map { it.toItem() }

@Composable
private fun LibraryNotice.toItem() = LicenseAttributionItem(
    title = name,
    body = stringResource(R.string.licenses_attributions_license_format, license),
    url = url,
    notices = notices
)

@Composable
private fun licenseTextItems() = listOf(
    LicenseAttributionItem(
        title = Apache2,
        body = stringResource(R.string.licenses_attributions_license_text_body),
        url = ApacheLicenseUrl,
        notices = listOf(R.raw.license_apache_2_0)
    ),
    LicenseAttributionItem(
        title = "GNU Lesser General Public License 2.1",
        body = stringResource(R.string.licenses_attributions_license_text_body),
        url = LgplUrl,
        notices = listOf(R.raw.license_lgpl_2_1)
    )
)
