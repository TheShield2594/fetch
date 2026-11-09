package com.fetch.tv.ui.components

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.fetch.tv.ui.theme.FetchPrimitives
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text as CoreText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import com.fetch.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * True when the error fills the screen and nothing else can hold focus: Retry takes it, so
     * the first D-pad press doesn't go to finding focus. Leave false where the error sits beside
     * other focusable content.
     */
    requestInitialFocus: Boolean = false
) {
    val retryFocus = remember { FocusRequester() }
    if (requestInitialFocus) {
        LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    }
    val displayMessage = buildErrorStatePresentation(
        message = message,
        strings = rememberErrorStateStrings()
    ).annotated

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(FetchTheme.spacing.xxxl),
            tint = FetchTheme.colors.Error
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
        CoreText(
            text = displayMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = FetchTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        StateActionButton(
            StateAction(stringResource(R.string.action_retry), onRetry),
            modifier = Modifier.focusRequester(retryFocus)
        )
    }
}

private data class ErrorStatePresentation(
    val text: String,
    val boldRanges: List<IntRange>
) {
    val annotated: AnnotatedString
        get() = buildAnnotatedString {
            append(text)
            boldRanges.forEach { range ->
                addStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = FetchPrimitives.white
                    ),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
}

private data class ErrorStateStrings(
    val genericIssue: String,
    val possibleFixFormat: String,
    val retrySoonFix: String,
    val noMetadataAnyAddonIssue: String,
    val noMetadataAnyAddonFix: String,
    val noSupportedMetadataPrefix: String,
    val noAddonForIdPrefix: String,
    val noSupportedMetadataFix: String,
    val triedMetaAddonsPrefix: String,
    val triedMetaAddonsFix: String,
    val missingMetadataForIdIssue: String,
    val missingMetadataForIdFix: String,
    val addonUnreachableIssue: String,
    val addonUnreachableFix: String,
    val addonConnectionFailedIssue: String,
    val addonConnectionFailedFix: String,
    val addonTimeoutIssue: String,
    val addonTimeoutFix: String,
    val addonCleartextIssue: String,
    val addonCleartextFix: String,
    val addonGenericFix: String,
    val addonIssueTemplate: String,
    val metaNotFound: String
)

@Composable
private fun rememberErrorStateStrings(): ErrorStateStrings {
    return ErrorStateStrings(
        genericIssue = stringResource(R.string.error_state_generic_issue),
        possibleFixFormat = stringResource(R.string.error_state_possible_fix, "%1\$s"),
        retrySoonFix = stringResource(R.string.error_state_fix_retry_soon),
        noMetadataAnyAddonIssue = stringResource(R.string.error_state_issue_no_metadata_any_addon),
        noMetadataAnyAddonFix = stringResource(R.string.error_state_fix_no_metadata_any_addon),
        noSupportedMetadataPrefix = stringResource(R.string.error_state_prefix_no_supported_metadata),
        noAddonForIdPrefix = stringResource(R.string.error_state_prefix_no_addon_for_id),
        noSupportedMetadataFix = stringResource(R.string.error_state_fix_no_supported_metadata),
        triedMetaAddonsPrefix = stringResource(R.string.error_state_prefix_tried_meta_addons),
        triedMetaAddonsFix = stringResource(R.string.error_state_fix_tried_meta_addons),
        missingMetadataForIdIssue = stringResource(R.string.error_state_issue_missing_metadata_for_id),
        missingMetadataForIdFix = stringResource(R.string.error_state_fix_missing_metadata_for_id),
        addonUnreachableIssue = stringResource(R.string.error_state_issue_addon_unreachable),
        addonUnreachableFix = stringResource(R.string.error_state_fix_addon_unreachable),
        addonConnectionFailedIssue = stringResource(R.string.error_state_issue_addon_connection_failed),
        addonConnectionFailedFix = stringResource(R.string.error_state_fix_addon_connection_failed),
        addonTimeoutIssue = stringResource(R.string.error_state_issue_addon_timeout),
        addonTimeoutFix = stringResource(R.string.error_state_fix_addon_timeout),
        addonCleartextIssue = stringResource(R.string.error_state_issue_addon_cleartext),
        addonCleartextFix = stringResource(R.string.error_state_fix_addon_cleartext),
        addonGenericFix = stringResource(R.string.error_state_fix_addon_generic),
        addonIssueTemplate = stringResource(R.string.error_state_addon_issue_template, "%1\$s", "%2\$s"),
        metaNotFound = stringResource(R.string.error_meta_not_found)
    )
}

private fun buildErrorStatePresentation(
    message: String,
    strings: ErrorStateStrings
): ErrorStatePresentation {
    val trimmed = message.trim()
    if (trimmed.isBlank()) {
        return ErrorStatePresentation(
            text = withPossibleFix(strings.genericIssue, strings.retrySoonFix, strings),
            boldRanges = emptyList()
        )
    }

    // Already written for the viewer as a title, a blank line, then detail (the stream search's
    // "No sources for S1 E2" with each addon's reason): show it as is, title in bold.
    val titleEnd = trimmed.indexOf("\n\n")
    if (titleEnd > 0) {
        return ErrorStatePresentation(text = trimmed, boldRanges = listOf(0 until titleEnd))
    }

    val addonName = trimmed.substringBefore(':', missingDelimiterValue = "").trim()
        .takeIf { it.isNotBlank() && it.length < trimmed.length }
    val reason = trimmed.substringAfter(':', missingDelimiterValue = trimmed).trim()

    fun messageWithFix(issue: String, fix: String): String {
        return withPossibleFix(issue, fix, strings)
    }

    val displayMessage = when {
        trimmed.equals(strings.metaNotFound, ignoreCase = true) ||
            trimmed.startsWith(strings.noAddonForIdPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = strings.noMetadataAnyAddonIssue,
                fix = strings.noMetadataAnyAddonFix
            )
        }

        trimmed.startsWith(strings.noSupportedMetadataPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = strings.noSupportedMetadataFix
            )
        }

        trimmed.startsWith(strings.triedMetaAddonsPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = strings.triedMetaAddonsFix
            )
        }

        reason.equals("returned no metadata for this id", ignoreCase = true) ||
            reason.equals("metadata was not found", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.missingMetadataForIdIssue, strings),
                fix = strings.missingMetadataForIdFix
            )
        }

        reason.contains("could not reach the addon server", ignoreCase = true) ||
            trimmed.contains("Unable to resolve host", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonUnreachableIssue, strings),
                fix = strings.addonUnreachableFix
            )
        }

        reason.contains("connection to the addon failed", ignoreCase = true) ||
            trimmed.contains("Failed to connect", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonConnectionFailedIssue, strings),
                fix = strings.addonConnectionFailedFix
            )
        }

        reason.contains("timed out", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonTimeoutIssue, strings),
                fix = strings.addonTimeoutFix
            )
        }

        reason.contains("insecure HTTP connection blocked by Android", ignoreCase = true) ||
            trimmed.contains("CLEARTEXT communication", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonCleartextIssue, strings),
                fix = strings.addonCleartextFix
            )
        }

        addonName != null -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, reason, strings),
                fix = strings.addonGenericFix
            )
        }

        // Already a sentence written for the viewer (a screen's own localized error): keep it,
        // and say what to try.
        trimmed.last() in SENTENCE_END -> messageWithFix(issue = trimmed, fix = strings.retrySoonFix)

        // Anything else is raw text from below, such as the HTTP reason phrase "Bad Gateway"
        // from an addon's 502. Lead with the generic issue and keep the raw text as detail.
        else -> return ErrorStatePresentation(
            text = strings.genericIssue + "\n" + trimmed + "\n\n" +
                strings.possibleFixFormat.format(strings.retrySoonFix),
            boldRanges = emptyList()
        )
    }

    val boldRanges = linkedSetOf<IntRange>()

    val aggregatePrefix = "${strings.triedMetaAddonsPrefix} "
    if (displayMessage.startsWith(aggregatePrefix)) {
        val listStart = aggregatePrefix.length
        displayMessage.indexOf('.', startIndex = listStart)
            .takeIf { it > listStart }
            ?.let { boldRanges += listStart until it }
    }

    displayMessage.indexOf(':')
        .takeIf {
            it > 0 &&
                !displayMessage.startsWith(strings.triedMetaAddonsPrefix, ignoreCase = true) &&
                !displayMessage.startsWith(strings.noSupportedMetadataPrefix, ignoreCase = true)
        }
        ?.let { boldRanges += 0 until it }

    Regex("""id=[^\s.,)]+""").findAll(displayMessage).forEach { match ->
        boldRanges += match.range.first..match.range.last
    }

    return ErrorStatePresentation(
        text = displayMessage,
        boldRanges = boldRanges.toList()
    )
}

private val SENTENCE_END = setOf('.', '!', '?', '…', '。', '！', '？', '؟', '।')

private fun withPossibleFix(issue: String, fix: String, strings: ErrorStateStrings): String {
    return issue + "\n\n" + strings.possibleFixFormat.format(fix)
}

private fun buildAddonIssue(addonName: String?, issue: String, strings: ErrorStateStrings): String {
    val normalizedIssue = issue.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
    return if (addonName.isNullOrBlank()) {
        normalizedIssue
    } else {
        strings.addonIssueTemplate.format(addonName, normalizedIssue)
    }
}
