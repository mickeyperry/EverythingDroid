package com.mickey.everythingdroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val type: String,            // "file" or "folder"
    val name: String,
    val parentPath: String,      // e.g. C:\Users\Mickey\Documents
    val size: Long?,
    val dateModified: String?,
) {
    val fullPath: String
        get() = if (parentPath.isBlank()) name
        else parentPath.trimEnd('\\', '/') + "\\" + name

    val isFile: Boolean get() = type.equals("file", ignoreCase = true)
}

data class SearchResponse(
    val totalResults: Int,
    val results: List<SearchResult>,
)

class EverythingApi(
    private val settingsProvider: () -> ServerSettings,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun client(): OkHttpClient = client

    suspend fun search(
        query: String,
        offset: Int = 0,
        count: Int = 100,
    ): Result<SearchResponse> = withContext(Dispatchers.IO) {
        val s = settingsProvider()
        if (!s.isConfigured()) return@withContext Result.failure(IllegalStateException("Server not configured"))

        runCatching {
            val url = (s.baseUrl()).toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("j", "1")
                .addQueryParameter("path_column", "1")
                .addQueryParameter("size_column", "1")
                .addQueryParameter("date_modified_column", "1")
                .addQueryParameter("count", count.toString())
                .addQueryParameter("offset", offset.toString())
                .build()

            val reqBuilder = Request.Builder().url(url).get()
            if (s.username.isNotBlank()) {
                reqBuilder.header("Authorization", Credentials.basic(s.username, s.password))
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
                }
                val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
                parseSearchJson(body)
            }
        }
    }

    private fun parseSearchJson(body: String): SearchResponse {
        val root = JSONObject(body)
        val total = root.optInt("totalResults", 0)
        val arr = root.optJSONArray("results")
        val list = mutableListOf<SearchResult>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sizeStr = o.optString("size", "")
                val size = sizeStr.toLongOrNull()
                list += SearchResult(
                    type = o.optString("type", "file"),
                    name = o.optString("name", ""),
                    parentPath = o.optString("path", ""),
                    size = size,
                    dateModified = o.optString("date_modified", "").ifBlank { null },
                )
            }
        }
        return SearchResponse(total, list)
    }

    /**
     * Build the URL Everything's HTTP server serves a file at.
     * Everything serves files at `http://host:port/<full path with forward slashes>` —
     * each path segment URL-encoded, drive colon left as ':'.
     */
    fun buildFileUrl(fullPath: String): String {
        val s = settingsProvider()
        val base = s.baseUrl().trimEnd('/')
        val segments = fullPath.split('\\', '/').filter { it.isNotEmpty() }
        val encoded = segments.joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8")
                .replace("+", "%20")
                .replace("%3A", ":")
        }
        return "$base/$encoded"
    }

    fun authHeader(): String? {
        val s = settingsProvider()
        return if (s.username.isNotBlank()) Credentials.basic(s.username, s.password) else null
    }
}
