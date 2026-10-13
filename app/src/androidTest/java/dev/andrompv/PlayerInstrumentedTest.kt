package dev.andrompv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).also { target ->
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }
    private fun await(message: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var value = false
            instrumentation.runOnMainSync { value = predicate() }
            if (value) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let { return it }
        return null
    }

    @Test fun nativePlaybackControlsSubtitlesAndPersistence() {
        val media = fixture("sample.mp4")
        val subtitle = fixture("sample.srt")
        var activity: MainActivity? = null
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity = it; it.enqueueUris(listOf(Uri.fromFile(media))) }
            await("mpv did not load local video") { activity!!.engine.hasFile && activity!!.engine.duration >= 20 }
            await("playback position did not advance") { activity!!.engine.position > 0.5 }
            val captured = CountDownLatch(1)
            var captureResult = -1
            var green = 0
            scenario.onActivity {
                fun findSurface(view: View): android.view.SurfaceView? {
                    if (view is android.view.SurfaceView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
                    return null
                }
                val surface = findSurface(it.window.decorView)!!
                val bitmap = android.graphics.Bitmap.createBitmap(surface.width, surface.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(surface, bitmap, { result ->
                    captureResult = result
                    green = Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    val png = File(context.cacheDir, "capture.png")
                    png.outputStream().use { output -> assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
                    assertTrue(png.length() > 100)
                    bitmap.recycle(); captured.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue("video capture timed out", captured.await(10, TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, captureResult)
            assertTrue("rendered video was black", green > 40)
            scenario.onActivity {
                assertEquals("160", it.engine.get("video-params/w"))
                assertEquals("90", it.engine.get("video-params/h"))
                assertTrue(it.engine.tracks("audio").isNotEmpty())
                find(it.window.decorView, "Play or pause")!!.performClick()
            }
            await("pause button failed") { activity!!.engine.paused }
            scenario.onActivity { find(it.window.decorView, "Seek forward 10 seconds")!!.performClick() }
            await("seek button failed") { activity!!.engine.position >= 10 }
            scenario.onActivity {
                it.engine.command("sub-add", subtitle.absolutePath, "select")
                it.engine.applySubtitles(SubtitleStyle(scale = 1.5, delay = 0.5, bold = true))
                it.engine.set("speed", 1.5)
            }
            await("external subtitle did not load") { activity!!.engine.tracks("sub").isNotEmpty() }
            scenario.onActivity {
                it.engine.seek(8.0)
            }
            await("exact seek failed") { kotlin.math.abs(activity!!.engine.position - 8.0) < 1 }
            await("speed and subtitle sync updates failed") {
                activity!!.engine.get("speed")?.toDoubleOrNull() == 1.5 && activity!!.engine.get("sub-delay")?.toDoubleOrNull() == 0.5
            }
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F)) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(View.GONE, find(it.window.decorView, "Open media files")!!.parent.let { it as View }.visibility) }
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) }
            val item = activity!!.queue.current!!
            val store = PlayerStore(context)
            store.remember(item, 8.0, 24.0); store.saveMarkers(item, Markers(2.0, 20.0)); store.save(activity!!.queue)
            assertEquals(8.0, PlayerStore(context).resume(item), 0.01)
            assertEquals(Markers(2.0, 20.0), PlayerStore(context).markers(item))
            val restored = PlaybackQueue(); PlayerStore(context).restore(restored)
            assertTrue(restored.items.any { it.key == item.key })
        }
    }

    @Test fun previewExtractsLocalFrameAndCancelsStaleResults() {
        val media = fixture("sample.mp4")
        val preview = SeekPreview(context)
        val done = CountDownLatch(1)
        var width = 0
        instrumentation.runOnMainSync {
            preview.request(media.absolutePath, 2.0) { bitmap -> width = bitmap?.width ?: 0; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(width in 1..240)
        val stale = CountDownLatch(1)
        instrumentation.runOnMainSync {
            preview.request(media.absolutePath, 10.0) { stale.countDown() }
            preview.cancel()
        }
        assertFalse(stale.await(1, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { preview.close() }
    }

    @Test fun nativeTorrentDownloadsRangesAndReusesVerifiedFilesOffline() {
        val media = fixture("sample.mp4")
        val torrentFile = fixture("sample.torrent")
        val server = ServerSocket(18765, 8, InetAddress.getByName("127.0.0.1"))
        val clients = Executors.newCachedThreadPool()
        val serving = Thread {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    clients.execute {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val request = input.readLine() ?: return@use
                            var range: String? = null
                            while (true) {
                                val line = input.readLine() ?: return@use
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                            }
                            val bytes = media.readBytes()
                            val selected = ByteRange.parse(range, bytes.size.toLong()) ?: return@use
                            val output = it.getOutputStream()
                            val header = "HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Length: ${selected.length}\r\nAccept-Ranges: bytes\r\nContent-Range: bytes ${selected.start}-${selected.end}/${bytes.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            if (!request.startsWith("HEAD")) output.write(bytes, selected.start.toInt(), selected.length.toInt())
                        }
                    }
                } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
            }
        }.apply { isDaemon = true; start() }
        // Production retains libtorrent's SSRF protection. This test explicitly
        // opts into its own loopback webseed and disables public peer discovery.
        val torrent = TorrentStream(context) { settings ->
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.ssrf_mitigation.swigValue(), false)
            settings.setEnableDht(false)
            settings.setEnableLsd(false)
            settings.setDhtBootstrapNodes("")
        }
        try {
            val item = QueueItem(Uri.fromFile(torrentFile).toString(), "sample.torrent")
            fun open(source: QueueItem): TorrentStream.Opened {
                val latch = CountDownLatch(1)
                var result: Result<TorrentStream.Opened>? = null
                instrumentation.runOnMainSync { torrent.open(source) { result = it; latch.countDown() } }
                assertTrue("torrent open timed out", latch.await(25, TimeUnit.SECONDS))
                return result!!.getOrThrow()
            }
            val resolved = open(item)
            assertEquals(listOf("Episode 2.mp4", "Episode 10.mp4"), resolved.items.map { it.title })
            assertEquals(listOf(1, 0), resolved.items.map { it.torrentIndex })
            val playing = open(resolved.items.first())
            val connection = URL(playing.url!!).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 35000
            connection.setRequestProperty("Range", "bytes=10-109")
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 10-109/${media.length()}", connection.getHeaderField("Content-Range"))
            assertArrayEquals(media.readBytes().copyOfRange(10, 110), connection.inputStream.use { it.readBytes() })
            connection.disconnect()
            // Fetch the entire selected file, forcing piece validation before reads.
            URL(playing.url).openConnection().apply { connectTimeout = 5000; readTimeout = 35000 }.getInputStream().use {
                assertArrayEquals(media.readBytes(), it.readBytes())
            }
            instrumentation.runOnMainSync { torrent.pause() }
            server.close()
            val reopened = open(resolved.items.first())
            URL(reopened.url!!).openConnection().apply { connectTimeout = 5000; readTimeout = 35000 }.getInputStream().use {
                assertArrayEquals(media.readBytes(), it.readBytes())
            }
            val invalid = URL(reopened.url).openConnection() as HttpURLConnection
            invalid.setRequestProperty("Range", "bytes=${media.length()}-")
            assertEquals(416, invalid.responseCode); invalid.disconnect()
        } finally {
            server.close(); clients.shutdownNow(); serving.join(2000)
            instrumentation.runOnMainSync { torrent.close() }
        }
    }
}
