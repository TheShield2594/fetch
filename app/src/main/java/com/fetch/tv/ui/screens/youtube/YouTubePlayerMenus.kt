package com.fetch.tv.ui.screens.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubePlaylistContext
import com.fetch.tv.data.youtube.YouTubeVideoDetails
import com.fetch.tv.ui.screens.detail.formatPlaybackTime
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.SponsorBlockColors
import java.text.NumberFormat
import java.util.Locale

/** The pages of the player's Down menu. */
internal enum class PlaybackOptionsPage { MAIN, CAPTIONS, SPEED, QUALITY, CHAPTERS, DETAILS }

/** A titled panel on the player's right, like the captions and actions menus. */
@Composable
private fun PlayerMenuPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .padding(FetchTheme.spacing.xl)
            .width(CAPTION_MENU_WIDTH)
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(FetchTheme.colors.Player.ScrimStrong)
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = FetchTheme.colors.Player.TextPrimary,
            modifier = Modifier.padding(bottom = FetchTheme.spacing.xs)
        )
        content()
    }
}

/**
 * Down's menu: captions (when the video has any), speed, and chapters (when it has them), each
 * showing what's chosen and opening its own list.
 */
@Composable
internal fun PlaybackOptionsMenu(
    captionLabel: String?,
    speed: Float,
    qualityLabel: String,
    currentChapter: String?,
    hasChapters: Boolean,
    onOpen: (PlaybackOptionsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    PlayerMenuPanel(title = stringResource(R.string.youtube_options_title), modifier = modifier) {
        if (captionLabel != null) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_options_captions),
                value = captionLabel,
                selected = false,
                onClick = { onOpen(PlaybackOptionsPage.CAPTIONS) },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        CaptionMenuRow(
            label = stringResource(R.string.youtube_options_speed),
            value = speedLabel(speed),
            selected = false,
            onClick = { onOpen(PlaybackOptionsPage.SPEED) },
            modifier = if (captionLabel == null) Modifier.focusRequester(firstFocus) else Modifier
        )
        CaptionMenuRow(
            label = stringResource(R.string.youtube_options_quality),
            value = qualityLabel,
            selected = false,
            onClick = { onOpen(PlaybackOptionsPage.QUALITY) }
        )
        if (hasChapters) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_options_chapters),
                value = currentChapter,
                selected = false,
                onClick = { onOpen(PlaybackOptionsPage.CHAPTERS) }
            )
        }
        CaptionMenuRow(
            label = stringResource(R.string.youtube_options_details),
            selected = false,
            onClick = { onOpen(PlaybackOptionsPage.DETAILS) }
        )
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterFrames() }
}

