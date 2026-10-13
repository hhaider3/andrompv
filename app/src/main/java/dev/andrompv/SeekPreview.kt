package dev.andrompv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Network/torrent scrubbing is timestamp-only: platform retriever HTTP reads
 * have no reliable cancellation/timeout. Never let those reads hang shutdown. */
class SeekPreview(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var decoder: MediaMetadataRetriever? = null
    private var decoderSource: String? = null
    private var generation = 0
    private var busy = false
    private var pending: Request? = null
    private var closed = false
    private data class Request(val source: String, val seconds: Double, val generation: Int, val callback: (Bitmap?) -> Unit)
    private val idleClose = Runnable { worker.execute { closeDecoder() } }

    fun request(source: String, seconds: Double, callback: (Bitmap?) -> Unit) {
        if (closed) return
        generation++
        main.removeCallbacks(idleClose)
        val uri = Uri.parse(source)
        if (uri.scheme !in setOf("content", "file", null)) { callback(null); return }
        val key = "$source#${seconds.toInt()}"
        cache.get(key)?.let { callback(it); return }
        pending = Request(source, seconds, generation, callback)
        dispatch()
    }
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
                decoder?.getScaledFrameAtTime((request.seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135)
            }
            val bitmap = result.getOrNull()
            if (bitmap != null) cache.put("${request.source}#${request.seconds.toInt()}", bitmap)
            main.post {
                busy = false
                if (!closed && request.generation == generation) request.callback(bitmap)
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
