package dev.andrompv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Scrub thumbnails from a silent decoder that never touches playback, as in macmpv:
 * while the finger moves, the keyframe at or before the time decodes quickly; once it
 * rests, [request] with `exact = true` replaces it with the exact frame.
 *
 * Network/torrent scrubbing is timestamp-only: platform retriever HTTP reads have no
 * reliable cancellation/timeout. Never let those reads hang shutdown.
 */
class SeekPreview(private val context: Context) {
    data class Frame(val bitmap: Bitmap, val seconds: Double)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Frame>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Frame) = value.bitmap.allocationByteCount
    }
    private var decoder: MediaMetadataRetriever? = null
    private var decoderSource: String? = null
    private var generation = 0
    private var busy = false
    private var pending: Request? = null
    private var closed = false
    private data class Request(
        val source: String,
        val seconds: Double,
        val exact: Boolean,
        val generation: Int,
        val callback: (Frame?) -> Unit
    )
    private val idleClose = Runnable { worker.execute { closeDecoder() } }

    fun supports(source: String?): Boolean =
        source != null && Uri.parse(source).scheme in setOf("content", "file", null)

    fun request(source: String, seconds: Double, exact: Boolean = false, callback: (Frame?) -> Unit) {
        if (closed) return
        generation++
        main.removeCallbacks(idleClose)
        if (!supports(source)) { callback(null); return }
        cache.get(key(source, seconds, exact))?.let { callback(it); return }
        if (!exact) cache.get(key(source, seconds, true))?.let { callback(it); return }
        pending = Request(source, seconds, exact, generation, callback)
        dispatch()
    }

    private fun key(source: String, seconds: Double, exact: Boolean) =
        if (exact) "$source#${(seconds * 4).toInt()}#exact" else "$source#${seconds.toInt()}"

    private fun dispatch() {
        if (busy || closed) return
        val request = pending ?: return
        pending = null; busy = true
        worker.execute {
            val result = runCatching {
                if (decoderSource != request.source) {
                    closeDecoder()
                    decoder = MediaMetadataRetriever().apply {
                        val uri = Uri.parse(request.source)
                        if (uri.scheme == "content") setDataSource(context, uri)
                        else setDataSource(uri.path ?: request.source)
                    }
                    decoderSource = request.source
                }
                val option = if (request.exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                decoder?.getScaledFrameAtTime((request.seconds * 1_000_000).toLong(), option, 320, 180)
            }
            val frame = result.getOrNull()?.let { Frame(it, request.seconds) }
            if (frame != null) cache.put(key(request.source, request.seconds, request.exact), frame)
            main.post {
                busy = false
                if (!closed && request.generation == generation) request.callback(frame)
                if (!closed) {
                    dispatch()
                    if (pending == null) main.postDelayed(idleClose, 5000)
                }
            }
        }
    }

    fun cancel() { generation++; pending = null; if (!closed) main.postDelayed(idleClose, 5000) }
    private fun closeDecoder() { decoder?.release(); decoder = null; decoderSource = null }
    fun close() {
        closed = true; generation++; pending = null
        main.removeCallbacks(idleClose)
        worker.execute { closeDecoder(); cache.evictAll() }
        worker.shutdown()
    }
}