@Composable
internal fun SpeedMenu(
    speed: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    PlayerMenuPanel(title = stringResource(R.string.youtube_options_speed), modifier = modifier) {
        LazyColumn(
            modifier = Modifier.heightIn(max = CAPTION_MENU_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            itemsIndexed(YouTubeViewModel.PLAYBACK_SPEEDS, key = { _, it -> it }) { _, option ->
                val selected = option == speed
                CaptionMenuRow(
                    label = speedLabel(option),
                    selected = selected,
                    onClick = { onSelect(option) },
                    modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { selectedFocus.requestFocusAfterFrames() }
}

/**
 * The heights the player's Quality list offers after Auto, tallest first: the ones this video has
 * on this device (#742). Nothing but Auto when they aren't known (an HLS manifest, which adapts).
 */
internal fun qualityOptions(available: List<Int>): List<Int?> =
    listOf<Int?>(null) + available.distinct().sortedDescending()

/** A row of the Quality list: Auto with Settings' cap, or a height. */
@Composable
internal fun qualityLabel(quality: Int?, defaultQuality: Int): String =
    if (quality == null) {
        stringResource(R.string.youtube_quality_auto, defaultQuality)
    } else {
        stringResource(R.string.youtube_quality_height, quality)
    }

/**
 * The quality shown on the options menu: the height actually playing, which can be below the one
 * picked when the video has nothing taller; Auto (with its cap) when it isn't known.
 */
@Composable
internal fun playingQualityLabel(quality: Int?, playingHeight: Int?, defaultQuality: Int): String =
    when {
        playingHeight == null -> qualityLabel(quality, defaultQuality)
        quality == null -> stringResource(R.string.youtube_quality_auto_playing, playingHeight)
        else -> stringResource(R.string.youtube_quality_height, playingHeight)
    }

/**
 * The player's Quality list: Auto (Settings' cap) and each height. A pick fetches the stream again
 * at that cap; the video carries on from where it was.
 */
@Composable
internal fun QualityMenu(
    quality: Int?,
    defaultQuality: Int,
    available: List<Int>,
    loading: Boolean,
    /** The quality being fetched while [loading]; its row shows the spinner. */
    pending: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    // The current pick stays listed even when the new stream's list lacks it, so it shows as selected.
    val options = remember(available, quality) { qualityOptions(available + listOfNotNull(quality)) }
    PlayerMenuPanel(title = stringResource(R.string.youtube_options_quality), modifier = modifier) {
        LazyColumn(
            modifier = Modifier.heightIn(max = CAPTION_MENU_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            itemsIndexed(options, key = { _, it -> it ?: 0 }) { _, option ->
                val selected = option == quality
                CaptionMenuRow(
                    label = qualityLabel(option, defaultQuality),
                    selected = selected && !loading,
                    loading = loading && option == pending,
                    onClick = { onSelect(option) },
                    modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { selectedFocus.requestFocusAfterFrames() }
}

/**
 * What's under the video on YouTube: views, upload date and channel, the description, and the
 * comments, read from the watch page the first time this opens. Each paragraph and comment takes
 * focus in turn, so the remote scrolls through them; more comments load near the end.
 */
@Composable
internal fun VideoDetailsPanel(
    details: YouTubeVideoDetails?,
    loading: Boolean,
    failed: Boolean,
    loadingMore: Boolean,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .padding(FetchTheme.spacing.xl)
            .width(DETAILS_PANEL_WIDTH)
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(FetchTheme.colors.Player.ScrimStrong)
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.youtube_options_details),
            style = MaterialTheme.typography.titleMedium,
            color = FetchTheme.colors.Player.TextPrimary
        )
        when {
            details == null && loading -> CaptionMenuRow(
                label = stringResource(R.string.youtube_details_loading),
                selected = false,
                loading = true,
                onClick = {},
                modifier = Modifier.focusRequester(firstFocus)
            )
            details == null -> CaptionMenuRow(
                label = stringResource(if (failed) R.string.youtube_details_failed else R.string.youtube_details_loading),
                selected = false,
                onClick = {},
                modifier = Modifier.focusRequester(firstFocus)
            )
            else -> {
                val paragraphs = remember(details.description) {
                    // Line by line, and long lines in pieces: each is a focus stop no taller than the panel,
                    // so the remote never skips over part of the text.
                    descriptionBlocks(details.description)
                }
                val listState = rememberLazyListState()
                val total = 1 + paragraphs.size + 1 + details.comments.size
                val nearEnd by remember(total) {
                    derivedStateOf {
                        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3
                    }
                }
                // The continuation too: a page that added nothing new still moves it on.
                LaunchedEffect(nearEnd, total, details.commentsContinuation) { if (nearEnd) onNearEnd() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = DETAILS_PANEL_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
                ) {
                    item(key = "numbers") {
                        DetailsBlock(
                            text = listOfNotNull(details.viewCount, details.published).joinToString(" · ")
                                .ifBlank { details.channelName.orEmpty() },
                            secondary = listOfNotNull(details.channelName, details.subscriberCount).joinToString(" · ").ifBlank { null },
                            modifier = Modifier.focusRequester(firstFocus)
                        )
                    }
                    itemsIndexed(paragraphs, key = { index, _ -> "p$index" }) { _, paragraph ->
                        DetailsBlock(text = paragraph)
                    }
                    item(key = "comments") {
                        Text(
                            text = details.commentCount?.let { stringResource(R.string.youtube_comments_count, it) }
                                ?: stringResource(R.string.youtube_comments),
                            style = MaterialTheme.typography.titleSmall,
                            color = FetchTheme.colors.Player.TextPrimary,
                            modifier = Modifier.padding(top = FetchTheme.spacing.sm)
                        )
                    }
                    itemsIndexed(details.comments, key = { _, comment -> comment.id }) { _, comment ->
                        DetailsBlock(
                            text = comment.text,
                            secondary = listOfNotNull(
                                comment.author.ifBlank { null },
                                comment.published,
                                comment.likeCount?.let { stringResource(R.string.youtube_comment_likes, it) },
                                stringResource(R.string.youtube_comment_pinned).takeIf { comment.isPinned }
                            ).joinToString(" · "),
                            heading = true
                        )
                    }
                    if (loadingMore) {
                        item(key = "more") {
                            CaptionMenuRow(label = stringResource(R.string.youtube_details_loading), selected = false, loading = true, onClick = {})
                        }
                    } else if (details.comments.isEmpty()) {
                        item(key = "none") {
                            DetailsBlock(text = stringResource(R.string.youtube_comments_none))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(details == null) { firstFocus.requestFocusAfterFrames() }
}

/**
 * [description] as the details panel's focus stops: one per line, and a line wider than
 * [maxChars] in pieces broken after a sentence where one falls late enough, else at a space.
 * Width counts a wide (CJK, full-width) character as two, so a piece of [maxChars] stays well
 * under the panel's height in any script, even with the system font enlarged.
 */
internal fun descriptionBlocks(description: String, maxChars: Int = DESCRIPTION_BLOCK_MAX_CHARS): List<String> =
    description.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { line ->
        val pieces = mutableListOf<String>()
        var rest = line
        while (displayWidth(rest) > maxChars) {
            // The longest prefix no wider than the limit (at least one character).
            var end = 0
            var width = 0
            while (end < rest.length) {
                val w = displayWidth(rest[end])
                if (width + w > maxChars && end > 0) break
                width += w
                end++
            }
            val window = rest.substring(0, minOf(end + 1, rest.length))
            val sentence = Regex("[.!?]\\s|[。！？]").findAll(window).lastOrNull()?.range?.first?.plus(1)
            val cut = (sentence?.takeIf { it >= end / 2 && it <= end }
                ?: window.lastIndexOf(' ').takeIf { it >= end / 2 && it <= end }
                ?: end).coerceAtLeast(1)
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        pieces
    }

private fun displayWidth(c: Char): Int = when (Character.UnicodeBlock.of(c)) {
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS, Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
    Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA, Character.UnicodeBlock.HANGUL_SYLLABLES,
    Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS -> 2
    else -> 1
}

private fun displayWidth(text: String): Int = text.sumOf { displayWidth(it) }

/** A focusable block of text in [VideoDetailsPanel], so the remote can move through it. */
@Composable
private fun DetailsBlock(
    text: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    /** [secondary] is the comment's author line, shown above the text. */
    heading: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .background(if (focused) FetchTheme.colors.Player.Control else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            if (heading && !secondary.isNullOrBlank()) {
                Text(text = secondary, style = MaterialTheme.typography.labelMedium, color = FetchTheme.colors.Player.TextSecondary)
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = FetchTheme.colors.Player.TextPrimary)
            if (!heading && !secondary.isNullOrBlank()) {
                Text(text = secondary, style = MaterialTheme.typography.labelMedium, color = FetchTheme.colors.Player.TextSecondary)
            }
        }
    }
}

/** Wide enough for a paragraph; the list scrolls past a panel as tall as it is wide. */
private val DETAILS_PANEL_WIDTH = CAPTION_MENU_WIDTH * 1.5f
private val DETAILS_PANEL_MAX_HEIGHT = DETAILS_PANEL_WIDTH

/**
 * About six lines of Latin text at the panel's width: under a quarter of its height, so a piece
 * fits even at the largest system font size.
 */
private const val DESCRIPTION_BLOCK_MAX_CHARS = 360

@Composable
internal fun ChapterMenu(
    chapters: List<YouTubeChapter>,
    current: YouTubeChapter?,
    onSelect: (YouTubeChapter) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    // Opens at the current chapter, so its row is composed when it's asked to take focus.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOf(current).coerceAtLeast(0))
    PlayerMenuPanel(title = stringResource(R.string.youtube_options_chapters), modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = CAPTION_MENU_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            itemsIndexed(chapters, key = { _, it -> it.startMs }) { index, chapter ->
                val selected = chapter == current || (current == null && index == 0)
                CaptionMenuRow(
                    label = chapter.title.ifBlank { formatPlaybackTime(chapter.startMs) },
                    value = formatPlaybackTime(chapter.startMs),
                    selected = chapter == current,
                    onClick = { onSelect(chapter) },
                    modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { selectedFocus.requestFocusAfterFrames() }
}

@Composable
internal fun speedLabel(speed: Float): String =
    if (speed == 1f) stringResource(R.string.youtube_speed_normal) else stringResource(R.string.youtube_speed_value, formatSpeed(speed))

/** 1.5 as "1.5", 2 as "2", 0.25 as "0.25". */
internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')

@Composable
internal fun sponsorBlockCategoryLabel(category: SponsorBlockCategory): String = stringResource(
    when (category) {
        SponsorBlockCategory.SPONSOR -> R.string.youtube_sponsor_block_category_sponsor
        SponsorBlockCategory.SELF_PROMO -> R.string.youtube_sponsor_block_category_selfpromo
        SponsorBlockCategory.INTERACTION -> R.string.youtube_sponsor_block_category_interaction
        SponsorBlockCategory.INTRO -> R.string.youtube_sponsor_block_category_intro
        SponsorBlockCategory.OUTRO -> R.string.youtube_sponsor_block_category_outro
        SponsorBlockCategory.PREVIEW -> R.string.youtube_sponsor_block_category_preview
        SponsorBlockCategory.MUSIC_OFFTOPIC -> R.string.youtube_sponsor_block_category_music_offtopic
        SponsorBlockCategory.FILLER -> R.string.youtube_sponsor_block_category_filler
    }
)

@Composable
internal fun sponsorBlockModeLabel(mode: SponsorBlockMode): String = stringResource(
    when (mode) {
        SponsorBlockMode.SKIP -> R.string.youtube_sponsor_block_mode_skip
        SponsorBlockMode.ASK -> R.string.youtube_sponsor_block_mode_ask
        SponsorBlockMode.SHOW -> R.string.youtube_sponsor_block_mode_show
        SponsorBlockMode.OFF -> R.string.youtube_sponsor_block_mode_off
    }
)

/** The colour a category's segments are marked in on the seek bar. */
internal fun sponsorBlockColor(category: SponsorBlockCategory?): Color = when (category) {
    SponsorBlockCategory.SPONSOR, null -> SponsorBlockColors.Sponsor
    SponsorBlockCategory.SELF_PROMO -> SponsorBlockColors.SelfPromo
    SponsorBlockCategory.INTERACTION -> SponsorBlockColors.Interaction
    SponsorBlockCategory.INTRO -> SponsorBlockColors.Intro
    SponsorBlockCategory.OUTRO -> SponsorBlockColors.Outro
    SponsorBlockCategory.PREVIEW -> SponsorBlockColors.Preview
    SponsorBlockCategory.MUSIC_OFFTOPIC -> SponsorBlockColors.MusicOfftopic
    SponsorBlockCategory.FILLER -> SponsorBlockColors.Filler
}

/** "Playlist name · 3 of 12", or just the name when YouTube doesn't say where the video is. */
@Composable
internal fun playlistPositionText(playlist: YouTubePlaylistContext): String? {
    val title = playlist.title ?: return null
    val index = playlist.currentIndex
    val total = playlist.totalVideos
    return if (index != null && total != null && total > 0) {
        stringResource(R.string.youtube_playlist_position, title, index + 1, total)
    } else {
        title
    }
}

/** A count the way YouTube shortens it: 1.2K, 3.4M. */
internal fun formatCount(count: Long, locale: Locale = Locale.getDefault()): String {
    val (value, suffix) = when {
        count >= 1_000_000_000 -> count / 1_000_000_000.0 to "B"
        count >= 1_000_000 -> count / 1_000_000.0 to "M"
        count >= 1_000 -> count / 1_000.0 to "K"
        else -> return NumberFormat.getIntegerInstance(locale).format(count)
    }
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (value < 10) 1 else 0
    }
    return format.format(value) + suffix
}
