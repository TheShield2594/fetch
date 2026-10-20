package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchTopBar(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(FetchTheme.colors.Background)
            .padding(horizontal = FetchTheme.spacing.xxxl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = FetchTheme.colors.Primary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarNavItem(text = stringResource(R.string.nav_home), isSelected = true)
            TopBarNavItem(text = stringResource(R.string.nav_movies), isSelected = false)
            TopBarNavItem(text = stringResource(R.string.nav_series), isSelected = false)
            TopBarNavItem(text = stringResource(R.string.nav_search), isSelected = false)
            TopBarNavItem(text = stringResource(R.string.nav_settings), isSelected = false)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBarNavItem(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (isSelected) FetchTheme.colors.Primary else FetchTheme.colors.TextSecondary,
        modifier = modifier
    )
}
