package com.mickey.everythingdroid.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.mickey.everythingdroid.ui.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream

sealed class DownloadEvent {
    data class Progress(val bytesRead: Long, val total: Long) : DownloadEvent()
    data class Done(val name: String, val uri: Uri, val mime: String) : DownloadEvent()
    data class Failed(val error: String) : DownloadEvent()
}

class Downloader(
    private val context: Context,
    private val api: EverythingApi,
    private val settingsProvider: () -> ServerSettings,
) {
    /** Where the next download will land, for display in Settings. */
    fun targetDescription(): String {
        val tree = settingsProvider().downloadTree
        if (tree.isNotBlank()) {
            val label = Uri.parse(tree).lastPathSegment
                ?.substringAfterLast(':')?.ifBlank { null }
            return label?.let { "/$it (custom folder)" } ?: "Custom folder"
        }
        return "Download folder"
    }

    private class Sink(val uri: Uri, val out: OutputStream, val finish: (ok: Boolean) -> Unit)

    /**
     * Open an output for [name]: the user-chosen SAF folder if set, otherwise the
     * device's public Downloads via MediaStore (API 29+) or the classic public
     * directory (API 24-28, falling back to app storage if unwritable).
     */
    private fun openSink(name: String, mime: String): Sink {
        val tree = settingsProvider().downloadTree
        if (tree.isNotBlank()) {
            try {
                val treeUri = Uri.parse(tree)
                val dirDoc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val doc = DocumentsContract.createDocument(
                    context.contentResolver, dirDoc, mime, name
                )
                val out = doc?.let { context.contentResolver.openOutputStream(it) }
                if (doc != null && out != null) {
                    return Sink(doc, out) { ok ->
                        if (!ok) runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, doc)
                        }
                    }
                }
            } catch (_: Exception) {
                // Folder was moved/revoked — fall through to the default location.
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create entry in Downloads")
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open Downloads entry for writing")
            return Sink(uri, out) { ok ->
                if (ok) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    runCatching { resolver.update(uri, values, null, null) }
                } else {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
        val file = try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            uniqueFile(dir, name).also { it.outputStream().close() }
        } catch (_: Exception) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            dir.mkdirs()
            uniqueFile(dir, name)
        }
        return Sink(Uri.fromFile(file), file.outputStream()) { ok ->
            if (!ok) runCatching { file.delete() }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    fun download(result: SearchResult): Flow<DownloadEvent> = callbackFlow {
        val url = api.buildFileUrl(result.fullPath)
        val reqBuilder = Request.Builder().url(url).get()
        api.authHeader()?.let { reqBuilder.header("Authorization", it) }

        val call = api.client().newCall(reqBuilder.build())
        try {
            val resp = call.execute()
            if (!resp.isSuccessful) {
                trySend(DownloadEvent.Failed("HTTP ${resp.code} ${resp.message}\nURL: $url"))
                close()
                return@callbackFlow
            }

            val body = resp.body ?: run {
                trySend(DownloadEvent.Failed("Empty response body\nURL: $url"))
                close()
                return@callbackFlow
            }

            val name = sanitizeFileName(result.name)
            val mime = FileActions.mimeTypeFor(result.name)
                .let { if (it == "*/*") "application/octet-stream" else it }
            val sink = openSink(name, mime)
            val total = body.contentLength()
            var ok = false
            try {
                body.byteStream().use { input ->
                    sink.out.use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            trySend(DownloadEvent.Progress(totalRead, total))
                        }
                    }
                }
                ok = true
            } finally {
                sink.finish(ok)
            }
            trySend(DownloadEvent.Done(name, sink.uri, mime))
            close()
        } catch (e: Exception) {
            trySend(DownloadEvent.Failed("${e.message ?: e::class.java.simpleName}\nURL: $url"))
            close(e)
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download.bin" }
}
