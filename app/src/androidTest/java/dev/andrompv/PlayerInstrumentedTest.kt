package dev.andrompv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// NAME_ASCENDING puts the live-playback test first: under the emulator's
// SwiftShader software GL, running it after the retriever test starves the
// first presented frames and screenshots read black while the clock advances.
// Order-only workaround for emulator contention; assertions are unchanged.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class PlayerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).also { target ->
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }
    private fun await(message: String, timeout: Long = 25000, predicate: () -> Boolean) {
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
    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun nativePlaybackControlsSubtitlesAndPersistence() {
        // Screenshots read black when the device dozed between tests, so force
        // the screen on up front (shell uid may do what the app uid may not).
        runCatching {
            instrumentation.uiAutomation.executeShellCommand("svc power stayon true").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
        val media = fixture("sample.mp4")
        val subtitle = fixture("sample.srt")
        context.getSharedPreferences("andrompv", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        var activity: MainActivity? = null
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity = it; it.enqueueUris(listOf(Uri.fromFile(media))) }
            await("mpv did not load local video") { activity!!.engine.hasFile && activity!!.engine.duration >= 20 }
            await("playback position did not advance") { activity!!.engine.position > 1.0 }
            // What the user sees is the SurfaceFlinger composition, so capture
            // that (UiAutomation screenshot) instead of PixelCopying the raw
            // SurfaceView buffer: under SwiftShader PixelCopy intermittently
            // returns a black/late buffer even while the composited screen
            // shows the frame. The bar is unchanged: a genuinely nonblack
            // rendered frame plus a valid PNG on disk, with bounded retries.
            var green = 0
            var attempts = 0
            while (attempts < 8 && green <= 40) {
                attempts++
                val shot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("screen capture was null", shot)
                val cx = shot!!.width / 2
                val cy = shot.height / 2
                for (dy in listOf(-20, -10, 0, 10, 20)) {
                    val y = (cy + dy).coerceIn(0, shot.height - 1)
                    green = maxOf(green, Color.green(shot.getPixel(cx, y)))
                }
                val interactive = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive
                android.util.Log.d("andrompv-test", "capture attempt $attempts: green=$green shot=${shot.width}x${shot.height} interactive=$interactive")
                if (green > 40) {
                    val png = File(context.cacheDir, "capture.png")
                    png.outputStream().use { output -> assertTrue(shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
                    assertTrue(png.length() > 100)
                }
                shot.recycle()
                if (green <= 40) SystemClock.sleep(1000)
            }
            assertTrue("rendered video was black after $attempts captures", green > 40)
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
                it.engine.applySubtitles(SubtitleStyle(scale = 1.5, outline = 2.5, delay = 0.5, bold = true))
                it.engine.set("speed", 1.5)
            }
            await("external subtitle did not load") { activity!!.engine.tracks("sub").isNotEmpty() }
            scenario.onActivity {
                it.engine.seek(8.0)
            }
            await("exact seek failed") { kotlin.math.abs(activity!!.engine.position - 8.0) < 1 }
            await("speed and subtitle sync updates failed") {
                activity!!.engine.get("speed")?.toDoubleOrNull() == 1.5 &&
                    activity!!.engine.get("sub-delay")?.toDoubleOrNull() == 0.5 &&
                    activity!!.engine.get("sub-border-size")?.toDoubleOrNull() == 2.5 &&
                    activity!!.engine.get("sub-outline-size")?.toDoubleOrNull() == 2.5
            }
            scenario.onActivity {
                // Portrait baseline screenshot while loaded (pulled to dist/ after the run).
                screenshot("portrait-loaded")
            }
            scenario.onActivity {
                it.setFullscreenMode(false)
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                // Fullscreen keeps header + transport reachable (macmpv parity):
                // the title, queue toggle and controls stay visible instead of
                // vanishing, so rotation/fullscreen never strands the user.
                assertEquals(View.VISIBLE, find(it.window.decorView, "Queue")!!.visibility)
                assertEquals(View.VISIBLE, find(it.window.decorView, "Play or pause")!!.visibility)
                assertTrue(it.queue.current != null && it.engine.hasFile)
            }
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) }
            val item = activity!!.queue.current!!
            val store = PlayerStore(context)
            store.remember(item, 8.0, 24.0); store.saveMarkers(item, Markers(2.0, 20.0)); store.save(activity!!.queue)
            assertEquals(8.0, PlayerStore(context).resume(item), 0.01)
            assertEquals(Markers(2.0, 20.0), PlayerStore(context).markers(item))
            val restored = PlaybackQueue(); PlayerStore(context).restore(restored)
            assertTrue(restored.items.any { it.key == item.key })

            // Now rotate to Landscape and verify full-bleed surface, zero control overlap, floating sidebar, and live subtitle sheet
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("landscape orientation did not apply") {
                val surface = findSurface(activity!!.window.decorView)
                surface != null && surface.width > surface.height
            }
            var initialWidth = 0
            var initialHeight = 0
            scenario.onActivity {
                val surface = findSurface(it.window.decorView)!!
                initialWidth = surface.width
                initialHeight = surface.height
                assertTrue("landscape width should exceed height ($initialWidth vs $initialHeight)", initialWidth > initialHeight)

                fun checkNoOverlapAndInsideScreen(group: ViewGroup, path: String = "group") {
                    val visibleChildren = (0 until group.childCount).map { idx -> group.getChildAt(idx) }
                        .filter { v -> v.visibility == View.VISIBLE && v.width > 0 && v.height > 0 }
                    if (group is android.widget.LinearLayout && group.orientation == android.widget.LinearLayout.HORIZONTAL) {
                        for (i in 0 until visibleChildren.size - 1) {
                            val a = visibleChildren[i]
                            val b = visibleChildren[i + 1]
                            val aLoc = IntArray(2); val bLoc = IntArray(2)
                            a.getLocationOnScreen(aLoc); b.getLocationOnScreen(bLoc)
                            assertTrue(
                                "Horizontal children overlapped in $path: ${a.contentDescription ?: a.javaClass.simpleName} [${aLoc[0]}..${aLoc[0] + a.width}] vs ${b.contentDescription ?: b.javaClass.simpleName} [${bLoc[0]}]",
                                aLoc[0] + a.width <= bLoc[0] + 1
                            )
                        }
                    }
                    for (child in visibleChildren) {
                        val loc = IntArray(2)
                        child.getLocationOnScreen(loc)
                        assertTrue("View ${child.contentDescription ?: child.javaClass.simpleName} left (${loc[0]}) is off-screen", loc[0] >= -1)
                        assertTrue("View ${child.contentDescription ?: child.javaClass.simpleName} right (${loc[0] + child.width}) exceeds screen ($initialWidth)", loc[0] + child.width <= initialWidth + 1)
                        if (child is ViewGroup) checkNoOverlapAndInsideScreen(child, "$path/${child.javaClass.simpleName}")
                    }
                }
                checkNoOverlapAndInsideScreen(it.window.decorView as ViewGroup)

                // Rotation must NOT force fullscreen or hide the header: title,
                // torrent status and queue toggle stay reachable in landscape.
                val queueBtn = find(it.window.decorView, "Queue")!!
                assertEquals("landscape rotation hid the header", View.VISIBLE, queueBtn.visibility)
                val transport = find(it.window.decorView, "Play or pause")!!
                assertEquals("landscape rotation hid transport", View.VISIBLE, transport.visibility)
                // Subtitle clearance must have run and produced a sane sub-pos
                // (16:9 video fills landscape height: bottomGap=0, controls
                // visible, so sub-pos lifts below 100 to clear the glass bar).
                val subPos = it.engine.get("sub-pos")?.toDoubleOrNull()
                assertNotNull("subtitle clearance never ran", subPos)
                assertTrue("sub-pos out of range: $subPos", subPos!! in 0.0..100.0)
                assertTrue("sub-pos should clear the landscape control bar (got $subPos)", subPos < 100.0)
                screenshot("landscape-controls")

                // Open queue sidebar via bottom control bar button
                find(it.window.decorView, "Toggle playlist")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val surface = findSurface(it.window.decorView)!!
                assertEquals("Queue sidebar must float as an overlay and never crush video surface width", initialWidth, surface.width)
                assertEquals("Queue sidebar must float as an overlay and never crush video surface height", initialHeight, surface.height)
                // Transport must stay visible AND tappable above the drawer:
                // sidebar floats, scrim never swallows the control bar.
                val transport = find(it.window.decorView, "Play or pause")!!
                assertEquals("queue drawer buried transport", View.VISIBLE, transport.visibility)
                assertTrue("transport not clickable with drawer open", transport.isClickable)
                val sidebarTop = IntArray(2); val transportTop = IntArray(2)
                find(it.window.decorView, "Hide playlist")!!.getLocationOnScreen(sidebarTop)
                transport.getLocationOnScreen(transportTop)
                assertTrue(
                    "drawer overlaps transport (drawer top ${sidebarTop[1]} vs transport top ${transportTop[1]})",
                    sidebarTop[1] + find(it.window.decorView, "Hide playlist")!!.height <= transportTop[1] ||
                        transportTop[0] >= sidebarTop[0] + 300
                )
                screenshot("landscape-queue")
                // Open live subtitle settings sheet
                find(it.window.decorView, "Subtitle appearance & sync")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val surface = findSurface(it.window.decorView)!!
                assertEquals("Subtitle sheet must float and preserve video surface", initialWidth, surface.width)
                assertEquals("Subtitle sheet must float and preserve video surface", initialHeight, surface.height)
                // Sheet card must fit fully on the short landscape screen.
                val card = find(it.window.decorView, "Close subtitle settings")!!
                val loc = IntArray(2)
                card.getLocationOnScreen(loc)
                assertTrue("sheet close button off-screen top (${loc[1]})", loc[1] >= 0)
                assertTrue("sheet close button off-screen bottom (${loc[1]})", loc[1] + card.height <= initialHeight + 1)
                screenshot("landscape-subtitles")
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun screenshot(name: String) {
        // UiAutomation screenshots capture SurfaceView video too (PixelCopy of
        // the decor view would not). Mirrored to the app-specific external dir
        // so plain `adb pull` works (no run-as needed); asserted non-empty.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name was null", bitmap)
        val out = File(context.cacheDir, "$name.png")
        out.outputStream().use { output -> assertTrue(bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
        assertTrue("screenshot $name was empty", out.length() > 100)
        val ext = File(context.getExternalFilesDir("screenshots"), "$name.png")
        ext.parentFile?.mkdirs()
        out.copyTo(ext, overwrite = true)
        bitmap!!.recycle()
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
