package com.mickey.everythingdroid.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny localhost HTTP relay that lets external players (VLC, MX Player, Photos…)
 * stream files off Everything's HTTP server.
 *
 * Handing players the server URL directly fails for three reasons:
 *  - the server requires Basic auth, which most players won't take from a URL;
 *  - UNC paths put `%5C%5C` (\\) in the URL, which some player URL parsers mangle;
 *  - Everything answers HEAD with 400, breaking players that probe before playing.
 *
 * The player instead gets `http://127.0.0.1:<port>/<id>/<encoded name>`. We forward
 * each request upstream with credentials, pass Range through both ways (seeking
 * works), and synthesize HEAD responses from a 1-byte ranged GET.
 */
class StreamProxy(
    private val client: OkHttpClient,
    private val authHeader: () -> String?,
) {
    private data class Entry(val remoteUrl: String, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nextId = AtomicInteger(1)
    private var server: ServerSocket? = null

    /** Register a remote file and return the localhost URL to hand to a player. */
    fun localUrlFor(remoteUrl: String, name: String, mime: String): String {
        val port = ensureStarted()
        val id = nextId.getAndIncrement().toString()
        entries[id] = Entry(remoteUrl, mime)
        val safeName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/$id/$safeName"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        entries.clear()
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = ss
        Thread({
            while (!ss.isClosed) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ handle(sock) }, "stream-proxy-conn")
                    .apply { isDaemon = true }.start()
            }
        }, "stream-proxy-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30_000
            val out = s.getOutputStream()
            try {
                val (method, path, range) = readRequest(s) ?: return
                if (method != "GET" && method != "HEAD") {
                    writeStatus(out, 405, "Method Not Allowed"); return
                }
                val id = path.removePrefix("/").substringBefore('/')
                val entry = entries[id]
                if (entry == null) {
                    writeStatus(out, 404, "Not Found"); return
                }
                serve(out, entry, method, range)
            } catch (_: Exception) {
                // Player hung up mid-stream or upstream died — nothing to do.
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    /** Returns (method, path, rangeHeader) or null on a malformed request. */
    private fun readRequest(s: Socket): Triple<String, String, String?>? {
        val input = s.getInputStream()
        val line = StringBuilder()
        val headerBlock = StringBuilder()
        // Read byte-wise until the blank line; GET/HEAD carry no body.
        while (true) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            if (c == '\n') {
                if (line.isEmpty()) break
                headerBlock.append(line).append('\n')
                line.setLength(0)
            } else if (c != '\r') {
                line.append(c)
            }
            if (headerBlock.length > 32_768) return null
        }
        val lines = headerBlock.lines()
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val range = lines.drop(1)
            .firstOrNull { it.startsWith("range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return Triple(requestLine[0].uppercase(), requestLine[1], range)
    }

    private fun serve(out: OutputStream, entry: Entry, method: String, range: String?) {
        val isHead = method == "HEAD"
        val reqBuilder = Request.Builder().url(entry.remoteUrl).get()
        authHeader()?.let { reqBuilder.header("Authorization", it) }
        // Everything 400s HEAD requests, so size a HEAD via a 1-byte ranged GET.
        val upstreamRange = if (isHead) range ?: "bytes=0-0" else range
        upstreamRange?.let { reqBuilder.header("Range", it) }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                writeStatus(out, resp.code, resp.message.ifBlank { "Upstream Error" })
                return
            }
            val contentRange = resp.header("Content-Range")
            val totalSize = contentRange?.substringAfter('/')?.toLongOrNull()
            val headers = StringBuilder()
            if (isHead && range == null) {
                // Report the full entity even though we ranged upstream.
                headers.append("HTTP/1.1 200 OK\r\n")
                totalSize?.let { headers.append("Content-Length: $it\r\n") }
            } else {
                val code = if (resp.code == 206) "206 Partial Content" else "200 OK"
                headers.append("HTTP/1.1 $code\r\n")
                resp.header("Content-Length")?.let { headers.append("Content-Length: $it\r\n") }
                contentRange?.let { headers.append("Content-Range: $it\r\n") }
            }
            val mime = resp.header("Content-Type")
                ?.takeIf { !it.startsWith("application/octet-stream") && !it.startsWith("text/html") }
                ?: entry.mime
            headers.append("Content-Type: $mime\r\n")
            headers.append("Accept-Ranges: bytes\r\n")
            headers.append("Connection: close\r\n\r\n")
            out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            if (!isHead) {
                resp.body?.byteStream()?.copyTo(out, bufferSize = 64 * 1024)
            }
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, message: String) {
        val body = "$code $message"
        out.write(
            ("HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n" + body).toByteArray(Charsets.ISO_8859_1)
        )
    }
}
