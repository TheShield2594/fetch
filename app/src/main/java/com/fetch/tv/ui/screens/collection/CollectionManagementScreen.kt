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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.fetch.tv.data.local.ValidationResult
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FetchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.FocusBackground,
            focusedContentColor = FetchTheme.colors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
        content = { content() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionManagementScreen(
    viewModel: CollectionManagementViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var collectionToDelete by remember { mutableStateOf<Collection?>(null) }
    val deleteDialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(collectionToDelete) {
        if (collectionToDelete != null) {
            repeat(3) { withFrameNanos { } }
            try {
                deleteDialogFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // true once the file is written, false when it couldn't be. A confirmation fades after a
    // moment; a failure stays until it has been up long enough to read and a key is then pressed.
    var exportSucceeded by remember { mutableStateOf<Boolean?>(null) }
    var exportErrorReadyToDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(exportSucceeded) {
        exportErrorReadyToDismiss = false
        when (exportSucceeded) {
            true -> {
                kotlinx.coroutines.delay(3000)
                exportSucceeded = null
            }
            false -> {
                kotlinx.coroutines.delay(8000)
                exportErrorReadyToDismiss = true
            }
            null -> Unit
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showImportDialog) {
        ImportContent(
            importText = uiState.importText,
            importError = uiState.importError,
            onTextChange = { viewModel.updateImportText(it) },
            onImport = { viewModel.importCollections() },
            onPaste = {},
            onBack = { viewModel.hideImportDialog() },
            importMode = uiState.importMode,
            onModeChange = { viewModel.setImportMode(it) },
            importUrl = uiState.importUrl,
            onUrlChange = { viewModel.updateImportUrl(it) },
            onFetchUrl = { viewModel.fetchUrl() },
            validationResult = uiState.validationResult,
            isLoadingImport = uiState.isLoadingImport,
            onValidate = { viewModel.validateCurrentText() },
            onPickFile = {
                scope.launch {
                    viewModel.loadFromFile(context)
                }
            },
            onConfirmImport = { viewModel.confirmImport() }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Observes keys without consuming them, to clear an export error that has been read.
            .onPreviewKeyEvent { event ->
                if (exportErrorReadyToDismiss && event.type == KeyEventType.KeyDown) {
                    exportSucceeded = null
                }
                false
            }
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
                text = stringResource(R.string.collections_header),
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                color = FetchTheme.colors.TextPrimary,
            )
            val newButtonFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(3) { withFrameNanos { } }
                val targetRequester = lastFocusedId?.let { itemFocusRequesters[it] } ?: newButtonFocusRequester
                try { targetRequester.requestFocus() } catch (_: Exception) {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                if (uiState.collections.isNotEmpty()) {
                    FetchButton(onClick = {
                        scope.launch {
                            exportSucceeded = try {
                                val json = viewModel.getExportJson()
                                withContext(Dispatchers.IO) {
                                    val resolver = context.contentResolver
                                    // Delete existing file first
                                    val existingUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                    resolver.delete(
                                        existingUri,
                                        "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                                        arrayOf("fetch-collections.json")
                                    )
                                    // Write new file
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "fetch-collections.json")
                                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                                    }
                                    // Saved only once the bytes are written; a null insert or stream is a failure.
                                    val uri = resolver.insert(existingUri, values) ?: return@withContext false
                                    resolver.openOutputStream(uri)?.use { os ->
                                        os.write(json.toByteArray())
                                        true
                                    } ?: false
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.collections_export_file))
                    }
                }
                FetchButton(onClick = { viewModel.showImportDialog() }) {
                    Text(stringResource(R.string.collections_import))
                }
                FetchButton(
                    onClick = {
                        lastFocusedId = null
                        onNavigateToEditor(null)
                    },
                    modifier = Modifier.focusRequester(newButtonFocusRequester)
                ) {
                    Text(stringResource(R.string.collections_new))
                }
            }
        }

        exportSucceeded?.let { saved ->
            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
            Text(
                text = if (saved) {
                    stringResource(R.string.collections_saved_downloads)
                } else {
                    stringResource(R.string.collections_export_failed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (saved) FetchTheme.colors.TextSecondary else FetchTheme.colors.Error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

        if (uiState.collections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.collections_empty),
                    color = FetchTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FetchTheme.spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                itemsIndexed(
                    items = uiState.collections,
                    key = { _, item -> item.id }
                ) { index, collection ->
                    val editFocusRequester = itemFocusRequesters.getOrPut(collection.id) { FocusRequester() }
                    CollectionListItem(
                        collection = collection,
                        isFirst = index == 0,
                        isLast = index == uiState.collections.size - 1,
                        editFocusRequester = editFocusRequester,
                        onEdit = {
                            lastFocusedId = collection.id
                            onNavigateToEditor(collection.id)
                        },
                        onDelete = { collectionToDelete = collection },
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) }
                    )
                }
            }
        }
    }

    collectionToDelete?.let { collection ->
        FetchDialog(
            onDismiss = { collectionToDelete = null },
            title = stringResource(R.string.collections_delete_confirm_title),
            subtitle = stringResource(R.string.collections_delete_confirm_subtitle, collection.title),
            suppressFirstKeyUp = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md, Alignment.End)
            ) {
                FetchButton(
                    onClick = { collectionToDelete = null },
                    modifier = Modifier.focusRequester(deleteDialogFocusRequester)
                ) {
                    Text(stringResource(R.string.collections_cancel))
                }
                FetchButton(
                    onClick = {
                        viewModel.deleteCollection(collection.id)
                        collectionToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.collections_delete_confirm))
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportContent(
    importText: String,
    importError: String?,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    importMode: ImportMode,
    onModeChange: (ImportMode) -> Unit,
    importUrl: String,
    onUrlChange: (String) -> Unit,
    onFetchUrl: () -> Unit,
    validationResult: ValidationResult?,
    isLoadingImport: Boolean,
    onValidate: () -> Unit,
    onPickFile: () -> Unit,
    onConfirmImport: () -> Unit
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
                text = stringResource(R.string.collections_import_header),
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                color = FetchTheme.colors.TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                FetchButton(onClick = onBack) { Text(stringResource(R.string.collections_cancel)) }
            }
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FetchTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.none)
        ) {
            item {
                Text(
                    text = stringResource(R.string.collections_import_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                // Mode tabs
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                    ImportMode.entries.filter { it != ImportMode.PASTE }.forEach { mode ->
                        val label = when (mode) {
                            ImportMode.PASTE -> stringResource(R.string.collections_mode_paste)
                            ImportMode.FILE -> stringResource(R.string.collections_mode_file)
                            ImportMode.URL -> stringResource(R.string.collections_mode_url)
                        }
                        if (mode == importMode) {
                            Button(
                                onClick = { onModeChange(mode) },
                                colors = ButtonDefaults.colors(
                                    containerColor = FetchTheme.colors.Primary,
                                    contentColor = FetchTheme.colors.Background,
                                    focusedContainerColor = FetchTheme.colors.Primary,
                                    focusedContentColor = FetchTheme.colors.Background
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                        shape = RoundedCornerShape(FetchTheme.radii.md)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                            ) {
                                Text(label)
                            }
                        } else {
                            FetchButton(onClick = { onModeChange(mode) }) {
                                Text(label)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
            }

            item {
                when (importMode) {
                    ImportMode.PASTE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                            FetchButton(onClick = onPaste) { Text(stringResource(R.string.collections_paste_clipboard)) }
                            if (importText.isNotBlank()) {
                                FetchButton(onClick = onValidate) { Text(stringResource(R.string.collections_validate)) }
                            }
                        }

                        Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                        Surface(
                            shape = RoundedCornerShape(FetchTheme.radii.md),
                            colors = SurfaceDefaults.colors(containerColor = FetchTheme.colors.BackgroundElevated),
                            border = Border(
                                border = BorderStroke(FetchTheme.spacing.hairline, if (importError != null) FetchTheme.colors.Error else FetchTheme.colors.Border),
                                shape = RoundedCornerShape(FetchTheme.radii.md)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            Box(modifier = Modifier.padding(FetchTheme.spacing.md)) {
                                BasicTextField(
                                    value = importText,
                                    onValueChange = onTextChange,
                                    modifier = Modifier.fillMaxSize(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = FetchTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                                    ),
                                    cursorBrush = SolidColor(FetchTheme.colors.Primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    decorationBox = { innerTextField ->
                                        if (importText.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.collections_paste_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = FetchTheme.colors.TextTertiary
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    ImportMode.FILE -> {
                        Text(
                            text = stringResource(R.string.collections_file_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        FetchButton(onClick = onPickFile) { Text(stringResource(R.string.collections_load_file)) }

                        if (importText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                            Text(
                                text = stringResource(R.string.collections_file_loaded, importText.length),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.TextSecondary
                            )
                        }
                    }

                    ImportMode.URL -> {
                        FetchTextField(
                            value = importUrl,
                            onValueChange = onUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = stringResource(R.string.collections_import_url_placeholder),
                            isError = importError != null
                        )

                        // The error belongs to the field, so it sits right under it.
                        if (importError != null) {
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                            Text(
                                text = importError,
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.Error
                            )
                        }

                        Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                        FetchButton(onClick = onFetchUrl) {
                            Text(if (isLoadingImport) stringResource(R.string.collections_fetching) else stringResource(R.string.collections_fetch_url))
                        }

                        if (isLoadingImport) {
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                            LoadingIndicator()
                        }
                    }
                }
            }

            // Validation result preview
            if (validationResult != null && validationResult.valid) {
                item {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
                    Surface(
                        shape = RoundedCornerShape(FetchTheme.radii.md),
                        colors = SurfaceDefaults.colors(containerColor = FetchTheme.colors.BackgroundCard),
                        border = Border(
                            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Primary),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(FetchTheme.spacing.lg)) {
                            Text(
                                text = stringResource(R.string.collections_valid_json),
                                style = MaterialTheme.typography.titleMedium,
                                color = FetchTheme.colors.Primary
                            )
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_valid_summary, validationResult.collectionCount, validationResult.folderCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.TextSecondary
                            )
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                            FetchButton(onClick = onConfirmImport) {
                                Text(stringResource(R.string.collections_confirm_import))
                            }
                        }
                    }
                }
            }

            if (importError != null && importMode != ImportMode.URL) {
                item {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                    Text(
                        text = importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.Error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionListItem(
    collection: Collection,
    isFirst: Boolean,
    isLast: Boolean,
    editFocusRequester: FocusRequester? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(FetchTheme.radii.md),
        colors = SurfaceDefaults.colors(containerColor = FetchTheme.colors.BackgroundCard),
        modifier = Modifier.fillMaxWidth()
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
                    text = collection.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = collection.title.contentTextDirection()
                    ),
                    color = FetchTheme.colors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.collections_folder_count, collection.folders.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
                Button(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up))
                }

                Button(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down))
                }

                Button(
                    onClick = onEdit,
                    modifier = if (editFocusRequester != null) Modifier.focusRequester(editFocusRequester) else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                }

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete))
                }
            }
        }
    }
}
