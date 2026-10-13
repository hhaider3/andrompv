package dev.andrompv

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaModelsTest {
    @Test fun completionConsumesOnlyLoadedEofOnce() {
        val completion = PlaybackCompletion()
        assertFalse(completion.observe(true))
        completion.beginLoad(); assertFalse(completion.observe(true))
        completion.fileLoaded(); assertFalse(completion.observe(false))
        assertTrue(completion.observe(true)); assertFalse(completion.observe(true))
        completion.beginLoad(); assertFalse(completion.observe(true))
        completion.fileLoaded(); assertTrue(completion.observe(true))
    }
    @Test fun repeatModesAndManualNext() {
        val queue = PlaybackQueue()
        val a = QueueItem("https://example.com/a", "a"); val b = QueueItem("https://example.com/b", "b")
        queue.enqueue(listOf(a, b)); queue.currentId = a.id
        assertEquals(b, queue.next(true)); queue.repeat = RepeatMode.ONE
        assertEquals(a, queue.next(true)); assertEquals(b, queue.next(false))
        queue.currentId = b.id; queue.repeat = RepeatMode.OFF; assertNull(queue.next(true))
        queue.repeat = RepeatMode.ALL; assertEquals(a, queue.next(true))
        queue.currentId = a.id; assertEquals(b, queue.previous(0.0)); assertEquals(a, queue.previous(4.0))
    }
    @Test fun duplicateEnqueuePreservesIdentity() {
        val queue = PlaybackQueue(); val a = QueueItem("content://provider/a", "a")
        assertEquals(a, queue.enqueue(listOf(a)))
        assertEquals(a, queue.enqueue(listOf(a.copy(id = "new"))))
        assertEquals(1, queue.items.size)
        queue.enqueue(listOf(a.copy(torrentIndex = 2), a.copy(torrentIndex = 10)))
        assertEquals(3, queue.items.size)
    }
    @Test fun queueReorderAndCurrentRemoval() {
        val queue = PlaybackQueue(); val items = (0..2).map { QueueItem("https://example.com/$it", "$it") }
        queue.enqueue(items); queue.currentId = items[1].id
        queue.move(items[2].id, -1); assertEquals(listOf(items[0], items[2], items[1]), queue.items)
        assertEquals(items[2], queue.remove(setOf(items[1].id)))
        queue.currentId = items[2].id
        assertNull(queue.remove(queue.items.map { it.id }.toSet()))
    }
    @Test fun naturalSortKeepsOriginalTorrentIndexes() {
        val names = listOf("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv", "Episode 99999999999999999999.mkv")
        assertEquals(listOf(names[2], names[1], names[0], names[3]), names.sortedWith(naturalOrder))
        val item = QueueItem("magnet:?xt=urn:btih:abc", "Episode 2.mkv", torrentIndex = 10)
        assertEquals("magnet:?xt=urn:btih:abc#torrent-file=10", item.key)
        assertTrue(item.isTorrent)
    }
    @Test fun resumeAndTimestampBounds() {
        assertNull(resumePosition(4.0, 100.0)); assertEquals(5.0, resumePosition(5.0, 100.0))
        assertNull(resumePosition(90.0, 100.0)); assertEquals(80.0, resumePosition(80.0, 0.0))
        assertNull(resumePosition(Double.NaN, 100.0))
        assertEquals("00:00", playbackTime(Double.NaN)); assertEquals("00:00", playbackTime(-10.0))
        assertEquals("1:01:01", playbackTime(3661.9))
    }
    @Test fun networkValidation() {
        assertTrue(MediaSupport.isNetwork("https://example.org/movie.mp4"))
        assertTrue(MediaSupport.isNetwork("rtsp://192.168.1.2/live"))
        assertTrue(MediaSupport.isNetwork("magnet:?xt=urn:btih:0123456789abcdef"))
        assertFalse(MediaSupport.isNetwork("javascript:alert(1)")); assertFalse(MediaSupport.isNetwork("http:"))
        assertFalse(MediaSupport.isNetwork("magnet:?dn=not-a-torrent"))
    }
    @Test fun playlistsKeepHlsIntactAndDeduplicate() {
        assertTrue(MediaSupport.isHls("#EXTM3U\n#EXT-X-TARGETDURATION:10\nclip.ts"))
        assertTrue(MediaSupport.playlistEntries("#EXT-X-STREAM-INF:abc\nstream.m3u8") { it }.isEmpty())
        assertEquals(listOf("https://example.com/a.mp4"), MediaSupport.playlistEntries("\uFEFF#EXTM3U\nhttps://example.com/a.mp4\n\n#comment\nhttps://example.com/a.mp4") { it })
    }
    @Test fun rangesAreBoundedAndSupportSuffixes() {
        assertEquals(ByteRange(0, 99), ByteRange.parse(null, 100))
        assertEquals(ByteRange(10, 20), ByteRange.parse("bytes=10-20", 100))
        assertEquals(ByteRange(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange(10, 99), ByteRange.parse("bytes=10-", 100))
        assertEquals(ByteRange(10, 99), ByteRange.parse("bytes=10-200", 100))
        listOf("bytes=100-", "bytes=20-10", "bytes=-0", "bytes=0-1,4-5", "garbage", "bytes=999999999999999999999-").forEach { assertNull(it, ByteRange.parse(it, 100)) }
        assertNull(ByteRange.parse(null, 0))
    }
    @Test fun torrentPathsCannotEscapeAppStorage() {
        val root = File(System.getProperty("java.io.tmpdir"), "andrompv-test")
        assertEquals(File(root, "folder/episode.mkv").canonicalFile, TorrentStream.safeFile(root, "folder/episode.mkv"))
        listOf("../escape.mkv", "/absolute.mkv", "folder/../../escape", "").forEach {
            assertTrue(it, runCatching { TorrentStream.safeFile(root, it) }.isFailure)
        }
    }
}
