package com.fetch.tv.ui.screens.collection

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.catalogTypesMatch
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.R
import androidx.compose.ui.res.stringResource
import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.domain.model.LocalListCollectionSource

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LocalListPickerContent(
    lists: List<LocalList>,
    alreadyAdded: List<CollectionSource>,
    onToggle: (LocalList) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = FetchTheme.spacing.xxxl, start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_select_local_lists),
                style = MaterialTheme.typography.headlineMedium,
                color = FetchTheme.colors.TextPrimary
            )
            FetchButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

        if (lists.isEmpty()) {
            Text(
                text = stringResource(R.string.collections_editor_no_local_lists),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = FetchTheme.spacing.sm, end = FetchTheme.spacing.sm, top = FetchTheme.spacing.xs, bottom = FetchTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            items(items = lists, key = { it.id }) { list ->
                val isAdded = alreadyAdded.any { it is LocalListCollectionSource && it.listId == list.id }
                Card(
                    onClick = { onToggle(list) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = if (isAdded) FetchTheme.colors.Secondary.copy(alpha = 0.15f) else FetchTheme.colors.BackgroundCard,
                        focusedContainerColor = FetchTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        border = if (isAdded) Border(
                            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Secondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        ) else Border.None,
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
                    scale = CardDefaults.scale(focusedScale = 1.01f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FetchTheme.spacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = list.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.collections_editor_local_list_count, list.items.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.TextTertiary
                            )
                        }
                        Icon(
                            imageVector = if (isAdded) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (isAdded) {
                                stringResource(R.string.collection_editor_remove_cd)
                            } else {
                                stringResource(R.string.cd_add)
                            },
                            tint = if (isAdded) FetchTheme.colors.TextSecondary else FetchTheme.colors.TextTertiary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CatalogPickerContent(
    catalogs: List<AvailableCatalog>,
    alreadyAdded: List<CollectionSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = FetchTheme.spacing.xxxl, start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_select_catalogs),
                style = MaterialTheme.typography.headlineMedium,
                color = FetchTheme.colors.TextPrimary
            )
            FetchButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = FetchTheme.spacing.sm, end = FetchTheme.spacing.sm, top = FetchTheme.spacing.xs, bottom = FetchTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            itemsIndexed(
                items = catalogs,
                key = { index, c -> "${c.addonId}_${c.type}_${c.catalogId}_$index" }
            ) { _, catalog ->
                val isAdded = alreadyAdded.any {
                    it is AddonCatalogCollectionSource && it.addonId == catalog.addonId && catalogTypesMatch(it.type, catalog.type) && it.catalogId == catalog.catalogId
                }
                Card(
                    onClick = { onToggle(catalog) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = if (isAdded) FetchTheme.colors.Secondary.copy(alpha = 0.15f) else FetchTheme.colors.BackgroundCard,
                        focusedContainerColor = FetchTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        border = if (isAdded) Border(
                            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Secondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        ) else Border.None,
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
                    scale = CardDefaults.scale(focusedScale = 1.01f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FetchTheme.spacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog.catalogName.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.TextPrimary
                            )
                            val supportingGenreText = when {
                                catalog.genreRequired -> stringResource(R.string.collections_editor_genre_required)
                                catalog.genreOptions.isNotEmpty() -> stringResource(R.string.collections_editor_genre_optional)
                                else -> null
                            }
                            Text(
                                text = listOfNotNull("${catalog.type} - ${catalog.addonName}", supportingGenreText).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.TextTertiary
                            )
                        }
                        if (isAdded) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.collection_editor_remove_cd),
                                tint = FetchTheme.colors.TextSecondary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_add),
                                tint = FetchTheme.colors.TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
