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
    data class TrackInfo(
        val id: String,
        val label: String,
        val selected: Boolean
    )

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
    @Volatile private var acceptLoaded = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause(true) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = !paused; pause(true, abandonFocus = false) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckVolume()
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

    fun attach(surface: Surface, width: Int = 0, height: Int = 0) = native {
        if (mpv == null) {
            val engine = MPVLib.create(context) ?: error("Could not create libmpv")
            mpv = engine
            val fonts = FileFonts.install(context)
            mapOf(
                "vo" to "gpu",
                "gpu-context" to "android",
                "opengl-es" to "yes",
                "hwdec" to if (preferHardwareDecoding()) "mediacodec-copy" else "no",
                "ao" to "audiotrack,opensles",
                "idle" to "yes",
                "keep-open" to "yes",
                "hr-seek-framedrop" to "yes",
                "input-default-bindings" to "no",
                "input-vo-keyboard" to "no",
                "load-scripts" to "no",
                "ytdl" to "no",
                "cache" to "yes",
                "demuxer-max-bytes" to "32MiB",
                "sub-clear-on-seek" to "yes",
                "sub-use-margins" to "no",
                "sub-margin-y" to "0",
                "sub-auto" to "fuzzy",
                "alang" to "auto",
                "slang" to "auto",
                "screenshot-format" to "png",
                "gpu-shader-cache-dir" to java.io.File(context.cacheDir, "shaders").apply { mkdirs() }.absolutePath,
                "sub-font" to fonts.family,
                "sub-fonts-dir" to fonts.directory
            ).forEach { (key, value) ->
                val rc = engine.setOptionString(key, value)
                if (rc < 0) {
                    android.util.Log.w("andrompv-mpv", "mpv rejected option $key=$value (rc=$rc)")
                }
            }
            if (width > 0 && height > 0) {
                engine.setOptionString("android-surface-size", "${width}x${height}")
            }
            engine.attachSurface(surface); attached = true
            engine.addObserver(this); engine.addLogObserver(this); engine.init()
            listOf("time-pos", "duration").forEach { engine.observeProperty(it, 5) }
            listOf("pause", "eof-reached", "paused-for-cache").forEach { engine.observeProperty(it, 3) }
            listOf(
                "track-list", "video-params/w", "video-params/h", "video-params/aspect",
                "video-out-params/aspect", "container-fps", "video-codec",
                "audio-codec-name", "audio-params/channel-count", "file-format",
                "file-size", "media-title", "speed", "sub-delay", "aid", "sid",
                "volume", "sub-scale", "sub-border-size", "sub-outline-size"
            ).forEach { engine.observeProperty(it, 1) }
        } else {
            if (width > 0 && height > 0) {
                mpv!!.setPropertyString("android-surface-size", "${width}x${height}")
            }
            mpv!!.attachSurface(surface); attached = true; mpv!!.setPropertyString("vo", "gpu")
        }
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        native {
            if (attached) mpv?.setPropertyString("android-surface-size", "${width}x${height}")
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
        acceptLoaded = true
        completion.beginLoad(); hasFile = false; loading = true; position = 0.0; duration = 0.0
        properties.keys.removeAll {
            it.startsWith("video-") || it.startsWith("audio-") ||
                it in setOf("track-list", "file-size", "file-format", "container-fps", "media-title")
        }
        if (!requestAudioFocus()) {
            failLoad(token, "Audio focus is unavailable; tap Play to retry", stopNative = false)
            return
        }
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
            engine.setPropertyBoolean("pause", false)
        }
        paused = false
        main.postDelayed({
            if (!closed && token == generation.get() && acceptLoaded && !hasFile) {
                failLoad(token, "Loading timed out. Check the source or try again.", stopNative = true)
            }
        }, 30_000)
        onChanged()
    }

    /** Drops a load that never became a file, including a late file-loaded event. */
    private fun failLoad(token: Int, message: String, stopNative: Boolean) {
        if (closed || token != generation.get()) return
        acceptLoaded = false
        generation.incrementAndGet()
        loading = false
        paused = true
        hasFile = false
        if (stopNative) {
            native {
                mpv?.command(arrayOf("stop"))
                descriptor?.close()
                descriptor = null
            }
        }
        audio.abandonAudioFocusRequest(focus)
        onError(message)
        onChanged()
    }

    private fun requestAudioFocus(): Boolean =
        audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    /** Duck downward only. A mute (volume 0) must not be raised to an audible level. */
    private fun duckVolume() {
        val current = properties["volume"]?.toDoubleOrNull() ?: return
        if (current <= 0.0) return
        set("volume", current.coerceAtMost(20.0))
    }

    fun stop() {
        generation.incrementAndGet(); acceptLoaded = false; completion.beginLoad(); hasFile = false; loading = false
        native { mpv?.command(arrayOf("stop")); descriptor?.close(); descriptor = null }
        position = 0.0; duration = 0.0; paused = true
        audio.abandonAudioFocusRequest(focus); onChanged()
    }

    fun pause(value: Boolean, abandonFocus: Boolean = true) {
        if (!value && !requestAudioFocus()) {
            onError("Audio focus is unavailable; tap Play to retry"); return
        }
        native { mpv?.setPropertyBoolean("pause", value) }
        paused = value
        if (value && abandonFocus) audio.abandonAudioFocusRequest(focus)
        onChanged()
    }

    fun seek(seconds: Double, exact: Boolean = true) {
        val clamped = seconds.coerceAtLeast(0.0)
        position = if (duration > 0) clamped.coerceAtMost(duration) else clamped
        command("seek", clamped.toString(), if (exact) "absolute+exact" else "absolute+keyframes")
    }

    fun command(vararg args: String) = native { mpv?.command(arrayOf(*args)) }
    fun set(name: String, value: Double) {
        properties[name] = value.toString()
        native { mpv?.setPropertyDouble(name, value) }
    }
    fun set(name: String, value: String) {
        properties[name] = value
        native { mpv?.setPropertyString(name, value) }
    }
    fun get(name: String) = properties[name]

    fun trackDetails(type: String): List<TrackInfo> {
        val array = runCatching { JSONArray(get("track-list") ?: "[]") }.getOrDefault(JSONArray())
        val activeProp = if (type == "sub") get("sid") else get("aid")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.optString("type") == type }
            .map { obj ->
                val id = obj.getInt("id").toString()
                val title = obj.optString("title").takeIf(String::isNotBlank)
                val lang = obj.optString("lang").takeIf(String::isNotBlank)?.uppercase()
                val codec = obj.optString("codec").takeIf(String::isNotBlank)?.uppercase()
                val fallback = if (type == "audio") "Audio $id" else "Subtitle $id"
                val primary = title ?: lang ?: fallback
                val label = if (codec != null) "$primary · $codec" else primary
                val selected = obj.optBoolean("selected", false) || activeProp == id
                TrackInfo(id, label, selected)
            }
    }

    fun tracks(type: String): List<Pair<String, String>> =
        trackDetails(type).map { it.id to it.label }

    fun applySubtitles(style: SubtitleStyle) {
        set("sub-scale", style.scale.coerceIn(0.25, 4.0))
        // mpv 0.41 renamed sub-border-size to sub-outline-size; set both so the
        // live Outline slider works regardless of which alias this build honors.
        set("sub-outline-size", style.outline.coerceIn(0.0, 10.0))
        set("sub-border-size", style.outline.coerceIn(0.0, 10.0))
        set("sub-bold", if (style.bold) "yes" else "no")
        set("sub-delay", style.delay)
        val (r, g, b) = parseHexRgb(style.color)
        set("sub-color", "%.3f/%.3f/%.3f/1.0".format(java.util.Locale.US, r, g, b))
        set("sub-back-color", "0.0/0.0/0.0/%.3f".format(java.util.Locale.US, style.background.coerceIn(0.0, 1.0)))
    }

    private fun parseHexRgb(hex: String): Triple<Double, Double, Double> {
        val clean = hex.removePrefix("#")
        val value = clean.toLongOrNull(16) ?: 0xFFFFFFL
        val r = ((value shr 16) and 0xFF) / 255.0
        val g = ((value shr 8) and 0xFF) / 255.0
        val b = (value and 0xFF) / 255.0
        return Triple(r, g, b)
    }

    fun subtitleClearance(viewWidth: Int, viewHeight: Int, controlsHeight: Int) {
        if (!hasFile) return
        if (controlsHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            set("sub-pos", 100.0)
            return
        }
        val aspect = (get("video-out-params/aspect")?.toDoubleOrNull()
            ?: get("video-params/aspect")?.toDoubleOrNull()
            ?: run {
                val w = get("video-params/w")?.toDoubleOrNull() ?: 0.0
                val h = get("video-params/h")?.toDoubleOrNull() ?: 0.0
                if (w > 0 && h > 0) w / h else null
            }) ?: run {
            set("sub-pos", 100.0)
            return
        }
        if (aspect <= 0) return
        val height = minOf(viewHeight.toDouble(), viewWidth / aspect)
        if (height <= 0) return
        val bottomGap = (viewHeight - height) / 2.0
        val requiredClearance = controlsHeight + 12.0
        if (requiredClearance <= bottomGap) {
            set("sub-pos", 100.0)
            return
        }
        val shift = (requiredClearance - bottomGap).coerceAtLeast(0.0)
        set("sub-pos", (100.0 - shift / height * 100.0).coerceIn(0.0, 100.0))
    }

    fun currentMediaMetadata(): MediaMetadata? {
        if (!hasFile) return null
        val w = get("video-params/w")?.toIntOrNull()
        val h = get("video-params/h")?.toIntOrNull()
        val fps = get("container-fps")?.toDoubleOrNull()
        val vCodec = get("video-codec")?.takeIf(String::isNotBlank)
        val aCodec = get("audio-codec-name")?.takeIf(String::isNotBlank)
        val channels = get("audio-params/channel-count")?.toIntOrNull()
        val format = get("file-format")?.takeIf(String::isNotBlank)
        val size = get("file-size")?.toLongOrNull()
        val dur = duration.takeIf { it > 0 }
        if (w == null && h == null && vCodec == null && aCodec == null && dur == null) return null
        return MediaMetadata(
            duration = dur,
            width = w,
            height = h,
            frameRate = fps,
            videoCodec = vCodec,
            audioCodec = aCodec,
            audioChannels = channels,
            formatName = format,
            fileSize = size
        )
    }

    fun metadata(): String = listOf(
        "Duration: ${playbackTime(duration)}",
        "Resolution: ${get("video-params/w") ?: "—"} × ${get("video-params/h") ?: "—"}",
        "Frame rate: ${get("container-fps")?.toDoubleOrNull()?.let { "%.2f fps".format(it) } ?: "—"}",
        "Video codec: ${get("video-codec")?.uppercase() ?: "—"}",
        "Audio codec: ${get("audio-codec-name")?.uppercase() ?: "—"}",
        "Channels: ${get("audio-params/channel-count") ?: "—"}",
        "Container: ${get("file-format") ?: "—"}",
        "Size: ${formatByteSize(get("file-size")?.toLongOrNull()) ?: (get("file-size")?.let { "$it bytes" } ?: "—")}"
    ).joinToString("\n")

    private fun dispatch(block: () -> Unit) {
        val token = generation.get()
        main.post { if (!closed && token == generation.get()) block() }
    }
    override fun eventProperty(property: String, value: Double) = dispatch {
        properties[property] = value.toString()
        when (property) { "time-pos" -> position = value; "duration" -> duration = value }
        onChanged()
    }
    override fun eventProperty(property: String, value: Boolean) = dispatch {
        properties[property] = value.toString()
        when (property) {
            "pause" -> if (acceptLoaded || hasFile) paused = value
            "paused-for-cache" -> if (acceptLoaded || hasFile) loading = value
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
        if (eventId == 8 && acceptLoaded) { hasFile = true; loading = false; completion.fileLoaded(); onLoaded(); onChanged() }
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

    companion object {
        fun preferHardwareDecoding(): Boolean =
            android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")
    }
}
