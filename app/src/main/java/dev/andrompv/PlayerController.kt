package dev.andrompv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Surface
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.abs
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** A short-lived message with an optional action, shown as a pill above the controls. */
data class Notice(
    val message: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val id: Long = SystemClock.uptimeMillis()
)

/**
 * The Android counterpart of macmpv's PlayerModel. It is application-scoped so the
 * activity, picture-in-picture and the media session all drive one player. libmpv
 * itself is still created by the first video surface. Compose reads the snapshot
 * state below; everything here runs on the main looper.
 */
class PlayerController(context: Context) {
    private val app = context.applicationContext
    val engine = MPVEngine(app)
    val queue = PlaybackQueue()
    val store = PlayerStore(app)
    val torrents = TorrentStream(app)
    val previews = SeekPreview(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    var position by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var bufferedAhead by mutableDoubleStateOf(0.0); private set
    var isPaused by mutableStateOf(true); private set
    var isLoading by mutableStateOf(false); private set
    var hasFile by mutableStateOf(false); private set
    private var volumeState by mutableDoubleStateOf(store.volume)
    val volume: Double get() = volumeState
    var isMuted by mutableStateOf(false); private set
    private var speedState by mutableDoubleStateOf(1.0)
    val speed: Double get() = speedState
    var isHoldingSpeed by mutableStateOf(false); private set
    var repeat by mutableStateOf(RepeatMode.OFF); private set
    var items by mutableStateOf<List<QueueItem>>(emptyList()); private set
    var currentId by mutableStateOf<String?>(null); private set
    var collapsedGroups by mutableStateOf<Set<String>>(emptySet()); private set
    var audioTracks by mutableStateOf<List<TrackInfo>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<TrackInfo>>(emptyList()); private set
    var subtitleStyle by mutableStateOf(store.style); private set
    var settings by mutableStateOf(store.settings); private set
    var metadata by mutableStateOf<MediaMetadata?>(null); private set
    var videoAspect by mutableStateOf<Double?>(null); private set
    var torrentStatus by mutableStateOf<String?>(null); private set
    private var videoFitState by mutableStateOf(VideoFit.FIT)
    val videoFit: VideoFit get() = videoFitState
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<Notice?>(null); private set
    var queueVersion by mutableLongStateOf(0L); private set
    private var sidebarState by mutableStateOf(store.sidebarVisible)
    val isSidebarVisible: Boolean get() = sidebarState

    val current: QueueItem? get() = items.firstOrNull { it.id == currentId }
    val hasMedia: Boolean get() = currentId != null
    val isPlaying: Boolean get() = hasFile && !isPaused
    val canGoNext: Boolean
        get() {
            val index = items.indexOfFirst { it.id == currentId }
            return index >= 0 && (index < items.lastIndex || repeat == RepeatMode.ALL)
        }
    val canGoPrevious: Boolean
        get() {
            val index = items.indexOfFirst { it.id == currentId }
            return index >= 0 && (index > 0 || repeat == RepeatMode.ALL || position > 3.0)
        }
    /** The URI mpv actually opened: a document, a stream, or the loopback torrent endpoint. */
    var resolvedSource by mutableStateOf<String?>(null); private set

    private var uiVisible = false
    private var surfaceAttached = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var controlsInset = 0
    private var pendingPlay: Pair<QueueItem, Boolean>? = null
    private var requestGeneration = 0
    private var lastSaved = -1.0
    private var lastSync = 0L
    private var syncPosted = false
    private var scrubbing = false
    private var wasPlayingBeforeScrub = false
    private var lastPreviewSeek = 0L
    private var pendingPreviewSeek: Double? = null
    private var trackSignature: String? = null
    private var sessionSignature: List<Any?>? = null
    private var serviceStarted = false
    private val sessionListeners = CopyOnWriteArraySet<() -> Unit>()

    private val ticker = object : Runnable {
        override fun run() {
            sync()
            // Only a torrent that play() started has a status; a restored one waits for Play.
            if (current?.isTorrent == true && torrentStatus != null) torrentStatus = torrents.statusLabel()
            if (uiVisible || isPlaying) main.postDelayed(this, 1000)
        }
    }
    private val flushPreviewSeek = Runnable {
        pendingPreviewSeek?.let { target ->
            pendingPreviewSeek = null
            lastPreviewSeek = SystemClock.uptimeMillis()
            engine.seek(target, exact = false)
        }
    }

    init {
        store.restore(queue)
        repeat = queue.repeat
        engine.hardwareDecoding = settings.hardwareDecoding
        engine.fastRendering = !settings.highQualityScaling
        publishQueue()
        io.execute { clearCachedSubtitles() }

        engine.onLoaded = {
            engine.set("speed", speed)
            engine.applySubtitles(subtitleStyle)
            applyVolume()
            engine.setVideoFit(videoFit)
            current?.let { item ->
                engine.currentMediaMetadata()?.let { meta ->
                    queue.updateMetadata(item.id, meta)
                    store.save(queue)
                    publishQueue()
                }
            }
            sync()
            updateClearance()
        }
        engine.onChanged = { requestSync() }
        engine.onFocusRestored = { applyVolume() }
        engine.onEof = {
            queue.current?.let(store::finished)
            val next = queue.next(automatic = true)
            if (next != null) play(next, resume = false) else sync()
        }
        engine.onError = { showError(it) }
        probeUnprobedItems()
    }

    // region Lifecycle and surface

    fun onUiShown() {
        uiVisible = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        flushPendingPlay()
        if (hasMedia) ensureService()
    }

    /** Leaving the app either keeps audio playing (when enabled) or pauses like macmpv. */
    fun onUiHidden() {
        uiVisible = false
        remember()
        store.save(queue)
        if (settings.backgroundPlayback && isPlaying) {
            engine.disableVideo()
        } else {
            if (engine.ready) engine.pause(true)
            if (queue.current?.isTorrent == true) torrents.suspendForBackground() else torrents.pause()
            stopService()
        }
        sync()
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        safely {
            engine.hardwareDecoding = settings.hardwareDecoding
            engine.fastRendering = !settings.highQualityScaling
            engine.attach(surface, width, height)
            surfaceAttached = true
            engine.applySubtitles(subtitleStyle)
            flushPendingPlay()
        }
    }

    fun surfaceResized(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        engine.updateSurfaceSize(width, height)
        updateClearance()
        // mpv does not redraw a paused frame into a resized surface (after rotating);
        // an exact zero-length seek re-presents it.
        main.removeCallbacks(redrawPaused)
        if (engine.hasFile && engine.paused && !scrubbing) main.postDelayed(redrawPaused, 120)
    }

    private val redrawPaused = Runnable {
        if (engine.hasFile && engine.paused && !scrubbing) engine.command("seek", "0", "relative+exact")
    }

    fun detachSurface() {
        surfaceAttached = false
        safely { engine.detach() }
    }

    /** Height (px) the visible controls cover at the bottom of the video, or 0 while hidden. */
    fun setControlsInset(pixels: Int) {
        if (pixels == controlsInset) return
        controlsInset = pixels
        updateClearance()
    }

    private fun updateClearance() {
        if (!surfaceAttached || surfaceWidth <= 0 || surfaceHeight <= 0) return
        safely { engine.subtitleClearance(surfaceWidth, surfaceHeight, controlsInset) }
    }

    // A queued attach precedes any load on mpv's single worker, so an attached surface is enough.
    private fun canStartPlayback() =
        surfaceAttached || (engine.ready && !uiVisible && settings.backgroundPlayback)

    private fun flushPendingPlay() {
        val (item, resume) = pendingPlay ?: return
        if (!canStartPlayback()) return
        if (queue.items.none { it.id == item.id }) {
            pendingPlay = null
            return
        }
        play(item, resume)
    }

    fun addSessionListener(listener: () -> Unit) { sessionListeners.add(listener) }
    fun removeSessionListener(listener: () -> Unit) { sessionListeners.remove(listener) }

    private fun ensureService() {
        if (serviceStarted) return
        serviceStarted = runCatching { app.startService(Intent(app, PlaybackService::class.java)) }.isSuccess
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    // endregion

    // region State publishing

    private fun requestSync() {
        if (engine.paused != isPaused || engine.loading != isLoading || engine.hasFile != hasFile) {
            sync()
            return
        }
        if (syncPosted) return
        // Position events arrive every frame; four UI updates a second are enough (as in macmpv).
        val wait = (lastSync + 250 - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (wait == 0L) sync() else {
            syncPosted = true
            main.postDelayed({ syncPosted = false; sync() }, wait)
        }
    }

    fun sync() {
        lastSync = SystemClock.uptimeMillis()
        isPaused = engine.paused
        isLoading = engine.loading
        hasFile = engine.hasFile
        if (!scrubbing) position = engine.position
        duration = engine.duration
        bufferedAhead = engine.cacheTime
        refreshTracks()
        refreshMetadata()
        val item = queue.current
        if (item != null && engine.hasFile && !scrubbing && abs(engine.position - lastSaved) >= 1.0) {
            if (settings.rememberPosition) store.remember(item, engine.position, engine.duration)
            lastSaved = engine.position
        }
        notifySessionIfChanged()
    }

    private fun refreshTracks() {
        val signature = "${engine.get("track-list")}|${engine.get("aid")}|${engine.get("sid")}"
        if (signature == trackSignature) return
        trackSignature = signature
        audioTracks = engine.trackDetails("audio")
        subtitleTracks = engine.trackDetails("sub")
    }

    private fun refreshMetadata() {
        val item = queue.current
        val live = engine.currentMediaMetadata()
        if (item != null && live != null && item.metadata?.width == null && (live.width != null || live.videoCodec != null)) {
            queue.updateMetadata(item.id, live)
            publishQueue()
        }
        metadata = if (item == null) null else live ?: item.metadata
        val w = engine.get("video-params/w")?.toDoubleOrNull()
        val h = engine.get("video-params/h")?.toDoubleOrNull()
        videoAspect = engine.get("video-params/aspect")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: if (w != null && h != null && w > 0 && h > 0) w / h else null
    }

    private fun publishQueue() {
        items = queue.items.toList()
        currentId = queue.currentId
        repeat = queue.repeat
        collapsedGroups = queue.collapsedGroups.toSet()
        queueVersion++
        notifySessionIfChanged()
    }

    private fun notifySessionIfChanged() {
        val signature = listOf(
            currentId, isPaused, isLoading, hasFile, duration.toLong(), speed, repeat, queueVersion,
            settings.seekStep
        )
        if (signature == sessionSignature) return
        sessionSignature = signature
        // The media session mirrors the player; its failures must never break playback itself.
        sessionListeners.forEach { listener ->
            runCatching(listener).onFailure { android.util.Log.w("andrompv", "Media session update failed", it) }
        }
    }

    fun dismissError() { error = null }
    fun dismissNotice() { notice = null }

    fun showError(message: String) { error = message }

    fun showNotice(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        notice = Notice(message, actionLabel, action)
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            showError(e.message ?: "Operation failed")
        } catch (e: LinkageError) {
            showError("Native library could not load: ${e.message}")
        }
    }

    // endregion

    // region Playback

    fun play(item: QueueItem, resume: Boolean = true) {
        if (!canStartPlayback()) {
            pendingPlay = item to resume
            queue.currentId = item.id
            publishQueue()
            return
        }
        torrents.clearBackgroundSuspend()
        remember()
        requestGeneration++
        val token = requestGeneration
        previews.cancel()
        resolvedSource = null
        lastSaved = -1.0
        trackSignature = null
        dismissError()
        queue.currentId = item.id
        pendingPlay = null
        torrentStatus = null
        if (item.isTorrent) {
            engine.stop()
            torrentStatus = if (item.torrentIndex == null) "Reading torrent contents…" else "Connecting to peers…"
            torrents.open(item) { result ->
                if (token != requestGeneration || queue.currentId != item.id) return@open
                result.fold(
                    onSuccess = { opened ->
                        if (item.torrentIndex == null) {
                            if (!queue.replace(item.id, opened.items)) return@fold
                            store.save(queue)
                            publishQueue()
                            // Reopening a torrent that is already queued keeps the existing entries,
                            // so play the queue's own copy rather than the freshly resolved object.
                            opened.items.firstNotNullOfOrNull { resolved -> queue.items.firstOrNull { it.key == resolved.key } }
                                ?.let { play(it, resume) }
                        } else {
                            torrentStatus = torrents.statusLabel()
                            loadSource(item, opened.url ?: error("No torrent stream"), resume)
                        }
                    },
                    onFailure = {
                        torrentStatus = null
                        showError(it.message ?: "Torrent playback failed")
                    }
                )
            }
        } else {
            torrents.pause()
            loadSource(item, item.uri, resume)
        }
        publishQueue()
        store.save(queue)
        sync()
        ensureService()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun loadSource(item: QueueItem, source: String, resume: Boolean) {
        safely {
            resolvedSource = source
            val start = if (resume && settings.rememberPosition) store.resume(item) else 0.0
            engine.load(source, start)
            if (start > 0) {
                showNotice("Resumed from ${playbackTime(start)}", "Start over") { seek(0.0) }
            }
        }
    }

    private fun remember() {
        if (!settings.rememberPosition) return
        queue.current?.takeIf { engine.hasFile }?.let {
            store.remember(it, engine.position, engine.duration)
        }
    }

    fun togglePlayback() = setPlaying(isPaused)

    fun setPlaying(playing: Boolean) {
        val current = queue.current
        if (!playing) {
            if (engine.hasFile || engine.loading) engine.pause(true)
            sync()
            return
        }
        if (current == null) {
            queue.items.firstOrNull()?.let { play(it) }
            return
        }
        if (current.isTorrent && (torrents.isBackgroundSuspended() || !engine.hasFile) && !engine.loading) {
            play(current)
            return
        }
        // mpv keeps the last frame open at the end; Play there starts the file over.
        if (engine.hasFile && engine.duration > 0 && engine.position >= engine.duration - 0.5) engine.seek(0.0)
        if (!engine.hasFile && !engine.loading) play(current) else engine.pause(false)
        sync()
    }

    fun seek(seconds: Double) {
        if (!engine.hasFile) return
        val target = if (duration > 0) seconds.coerceIn(0.0, duration) else seconds.coerceAtLeast(0.0)
        engine.seek(target)
        position = target
        sync()
    }

    fun seekBy(seconds: Double) = seek(engine.position + seconds)

    fun beginScrub() {
        if (!engine.hasFile) return
        scrubbing = true
        wasPlayingBeforeScrub = !engine.paused
        if (wasPlayingBeforeScrub) engine.pause(true, abandonFocus = false)
    }

    /** Fast keyframe seeks while dragging, throttled to ~180 ms like macmpv's previewSeek. */
    fun scrub(seconds: Double) {
        if (!scrubbing) return
        val target = if (duration > 0) seconds.coerceIn(0.0, duration) else seconds.coerceAtLeast(0.0)
        position = target
        pendingPreviewSeek = target
        main.removeCallbacks(flushPreviewSeek)
        val wait = (lastPreviewSeek + 180 - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (wait == 0L) flushPreviewSeek.run() else main.postDelayed(flushPreviewSeek, wait)
    }

    fun endScrub(seconds: Double) {
        if (!scrubbing) return
        main.removeCallbacks(flushPreviewSeek)
        pendingPreviewSeek = null
        scrubbing = false
        seek(seconds)
        if (wasPlayingBeforeScrub) engine.pause(false)
        wasPlayingBeforeScrub = false
        sync()
    }

    fun next() {
        queue.next()?.let { play(it) }
    }

    fun previous() {
        if (engine.position > 3) seek(0.0)
        else queue.previous(engine.position)?.let { play(it) }
    }

    fun setVolume(value: Double) {
        volumeState = value.coerceIn(0.0, 100.0)
        isMuted = false
        store.volume = volume
        applyVolume()
    }

    fun adjustVolume(delta: Double) = setVolume(volume + delta)

    fun toggleMuted() {
        isMuted = !isMuted
        applyVolume()
    }

    private fun applyVolume() = engine.set("volume", if (isMuted) 0.0 else volume)

    fun setSpeed(value: Double) {
        speedState = value.coerceIn(0.25, 4.0)
        if (!isHoldingSpeed) engine.set("speed", speed)
        notifySessionIfChanged()
    }

    fun adjustSpeed(delta: Double) = setSpeed(((speed + delta) * 100).toLong() / 100.0)

    /** Touch-and-hold fast playback; the chosen speed returns on release. */
    fun beginHoldSpeed() {
        if (!isPlaying || isHoldingSpeed) return
        isHoldingSpeed = true
        engine.set("speed", (speed * 2).coerceAtMost(4.0))
    }

    fun endHoldSpeed() {
        if (!isHoldingSpeed) return
        isHoldingSpeed = false
        engine.set("speed", speed)
    }

    fun cycleRepeat() {
        queue.repeat = queue.repeat.next()
        store.save(queue)
        publishQueue()
    }

    fun setVideoFit(fit: VideoFit) {
        videoFitState = fit
        engine.setVideoFit(fit)
    }

    fun selectAudioTrack(id: String) {
        engine.set("aid", id)
        trackSignature = null
        sync()
    }

    /** `null` turns subtitles off. */
    fun selectSubtitleTrack(id: String?) {
        engine.set("sid", id ?: "no")
        trackSignature = null
        sync()
    }

    fun updateSubtitleStyle(style: SubtitleStyle) {
        subtitleStyle = style
        store.style = style
        engine.applySubtitles(style)
    }

    fun resetSubtitleStyle() = updateSubtitleStyle(SubtitleStyle())

    fun loadExternalSubtitle(uri: Uri) {
        if (queue.current == null) {
            showError("Open a media file first")
            return
        }
        val token = requestGeneration
        io.execute {
            val result = runCatching {
                val ext = MediaSupport.extension(displayName(uri)).ifEmpty { "srt" }
                clearCachedSubtitles()
                val file = File(app.cacheDir, "external-${UUID.randomUUID()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { it.write(input.readBytesLimited(16 * 1024 * 1024)) }
                } ?: error("Cannot read subtitle")
                file
            }
            main.post {
                if (token != requestGeneration) return@post
                result.fold(
                    onSuccess = { safely { engine.command("sub-add", it.absolutePath, "select") } },
                    onFailure = { showError(it.message ?: "Subtitle failed") }
                )
            }
        }
    }

    // endregion

    // region Queue

    fun setSidebarVisible(visible: Boolean) {
        sidebarState = visible
        store.sidebarVisible = visible
    }

    fun playItem(id: String) {
        queue.items.firstOrNull { it.id == id }?.let { play(it) }
    }

    fun toggleGroup(id: String) {
        if (!queue.collapsedGroups.add(id)) queue.collapsedGroups.remove(id)
        store.save(queue)
        publishQueue()
    }

    fun moveItem(id: String, delta: Int) {
        queue.move(id, delta)
        store.save(queue)
        publishQueue()
    }

    fun moveItemBefore(draggedId: String, destinationId: String) {
        queue.moveBefore(draggedId, destinationId)
        store.save(queue)
        publishQueue()
    }

    fun moveItemOnto(draggedId: String, targetId: String) {
        queue.moveOnto(draggedId, targetId)
        store.save(queue)
        publishQueue()
    }

    fun removeItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        val wasCurrent = queue.currentId in ids
        if (wasCurrent) remember()
        val next = queue.remove(ids)
        if (wasCurrent) {
            requestGeneration++
            pendingPlay = null
            if (next != null) play(next) else stopPlayback()
        }
        store.save(queue)
        publishQueue()
    }

    fun clearQueue() {
        remember()
        queue.clear()
        stopPlayback()
        store.save(queue)
        publishQueue()
    }

    private fun stopPlayback() {
        requestGeneration++
        pendingPlay = null
        queue.currentId = null
        engine.stop()
        torrents.pause()
        resolvedSource = null
        torrentStatus = null
        sync()
    }

    /** Deletes documents or app-owned torrent files, then drops whatever was deleted from the queue. */
    fun deleteItems(targets: List<QueueItem>) {
        if (targets.any { it.id == queue.currentId }) {
            remember()
            engine.stop()
            requestGeneration++
        }
        torrents.pause()
        io.execute {
            val deleted = mutableSetOf<String>()
            val errors = mutableListOf<String>()
            targets.forEach { item ->
                runCatching {
                    if (item.isTorrent) torrents.deleteDownload(item)
                    else {
                        val uri = Uri.parse(item.uri)
                        require(uri.scheme == "content" && DocumentsContract.isDocumentUri(app, uri)) {
                            "Only selected documents and app-owned downloads can be deleted"
                        }
                        check(DocumentsContract.deleteDocument(app.contentResolver, uri)) {
                            "Document provider refused deletion"
                        }
                    }
                }.fold({ deleted.add(item.id) }, { errors.add(it.message ?: "Deletion failed") })
            }
            main.post {
                removeItems(deleted)
                if (errors.isNotEmpty()) {
                    queue.current?.let { play(it) }
                    showError(errors.joinToString("\n"))
                } else {
                    showNotice(if (deleted.size == 1) "Deleted 1 item" else "Deleted ${deleted.size} items")
                }
            }
        }
    }

    fun openNetworkUrl(text: String): Boolean {
        val url = text.trim()
        if (!MediaSupport.isNetwork(url)) return false
        enqueueUris(listOf(Uri.parse(url)))
        return true
    }

    fun enqueueUris(uris: List<Uri>, playNow: Boolean = false) {
        if (uris.isEmpty()) return
        val group = UUID.randomUUID().toString()
        io.execute {
            val result = runCatching { resolveUris(uris, group) }
            main.post {
                result.fold(
                    onSuccess = { added ->
                        val firstAdded = queue.enqueue(added)
                        if (firstAdded != null && (playNow || queue.current == null || (!engine.hasFile && !engine.loading))) {
                            play(firstAdded)
                        }
                        store.save(queue)
                        publishQueue()
                        if (added.size > 1) {
                            probeUnprobedItems()
                            showNotice("Added ${added.size} items to the queue")
                        }
                    },
                    onFailure = { showError(it.message ?: "Could not open media") }
                )
            }
        }
    }

    private fun resolveUris(uris: List<Uri>, group: String): List<QueueItem> = uris.flatMap { uri ->
        retain(uri)
        val title = displayName(uri)
        if (MediaSupport.extension(title) in setOf("m3u", "m3u8") && uri.scheme in setOf("content", "file")) {
            val text = app.contentResolver.openInputStream(uri)?.use {
                String(it.readBytesLimited(2 * 1024 * 1024), Charsets.UTF_8)
            } ?: error("Cannot read playlist")
            if (MediaSupport.isHls(text)) {
                listOf(QueueItem(uri.toString(), title, group = group, groupTitle = title, groupKind = GroupKind.STANDALONE))
            } else {
                val entries = MediaSupport.playlistEntries(text) { line ->
                    if (MediaSupport.isNetwork(line)) line
                    else if (uri.scheme == "file") Uri.fromFile(File(File(uri.path!!).parentFile, line)).toString()
                    else null
                }
                require(entries.isNotEmpty()) {
                    "This playlist has no accessible URLs. Android document playlists need absolute network URLs; pick local media files together instead."
                }
                entries.map {
                    QueueItem(it, displayName(Uri.parse(it)), group = group, groupTitle = title, groupKind = GroupKind.PLAYLIST)
                }
            }
        } else {
            val kind = when {
                MediaSupport.isTorrent(uri.toString(), title) -> GroupKind.TORRENT
                uris.size > 1 -> GroupKind.PLAYLIST
                else -> GroupKind.STANDALONE
            }
            listOf(
                QueueItem(
                    uri = uri.toString(),
                    title = title,
                    group = group,
                    groupTitle = if (uris.size > 1) selectionTitle(uris.size) else title,
                    groupKind = kind
                )
            )
        }
    }

    private fun selectionTitle(count: Int) =
        "$count items · ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"

    fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0) ?: "Media"
                }
            }
        }
        if (uri.scheme.equals("magnet", ignoreCase = true)) return MediaSupport.magnetName(uri.toString()) ?: "Magnet stream"
        return uri.lastPathSegment?.takeIf(String::isNotBlank) ?: uri.host ?: "Media"
    }

    private fun retain(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            /* Shared URIs can grant only temporary access. */
        }
    }

    private fun probeUnprobedItems() {
        val candidates = queue.items.filter { it.metadata == null && !it.probeFailed && !it.isTorrent }
        if (candidates.isEmpty()) return
        io.execute {
            for (item in candidates) {
                val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("file", "content")) continue
                val meta = runCatching { probe(uri, item) }.getOrNull()
                main.post {
                    queue.updateMetadata(item.id, meta, probeFailed = meta == null)
                    store.save(queue)
                    publishQueue()
                }
            }
        }
    }

    private fun probe(uri: Uri, item: QueueItem): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "content") retriever.setDataSource(app, uri)
            else retriever.setDataSource(uri.path ?: item.uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val size = if (uri.scheme == "file") uri.path?.let(::File)?.length() else documentSize(uri)
            val rotated = rotation == 90 || rotation == 270
            return MediaMetadata(
                duration = durMs?.let { it / 1000.0 },
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                frameRate = fps,
                formatName = mime?.substringAfter('/')?.uppercase(),
                fileSize = size
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun documentSize(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }.getOrNull()

    // endregion

    // region Torrent downloads

    fun torrentDownloads(callback: (List<TorrentStream.Download>) -> Unit) {
        io.execute {
            val list = runCatching { torrents.downloads() }.getOrDefault(emptyList())
            main.post { callback(list) }
        }
    }

    fun deleteTorrentDownload(download: TorrentStream.Download, callback: () -> Unit) {
        val affected = queue.items.filter { it.isTorrent && TorrentStream.sourceHash(it.uri) == download.sourceHash }
        if (affected.any { it.id == queue.currentId }) {
            remember()
            engine.stop()
            requestGeneration++
            torrentStatus = null
        }
        io.execute {
            val result = runCatching { torrents.deleteDownload(download) }
            main.post {
                result.fold(
                    onSuccess = {
                        removeItems(affected.map { it.id }.toSet())
                        showNotice("Deleted ${download.name}")
                    },
                    onFailure = { showError(it.message ?: "Could not delete download") }
                )
                callback()
            }
        }
    }

    // endregion

    // region Screenshots

    /** Captures the decoded frame with mpv; `null` means the caller should fall back to the surface. */
    fun captureFrame(callback: (File?) -> Unit) {
        if (!engine.hasFile) {
            showError("Load a video first")
            return
        }
        // The bundled FFmpeg may lack image encoders; after one failure, go straight to the fallback.
        if (!mpvScreenshots) {
            callback(null)
            return
        }
        val target = File(File(app.cacheDir, "screenshots").apply { mkdirs() }, screenshotFilename())
        engine.screenshot(target) { written ->
            if (!written) mpvScreenshots = false
            callback(target.takeIf { written })
        }
    }

    private var mpvScreenshots = true

    fun screenshotFile(): File = File(File(app.cacheDir, "screenshots").apply { mkdirs() }, screenshotFilename())

    private fun screenshotFilename(): String {
        val safeTitle = (queue.current?.title ?: "Screenshot").substringBeforeLast('.')
            .split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty).joinToString("-")
            .ifEmpty { "Screenshot" }.take(80)
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        return "$safeTitle-$stamp.png"
    }

    /** Android 10+ saves straight into Pictures/andrompv; Android 9 needs the document picker. */
    val canSaveToGallery: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun saveScreenshotToGallery(file: File) {
        io.execute {
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/andrompv")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create the screenshot")
                try {
                    resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Cannot write screenshot")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            file.delete()
            main.post {
                result.fold(
                    onSuccess = { showNotice("Screenshot saved to Pictures/andrompv") },
                    onFailure = { showError(it.message ?: "Screenshot failed") }
                )
            }
        }
    }

    fun saveScreenshotTo(file: File, destination: Uri) {
        io.execute {
            val result = runCatching {
                app.contentResolver.openOutputStream(destination)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Cannot write screenshot")
            }
            file.delete()
            main.post {
                result.fold(
                    onSuccess = { showNotice("Screenshot saved") },
                    onFailure = { showError(it.message ?: "Screenshot failed") }
                )
            }
        }
    }

    // endregion

    // region Settings and info

    fun updateSettings(change: (PlayerSettings) -> PlayerSettings) {
        val old = settings
        val new = change(old)
        if (new == old) return
        settings = new
        store.settings = new
        if (new.hardwareDecoding != old.hardwareDecoding) engine.changeHardwareDecoding(new.hardwareDecoding)
        if (new.highQualityScaling != old.highQualityScaling) engine.changeFastRendering(!new.highQualityScaling)
        notifySessionIfChanged()
    }

    fun clearResumeHistory() {
        store.clearPositions()
        showNotice("Cleared saved playback positions")
    }

    fun mediaInfo(): List<Pair<String, String>> {
        val item = queue.current ?: return emptyList()
        val meta = metadata ?: item.metadata
        fun prop(name: String) = engine.get(name)?.takeIf(String::isNotBlank)
        return listOfNotNull(
            "Title" to item.title,
            prop("media-title")?.takeIf { it != item.title }?.let { "Media title" to it },
            (meta?.duration ?: duration.takeIf { it > 0 })?.let { "Duration" to playbackTime(it) },
            meta?.resolution?.let { "Resolution" to it },
            meta?.frameRateLabel?.let { "Frame rate" to it },
            (prop("video-codec") ?: meta?.videoCodec)?.let { "Video codec" to it },
            (prop("audio-codec") ?: prop("audio-codec-name") ?: meta?.audioCodec)?.let { "Audio codec" to it },
            (prop("audio-params/channel-count") ?: meta?.audioChannels?.toString())?.let { "Channels" to it },
            (prop("file-format") ?: meta?.formatName)?.let { "Container" to it },
            formatByteSize(prop("file-size")?.toLongOrNull() ?: meta?.fileSize ?: item.length.takeIf { it > 0 })
                ?.let { "Size" to it },
            "Decoder" to if (settings.hardwareDecoding) "Hardware (MediaCodec copy-back)" else "Software (FFmpeg)",
            "Source" to when {
                item.isTorrent -> "BitTorrent · ${item.torrentPath ?: item.title}"
                item.uri.startsWith("content:") -> "Local document"
                else -> item.uri
            }
        )
    }

    // endregion

    private fun clearCachedSubtitles() {
        app.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("external-")) file.delete()
        }
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "File exceeds the ${limit / 1024 / 1024} MB limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
