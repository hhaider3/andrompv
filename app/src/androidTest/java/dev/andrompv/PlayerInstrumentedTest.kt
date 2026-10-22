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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
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
    @get:Rule val compose = createEmptyComposeRule()
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
    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun node(description: String) = compose.onAllNodesWithContentDescription(description).onFirst()

    /** Brings the auto-hiding controls back and freezes the compose clock so they stay up for the next action. */
    private fun showControls(activity: MainActivity) {
        compose.mainClock.autoAdvance = false
        instrumentation.runOnMainSync { activity.ui.interact() }
        compose.mainClock.advanceTimeBy(500)
    }
    private fun resumeClock() {
        compose.mainClock.autoAdvance = true
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
        // The player is created lazily by the first activity, so it restores these cleared preferences.
        context.getSharedPreferences("andrompv", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        var activity: MainActivity? = null
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity = it; it.player.enqueueUris(listOf(Uri.fromFile(media))) }
            val player = activity!!.player
            await("mpv did not load local video") { player.engine.hasFile && player.engine.duration >= 20 }
            await("playback position did not advance") { player.engine.position > 1.0 }
            await("controller state did not follow mpv") { player.hasFile && !player.isPaused && player.duration >= 20 }
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
                android.util.Log.d("andrompv-test", "capture attempt $attempts: green=$green shot=${shot.width}x${shot.height}")
                shot.recycle()
                if (green <= 40) SystemClock.sleep(1000)
            }
            assertTrue("rendered video was black after $attempts captures", green > 40)
            scenario.onActivity {
                assertEquals("160", player.engine.get("video-params/w"))
                assertEquals("90", player.engine.get("video-params/h"))
                assertTrue(player.audioTracks.isNotEmpty())
            }

            // Save Screenshot: a decoded frame (mpv, or the surface as a fallback) lands in Pictures/andrompv.
            instrumentation.runOnMainSync { activity!!.takeScreenshot() }
            await("screenshot was not saved", 15000) { player.notice?.message == "Screenshot saved to Pictures/andrompv" }
            val resolver = context.contentResolver
            val projection = arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            val saved = resolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("sample-%.png"), null
            )!!.use { cursor ->
                assertTrue("screenshot missing from MediaStore", cursor.moveToFirst())
                android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            }
            val png = resolver.openInputStream(saved)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            assertNotNull("saved screenshot was not an image", png)
            assertTrue("saved screenshot was empty", png.width > 0 && png.height > 0)
            resolver.delete(saved, null, null)

            showControls(activity!!)
            node("Pause").performClick()
            compose.mainClock.advanceTimeBy(300)
            resumeClock()
            await("pause button failed") { player.engine.paused && player.isPaused }
            node("Forward 10 seconds").performClick()
            await("seek button failed") { player.engine.position >= 10 }
            scenario.onActivity {
                player.engine.command("sub-add", subtitle.absolutePath, "select")
                player.updateSubtitleStyle(SubtitleStyle(scale = 1.5, outline = 2.5, delay = 0.5, bold = true))
                player.setSpeed(1.5)
            }
            await("external subtitle did not load") { player.subtitleTracks.isNotEmpty() && player.subtitleTracks.any { it.selected } }
            scenario.onActivity { player.seek(8.0) }
            await("exact seek failed") { kotlin.math.abs(player.engine.position - 8.0) < 1 }
            await("speed and subtitle sync updates failed") {
                player.engine.get("speed")?.toDoubleOrNull() == 1.5 &&
                    player.engine.get("sub-delay")?.toDoubleOrNull() == 0.5 &&
                    player.engine.get("sub-border-size")?.toDoubleOrNull() == 2.5 &&
                    player.engine.get("sub-outline-size")?.toDoubleOrNull() == 2.5
            }
            node("Playback speed 1.5×").assertIsDisplayed()
            screenshot("portrait-paused")

            // Keyboard: F toggles full screen; controls stay reachable while paused; Esc leaves it.
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F)) }
            await("F did not enter full screen") { activity!!.isFullscreen }
            node("Play").assertIsDisplayed()
            scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)) }
            await("Esc did not leave full screen") { !activity!!.isFullscreen }

            val item = player.queue.current!!
            val store = PlayerStore(context)
            store.remember(item, 8.0, 24.0); store.save(player.queue)
            assertEquals(8.0, PlayerStore(context).resume(item), 0.01)
            val restored = PlaybackQueue(); PlayerStore(context).restore(restored)
            assertTrue(restored.items.any { it.key == item.key })

            // Landscape: full-bleed surface, controls inside the screen, floating queue and subtitle panel.
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
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
            }
            showControls(activity!!)
            resumeClock()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            listOf("Play", "Previous", "Next", "Show queue", "Subtitle settings", "Enter full screen").forEach { description ->
                val bounds = node(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$description is outside $root: $bounds", bounds.left >= root.left - 1 && bounds.right <= root.right + 1 && bounds.bottom <= root.bottom + 1)
            }
            await("subtitle clearance never lifted subtitles above the controls") {
                val subPos = player.engine.get("sub-pos")?.toDoubleOrNull()
                subPos != null && subPos in 0.0..99.99
            }
            screenshot("landscape-controls")

            node("Show queue").performClick()
            node("Hide queue").assertIsDisplayed()
            // Header title plus the queue row and the Now Playing card.
            assertTrue(compose.onAllNodesWithText("sample.mp4", useUnmergedTree = true).fetchSemanticsNodes().size >= 3)
            scenario.onActivity {
                val surface = findSurface(it.window.decorView)!!
                assertEquals("Queue must float over the video", initialWidth, surface.width)
                assertEquals("Queue must float over the video", initialHeight, surface.height)
            }
            val play = node("Play").fetchSemanticsNode().boundsInRoot
            val queueBottom = compose.onAllNodesWithText("Now Playing".uppercase(), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("queue panel overlaps the control bar ($queueBottom vs ${play.top})", queueBottom <= play.top)
            screenshot("landscape-queue")
            node("Hide queue").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("UP NEXT", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

            node("Subtitle settings").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Close subtitle settings").fetchSemanticsNodes().isNotEmpty() }
            val close = node("Close subtitle settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("subtitle panel off screen: $close", close.top >= 0 && close.bottom <= initialHeight + 1)
            node("Subtitle Size").assertIsDisplayed()
            screenshot("landscape-subtitles")
            scenario.onActivity {
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
            preview.request(media.absolutePath, 2.0) { frame -> width = frame?.bitmap?.width ?: 0; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(width in 1..320)
        val exact = CountDownLatch(1)
        var seconds = 0.0
        instrumentation.runOnMainSync {
            preview.request(media.absolutePath, 5.5, exact = true) { frame -> seconds = frame?.seconds ?: -1.0; exact.countDown() }
        }
        assertTrue(exact.await(10, TimeUnit.SECONDS)); assertEquals(5.5, seconds, 0.001)
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
            fun open(source: QueueItem, queued: Set<Int>? = null): TorrentStream.Opened {
                val latch = CountDownLatch(1)
                var result: Result<TorrentStream.Opened>? = null
                instrumentation.runOnMainSync { torrent.open(source, queued) { result = it; latch.countDown() } }
                assertTrue("torrent open timed out", latch.await(25, TimeUnit.SECONDS))
                return result!!.getOrThrow()
            }
            val resolved = open(item)
            assertEquals(listOf("Episode 2.mp4", "Episode 10.mp4"), resolved.items.map { it.title })
            assertEquals(listOf(1, 0), resolved.items.map { it.torrentIndex })
            // Only the first episode is queued, so only it is downloaded.
            val queuedIndex = resolved.items.first().torrentIndex!!
            val otherIndex = resolved.items.last().torrentIndex!!
            val playing = open(resolved.items.first(), setOf(queuedIndex))
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
            // Everything queued is saved: the progress file says so, and the torrent left the swarm
            // (no uploading) while the stream above was served from disk.
            val directory = TorrentStorage.directory(context)
            val sourceKey = TorrentStream.sourceHash(item.uri)
            var saved: TorrentProgress? = null
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                saved = TorrentStorage.readProgress(directory, sourceKey)
                if (saved?.files?.get(queuedIndex)?.done == true) break
                SystemClock.sleep(250)
            }
            assertTrue("progress file never reported the queued file as saved: $saved", saved?.files?.get(queuedIndex)?.done == true)
            assertFalse("a file that is not queued must not be downloaded", saved!!.files[otherIndex]?.done == true)
            assertEquals("Playing from local storage", torrent.statusLabel())
            // A verified file plays straight from disk, with no torrent running.
            val verified = TorrentStorage.verifiedFile(directory, resolved.items.first(), saved)
            assertNotNull("verified file should be playable directly", verified)
            assertArrayEquals(media.readBytes(), verified!!.readBytes())
            assertNull(TorrentStorage.verifiedFile(directory, resolved.items.last(), saved))
            // The queue's progress: LOCAL for the saved file, a percentage for the other.
            val states = TorrentStorage.loadProgress(directory, resolved.items, { TorrentStorage.infoHashOf(directory, it) })[item.uri]!!
            assertEquals(TorrentDownloadState.Local, torrentDownloadState(resolved.items.first(), states))
            assertTrue(torrentDownloadState(resolved.items.last(), states) is TorrentDownloadState.Downloading?)
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
