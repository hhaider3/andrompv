package dev.andrompv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.libtorrent4j.*
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TorrentStream(private val context: Context, private val configureSession: (SettingsPack) -> Unit = {}) {
    data class Opened(val items: List<QueueItem>, val url: String? = null)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val requests = Executors.newFixedThreadPool(4)
    private val generation = AtomicInteger()
    private val directory = File(context.filesDir, "Torrents").apply { mkdirs() }
    @Volatile private var session: SessionManager? = null
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var info: TorrentInfo? = null
    @Volatile private var root: File? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeSource: String? = null
    @Volatile private var selected: QueueItem? = null
    @Volatile private var closed = false
    @Volatile private var backgroundSuspended = false
    @Volatile private var bufferFailed = false
    private var token = UUID.randomUUID().toString()
    private var status = "No active torrent"
    private val openSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    fun open(item: QueueItem, callback: (Result<Opened>) -> Unit) {
        val request = generation.incrementAndGet()
        selected = null
        closeSockets()
        worker.execute {
            val result = runCatching {
                check(!closed) { "Torrent session closed" }
                var manager = session
                if (manager == null) {
                    manager = SessionManager()
                    manager.addListener(object : AlertListener {
                        override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.URL_SEED.swig(), org.libtorrent4j.alerts.AlertType.TORRENT_ERROR.swig())
                        override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
                            status = alert.message()
                            android.util.Log.w("andrompv-torrent", status)
                        }
                    })
                    val settings = SettingsPack().connectionsLimit(80).activeDownloads(1).activeSeeds(0)
                    configureSession(settings)
                    manager.start(SessionParams(settings))
                    session = manager
                }
                manager.resume()
                if (activeSource != item.uri || handle?.isValid != true) {
                    handle?.let { manager.remove(it) }
                    handle = null; info = null; activeSource = item.uri
                    val metadata = File(directory, "${sourceHash(item.uri)}.torrent")
                    var data = readCachedMetadata(metadata)
                    if (data == null) {
                        val uri = Uri.parse(item.uri)
                        if (uri.scheme == "magnet") {
                            status = "Resolving magnet metadata (up to 60 seconds)…"
                            data = manager.fetchMagnet(item.uri, 60, context.cacheDir)
                                ?: error("Magnet metadata timed out. Check peers and try again.")
                        } else {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(8 * 1024 * 1024) }
                                ?: error("Cannot read torrent source")
                            if (MediaSupport.extension(item.title) == "magnet") {
                                val magnet = bytes.toString(Charsets.UTF_8).trim()
                                require(MediaSupport.isNetwork(magnet) && magnet.startsWith("magnet:", true)) { "The .magnet file must contain a valid magnet URI" }
                                data = manager.fetchMagnet(magnet, 60, context.cacheDir)
                                    ?: error("Magnet metadata timed out")
                            } else data = bytes
                        }
                        check(request == generation.get() && !closed) { "Torrent request canceled" }
                        val fetched = data ?: error("Invalid torrent metadata")
                        require(isValidTorrent(fetched)) { "Invalid torrent metadata" }
                        metadata.writeBytes(fetched)
                        data = fetched
                    }
                    val payload = data ?: error("Invalid torrent metadata")
                    val torrent = TorrentInfo(payload)
                    require(torrent.isValid) { "Invalid torrent metadata" }
                    val downloadRoot = File(directory, torrent.infoHash().toHex()).apply { mkdirs() }
                    // Validate every path, not just playable paths, before libtorrent writes.
                    for (i in 0 until torrent.numFiles()) safeFile(downloadRoot, torrent.files().filePath(i))
                    root = downloadRoot; info = torrent
                    // libtorrent 2.x keeps trackers/webseeds in add_torrent_params,
                    // not TorrentInfo. Loading only the info dict loses connectivity.
                    val params = AddTorrentParams(org.libtorrent4j.swig.libtorrent.load_torrent_file(metadata.absolutePath))
                    params.savePath = downloadRoot.absolutePath
                    params.filePriorities(Array(torrent.numFiles()) { Priority.IGNORE })
                    params.flags = params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
                    SessionHandle(manager.swig()).asyncAddTorrent(params)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var current: TorrentHandle? = null
                    while (current == null && System.nanoTime() < deadline && request == generation.get() && !closed) {
                        current = manager.find(torrent.infoHash())
                        if (current == null) Thread.sleep(50)
                    }
                    handle = current ?: error("Could not start torrent download")
                }
                check(request == generation.get() && !closed) { "Torrent request canceled" }
                val torrent = info ?: error("No torrent metadata")
                val files = torrent.files()
                val items = (0 until files.numFiles()).filter { MediaSupport.extension(files.filePath(it)) in MediaSupport.extensions && files.fileSize(it) > 0 }
                    .sortedWith { a, b -> naturalOrder.compare(files.filePath(a), files.filePath(b)) }
                    .map { index -> QueueItem(item.uri, File(files.filePath(index)).name, group = item.group,
                        groupTitle = item.groupTitle, torrentIndex = index, torrentPath = files.filePath(index), length = files.fileSize(index)) }
                require(items.isNotEmpty()) { "This torrent contains no supported media" }
                if (item.torrentIndex == null) Opened(items)
                else {
                    val actual = items.firstOrNull { it.torrentIndex == item.torrentIndex } ?: error("The selected file is no longer in this torrent")
                    selected = actual
                    bufferFailed = false
                    token = UUID.randomUUID().toString()
                    prioritize(actual.torrentIndex!!)
                    ensureServer()
                    status = "Buffering ${actual.title}…"
                    Opened(items, "http://127.0.0.1:${server!!.localPort}/$token/${actual.torrentIndex}")
                }
            }
            main.post { if (!closed && request == generation.get()) callback(result) }
        }
    }
    private fun prioritize(index: Int) {
        val torrent = info ?: return; val current = handle ?: return
        val media = (0 until torrent.numFiles()).filter { MediaSupport.extension(torrent.files().filePath(it)) in MediaSupport.extensions }
        val progress = current.fileProgress()
        // The selected file monopolizes downloads until complete; then one remaining
        // media file at a time is downloaded in natural order, reusing the session.
        val target = if (progress.getOrElse(index) { 0L } < torrent.files().fileSize(index)) index
            else media.sortedWith { a, b -> naturalOrder.compare(torrent.files().filePath(a), torrent.files().filePath(b)) }
                .firstOrNull { progress.getOrElse(it) { 0L } < torrent.files().fileSize(it) }
        current.clearPieceDeadlines()
        current.prioritizeFiles(Array(torrent.numFiles()) { if (it == target) Priority.TOP_PRIORITY else Priority.IGNORE })
        current.unsetFlags(TorrentFlags.AUTO_MANAGED)
        current.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        current.resume()
    }
    private fun ensureServer() {
        if (server != null) return
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = listener
        Thread({
            while (!closed && !listener.isClosed) {
                try {
                    val socket = listener.accept()
                    openSockets.add(socket)
                    requests.execute { serve(socket) }
                } catch (e: Exception) {
                    if (!closed && !listener.isClosed) android.util.Log.e("andrompv-torrent", "HTTP listener failed", e)
                }
            }
        }, "andrompv-torrent-http").apply { isDaemon = true; start() }
        worker.execute {
            // A monitor runs separately so torrent operations never queue behind it.
            Thread({
                while (!closed) {
                    try {
                        selected?.torrentIndex?.let { index ->
                            val current = handle
                            val torrent = info
                            if (current?.isValid == true && torrent != null && session?.isPaused == false) {
                                val progress = current.fileProgress()
                                if (progress.getOrElse(index) { 0L } >= torrent.files().fileSize(index)) prioritize(index)
                            }
                        }
                        Thread.sleep(1500)
                    } catch (e: InterruptedException) { return@Thread }
                    catch (e: Exception) { android.util.Log.e("andrompv-torrent", "Download monitoring failed", e) }
                }
            }, "andrompv-torrent-priority").apply { isDaemon = true; start() }
        }
    }
    private fun serve(socket: Socket) {
        val request = generation.get()
        try {
            socket.use {
                socket.soTimeout = 10_000
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = input.readLine() ?: return
                val parts = first.split(' ')
                if (parts.size < 3 || parts[0] !in setOf("GET", "HEAD")) { response(socket, 405, "Method Not Allowed", emptyMap()); return }
                var rangeHeader: String? = null
                var bytes = first.length
                while (true) {
                    val line = input.readLine() ?: return
                    bytes += line.length
                    if (bytes > 8192) { response(socket, 431, "Request Header Fields Too Large", emptyMap()); return }
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
                }
                val item = selected
                if (item == null || parts[1] != "/$token/${item.torrentIndex}") { response(socket, 404, "Not Found", emptyMap()); return }
                val range = ByteRange.parse(rangeHeader, item.length)
                if (range == null) { response(socket, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${item.length}")); return }
                val headers = mutableMapOf("Content-Type" to "application/octet-stream", "Accept-Ranges" to "bytes", "Content-Length" to range.length.toString())
                if (rangeHeader != null) headers["Content-Range"] = "bytes ${range.start}-${range.end}/${item.length}"
                if (parts[0] == "HEAD") { response(socket, if (rangeHeader == null) 200 else 206, "OK", headers); return }
                val torrent = info
                val current = handle
                val base = root
                val path = item.torrentPath
                if (torrent == null || current == null || base == null || path == null) {
                    response(socket, 503, "Service Unavailable", mapOf("Retry-After" to "2"))
                    return
                }
                val file = safeFile(base, path)
                val offset = torrent.files().fileOffset(item.torrentIndex!!)
                // Wait BEFORE emitting a success response. Holes in a sparse file
                // must never be presented as successfully downloaded media bytes.
                if (!waitPiece(current, torrent, offset + range.start, request)) {
                    response(socket, 503, "Service Unavailable", mapOf("Retry-After" to "2")); return
                }
                response(socket, if (rangeHeader == null) 200 else 206, "OK", headers)
                RandomAccessFile(file, "r").use { reader ->
                    reader.seek(range.start)
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var position = range.start
                    while (position <= range.end && request == generation.get() && !closed) {
                        if (!waitPiece(current, torrent, offset + position, request)) return
                        val pieceRemaining = torrent.pieceLength() - ((offset + position) % torrent.pieceLength())
                        val count = minOf(buffer.size.toLong(), range.end - position + 1, pieceRemaining).toInt()
                        reader.readFully(buffer, 0, count)
                        output.write(buffer, 0, count)
                        position += count
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // Client disconnects after a seek are normal; genuine timeouts are visible.
            if (!closed && e !is java.net.SocketException) android.util.Log.w("andrompv-torrent", "Stream request ended: ${e.message}")
        } finally { openSockets.remove(socket); runCatching { socket.close() } }
    }
    private fun waitPiece(current: TorrentHandle, torrent: TorrentInfo, offset: Long, request: Int): Boolean {
        val piece = (offset / torrent.pieceLength()).toInt()
        if (current.havePiece(piece)) {
            bufferFailed = false
            return true
        }
        for (i in piece until minOf(piece + 8, torrent.numPieces())) current.setPieceDeadline(i, (i - piece) * 250)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline && request == generation.get() && !closed && current.isValid) {
            if (current.havePiece(piece)) {
                bufferFailed = false
                return true
            }
            Thread.sleep(50)
        }
        val state = current.status()
        bufferFailed = true
        status = "Torrent buffering timed out (${state.state()}, ${state.progress()}, ${state.errorCode().message}); select the file to retry"
        android.util.Log.w("andrompv-torrent", status)
        return false
    }
    private fun response(socket: Socket, code: Int, reason: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if ("Content-Length" !in headers) append("Content-Length: 0\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
    }
    fun pause() {
        generation.incrementAndGet(); selected = null; closeSockets()
        session?.pause()
    }

    /** Backgrounding must not leave Play pointed at a loopback URL that 404s. */
    fun suspendForBackground() {
        backgroundSuspended = true
        pause()
    }

    fun isBackgroundSuspended(): Boolean = backgroundSuspended

    fun clearBackgroundSuspend() {
        backgroundSuspended = false
    }

    fun statusLabel(): String {
        val current = handle
        if (bufferFailed || current?.isValid != true) return status
        val state = current.status()
        return "Torrent ${(state.progress() * 100).toInt()}% · ${state.downloadRate() / 1024} KB/s · ${state.numPeers()} peers"
    }
    fun downloadSummary(): String = directory.walkTopDown().filter(File::isFile)
        .map { "${it.relativeTo(directory).path} (${it.length() / 1024} KB)" }.take(50).joinToString("\n").ifEmpty { "No downloaded files yet." }
    fun deleteDownload(item: QueueItem) {
        val metadata = File(directory, "${sourceHash(item.uri)}.torrent")
        require(metadata.exists()) { "No cached metadata for this download" }
        val torrent = TorrentInfo(metadata)
        require(torrent.isValid) { "Invalid torrent metadata" }
        val targetRoot = File(directory, torrent.infoHash().toHex())
        if (item.torrentPath == null) {
            session?.find(torrent.infoHash())?.let { session?.remove(it) }
            check(!targetRoot.exists() || targetRoot.deleteRecursively()) { "Could not delete torrent files" }
            check(metadata.delete()) { "Could not delete cached torrent metadata" }
            if (activeSource == item.uri) {
                handle = null
                info = null
                activeSource = null
                selected = null
            }
        } else {
            val file = safeFile(targetRoot, item.torrentPath)
            check(!file.exists() || file.delete()) { "Could not delete ${item.title}" }
            val current = if (activeSource == item.uri && handle?.isValid == true) handle else session?.find(torrent.infoHash())
            if (current?.isValid == true && item.torrentIndex != null) current.filePriority(item.torrentIndex, Priority.IGNORE)
            if (activeSource == item.uri && selected?.torrentIndex == item.torrentIndex) {
                selected = null
                closeSockets()
            }
        }
    }
    private fun closeSockets() { synchronized(openSockets) { openSockets.forEach { runCatching { it.close() } }; openSockets.clear() } }
    fun close() {
        closed = true; generation.incrementAndGet(); closeSockets(); server?.close(); requests.shutdownNow()
        worker.execute { session?.stop(); session = null }
        worker.shutdown()
    }
    companion object {
        private fun isValidTorrent(bytes: ByteArray) = runCatching { TorrentInfo(bytes).isValid }.getOrDefault(false)

        private fun readCachedMetadata(metadata: File): ByteArray? {
            if (!metadata.isFile) return null
            val bytes = runCatching { metadata.readBytes() }.getOrNull() ?: return null
            if (isValidTorrent(bytes)) return bytes
            metadata.delete()
            return null
        }

        fun safeFile(root: File, path: String): File {
            require(path.isNotBlank() && !File(path).isAbsolute) { "Unsafe torrent file path" }
            val file = File(root, path).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator)) { "Torrent path escapes its download directory" }
            return file
        }
        private fun sourceHash(source: String) = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
