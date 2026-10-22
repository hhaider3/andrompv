package dev.andrompv

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.libtorrent4j.TorrentInfo
import java.io.File

/** Where torrents keep their files and what has been saved of them. */
object TorrentStorage {
    fun directory(context: Context) = File(context.filesDir, "Torrents").apply { mkdirs() }

    fun progressFile(directory: File, sourceHash: String) = File(directory, "$sourceHash.progress.json")

    fun readProgress(directory: File, sourceHash: String): TorrentProgress? =
        runCatching { TorrentProgress.parse(progressFile(directory, sourceHash).readText()) }.getOrNull()

    /** Written whole and renamed into place, so a reader never sees half a file. */
    fun writeProgress(directory: File, sourceHash: String, progress: TorrentProgress) {
        val target = progressFile(directory, sourceHash)
        val temporary = File(directory, "$sourceHash.progress.json.tmp")
        temporary.writeText(progress.toJson())
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not save torrent progress" }
        }
    }

    fun infoHashOf(directory: File, sourceHash: String): String? = runCatching {
        TorrentInfo(File(directory, "$sourceHash.torrent")).takeIf { it.isValid }?.infoHash()?.toHex()
    }.getOrNull()

    /**
     * The disk space a download occupies. libtorrent writes only verified pieces into sparse files, so
     * allocated blocks track how much is saved. An estimate: a few out-of-order pieces can be counted
     * early. Null when the file does not exist.
     */
    fun allocatedBytes(file: File): Long? = runCatching {
        val stat = Os.stat(file.path)
        if (OsConstants.S_ISREG(stat.st_mode)) stat.st_blocks * 512L else null
    }.getOrNull()

    /**
     * Progress of every queued torrent file, by source: what libtorrent last saved, or for files it has
     * not reported (downloads from before progress files existed) an estimate from disk usage. Files
     * missing from disk report nothing.
     */
    fun loadProgress(
        directory: File,
        items: List<QueueItem>,
        infoHashOf: (sourceHash: String) -> String?,
        allocated: (File) -> Long? = ::allocatedBytes
    ): Map<String, TorrentProgress> {
        val result = mutableMapOf<String, TorrentProgress>()
        items.filter { it.torrentIndex != null && it.torrentPath != null }.groupBy { it.uri }.forEach { (uri, members) ->
            val sourceHash = TorrentStream.sourceHash(uri)
            val saved = readProgress(directory, sourceHash)
            val infoHash = saved?.infoHash ?: infoHashOf(sourceHash) ?: return@forEach
            val folder = File(directory, infoHash)
            val files = mutableMapOf<Int, TorrentFileProgress>()
            for (item in members) {
                val file = runCatching { TorrentStream.safeFile(folder, item.torrentPath!!) }.getOrNull() ?: continue
                val bytes = allocated(file) ?: continue
                files[item.torrentIndex!!] = saved?.files?.get(item.torrentIndex)
                    ?: TorrentFileProgress(minOf(bytes, item.length), item.length > 0 && bytes >= item.length, measuredOnDisk = true)
            }
            result[uri] = TorrentProgress(infoHash, files)
        }
        return result
    }

    /** A file libtorrent verified as fully saved, which plays from disk without starting the torrent. */
    fun verifiedFile(directory: File, item: QueueItem, progress: TorrentProgress?): File? {
        val index = item.torrentIndex ?: return null
        val path = item.torrentPath ?: return null
        val saved = progress?.files?.get(index) ?: return null
        if (!saved.done || saved.measuredOnDisk || item.length <= 0) return null
        val folder = File(directory, progress.infoHash ?: return null)
        val file = runCatching { TorrentStream.safeFile(folder, path) }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.length() == item.length }
    }
}
