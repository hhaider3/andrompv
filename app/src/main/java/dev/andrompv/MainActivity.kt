package dev.andrompv

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    internal lateinit var engine: MPVEngine
    internal val queue = PlaybackQueue()
    private lateinit var store: PlayerStore
    private lateinit var torrents: TorrentStream
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var surface: SurfaceView
    private lateinit var video: FrameLayout
    private lateinit var empty: TextView
    private lateinit var controls: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var time: TextView
    private lateinit var playButton: Button
    private lateinit var queuePanel: LinearLayout
    private lateinit var queueRows: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var previews: SeekPreview
    private var volume = 80.0
    private var speed = 1.0
    private var muted = false
    private var fullscreen = false
    private var scrubbing = false
    private var wasPlaying = false
    private var surfaceReady = false
    private var pendingPlay: QueueItem? = null
    private var pendingScreenshot: Bitmap? = null
    private var requestGeneration = 0
    private var lastSaved = -1.0
    private var lastPreviewSeek = 0L
    private var queueVisible = false
    private var foreground = false
    private var destroyed = false
    private var resolvedSource: String? = null
    private val collapsedGroups = mutableSetOf<String>()
    private val teal = Color.rgb(102, 226, 203)
    private val panelColor = Color.rgb(24, 30, 34)
    private val autoHide = Runnable { if (fullscreen && !engine.paused && !scrubbing) showControls(false) }
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            renderPlayback()
            if (foreground) main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlayerStore(this); store.restore(queue); volume = store.volume
        engine = MPVEngine(this)
        torrents = TorrentStream(this)
        previews = SeekPreview(this)
        engine.onLoaded = {
            engine.set("speed", speed)
            engine.applySubtitles(store.style)
            engine.set("volume", if (muted) 0.0 else volume)
            renderPlayback(); clearance()
        }
        engine.onChanged = { renderPlayback() }
        engine.onFocusRestored = { engine.set("volume", if (muted) 0.0 else volume) }
        engine.onEof = {
            queue.current?.let(store::finished)
            val next = queue.next(automatic = true)
            if (next != null) play(next, resume = false) else renderPlayback()
        }
        engine.onError = { showError(it) }
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) toggleFullscreen() else finish()
            }
        })
        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun button(label: String, description: String = label, action: () -> Unit) = Button(this).apply {
        text = label; contentDescription = description; textSize = 13f
        setTextColor(teal); minimumWidth = dp(48); minWidth = dp(48)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { safely(action); scheduleHide() }
    }
    private fun LinearLayout.addWeighted(view: View, weight: Float = 1f) {
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
    }

    private fun buildUi() {
        root = column().apply { setBackgroundColor(Color.rgb(16, 19, 22)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        header = row().apply { setBackgroundColor(panelColor) }
        title = text("andrompv", 18f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        header.addWeighted(title)
        header.addView(button("Open", "Open media files") { pickMedia() })
        header.addView(button("URL", "Open network URL") { openUrlDialog() })
        header.addView(button("Queue") { queueVisible = !queueVisible; renderQueue() })
        root.addView(header)
        video = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = object : SurfaceView(this) {
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        surface.holder.addCallback(this)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        empty = text("▶\nandrompv\n\nOpen video, audio, a playlist or torrent\nNative mpv · No account required", 18f).apply {
            gravity = Gravity.CENTER; setTextColor(Color.rgb(160, 172, 177)); isClickable = false
        }
        video.addView(empty, FrameLayout.LayoutParams(-1, -1))
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewLabel = text("", 14f).apply { gravity = Gravity.CENTER }
        val previewBox = column().apply {
            setBackgroundColor(Color.argb(220, 20, 25, 28)); addView(previewImage, LinearLayout.LayoutParams(dp(160), dp(90)))
            addView(previewLabel); visibility = View.GONE; tag = "preview"
        }
        video.addView(previewBox, FrameLayout.LayoutParams(dp(160), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(135) })
        controls = column().apply { setBackgroundColor(Color.argb(220, 24, 30, 34)) }
        time = text("00:00 / 00:00")
        seekBar = SeekBar(this).apply { max = 10000; contentDescription = "Playback position" }
        val progressRow = row()
        progressRow.addWeighted(seekBar); progressRow.addView(time)
        controls.addView(progressRow)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek: SeekBar?) {
                scrubbing = true; wasPlaying = !engine.paused; engine.pause(true)
                video.findViewWithTag<View>("preview").visibility = View.VISIBLE
            }
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || engine.duration <= 0) return
                val target = progress / 10000.0 * engine.duration
                time.text = "${playbackTime(target)} / ${playbackTime(engine.duration)}"
                previewLabel.text = playbackTime(target)
                val source = resolvedSource ?: return
                previews.request(source, target) { bitmap -> if (scrubbing) previewImage.setImageBitmap(bitmap) }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPreviewSeek >= 180) { engine.seek(target, exact = false); lastPreviewSeek = now }
            }
            override fun onStopTrackingTouch(seek: SeekBar?) {
                engine.seek(seekBar.progress / 10000.0 * engine.duration)
                scrubbing = false; if (wasPlaying) engine.pause(false)
                video.findViewWithTag<View>("preview").visibility = View.GONE
                previews.cancel(); scheduleHide()
            }
        })
        val transport = row().apply { gravity = Gravity.CENTER }
        transport.addWeighted(button("|◀", "Previous item") { previous() })
        transport.addWeighted(button("−10s", "Seek backward 10 seconds") { relativeSeek(-10.0) })
        playButton = button("▶", "Play or pause") { togglePlayback() }
        transport.addWeighted(playButton)
        transport.addWeighted(button("+10s", "Seek forward 10 seconds") { relativeSeek(10.0) })
        transport.addWeighted(button("▶|", "Next item") { queue.next()?.let { play(it) } })
        transport.addWeighted(button("⋮", "Player options") { showOptions() })
        controls.addView(transport)
        video.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clearance() }
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullscreen) showControls(controls.visibility != View.VISIBLE) else togglePlayback()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleFullscreen(); return true }
        })
        surface.setOnTouchListener { view, event ->
            val consumed = gesture.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("Ready · Hardware-accelerated libmpv", 12f)
        root.addView(status)
        queuePanel = column().apply { setBackgroundColor(panelColor) }
        val queueHeader = row()
        queueHeader.addWeighted(text("PLAY QUEUE", 13f))
        queueHeader.addView(button("Repeat") { queue.repeat = queue.repeat.next(); store.save(queue); renderQueue() })
        queueHeader.addView(button("Clear") {
            AlertDialog.Builder(this).setTitle("Clear queue?").setMessage("Removes queue entries only. Your files and torrent downloads are kept.")
                .setPositiveButton("Clear") { _, _ -> remember(); requestGeneration++; pendingPlay = null; queue.items.clear(); queue.currentId = null; engine.stop(); torrents.pause(); resolvedSource = null; store.save(queue); renderQueue() }
                .setNegativeButton("Cancel", null).show()
        })
        queuePanel.addView(queueHeader)
        queueRows = column()
        queuePanel.addView(ScrollView(this).apply { addView(queueRows) }, LinearLayout.LayoutParams(-1, dp(220)))
        root.addView(queuePanel)
        setContentView(root); renderQueue(); renderPlayback()
    }

    private fun renderPlayback() {
        if (!::title.isInitialized) return
        val item = queue.current
        title.text = item?.title ?: "andrompv"
        empty.visibility = if (item == null) View.VISIBLE else View.GONE
        playButton.text = if (item == null || engine.paused) "▶" else "Ⅱ"
        if (!scrubbing) {
            time.text = "${playbackTime(engine.position)} / ${playbackTime(engine.duration)}"
            seekBar.progress = if (engine.duration > 0) (engine.position / engine.duration * 10000).toInt().coerceIn(0, 10000) else 0
        }
        seekBar.isEnabled = engine.duration > 0
        if (item != null) status.text = if (item.isTorrent) torrents.statusLabel()
            else if (engine.loading) "Loading…" else "${if (engine.paused) "Paused" else "Playing"} · ${speed}× · ${if (muted) "Muted" else "Volume ${volume.toInt()}%"}"
        else status.text = "Ready · libmpv · Repeat ${queue.repeat.name.lowercase()}"
        if (item != null && !engine.paused && foreground) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (item != null && engine.hasFile && !scrubbing && kotlin.math.abs(engine.position - lastSaved) >= 1) {
            store.remember(item, engine.position, engine.duration); lastSaved = engine.position
        }
    }

    private fun renderQueue() {
        queuePanel.visibility = if (queueVisible && !fullscreen) View.VISIBLE else View.GONE
        queueRows.removeAllViews()
        for ((group, items) in queue.items.groupBy { it.group }) {
            if (items.size > 1 || items[0].isTorrent) {
                val groupRow = row()
                groupRow.addWeighted(button("${if (group in collapsedGroups) "▸" else "▾"} ${items[0].groupTitle}") {
                    if (!collapsedGroups.add(group)) collapsedGroups.remove(group); renderQueue()
                })
                groupRow.addView(button("⋮", "Collection options") { groupOptions(group) })
                queueRows.addView(groupRow)
            }
            if (group in collapsedGroups) continue
            items.forEach { item ->
                val itemRow = row()
                itemRow.setBackgroundColor(if (item.id == queue.currentId) Color.rgb(31, 57, 53) else panelColor)
                val label = text((if (item.id == queue.currentId) "▶  " else "") + item.title).apply {
                    maxLines = 2; setOnClickListener { safely { play(item) } }
                    setOnLongClickListener { itemOptions(item); true }
                }
                itemRow.addWeighted(label)
                itemRow.addView(button("⋮", "Options for ${item.title}") { itemOptions(item) })
                queueRows.addView(itemRow)
            }
        }
        if (queue.items.isEmpty()) queueRows.addView(text("Your queue is empty. Open files or a stream."))
        status.contentDescription = "Repeat ${queue.repeat.name.lowercase()}"
    }

    internal fun play(item: QueueItem, resume: Boolean = true) {
        if (!foreground || !surfaceReady) { pendingPlay = item; return }
        remember(); requestGeneration++; val token = requestGeneration
        previews.cancel(); resolvedSource = null; lastSaved = -1.0
        queue.currentId = item.id; pendingPlay = null
        if (item.isTorrent) {
            engine.stop(); status.text = "Resolving torrent metadata…"
            torrents.open(item) { result ->
                if (destroyed || token != requestGeneration || queue.currentId != item.id) return@open
                result.fold(onSuccess = { opened ->
                    if (item.torrentIndex == null) {
                        val index = queue.items.indexOfFirst { it.id == item.id }
                        if (index < 0) return@fold
                        queue.items.removeAt(index); queue.items.addAll(index, opened.items)
                        store.save(queue); opened.items.firstOrNull()?.let { play(it, resume) }
                    } else loadSource(item, opened.url ?: error("No torrent stream"), resume)
                    renderQueue()
                }, onFailure = { showError(it.message ?: "Torrent playback failed") })
            }
        } else {
            torrents.pause()
            loadSource(item, item.uri, resume)
        }
        renderPlayback(); renderQueue(); scheduleHide()
    }
    private fun loadSource(item: QueueItem, source: String, resume: Boolean) {
        safely {
            resolvedSource = source
            engine.load(source, if (resume) store.resume(item) else 0.0)
        }
    }
    private fun remember() { queue.current?.takeIf { engine.hasFile }?.let { store.remember(it, engine.position, engine.duration) } }
    private fun togglePlayback() {
        if (queue.current?.isTorrent == true && engine.paused) { queue.current?.let { play(it) }; return }
        if (queue.current == null) { if (queue.items.isNotEmpty()) play(queue.items.first()) else pickMedia(); return }
        if (!engine.hasFile && !engine.loading) queue.current?.let { play(it) } else engine.pause(!engine.paused)
        scheduleHide()
    }
    private fun previous() {
        if (engine.position > 3) engine.seek(0.0) else queue.previous(engine.position)?.let { play(it) }
    }
    private fun relativeSeek(seconds: Double) { engine.seek((engine.position + seconds).coerceIn(0.0, engine.duration.coerceAtLeast(0.0))) }

    private fun pickMedia() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, OPEN_MEDIA)
    private fun pickSubtitle() {
        if (queue.current == null) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, OPEN_SUBTITLE)
    }
    private fun openUrlDialog() {
        val input = EditText(this).apply { hint = "https://, rtsp://, rtmp:// or magnet:"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val dialog = AlertDialog.Builder(this).setTitle("Open stream").setView(input).setPositiveButton("Open", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text.toString().trim()
            if (!MediaSupport.isNetwork(url)) input.error = "Enter a valid HTTP, HTTPS, RTMP, RTSP or magnet URL"
            else { enqueueUris(listOf(Uri.parse(url))); dialog.dismiss() }
        } }
        dialog.show()
    }
    private fun name(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "Media"
            }
        }
        if (uri.scheme == "magnet") return uri.getQueryParameter("dn") ?: "Magnet stream"
        return uri.lastPathSegment?.takeIf(String::isNotBlank) ?: uri.host ?: "Media"
    }
    private fun retain(uri: Uri) {
        if (uri.scheme == "content") {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Shared URIs can grant only temporary access. */ }
        }
    }
    internal fun enqueueUris(uris: List<Uri>) {
        val group = UUID.randomUUID().toString()
        io.execute {
            val result = runCatching {
                uris.flatMap { uri ->
                    retain(uri)
                    val title = name(uri)
                    if (MediaSupport.extension(title) in setOf("m3u", "m3u8") && uri.scheme in setOf("content", "file")) {
                        val text = contentResolver.openInputStream(uri)?.use { String(it.readBytesLimited(2 * 1024 * 1024), Charsets.UTF_8) }
                            ?: error("Cannot read playlist")
                        if (MediaSupport.isHls(text)) listOf(QueueItem(uri.toString(), title, group = group, groupTitle = title))
                        else {
                            val entries = MediaSupport.playlistEntries(text) { line ->
                                if (MediaSupport.isNetwork(line)) line
                                else if (uri.scheme == "file") Uri.fromFile(File(File(uri.path!!).parentFile, line)).toString()
                                else null
                            }
                            require(entries.isNotEmpty()) { "This playlist has no accessible URLs. Android document playlists need absolute network URLs; pick local media files together instead." }
                            entries.map { QueueItem(it, name(Uri.parse(it)), group = group, groupTitle = title) }
                        }
                    } else listOf(QueueItem(uri.toString(), title, group = group, groupTitle = if (uris.size > 1) "Playlist" else title))
                }
            }
            main.post {
                if (destroyed) return@post
                result.fold({ items -> queue.enqueue(items)?.let { play(it) }; store.save(queue); queueVisible = true; renderQueue() }, { showError(it.message ?: "Could not open media") })
            }
        }
    }

    private fun showOptions() {
        main.removeCallbacks(autoHide)
        val options = arrayOf("Playback speed ($speed×)", "Volume / mute", "Repeat: ${queue.repeat.name.lowercase()}", "Audio track", "Subtitle track", "Open external subtitles", "Subtitle appearance & sync", "Intro / outro markers", "Save PNG screenshot", "Media information", if (fullscreen) "Exit fullscreen" else "Fullscreen", "Torrent downloads")
        AlertDialog.Builder(this).setTitle("Player options").setItems(options) { _, which -> safely {
            when (which) {
                0 -> { val values = arrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0)
                    AlertDialog.Builder(this).setTitle("Playback speed").setItems(values.map { "$it×" }.toTypedArray()) { _, i -> speed = values[i]; engine.set("speed", speed); renderPlayback() }.show() }
                1 -> volumeDialog()
                2 -> { queue.repeat = queue.repeat.next(); store.save(queue); renderQueue(); toast("Repeat ${queue.repeat.name.lowercase()}") }
                3 -> trackDialog("audio")
                4 -> trackDialog("sub")
                5 -> pickSubtitle()
                6 -> subtitleDialog()
                7 -> markerDialog()
                8 -> screenshot()
                9 -> AlertDialog.Builder(this).setTitle(queue.current?.title ?: "Media information").setMessage(engine.metadata()).setPositiveButton("OK", null).show()
                10 -> toggleFullscreen()
                11 -> downloadDialog()
            }
        } }.setOnDismissListener { scheduleHide() }.show()
    }
    private fun trackDialog(type: String) {
        val tracks = engine.tracks(type)
        val choices = if (type == "sub") listOf("no" to "Off") + tracks else tracks
        if (choices.isEmpty()) { toast("No $type tracks available"); return }
        AlertDialog.Builder(this).setTitle(if (type == "sub") "Subtitles" else "Audio tracks")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first == engine.get(if (type == "sub") "sid" else "aid") }) { dialog, i ->
                engine.set(if (type == "sub") "sid" else "aid", choices[i].first); dialog.dismiss()
            }.show()
    }
    private fun volumeDialog() {
        val layout = column(); val label = text("Volume ${volume.toInt()}%")
        val slider = SeekBar(this).apply { max = 100; progress = volume.toInt(); contentDescription = "Volume" }
        layout.addView(label); layout.addView(slider)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, user: Boolean) {
                if (user) { volume = value.toDouble(); muted = false; store.volume = volume; engine.set("volume", volume); label.text = "Volume $value%" }
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        layout.addView(Switch(this).apply { text = "Mute"; isChecked = muted; setOnCheckedChangeListener { _, checked -> muted = checked; engine.set("volume", if (muted) 0.0 else volume); renderPlayback() } })
        AlertDialog.Builder(this).setTitle("Volume").setView(layout).setPositiveButton("Done", null).show()
    }
    private fun subtitleDialog() {
        val style = store.style; val layout = column()
        fun field(label: String, value: String): EditText {
            layout.addView(text(label)); return EditText(this).apply { setText(value); isSingleLine = true; layout.addView(this) }
        }
        val scale = field("Scale (0.25–4)", style.scale.toString())
        val outline = field("Outline (0–10)", style.outline.toString())
        val delay = field("Sync delay in seconds (−60–60)", style.delay.toString())
        val color = field("Text color (#RRGGBB)", style.color)
        val background = field("Background opacity (0–1)", style.background.toString())
        val bold = CheckBox(this).apply { text = "Bold"; isChecked = style.bold }; layout.addView(bold)
        val dialog = AlertDialog.Builder(this).setTitle("Subtitle appearance & sync").setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Apply", null).setNeutralButton("Reset") { _, _ -> store.style = SubtitleStyle(); engine.applySubtitles(store.style) }
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun number(input: EditText, min: Double, max: Double): Double? = input.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max }
            val a = number(scale, 0.25, 4.0); val b = number(outline, 0.0, 10.0); val c = number(delay, -60.0, 60.0); val d = number(background, 0.0, 1.0)
            if (a == null || b == null || c == null || d == null || !Regex("#[0-9a-fA-F]{6}").matches(color.text.toString())) toast("Check the numeric ranges and #RRGGBB color")
            else { store.style = SubtitleStyle(a, b, bold.isChecked, c, color.text.toString(), d); engine.applySubtitles(store.style); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun markerDialog() {
        val item = queue.current ?: return; val markers = store.markers(item)
        val options = arrayOf("Set intro end at ${playbackTime(engine.position)}", "Set outro start at ${playbackTime(engine.position)}", "Skip intro (${markers.intro?.let(::playbackTime) ?: "not set"})", "Skip outro (${markers.outro?.let(::playbackTime) ?: "not set"})", "Clear markers")
        AlertDialog.Builder(this).setTitle("Intro / outro").setItems(options) { _, i ->
            when (i) {
                0 -> store.saveMarkers(item, markers.copy(intro = engine.position))
                1 -> store.saveMarkers(item, markers.copy(outro = engine.position))
                2 -> markers.intro?.let { engine.seek(it) }
                3 -> if (markers.outro != null) { val next = queue.next(); if (next != null) play(next) else { engine.seek(engine.duration); engine.pause(true) } }
                4 -> store.saveMarkers(item, Markers())
            }
        }.show()
    }
    private fun itemOptions(item: QueueItem) {
        AlertDialog.Builder(this).setTitle(item.title).setItems(arrayOf("Play", "Move up", "Move down", "Remove from queue", "Delete file / download…")) { _, i ->
            when (i) {
                0 -> safely { play(item) }
                1 -> { queue.move(item.id, -1); store.save(queue); renderQueue() }
                2 -> { queue.move(item.id, 1); store.save(queue); renderQueue() }
                3 -> removeItems(setOf(item.id))
                4 -> deleteItems(listOf(item))
            }
        }.show()
    }
    private fun groupOptions(group: String) {
        val items = queue.items.filter { it.group == group }
        AlertDialog.Builder(this).setTitle(items.firstOrNull()?.groupTitle).setItems(arrayOf("Remove collection from queue", "Delete collection files / downloads…")) { _, i ->
            if (i == 0) removeItems(items.map { it.id }.toSet()) else deleteItems(items)
        }.show()
    }
    private fun removeItems(ids: Set<String>) {
        val wasCurrent = queue.currentId in ids
        if (wasCurrent) remember()
        val next = queue.remove(ids)
        if (wasCurrent) {
            requestGeneration++; pendingPlay = null
            if (next != null) play(next) else { queue.currentId = null; engine.stop(); torrents.pause(); resolvedSource = null }
        }
        store.save(queue); renderQueue(); renderPlayback()
    }
    private fun deleteItems(items: List<QueueItem>) {
        AlertDialog.Builder(this).setTitle("Permanently delete?")
            .setMessage("Android has no app-wide Trash. Deletes selected documents or app-owned torrent files. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (items.any { it.id == queue.currentId }) { remember(); engine.stop(); requestGeneration++ }
                torrents.pause()
                io.execute {
                    val deleted = mutableSetOf<String>(); val errors = mutableListOf<String>()
                    items.forEach { item -> runCatching {
                        if (item.isTorrent) torrents.deleteDownload(item)
                        else {
                            val uri = Uri.parse(item.uri)
                            require(uri.scheme == "content" && android.provider.DocumentsContract.isDocumentUri(this, uri)) { "Only selected documents and app-owned downloads can be deleted" }
                            check(android.provider.DocumentsContract.deleteDocument(contentResolver, uri)) { "Document provider refused deletion" }
                        }
                    }.fold({ deleted.add(item.id) }, { errors.add(it.message ?: "Deletion failed") }) }
                    main.post {
                        if (destroyed) return@post
                        removeItems(deleted)
                        if (errors.isNotEmpty()) { queue.current?.let { play(it) }; showError(errors.joinToString("\n")) }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun downloadDialog() {
        AlertDialog.Builder(this).setTitle("Torrent downloads").setMessage("${torrents.statusLabel()}\n\n${torrents.downloadSummary()}\n\nDownloads are app-owned and retained between launches. Uninstalling removes them.")
            .setPositiveButton("OK", null).show()
    }
    private fun screenshot() {
        if (!engine.hasFile || surface.width <= 0 || surface.height <= 0) { toast("Load a video first"); return }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bitmap, { result ->
            if (result != PixelCopy.SUCCESS || destroyed) { bitmap.recycle(); if (!destroyed) showError("Could not capture video ($result)"); return@request }
            pendingScreenshot?.recycle(); pendingScreenshot = bitmap
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "image/png"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "andrompv-${System.currentTimeMillis()}.png")
            }, SAVE_SCREENSHOT)
        }, main)
    }
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        header.visibility = if (fullscreen) View.GONE else View.VISIBLE
        status.visibility = if (fullscreen) View.GONE else View.VISIBLE
        renderQueue(); showControls(true)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) controller.hide(WindowInsets.Type.systemBars()) else controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (fullscreen) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_VISIBLE
        }
        scheduleHide()
    }
    private fun showControls(show: Boolean) { controls.visibility = if (show) View.VISIBLE else View.GONE; clearance(); if (show) scheduleHide() }
    private fun scheduleHide() { main.removeCallbacks(autoHide); if (fullscreen) main.postDelayed(autoHide, 3500) }
    private fun clearance() { if (surfaceReady) safely { engine.subtitleClearance(video.width, video.height, if (controls.visibility == View.VISIBLE) controls.height else 0) } }
    private fun safely(action: () -> Unit) { try { action() } catch (e: Exception) { showError(e.message ?: "Operation failed") } catch (e: LinkageError) { showError("Native library could not load: ${e.message}") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun showError(message: String) {
        if (!destroyed && !isFinishing) AlertDialog.Builder(this).setTitle("andrompv").setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        safely { engine.attach(holder.surface); surfaceReady = true; engine.applySubtitles(store.style); pendingPlay?.let { play(it) } }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { clearance() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; safely { engine.detach() } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); root.post { clearance() } }
    override fun onStart() { super.onStart(); foreground = true; main.post(ticker); if (surfaceReady) pendingPlay?.let { play(it) } }
    override fun onStop() {
        foreground = false; remember(); store.save(queue)
        main.removeCallbacks(ticker); main.removeCallbacks(autoHide)
        if (engine.ready) engine.pause(true)
        torrents.pause(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true; requestGeneration++; main.removeCallbacksAndMessages(null)
        previews.close(); engine.release(); torrents.close(); io.shutdownNow()
        pendingScreenshot?.recycle(); pendingScreenshot = null
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(uris::add)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(MediaSupport::isNetwork)?.let { uris.add(Uri.parse(it)) }
        }
        if (uris.isNotEmpty()) enqueueUris(uris.distinct())
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == SAVE_SCREENSHOT) { pendingScreenshot?.recycle(); pendingScreenshot = null }
            return
        }
        when (requestCode) {
            OPEN_MEDIA -> {
                val uris = mutableListOf<Uri>(); data?.data?.let(uris::add)
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                enqueueUris(uris.distinct())
            }
            OPEN_SUBTITLE -> data?.data?.let { uri ->
                val token = requestGeneration
                io.execute {
                    val result = runCatching {
                        val file = File(cacheDir, "external-${UUID.randomUUID()}.${MediaSupport.extension(name(uri)).ifEmpty { "srt" }}")
                        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { it.write(input.readBytesLimited(16 * 1024 * 1024)) } } ?: error("Cannot read subtitle")
                        file
                    }
                    main.post { if (!destroyed && token == requestGeneration) result.fold({ safely { engine.command("sub-add", it.absolutePath, "select") } }, { showError(it.message ?: "Subtitle failed") }) }
                }
            }
            SAVE_SCREENSHOT -> {
                val bitmap = pendingScreenshot ?: return; pendingScreenshot = null
                val uri = data?.data ?: run { bitmap.recycle(); return }
                io.execute {
                    val result = runCatching { contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("Cannot write screenshot") }
                    bitmap.recycle(); main.post { if (!destroyed) result.fold({ toast("Screenshot saved") }, { showError(it.message ?: "Screenshot failed") }) }
                }
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayback()
            KeyEvent.KEYCODE_MEDIA_NEXT -> queue.next()?.let { play(it) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
            KeyEvent.KEYCODE_DPAD_LEFT -> relativeSeek(-10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> relativeSeek(10.0)
            KeyEvent.KEYCODE_M -> { muted = !muted; engine.set("volume", if (muted) 0.0 else volume) }
            KeyEvent.KEYCODE_LEFT_BRACKET -> { speed = (speed - 0.25).coerceAtLeast(0.25); engine.set("speed", speed) }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { speed = (speed + 0.25).coerceAtMost(4.0); engine.set("speed", speed) }
            KeyEvent.KEYCODE_BACKSLASH -> { speed = 1.0; engine.set("speed", speed) }
            KeyEvent.KEYCODE_F -> toggleFullscreen()
            KeyEvent.KEYCODE_ESCAPE -> if (fullscreen) toggleFullscreen() else return super.onKeyDown(keyCode, event)
            else -> return super.onKeyDown(keyCode, event)
        }
        renderPlayback(); return true
    }
    companion object { private const val OPEN_MEDIA = 1; private const val OPEN_SUBTITLE = 2; private const val SAVE_SCREENSHOT = 3 }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        require(output.size() + count <= limit) { "File exceeds the ${limit / 1024 / 1024} MB limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
