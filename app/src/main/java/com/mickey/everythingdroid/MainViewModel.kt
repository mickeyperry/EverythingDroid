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

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val totalResults: Int = 0,
    val error: String? = null,
)

data class DownloadUiState(
    val activeKey: String? = null,
    val bytesRead: Long = 0,
    val total: Long = 0,
    val lastMessage: String? = null,
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

    val downloadDir: String get() = downloader.targetDirPath()

    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        settingsStore.settings
            .onEach { currentSettings.value = it }
            .launchIn(viewModelScope)
    }

    fun updateQuery(q: String) {
        _search.value = _search.value.copy(query = q)
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
            // Ensure latest persisted settings are loaded before issuing the call.
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
                            lastMessage = "Saved to ${ev.file.absolutePath}",
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
