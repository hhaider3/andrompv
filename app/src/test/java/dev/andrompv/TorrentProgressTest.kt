package dev.andrompv

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TorrentProgressTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val source = "magnet:?xt=urn:btih:$hash"
    private fun episode(index: Int, length: Long = 1000) =
        QueueItem(source, "Episode $index.mkv", torrentIndex = index, torrentPath = "Show/Episode $index.mkv", length = length)

    private fun folder(): File = Files.createTempDirectory("andrompv-torrents").toFile().also { it.deleteOnExit() }

    private fun withFile(directory: File, index: Int, length: Long): File =
        File(File(directory, hash), "Show/Episode $index.mkv").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(length.toInt())) }

    @Test fun progressSurvivesTheJsonRoundTrip() {
        val progress = TorrentProgress(hash, mapOf(0 to TorrentFileProgress(500, false), 3 to TorrentFileProgress(1000, true)))
        assertEquals(progress, TorrentProgress.parse(progress.toJson()))
        assertNull(TorrentProgress.parse("not json"))
        assertNull(TorrentProgress.parse("""{"infoHash":"$hash"}"""))
        // A bad info hash or a bad entry is ignored rather than trusted.
        val parsed = TorrentProgress.parse("""{"infoHash":"short","files":{"x":{"downloaded":1,"done":true},"2":{"downloaded":-5,"done":false}}}""")!!
        assertNull(parsed.infoHash)
        assertEquals(mapOf(2 to TorrentFileProgress(0, false)), parsed.files)
    }

    @Test fun rowsShowAPercentageOrLocalAndNeverAHundredPercentUntilVerified() {
        val progress = TorrentProgress(hash, mapOf(
            0 to TorrentFileProgress(250, false),
            1 to TorrentFileProgress(1000, false),
            2 to TorrentFileProgress(1000, true),
            3 to TorrentFileProgress(0, false)
        ))
        assertEquals(TorrentDownloadState.Downloading(25), torrentDownloadState(episode(0), progress))
        assertEquals(TorrentDownloadState.Downloading(99), torrentDownloadState(episode(1), progress))
        assertEquals(TorrentDownloadState.Local, torrentDownloadState(episode(2), progress))
        assertEquals("0%", torrentDownloadState(episode(3), progress)!!.label)
        assertEquals("LOCAL", torrentDownloadState(episode(2), progress)!!.label)
        assertNull(torrentDownloadState(episode(9), progress))
        assertNull(torrentDownloadState(episode(0), null))
        assertNull(torrentDownloadState(QueueItem("https://example.com/a.mp4", "a.mp4"), progress))
    }

    @Test fun nowPlayingSaysWhereTheBytesComeFrom() {
        assertEquals("Playing from local storage", torrentSourceLabel(TorrentDownloadState.Local))
        assertEquals("Streaming from BitTorrent • 40% downloaded", torrentSourceLabel(TorrentDownloadState.Downloading(40)))
        assertEquals("Streaming from BitTorrent", torrentSourceLabel(null))
    }

    @Test fun savedProgressIsPreferredAndOlderDownloadsAreEstimatedFromDisk() {
        val directory = folder()
        withFile(directory, 0, 1000); withFile(directory, 1, 1000); withFile(directory, 2, 1000)
        TorrentStorage.writeProgress(directory, TorrentStream.sourceHash(source), TorrentProgress(hash, mapOf(0 to TorrentFileProgress(400, false))))
        val onDisk = mapOf("Episode 0.mkv" to 1000L, "Episode 1.mkv" to 600L, "Episode 2.mkv" to 1000L)
        val loaded = TorrentStorage.loadProgress(
            directory, listOf(episode(0), episode(1), episode(2), episode(3)),
            infoHashOf = { error("the saved progress already names the torrent") },
            allocated = { file -> onDisk[file.name] }
        )[source]!!
        assertEquals(hash, loaded.infoHash)
        // Reported by the torrent: used as is, even though the disk holds more.
        assertEquals(TorrentFileProgress(400, false), loaded.files[0])
        // Not reported: estimated from the space the sparse file occupies, and marked as unverified.
        assertEquals(TorrentFileProgress(600, false, measuredOnDisk = true), loaded.files[1])
        assertEquals(TorrentFileProgress(1000, true, measuredOnDisk = true), loaded.files[2])
        // A file that is not on disk reports nothing.
        assertFalse(3 in loaded.files)
    }

    @Test fun withoutAProgressFileTheInfoHashComesFromTheCachedMetadata() {
        val directory = folder()
        withFile(directory, 0, 1000)
        var asked: String? = null
        val loaded = TorrentStorage.loadProgress(directory, listOf(episode(0)), infoHashOf = { asked = it; hash }, allocated = { 1000L })[source]!!
        assertEquals(TorrentStream.sourceHash(source), asked)
        assertTrue(loaded.files[0]!!.measuredOnDisk)
        assertTrue(TorrentStorage.loadProgress(directory, listOf(episode(0)), infoHashOf = { null }, allocated = { 1000L }).isEmpty())
    }

    @Test fun onlyVerifiedCompleteFilesPlayStraightFromDisk() {
        val directory = folder()
        val file = withFile(directory, 0, 1000)
        fun progress(done: Boolean, measured: Boolean) = TorrentProgress(hash, mapOf(0 to TorrentFileProgress(1000, done, measured)))
        assertEquals(file.canonicalFile, TorrentStorage.verifiedFile(directory, episode(0), progress(done = true, measured = false))!!.canonicalFile)
        // A disk estimate is not trusted: the torrent checks the pieces once first.
        assertNull(TorrentStorage.verifiedFile(directory, episode(0), progress(done = true, measured = true)))
        assertNull(TorrentStorage.verifiedFile(directory, episode(0), progress(done = false, measured = false)))
        assertNull(TorrentStorage.verifiedFile(directory, episode(0), null))
        // The file changed size since it was verified, or was deleted.
        assertNull(TorrentStorage.verifiedFile(directory, episode(0, length = 999), progress(done = true, measured = false)))
        file.delete()
        assertNull(TorrentStorage.verifiedFile(directory, episode(0), progress(done = true, measured = false)))
        // A path that escapes the download folder never resolves.
        val escape = episode(0).copy(torrentPath = "../outside.mkv")
        assertNull(TorrentStorage.verifiedFile(directory, escape, progress(done = true, measured = false)))
    }
}
