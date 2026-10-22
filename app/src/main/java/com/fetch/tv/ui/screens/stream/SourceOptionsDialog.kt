package com.fetch.tv.ui.screens.stream

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.StateActionButton

/** A source's long-press menu: rules that teach Best match from this one source. */
@Composable
internal fun SourceOptionsDialog(
    rules: List<SourceRule>,
    onRuleSelected: (SourceRule) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.source_options_title),
        subtitle = stringResource(R.string.source_options_subtitle)
    ) {
        rules.forEachIndexed { index, rule ->
            val label = when (rule) {
                is SourceRule.PreferAddon -> stringResource(R.string.source_rule_prefer_addon, rule.addon)
                is SourceRule.NeverReleaseGroup -> stringResource(R.string.source_rule_never_group, rule.group)
            }
            StateActionButton(
                action = StateAction(label) { onRuleSelected(rule) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
            )
        }
    }
}
