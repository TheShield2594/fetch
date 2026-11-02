package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.CatalogRow
import com.fetch.desktop.Screen
import com.fetch.tv.domain.model.MetaPreview

@Composable
fun HomeScreen(state: AppState) {
    when {
        state.addonsLoading -> CenteredProgress()
        state.rows.isEmpty() -> EmptyState(
            title = "Nothing to show yet",
            body = "Install an addon with catalogs on the Addons page."
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.rows, key = { it.key }) { row ->
                CatalogRowView(row, onOpen = { state.navigate(Screen.Detail(it)) })
            }
        }
    }
}

@Composable
private fun CatalogRowView(row: CatalogRow, onOpen: (MetaPreview) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = listOf(row.catalog.name, typeLabel(row.catalog.apiType), row.addon.displayName).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        when {
            row.loading -> Box(Modifier.height(PosterHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            row.error != null -> Text(
                "Couldn't load this row (${row.error})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            row.items.isEmpty() -> Text(
                "This catalog is empty",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(row.items, key = { it.id }) { item -> PosterCard(item, onClick = { onOpen(item) }) }
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> "Movies"
    "series" -> "Series"
    "channel" -> "Channels"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

private val PosterWidth = 150.dp
private val PosterHeight = 225.dp

@Composable
private fun PosterCard(item: MetaPreview, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(PosterWidth).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .width(PosterWidth)
                .height(PosterHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(item.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.releaseInfo?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
