package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.parental.ScreenTimeManager
import com.fetch.tv.core.parental.ScreenTimeStatus
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScreenTimeViewModel @Inject constructor(
    private val screenTime: ScreenTimeManager
) : ViewModel() {
    fun grant(minutes: Int, then: () -> Unit) {
        viewModelScope.launch {
            screenTime.grantMinutes(minutes)
            then()
        }
    }

    fun unlockForToday(then: () -> Unit) {
        viewModelScope.launch {
            screenTime.unlockForToday()
            then()
        }
    }
}

/**
 * "That's all for today": a kids profile reached its daily screen time or bedtime. The parent
 * PIN adds time or lifts the limits for the day ([onGranted]); otherwise [onExit].
 */
@Composable
fun ScreenTimeLimitDialog(
    status: ScreenTimeStatus,
    onExit: () -> Unit,
    onGranted: () -> Unit,
    viewModel: ScreenTimeViewModel = hiltViewModel()
) {
    val gate = LocalParentalGate.current
    var choosingTime by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(choosingTime) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val primary = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.Primary,
        contentColor = FetchTheme.colors.OnPrimary
    )
    val secondary = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )

    FetchDialog(
        onDismiss = if (choosingTime) ({ choosingTime = false }) else onExit,
        title = stringResource(R.string.screen_time_title),
        subtitle = stringResource(
            when {
                choosingTime -> R.string.screen_time_choose
                status == ScreenTimeStatus.Bedtime -> R.string.screen_time_bedtime
                else -> R.string.screen_time_daily
            }
        ),
        width = 480.dp,
        suppressFirstKeyUp = false
    ) {
        if (!choosingTime) {
            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                colors = primary
            ) {
                Text(stringResource(R.string.screen_time_ok))
            }
            Button(
                onClick = { gate.runGuarded { choosingTime = true } },
                modifier = Modifier.fillMaxWidth(),
                colors = secondary
            ) {
                Text(stringResource(R.string.screen_time_more))
            }
        } else {
            listOf(15, 30).forEachIndexed { index, minutes ->
                Button(
                    onClick = { viewModel.grant(minutes, onGranted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    colors = secondary
                ) {
                    Text(stringResource(R.string.screen_time_add_minutes, minutes))
                }
            }
            Button(
                onClick = { viewModel.unlockForToday(onGranted) },
                modifier = Modifier.fillMaxWidth(),
                colors = secondary
            ) {
                Text(stringResource(R.string.screen_time_rest_of_day))
            }
        }
    }
}
