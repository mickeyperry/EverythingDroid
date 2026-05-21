package com.mickey.everythingdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mickey.everythingdroid.data.DownloadEvent
import com.mickey.everythingdroid.data.Downloader
import com.mickey.everythingdroid.data.EverythingApi
import com.mickey.everythingdroid.data.SearchResult
import com.mickey.everythingdroid.data.ServerSettings
import com.mickey.everythingdroid.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

enum class SortMode(val label: String) {
    NameAsc("Name ↑"),
    NameDesc("Name ↓"),
    SizeAsc("Size ↑"),
    SizeDesc("Size ↓"),
    ModifiedNewest("Modified ↓"),
    ModifiedOldest("Modified ↑"),
}

data class NavEntry(
    val title: String,
    val query: String,
    val isBrowse: Boolean,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val totalResults: Int = 0,
    val error: String? = null,
    val sort: SortMode = SortMode.NameAsc,
) {
    val sortedResults: List<SearchResult>
        get() = when (sort) {
            SortMode.NameAsc -> results.sortedBy { it.name.lowercase() }
            SortMode.NameDesc -> results.sortedByDescending { it.name.lowercase() }
            SortMode.SizeAsc -> results.sortedBy { it.size ?: -1L }
            SortMode.SizeDesc -> results.sortedByDescending { it.size ?: -1L }
            SortMode.ModifiedNewest -> results.sortedByDescending { it.dateModified.orEmpty() }
            SortMode.ModifiedOldest -> results.sortedBy { it.dateModified.orEmpty() }
        }
}

data class DownloadUiState(
    val activeKey: String? = null,
    val bytesRead: Long = 0,
    val total: Long = 0,
    val lastMessage: String? = null,
    val completedFile: File? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val currentSettings = MutableStateFlow(ServerSettings())
    private val api = EverythingApi { currentSettings.value }
    private val downloader = Downloader(app, api)

    val settings: StateFlow<ServerSettings> = currentSettings.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _download = MutableStateFlow(DownloadUiState())
    val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    private val _navStack = MutableStateFlow<List<NavEntry>>(
        listOf(NavEntry(title = "Search", query = "", isBrowse = false))
    )
    val navStack: StateFlow<List<NavEntry>> = _navStack.asStateFlow()
    private val currentNav: NavEntry get() = _navStack.value.last()
    val isInBrowse: Boolean get() = currentNav.isBrowse

    val downloadDir: String get() = downloader.targetDirPath()
    fun fileUrl(r: SearchResult): String = api.buildFileUrl(r.fullPath)
    fun authHeader(): String? = api.authHeader()
    fun streamUrlWithCreds(r: SearchResult): String {
        val s = currentSettings.value
        val raw = api.buildFileUrl(r.fullPath)
        if (s.username.isBlank()) return raw
        // Embed credentials: http://user:pass@host... Most external players (VLC, MX Player)
        // honour this. Falls back to whatever the player does on 401 if not.
        val schemeEnd = raw.indexOf("://").takeIf { it >= 0 } ?: return raw
        val prefix = raw.substring(0, schemeEnd + 3)
        val rest = raw.substring(schemeEnd + 3)
        val userPart = java.net.URLEncoder.encode(s.username, "UTF-8")
        val passPart = java.net.URLEncoder.encode(s.password, "UTF-8")
        return "$prefix$userPart:$passPart@$rest"
    }

    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        settingsStore.settings
            .onEach { currentSettings.value = it }
            .launchIn(viewModelScope)
    }

    fun updateQuery(q: String) {
        if (currentNav.isBrowse) return  // query is locked inside browse mode
        _search.value = _search.value.copy(query = q)
    }

    fun updateSort(s: SortMode) {
        _search.value = _search.value.copy(sort = s)
    }

    fun saveSettings(s: ServerSettings) {
        viewModelScope.launch {
            settingsStore.save(s)
            currentSettings.value = s
        }
    }

    fun runSearch() {
        searchJob?.cancel()
        val q = _search.value.query
        if (q.isBlank()) return
        searchJob = viewModelScope.launch {
            _search.value = _search.value.copy(isSearching = true, error = null)
            currentSettings.value = settingsStore.settings.first()
            api.search(q).fold(
                onSuccess = { resp ->
                    _search.value = _search.value.copy(
                        isSearching = false,
                        results = resp.results,
                        totalResults = resp.totalResults,
                    )
                },
                onFailure = { e ->
                    _search.value = _search.value.copy(
                        isSearching = false,
                        results = emptyList(),
                        totalResults = 0,
                        error = e.message ?: "Search failed",
                    )
                },
            )
        }
    }

    fun openFolder(folder: SearchResult) {
        if (folder.isFile) return
        val parentQuery = "parent:\"${folder.fullPath}\""
        _navStack.value = _navStack.value + NavEntry(
            title = folder.name.ifBlank { folder.fullPath },
            query = parentQuery,
            isBrowse = true,
        )
        _search.value = _search.value.copy(query = parentQuery, results = emptyList(), error = null)
        runSearch()
    }

    /** Returns true if back was consumed (popped), false if already at root. */
    fun navigateBack(): Boolean {
        val stack = _navStack.value
        if (stack.size <= 1) return false
        val newStack = stack.dropLast(1)
        _navStack.value = newStack
        val top = newStack.last()
        _search.value = _search.value.copy(query = top.query, results = emptyList(), error = null)
        if (top.query.isNotBlank()) runSearch()
        return true
    }

    fun downloadResult(result: SearchResult) {
        if (!result.isFile) {
            _download.value = _download.value.copy(lastMessage = "Folders cannot be downloaded directly")
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val key = result.fullPath
            _download.value = DownloadUiState(activeKey = key, lastMessage = "Starting ${result.name}…")
            downloader.download(result).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress -> {
                        _download.value = _download.value.copy(
                            activeKey = key,
                            bytesRead = ev.bytesRead,
                            total = ev.total,
                        )
                    }
                    is DownloadEvent.Done -> {
                        _download.value = DownloadUiState(
                            activeKey = null,
                            lastMessage = "Saved ${ev.file.name}",
                            completedFile = ev.file,
                        )
                    }
                    is DownloadEvent.Failed -> {
                        _download.value = DownloadUiState(
                            activeKey = null,
                            lastMessage = "Download failed: ${ev.error}",
                        )
                    }
                }
            }
        }
    }

    fun clearDownloadMessage() {
        _download.value = _download.value.copy(lastMessage = null)
    }
}
