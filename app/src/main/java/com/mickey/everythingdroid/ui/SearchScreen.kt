package com.mickey.everythingdroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mickey.everythingdroid.MainViewModel
import com.mickey.everythingdroid.SortMode
import com.mickey.everythingdroid.data.SearchResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val search by vm.search.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navStack by vm.navStack.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val inBrowse = navStack.size > 1
    val currentTitle = navStack.last().title

    LaunchedEffect(download.lastMessage, download.completedFile) {
        val msg = download.lastMessage
        if (!msg.isNullOrBlank()) {
            val file = download.completedFile
            val res = if (file != null) {
                snackbar.showSnackbar(msg, actionLabel = "Open", withDismissAction = true)
            } else {
                snackbar.showSnackbar(msg)
            }
            if (res == SnackbarResult.ActionPerformed && file != null) {
                FileActions.openFile(context, file)
            }
            vm.clearDownloadMessage()
        }
    }

    var sortMenuOpen by remember { mutableStateOf(false) }
    var menuResult by remember { mutableStateOf<SearchResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inBrowse) currentTitle else "EverythingDroid",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (inBrowse) {
                        IconButton(onClick = { vm.navigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        vm.updateSort(mode)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (!inBrowse) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = vm::updateQuery,
                    label = { Text("Everything search") },
                    placeholder = { Text("e.g. *.pdf  size:>10mb  ext:zip") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = vm::runSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Run search")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (settings.isConfigured()) "Server: ${settings.baseUrl()}" else "Server not set — open settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Browsing  ${navStack.last().query.removePrefix("parent:").trim('"')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (search.isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("Searching…", modifier = Modifier.padding(start = 12.dp))
                }
            }

            search.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            val rows = search.sortedResults
            if (!search.isSearching && rows.isEmpty() && search.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            inBrowse -> "Empty folder."
                            search.query.isBlank() -> "Type a query and press search."
                            else -> "No results yet — press the search icon."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "${rows.size} of ${search.totalResults} shown · sort: ${search.sort.label}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows, key = { it.fullPath }) { r ->
                        ResultRow(
                            result = r,
                            isDownloading = download.activeKey == r.fullPath,
                            bytesRead = if (download.activeKey == r.fullPath) download.bytesRead else 0,
                            total = if (download.activeKey == r.fullPath) download.total else 0,
                            onTap = {
                                if (r.isFile) vm.downloadResult(r) else vm.openFolder(r)
                            },
                            onLongPress = { menuResult = r },
                            onPlay = {
                                FileActions.streamUrl(context, vm.streamUrlWithCreds(r), r.name)
                            },
                            onDownload = { vm.downloadResult(r) },
                        )
                    }
                }
            }
        }
    }

    menuResult?.let { r ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { menuResult = null },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    r.name.ifBlank { "(no name)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    r.parentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (!r.isFile) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                        headlineContent = { Text("Open folder") },
                        modifier = Modifier.combinedClickable(onClick = {
                            menuResult = null
                            scope.launch { sheetState.hide() }
                            vm.openFolder(r)
                        }),
                    )
                }
                if (r.isFile) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Download, null) },
                        headlineContent = { Text("Download") },
                        modifier = Modifier.combinedClickable(onClick = {
                            menuResult = null
                            scope.launch { sheetState.hide() }
                            vm.downloadResult(r)
                        }),
                    )
                    if (FileActions.isMedia(r.name)) {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                            headlineContent = { Text("Play / Stream") },
                            modifier = Modifier.combinedClickable(onClick = {
                                menuResult = null
                                scope.launch { sheetState.hide() }
                                FileActions.streamUrl(context, vm.streamUrlWithCreds(r), r.name)
                            }),
                        )
                    }
                }
                ListItem(
                    leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                    headlineContent = { Text("Copy Windows path") },
                    modifier = Modifier.combinedClickable(onClick = {
                        FileActions.copyToClipboard(context, "path", r.fullPath)
                        menuResult = null
                    }),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Link, null) },
                    headlineContent = { Text("Copy download URL") },
                    modifier = Modifier.combinedClickable(onClick = {
                        FileActions.copyToClipboard(context, "URL", vm.fileUrl(r))
                        menuResult = null
                    }),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Share, null) },
                    headlineContent = { Text("Share path") },
                    modifier = Modifier.combinedClickable(onClick = {
                        FileActions.shareText(context, r.fullPath, subject = r.name)
                        menuResult = null
                    }),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    result: SearchResult,
    isDownloading: Boolean,
    bytesRead: Long,
    total: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FileIcons.iconFor(result.name, isFolder = !result.isFile),
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        result.name.ifBlank { "(no name)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        result.parentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.isFile) {
                        result.size?.let {
                            Text(
                                humanSize(it) + (result.dateModified?.let { d -> "  •  $d" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (result.isFile) {
                    if (FileActions.isMedia(result.name)) {
                        IconButton(onClick = onPlay) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Stream")
                        }
                    }
                    IconButton(onClick = onDownload, enabled = !isDownloading) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                } else {
                    IconButton(onClick = onTap) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open folder")
                    }
                }
            }
            if (isDownloading) {
                Spacer(Modifier.height(8.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${humanSize(bytesRead)} / ${humanSize(total)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(humanSize(bytesRead), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
    return "%.2f %s".format(v, units[i])
}
