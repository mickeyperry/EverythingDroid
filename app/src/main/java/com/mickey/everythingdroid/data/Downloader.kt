package com.mickey.everythingdroid.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File

sealed class DownloadEvent {
    data class Progress(val bytesRead: Long, val total: Long) : DownloadEvent()
    data class Done(val file: File) : DownloadEvent()
    data class Failed(val error: String) : DownloadEvent()
}

class Downloader(
    private val context: Context,
    private val api: EverythingApi,
) {
    private fun targetDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun targetDirPath(): String = targetDir().absolutePath

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

            val outFile = File(targetDir(), sanitizeFileName(result.name))
            val total = body.contentLength()
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
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
            trySend(DownloadEvent.Done(outFile))
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
