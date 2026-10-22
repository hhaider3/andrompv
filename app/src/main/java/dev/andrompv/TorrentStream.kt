package dev.andrompv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.libtorrent4j.*
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

    /** One cached torrent: its metadata (named by source hash) and its downloaded files. */
    data class Download(
        val sourceHash: String,
        val infoHash: String?,
        val name: String,
        val bytes: Long,
        val totalBytes: Long,
        val files: Int
    )

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
    /** The torrent files that are queued; only these are downloaded. Null means every media file. */
    @Volatile private var allowed: Set<Int>? = null
    /** Everything queued is saved: the torrent has left the swarm and files are served from disk. */
    @Volatile private var complete = false
    private var savedProgress: String? = null
    private var token = UUID.randomUUID().toString()
    private var status = "No active torrent"
    private val openSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    /** [queuedIndexes] are the torrent's files in the queue; others are not downloaded. */
    fun open(item: QueueItem, queuedIndexes: Set<Int>? = null, callback: (Result<Opened>) -> Unit) {
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
                    writeProgress()
                    handle?.let { manager.remove(it) }
                    handle = null; info = null; activeSource = item.uri; complete = false; savedProgress = null
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
                    allowed = queuedIndexes
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
    private fun isMedia(torrent: TorrentInfo, index: Int) =
        MediaSupport.extension(torrent.files().filePath(index)) in MediaSupport.extensions

    /** Media files that are queued, and so should be downloaded. */
    private fun wantedFiles(torrent: TorrentInfo): List<Int> =
        (0 until torrent.numFiles()).filter { isMedia(torrent, it) && allowed?.contains(it) != false }

    /** Bytes of each file held in verified pieces: a file counts as done only when libtorrent has checked every piece of it. */
    private fun verifiedProgress(current: TorrentHandle) = current.fileProgress(TorrentHandle.PIECE_GRANULARITY)

    private fun prioritize(index: Int) {
        val torrent = info ?: return; val current = handle ?: return
        val files = torrent.files()
        val wanted = wantedFiles(torrent)
        val progress = verifiedProgress(current)
        fun missing(file: Int) = progress.getOrElse(file) { 0L } < files.fileSize(file)
        // The selected file monopolizes downloads until complete; then one remaining queued
        // media file at a time is downloaded in natural order, reusing the session.
        val target = if (index in wanted && missing(index)) index
            else wanted.sortedWith { a, b -> naturalOrder.compare(files.filePath(a), files.filePath(b)) }.firstOrNull(::missing)
        current.clearPieceDeadlines()
        if (target == null) {
            leaveSwarm(current)
            return
        }
        complete = false
        current.prioritizeFiles(Array(torrent.numFiles()) { if (it == target) Priority.TOP_PRIORITY else Priority.IGNORE })
        current.unsetFlags(TorrentFlags.AUTO_MANAGED)
        current.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        current.resume()
    }

    /**
     * Every queued file is saved: stop sharing. A paused torrent makes no connections, so nothing is
     * uploaded; files keep being served from disk, and a stream that was waiting on pieces simply
     * continues from the files.
     */
    private fun leaveSwarm(current: TorrentHandle) {
        if (complete) return
        complete = true
        runCatching { current.pause() }
        status = "Playing from local storage"
        android.util.Log.i("andrompv-torrent", "All queued files saved; left the swarm")
    }

    /** Stops downloading files that are no longer queued; the torrent leaves the swarm if nothing queued remains. */
    fun restrict(source: String, queuedIndexes: Set<Int>) {
        if (activeSource != source) return
        allowed = queuedIndexes
        worker.execute { runCatching { selected?.torrentIndex?.let(::prioritize) } }
    }

    /** Saves each media file's progress so the queue can show it, also while the torrent is not running. */
    private fun writeProgress() {
        val torrent = info ?: return; val current = handle ?: return; val source = activeSource ?: return
        if (!current.isValid) return
        val files = torrent.files()
        val progress = verifiedProgress(current)
        val snapshot = TorrentProgress(
            torrent.infoHash().toHex(),
            (0 until torrent.numFiles()).filter { isMedia(torrent, it) }.associateWith { index ->
                val size = files.fileSize(index)
                val bytes = minOf(progress.getOrElse(index) { 0L }, size)
                TorrentFileProgress(bytes, size > 0 && bytes >= size)
            }
        )
        val json = snapshot.toJson()
        if (json == savedProgress) return
        runCatching { TorrentStorage.writeProgress(directory, sourceHash(source), snapshot) }
            .onSuccess { savedProgress = json }
            .onFailure { android.util.Log.w("andrompv-torrent", "Could not save progress", it) }
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
                        val current = handle
                        val torrent = info
                        if (current?.isValid == true && torrent != null && session?.isPaused == false) {
                            writeProgress()
                            selected?.torrentIndex?.let { index ->
                                val progress = verifiedProgress(current)
                                val queued = wantedFiles(torrent)
                                val saved = { file: Int -> progress.getOrElse(file) { 0L } >= torrent.files().fileSize(file) }
                                // The selected file finished (move on to the next queued one) or everything queued is saved.
                                if (!complete && (saved(index) || queued.all(saved))) prioritize(index)
                            }
                        }
                        Thread.sleep(1000)
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
        // Saved files are served straight from disk once the torrent has left the swarm.
        if (complete) return true
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
        worker.execute { runCatching { writeProgress() } }
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
        if (complete && selected != null) return "Playing from local storage"
        if (bufferFailed || current?.isValid != true) return status
        val state = current.status()
        val index = selected?.torrentIndex
        val fileProgress = if (index != null) {
            val size = info?.files()?.fileSize(index) ?: 0L
            if (size > 0) (current.fileProgress().getOrElse(index) { 0L } * 100 / size).toInt() else null
        } else null
        val percent = fileProgress ?: (state.progress() * 100).toInt()
        val peers = state.numPeers()
        return "$percent% · ${formatRate(state.downloadRate().toLong())} · $peers ${if (peers == 1) "peer" else "peers"}"
    }

    fun downloads(): List<Download> {
        val listed = mutableSetOf<String>()
        val result = mutableListOf<Download>()
        directory.listFiles { file -> file.isFile && file.extension == "torrent" }?.forEach { metadata ->
            val torrent = runCatching { TorrentInfo(metadata) }.getOrNull()?.takeIf { it.isValid } ?: return@forEach
            val hash = torrent.infoHash().toHex()
            listed += hash
            val folder = File(directory, hash)
            result += Download(
                sourceHash = metadata.nameWithoutExtension,
                infoHash = hash,
                name = torrent.name(),
                bytes = folder.walkTopDown().filter(File::isFile).sumOf(File::length),
                totalBytes = torrent.totalSize(),
                files = torrent.numFiles()
            )
        }
        // Folders whose metadata was lost still occupy space and can be removed.
        directory.listFiles { file -> file.isDirectory && file.name !in listed }?.forEach { folder ->
            result += Download(
                sourceHash = "",
                infoHash = folder.name,
                name = folder.name,
                bytes = folder.walkTopDown().filter(File::isFile).sumOf(File::length),
                totalBytes = 0,
                files = folder.walkTopDown().count(File::isFile)
            )
        }
        return result.sortedByDescending(Download::bytes)
    }

    fun deleteDownload(download: Download) {
        val hash = download.infoHash
        if (hash != null) {
            val active = info?.infoHash()?.toHex() == hash
            session?.find(Sha1Hash.parseHex(hash))?.let { session?.remove(it) }
            if (active) {
                generation.incrementAndGet()
                handle = null; info = null; activeSource = null; selected = null
                closeSockets()
            }
            val folder = File(directory, hash)
            check(!folder.exists() || folder.deleteRecursively()) { "Could not delete torrent files" }
        }
        if (download.sourceHash.isNotEmpty()) {
            File(directory, "${download.sourceHash}.torrent").delete()
            TorrentStorage.progressFile(directory, download.sourceHash).delete()
        }
    }
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
            TorrentStorage.progressFile(directory, sourceHash(item.uri)).delete()
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
        fun sourceHash(source: String) = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
