package com.fetch.tv.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.components.MonochromePosterPlaceholder
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.StateActionButton
import com.fetch.tv.ui.theme.FetchTheme

/** The title row of Stats and Calendar, with where the figures come from beside it. */
@Composable
internal fun InsightsHeader(title: String, eyebrow: String = stringResource(R.string.insights_source_eyebrow)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = FetchTheme.textStyles.sectionTitle,
            color = FetchTheme.colors.TextPrimary
        )
        Text(
            text = eyebrow.uppercase(LocalConfiguration.current.locales[0]),
            style = FetchTheme.textStyles.eyebrow,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

/**
 * A screen with nothing to show yet, or nothing at all, with its next step. The action takes
 * focus, so the first D-pad press lands on it rather than on finding something to focus.
 */
@Composable
internal fun InsightsMessage(
    title: String,
    subtitle: String?,
    action: StateAction?,
    modifier: Modifier = Modifier
) {
    val actionFocus = remember { FocusRequester() }
    val holderFocus = remember { FocusRequester() }
    LaunchedEffect(action != null) {
        runCatching { if (action != null) actionFocus.requestFocus() else holderFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FetchTheme.spacing.xxxl, horizontal = FetchTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Without an action nothing else here can hold focus; this keeps it on the screen, and is
        // named so TalkBack reads the message.
        Column(
            modifier = Modifier
                .then(if (action == null) Modifier.focusRequester(holderFocus).focusable() else Modifier)
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = FetchTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(560.dp)
                )
            }
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
            StateActionButton(action, modifier = Modifier.focusRequester(actionFocus))
        }
    }
}

/** Holds focus while the website answers, so the D-pad doesn't wander off the screen. */
@Composable
internal fun InsightsLoading(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val text = stringResource(R.string.insights_loading)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .focusRequester(focus)
            .focusable()
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * Every state but [WebsiteReadState.Loaded] and [WebsiteReadState.Loading]: why the website had
 * nothing for this screen, and what to do about it.
 */
@Composable
internal fun WebsiteUnavailable(
    state: WebsiteReadState<*>,
    notPairedTitle: String,
    failedMessage: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    val openSettings = StateAction(stringResource(R.string.insights_open_settings), onOpenSettings)
    when (state) {
        WebsiteReadState.NotPaired -> InsightsMessage(
            title = notPairedTitle,
            subtitle = stringResource(R.string.insights_not_paired_subtitle),
            action = openSettings
        )
        WebsiteReadState.NoProfile -> InsightsMessage(
            title = stringResource(R.string.insights_no_profile_title),
            subtitle = stringResource(R.string.insights_no_profile_subtitle),
            action = null
        )
        WebsiteReadState.Locked -> InsightsMessage(
            title = stringResource(R.string.insights_locked_title),
            subtitle = stringResource(R.string.insights_locked_subtitle),
            action = null
        )
        is WebsiteReadState.Failed -> Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            ErrorState(
                message = state.message?.takeIf { it.isNotBlank() }?.let { "$failedMessage\n$it" } ?: failedMessage,
                onRetry = onRetry,
                requestInitialFocus = true
            )
        }
        WebsiteReadState.Loading, is WebsiteReadState.Loaded -> Unit
    }
}

/**
 * A section of figures. It takes focus, though there's nothing in it to press: on a TV the D-pad
 * is how the screen scrolls, and a chart nothing can focus would be scrolled straight past. Its
 * [description] is what TalkBack reads for it.
 */
@Composable
internal fun InsightPanel(
    description: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FetchTheme.radii.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FetchTheme.colors.BackgroundCard)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FetchTheme.colors.FocusRing else FetchTheme.colors.Divider,
                shape = shape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        content = content
    )
}

@Composable
internal fun PanelTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = FetchTheme.textStyles.cardTitle,
            color = FetchTheme.colors.TextPrimary
        )
        trailing?.invoke()
    }
}

/** A labelled figure, such as "Movies 120". */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(FetchTheme.colors.Surface)
            .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = FetchTheme.textStyles.metadata,
            color = FetchTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = FetchTheme.textStyles.headline,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A small rounded label, such as a badge or a genre with its count. */
@Composable
internal fun InsightChip(text: String, emphasized: Boolean = false) {
    Text(
        text = text,
        style = FetchTheme.textStyles.metadata,
        color = if (emphasized) FetchTheme.colors.Rating else FetchTheme.colors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (emphasized) FetchTheme.colors.Rating.copy(alpha = 0.12f) else FetchTheme.colors.Surface)
            .border(
                width = 1.dp,
                color = if (emphasized) FetchTheme.colors.Rating.copy(alpha = 0.35f) else FetchTheme.colors.Divider,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
    )
}

internal val InsightPosterWidth: Dp = 120.dp
internal val InsightPosterHeight: Dp = 180.dp

/** A poster that opens its title, with a [badge] (a rating) over its corner. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun InsightPoster(
    poster: String?,
    name: String,
    subtitle: String?,
    badge: String?,
    badgeDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    val description = listOfNotNull(name, subtitle, badgeDescription).joinToString(", ")
    Column(
        modifier = modifier.width(InsightPosterWidth),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .size(InsightPosterWidth, InsightPosterHeight)
                .semantics { contentDescription = description },
            shape = CardDefaults.shape(shape = shape),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            border = CardDefaults.border(
                border = FetchCardDefaults.restingBorder(shape),
                focusedBorder = Border(border = FetchTheme.focusRing.border(), shape = shape)
            )
        ) {
            Box(modifier = Modifier.fillMaxSize().clip(shape).background(FetchTheme.colors.BackgroundCard)) {
                if (!poster.isNullOrBlank()) {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MonochromePosterPlaceholder()
                }
                if (badge != null) {
                    Text(
                        text = badge,
                        style = FetchTheme.textStyles.badge,
                        color = FetchTheme.colors.Rating,
                        modifier = Modifier
                            .padding(FetchTheme.spacing.xs)
                            .clip(RoundedCornerShape(50))
                            .background(FetchTheme.colors.Overlay)
                            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
                    )
                }
            }
        }
        Text(
            text = name,
            style = FetchTheme.textStyles.metadata,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = FetchTheme.textStyles.metadata,
                color = FetchTheme.colors.TextTertiary,
                maxLines = 1
            )
        }
    }
}
