package com.mickey.everythingdroid.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Localhost HTTP relay that lets external players (VLC, MX Player, Photos…)
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
 *
 * Process-wide singleton, started eagerly from [com.mickey.everythingdroid.EverythingDroidApp]
 * on a fixed port so it outlives any single screen. `GET /log` returns a ring buffer
 * of recent relay events — invaluable when a player errors with no explanation.
 */
object StreamProxy {

    const val PREFERRED_PORT = 42817
    private const val LOG_LINES = 300

    private class Entry(
        val remoteUrl: String,
        val mime: String,
        val client: OkHttpClient,
        val auth: () -> String?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nextId = AtomicInteger(1)
    private val activeConns = AtomicInteger(0)
    @Volatile private var lastActivityMs = 0L
    private var server: ServerSocket? = null
    private val logRing = ArrayDeque<String>()

    /** Bind the listener now (app start) so the port is up before any Play tap. */
    fun warmUp() {
        runCatching { ensureStarted() }
            .onFailure { log("warmUp failed: $it") }
    }

    /** True when no player connection is open and none has been for [idleMs]. */
    fun isIdle(idleMs: Long): Boolean =
        activeConns.get() == 0 && System.currentTimeMillis() - lastActivityMs > idleMs

    /** Register a remote file and return the localhost URL to hand to a player. */
    fun localUrlFor(
        client: OkHttpClient,
        auth: () -> String?,
        remoteUrl: String,
        name: String,
        mime: String,
    ): String {
        val port = ensureStarted()
        val id = nextId.getAndIncrement().toString()
        entries[id] = Entry(remoteUrl, mime, client, auth)
        val safeName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        log("registered #$id -> $remoteUrl")
        return "http://127.0.0.1:$port/$id/$safeName"
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        // Explicit IPv4 loopback: getLoopbackAddress() returns ::1 on some devices
        // (OnePlus/Android 14), while the URLs we hand out say 127.0.0.1 — players
        // then get connection-refused even though the relay is up.
        val lo4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val ss = try {
            ServerSocket(PREFERRED_PORT, 8, lo4)
        } catch (_: Exception) {
            ServerSocket(0, 8, lo4)
        }
        server = ss
        log("listening on 127.0.0.1:${ss.localPort}")
        Thread({
            while (!ss.isClosed) {
                val sock = try {
                    ss.accept()
                } catch (e: Exception) {
                    log("accept loop ended: $e")
                    break
                }
                Thread({ handle(sock) }, "stream-proxy-conn")
                    .apply { isDaemon = true }.start()
            }
        }, "stream-proxy-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun handle(sock: Socket) {
        activeConns.incrementAndGet()
        lastActivityMs = System.currentTimeMillis()
        sock.use { s ->
            s.soTimeout = 30_000
            val out = s.getOutputStream()
            try {
                val (method, path, range) = readRequest(s) ?: run {
                    log("malformed request"); return
                }
                log("$method $path range=$range")
                when {
                    path == "/log" || path == "/health" -> serveLog(out)
                    method != "GET" && method != "HEAD" ->
                        writeStatus(out, 405, "Method Not Allowed")
                    else -> {
                        val id = path.removePrefix("/").substringBefore('/')
                        val entry = entries[id]
                        if (entry == null) {
                            log("no entry for id=$id (${entries.size} registered)")
                            writeStatus(out, 404, "Not Found")
                        } else {
                            serve(out, entry, method, range)
                        }
                    }
                }
            } catch (e: Exception) {
                // Player hung up mid-stream or upstream died.
                log("conn error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { out.flush() }
                activeConns.decrementAndGet()
                lastActivityMs = System.currentTimeMillis()
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
        entry.auth()?.let { reqBuilder.header("Authorization", it) }
        // Everything 400s HEAD requests, so size a HEAD via a 1-byte ranged GET.
        val upstreamRange = if (isHead) range ?: "bytes=0-0" else range
        upstreamRange?.let { reqBuilder.header("Range", it) }

        entry.client.newCall(reqBuilder.build()).execute().use { resp ->
            log("upstream ${resp.code} len=${resp.header("Content-Length")}")
            if (!resp.isSuccessful) {
                writeStatus(out, resp.code, resp.message.ifBlank { "Upstream Error" })
                return
            }
            val contentRange = resp.header("Content-Range")
            val totalSize = contentRange?.substringAfter('/')?.toLongOrNull()
            val upLen = resp.header("Content-Length")?.toLongOrNull()
            val reqStart = range?.trim()
                ?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()

            // VLC probes past EOF and expects 416; Everything answers an empty 206.
            if (!isHead && resp.code == 206 && upLen == 0L && reqStart != null && reqStart > 0) {
                val cr = totalSize?.let { "Content-Range: bytes */$it\r\n" } ?: ""
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" + cr +
                        "Accept-Ranges: bytes\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1)
                )
                log("translated empty 206 -> 416")
                return
            }

            val headers = StringBuilder()
            if (isHead && range == null) {
                // Report the full entity even though we ranged upstream.
                headers.append("HTTP/1.1 200 OK\r\n")
                totalSize?.let { headers.append("Content-Length: $it\r\n") }
            } else if (resp.code == 200 && reqStart == 0L && upLen != null) {
                // Everything answers `Range: bytes=0-` with a plain 200; a player that
                // asked for a range (VLC) treats that as unseekable or errors out.
                headers.append("HTTP/1.1 206 Partial Content\r\n")
                headers.append("Content-Length: $upLen\r\n")
                headers.append("Content-Range: bytes 0-${upLen - 1}/$upLen\r\n")
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
                val sent = resp.body?.byteStream()?.copyTo(out, bufferSize = 64 * 1024) ?: 0
                log("done, $sent bytes relayed")
            }
        }
    }

    private fun serveLog(out: OutputStream) {
        val body = synchronized(logRing) { logRing.joinToString("\n") }
            .ifBlank { "(no events)" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1)
        )
        out.write(bytes)
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

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        synchronized(logRing) {
            logRing.addLast(line)
            while (logRing.size > LOG_LINES) logRing.removeFirst()
        }
    }
}
