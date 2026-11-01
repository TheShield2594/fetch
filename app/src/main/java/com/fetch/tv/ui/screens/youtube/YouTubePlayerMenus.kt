package com.fetch.tv.ui.screens.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubePlaylistContext
import com.fetch.tv.ui.screens.detail.formatPlaybackTime
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchTheme
import java.text.NumberFormat
import java.util.Locale

/** The pages of the player's Down menu. */
internal enum class PlaybackOptionsPage { MAIN, CAPTIONS, SPEED, CHAPTERS }

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
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
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
        if (hasChapters) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_options_chapters),
                value = currentChapter,
                selected = false,
                onClick = { onOpen(PlaybackOptionsPage.CHAPTERS) }
            )
        }
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

@Composable
internal fun ChapterMenu(
    chapters: List<YouTubeChapter>,
    current: YouTubeChapter?,
    onSelect: (YouTubeChapter) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    PlayerMenuPanel(title = stringResource(R.string.youtube_options_chapters), modifier = modifier) {
        LazyColumn(
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

/** The colour a category's segments are marked in on the seek bar, as SponsorBlock's extension marks them. */
internal fun sponsorBlockColor(category: SponsorBlockCategory?): Color = when (category) {
    SponsorBlockCategory.SPONSOR, null -> Color(0xFF00D400)
    SponsorBlockCategory.SELF_PROMO -> Color(0xFFFFFF00)
    SponsorBlockCategory.INTERACTION -> Color(0xFFCC00FF)
    SponsorBlockCategory.INTRO -> Color(0xFF00FFFF)
    SponsorBlockCategory.OUTRO -> Color(0xFF0202ED)
    SponsorBlockCategory.PREVIEW -> Color(0xFF008FD6)
    SponsorBlockCategory.MUSIC_OFFTOPIC -> Color(0xFFFF9900)
    SponsorBlockCategory.FILLER -> Color(0xFF7300FF)
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
