package dev.andrompv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** libmpv's synchronous API must never block the main looper: MediaCodec and
 * surface initialization can themselves need that looper. UI reads snapshots. */
class MPVEngine(private val context: Context) : MPVLib.EventObserver, MPVLib.LogObserver {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var mpv: MPVLib? = null
    @Volatile private var closed = false
    private var descriptor: ParcelFileDescriptor? = null
    private val properties = ConcurrentHashMap<String, String>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var resumeOnFocus = false
    private var attached = false
    var onLoaded: () -> Unit = {}
    var onChanged: () -> Unit = {}
    var onEof: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var position = 0.0; private set
    var duration = 0.0; private set
    var paused = true; private set
    var loading = false; private set
    var hasFile = false; private set
    private val generation = AtomicInteger()
    private val completion = PlaybackCompletion()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause(true) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = !paused; pause(true, abandonFocus = false) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> set("volume", 20.0)
                AudioManager.AUDIOFOCUS_GAIN -> { onFocusRestored(); if (resumeOnFocus) pause(false); resumeOnFocus = false }
            }
        }.build()
    var onFocusRestored: () -> Unit = {}
    val ready get() = mpv != null && !closed

    private fun native(action: () -> Unit) {
        if (closed) return
        worker.execute {
            if (closed) return@execute
            try { action() }
            catch (e: Exception) { dispatch { loading = false; paused = true; onError(e.message ?: "mpv operation failed") } }
            catch (e: LinkageError) { dispatch { loading = false; paused = true; onError("Native library could not load: ${e.message}") } }
        }
    }
    fun attach(surface: Surface) = native {
        if (mpv == null) {
            val engine = MPVLib.create(context) ?: error("Could not create libmpv")
            mpv = engine
            mapOf("vo" to "gpu", "gpu-context" to "android", "opengl-es" to "yes",
                "hwdec" to if (android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) "no" else "mediacodec-copy", "ao" to "audiotrack,opensles", "idle" to "yes",
                "keep-open" to "yes", "input-default-bindings" to "no", "input-vo-keyboard" to "no",
                "load-scripts" to "no", "ytdl" to "no", "cache" to "yes", "demuxer-max-bytes" to "32MiB",
                "sub-use-margins" to "no", "screenshot-format" to "png",
                "gpu-shader-cache-dir" to java.io.File(context.cacheDir, "shaders").apply { mkdirs() }.absolutePath,
                "sub-font" to "Roboto", "sub-fonts-dir" to FileFonts.directory(context)).forEach { (key, value) ->
                check(engine.setOptionString(key, value) >= 0) { "mpv rejected $key=$value" }
            }
            engine.attachSurface(surface); attached = true
            engine.addObserver(this); engine.addLogObserver(this); engine.init()
            listOf("time-pos", "duration").forEach { engine.observeProperty(it, 5) }
            listOf("pause", "eof-reached", "paused-for-cache").forEach { engine.observeProperty(it, 3) }
            listOf("track-list", "video-params/w", "video-params/h", "video-out-params/aspect", "container-fps",
                "video-codec", "audio-codec-name", "audio-params/channel-count", "file-format", "file-size",
                "speed", "sub-delay", "aid", "sid", "volume", "sub-scale").forEach { engine.observeProperty(it, 1) }
        } else {
            mpv!!.attachSurface(surface); attached = true; mpv!!.setPropertyString("vo", "gpu")
        }
    }
    fun detach() {
        pause(true)
        native {
            if (attached) {
                mpv?.setPropertyString("vo", "null"); mpv?.detachSurface(); attached = false
            }
        }
    }
    fun load(source: String, resume: Double = 0.0) {
        val token = generation.incrementAndGet()
        completion.beginLoad(); hasFile = false; loading = true; position = 0.0; duration = 0.0
        properties.keys.removeAll { it.startsWith("video-") || it.startsWith("audio-") || it in setOf("track-list", "file-size", "file-format", "container-fps") }
        native {
            val engine = mpv ?: error("The video surface is not ready")
            engine.command(arrayOf("stop"))
            descriptor?.close(); descriptor = null
            val resolved = if (source.startsWith("content:")) {
                descriptor = context.contentResolver.openFileDescriptor(android.net.Uri.parse(source), "r")
                    ?: error("This document is no longer readable; open it again")
                "fd://${descriptor!!.fd}"
            } else source
            engine.command(arrayOf("loadfile", resolved, "replace", "-1", "start=${resume.coerceAtLeast(0.0)}"))
        }
        pause(false)
        main.postDelayed({
            if (!closed && token == generation.get() && !hasFile && loading) {
                loading = false; paused = true; onError("Loading timed out. Check the source or try again.")
            }
        }, 30_000)
        onChanged()
    }
    fun stop() {
        generation.incrementAndGet(); completion.beginLoad(); hasFile = false; loading = false
        native { mpv?.command(arrayOf("stop")); descriptor?.close(); descriptor = null }
        position = 0.0; duration = 0.0; paused = true
        audio.abandonAudioFocusRequest(focus); onChanged()
    }
    fun pause(value: Boolean, abandonFocus: Boolean = true) {
        if (!value && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onError("Audio focus is unavailable; tap Play to retry"); return
        }
        native { mpv?.setPropertyBoolean("pause", value) }
        paused = value
        if (value && abandonFocus) audio.abandonAudioFocusRequest(focus)
        onChanged()
    }
    fun seek(seconds: Double, exact: Boolean = true) = command("seek", seconds.coerceAtLeast(0.0).toString(), if (exact) "absolute+exact" else "absolute+keyframes")
    fun command(vararg args: String) = native { mpv?.command(arrayOf(*args)) }
    fun set(name: String, value: Double) = native { mpv?.setPropertyDouble(name, value) }
    fun set(name: String, value: String) = native { mpv?.setPropertyString(name, value) }
    fun get(name: String) = properties[name]
    fun tracks(type: String): List<Pair<String, String>> {
        val array = runCatching { JSONArray(get("track-list") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("type") == type }.map {
            it.getInt("id").toString() to listOf(it.optString("title"), it.optString("lang"), it.optString("codec"))
                .filter(String::isNotBlank).joinToString(" · ").ifEmpty { "Track ${it.getInt("id")}" }
        }
    }
    fun applySubtitles(style: SubtitleStyle) {
        set("sub-scale", style.scale); set("sub-outline-size", style.outline)
        set("sub-bold", if (style.bold) "yes" else "no"); set("sub-delay", style.delay)
        set("sub-color", style.color); set("sub-back-color", "0.0/0.0/0.0/${style.background}")
    }
    fun subtitleClearance(viewWidth: Int, viewHeight: Int, controlsHeight: Int) {
        if (!hasFile) return
        val aspect = get("video-out-params/aspect")?.toDoubleOrNull() ?: return
        if (aspect <= 0 || viewHeight <= 0) return
        val height = minOf(viewHeight.toDouble(), viewWidth / aspect)
        if (height <= 0) return
        val bottomGap = (viewHeight - height) / 2
        val shift = (controlsHeight + 8 - bottomGap).coerceAtLeast(0.0)
        set("sub-pos", (100 - shift / height * 100).coerceIn(0.0, 100.0))
    }
    fun metadata(): String = listOf(
        "Duration: ${playbackTime(duration)}",
        "Resolution: ${get("video-params/w") ?: "—"} × ${get("video-params/h") ?: "—"}",
        "Frame rate: ${get("container-fps") ?: "—"}", "Video codec: ${get("video-codec") ?: "—"}",
        "Audio codec: ${get("audio-codec-name") ?: "—"}", "Channels: ${get("audio-params/channel-count") ?: "—"}",
        "Container: ${get("file-format") ?: "—"}", "Size: ${get("file-size") ?: "—"} bytes"
    ).joinToString("\n")
    private fun dispatch(block: () -> Unit) {
        val token = generation.get()
        main.post { if (!closed && token == generation.get()) block() }
    }
    override fun eventProperty(property: String, value: Double) = dispatch {
        when (property) { "time-pos" -> position = value; "duration" -> duration = value }
        onChanged()
    }
    override fun eventProperty(property: String, value: Boolean) = dispatch {
        when (property) {
            "pause" -> paused = value
            "paused-for-cache" -> loading = value
            "eof-reached" -> if (completion.observe(value)) { paused = true; onEof() }
        }
        onChanged()
    }
    override fun eventProperty(property: String, value: Long) = eventProperty(property, value.toString())
    override fun eventProperty(property: String, value: String) = dispatch {
        properties[property] = value
        onChanged()
    }
    override fun eventProperty(property: String) = dispatch { properties.remove(property) }
    override fun event(eventId: Int) = dispatch {
        if (eventId == 8) { hasFile = true; loading = false; completion.fileLoaded(); onLoaded(); onChanged() }
        // END_FILE includes replacement/stop events. Only eof-reached advances.
    }
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= 20) android.util.Log.e("andrompv-mpv", "$prefix: ${text.trim()}")
    }
    fun release() {
        if (closed) return
        closed = true; generation.incrementAndGet()
        audio.abandonAudioFocusRequest(focus)
        worker.execute {
            val engine = mpv; mpv = null
            engine?.removeObserver(this); engine?.removeLogObserver(this)
            engine?.destroy(); descriptor?.close(); descriptor = null
        }
        worker.shutdown()
    }
}
