package dev.andrompv

import java.net.URI
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RepeatMode(val label: String, val shortLabel: String) {
    OFF("Repeat Off", "Off"),
    ALL("Repeat All", "All"),
    ONE("Repeat One", "One");

    fun next() = entries[(ordinal + 1) % entries.size]
}

enum class GroupKind { STANDALONE, PLAYLIST, TORRENT }

data class MediaMetadata(
    val duration: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val formatName: String? = null,
    val fileSize: Long? = null
) {
    val resolution: String?
        get() = if (width != null && height != null && width > 0 && height > 0) "$width × $height" else null

    val frameRateLabel: String?
        get() {
            val fps = frameRate ?: return null
            if (fps <= 0 || !fps.isFinite()) return null
            val rounded = fps.roundToInt()
            return if (abs(fps - rounded) < 0.01) "$rounded FPS" else "%.2f FPS".format(fps)
        }

    val badges: List<String>
        get() = listOfNotNull(
            resolution,
            (videoCodec ?: audioCodec)?.uppercase(),
            frameRateLabel
        )
}

data class QueueItem(
    val uri: String,
    val title: String,
    val id: String = UUID.randomUUID().toString(),
    val group: String = UUID.randomUUID().toString(),
    val groupTitle: String = title,
    val torrentIndex: Int? = null,
    val torrentPath: String? = null,
    val length: Long = 0,
    val groupKind: GroupKind = if (torrentIndex != null || MediaSupport.isTorrent(uri, title)) GroupKind.TORRENT else GroupKind.STANDALONE,
    val metadata: MediaMetadata? = null,
    val probeFailed: Boolean = false
) {
    val key: String get() = uri + (torrentIndex?.let { "#torrent-file=$it" } ?: "")
    val isTorrent: Boolean get() = torrentIndex != null || MediaSupport.isTorrent(uri, title)
    val fileExtension: String
        get() {
            val raw = torrentPath ?: title.ifBlank { uri }
            return MediaSupport.extension(raw).uppercase()
        }
    val sizeLabel: String?
        get() = formatByteSize(length.takeIf { it > 0 } ?: metadata?.fileSize)
}

data class QueueGroup(
    val id: String,
    val title: String,
    val kind: GroupKind,
    val items: List<QueueItem>
) {
    val isCollapsible: Boolean get() = kind != GroupKind.STANDALONE || items.size > 1
}

data class TrackInfo(
    val id: String,
    val label: String,
    val detail: String?,
    val selected: Boolean
)

enum class VideoFit(val label: String) {
    FIT("Fit to screen"),
    FILL("Zoom to fill"),
    STRETCH("Stretch");

    fun next() = entries[(ordinal + 1) % entries.size]
}

data class SubtitleStyle(
    val scale: Double = 1.0,
    val outline: Double = 1.65,
    val bold: Boolean = false,
    val delay: Double = 0.0,
    val color: String = "#FFFFFF",
    val background: Double = 0.0
)

object MediaSupport {
    val extensions = setOf(
        "3g2", "3gp", "aac", "ac3", "aiff", "alac", "ape", "asf", "avi",
        "av1", "caf", "divx", "dts", "dv", "f4v", "flac", "flv", "m2ts",
        "m4a", "m4v", "mka", "mkv", "mov", "mp2", "mp3", "mp4", "mpeg",
        "mpg", "mts", "oga", "ogg", "ogm", "ogv", "opus", "rm", "rmvb",
        "ts", "vob", "wav", "webm", "wma", "wmv"
    )
    fun extension(name: String) = name.substringBefore('?').substringAfterLast('.', "").lowercase()
    fun isTorrent(uri: String, title: String = uri) = uri.startsWith("magnet:", true) || extension(title) in setOf("torrent", "magnet")
    fun isNetwork(text: String): Boolean = runCatching {
        val uri = URI(text)
        if (uri.scheme.equals("magnet", true)) {
            uri.rawSchemeSpecificPart.contains(Regex("(?i)(?:^|[?&])xt=urn(?:%3a|:)(?:btih|btmh)(?:%3a|:)[a-z0-9]+"))
        } else uri.scheme?.lowercase() in setOf("http", "https", "rtmp", "rtsp") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
    /**
     * The `dn` (display name) of a magnet link. Magnet URIs are opaque ("magnet:?…"), so
     * Android's Uri query helpers throw on them; parse the query by hand instead.
     */
    fun magnetName(magnet: String): String? = magnet.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.substring(3)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun isHls(text: String) = text.uppercase().let { "#EXT-X-TARGETDURATION" in it || "#EXT-X-STREAM-INF" in it || "#EXT-X-MEDIA-SEQUENCE" in it }
    fun playlistEntries(text: String, resolve: (String) -> String?): List<String> {
        if (isHls(text)) return emptyList()
        return text.removePrefix("\uFEFF").lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }.mapNotNull(resolve).distinct().toList()
    }
}

class PlaybackQueue {
    val items = mutableListOf<QueueItem>()
    var currentId: String? = null
    var repeat = RepeatMode.OFF
    /** Collections are expanded by default; only the user's collapsed choices persist. */
    val collapsedGroups = mutableSetOf<String>()
    val current: QueueItem? get() = items.firstOrNull { it.id == currentId }
    val currentIndex: Int get() = items.indexOfFirst { it.id == currentId }

    val canGoNext: Boolean
        get() {
            val idx = currentIndex
            if (idx < 0) return false
            return idx < items.lastIndex || repeat == RepeatMode.ALL
        }

    fun canGoPrevious(position: Double): Boolean {
        val idx = currentIndex
        if (idx < 0) return false
        return idx > 0 || repeat == RepeatMode.ALL || position > 3.0
    }

    val groups: List<QueueGroup>
        get() {
            val result = mutableListOf<QueueGroup>()
            val indexByGroup = mutableMapOf<String, Int>()
            for (item in items) {
                val existing = indexByGroup[item.group]
                if (existing != null) {
                    val prev = result[existing]
                    result[existing] = prev.copy(items = prev.items + item)
                } else {
                    indexByGroup[item.group] = result.size
                    result.add(QueueGroup(item.group, item.groupTitle, item.groupKind, listOf(item)))
                }
            }
            return result
        }

    fun enqueue(newItems: List<QueueItem>): QueueItem? {
        val accepted = newItems.map { item -> items.firstOrNull { it.key == item.key } ?: item.also(items::add) }
        return accepted.firstOrNull()
    }

    fun next(automatic: Boolean = false): QueueItem? {
        val index = items.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        if (automatic && repeat == RepeatMode.ONE) return items[index]
        return items.getOrNull(index + 1) ?: if (repeat == RepeatMode.ALL) items.firstOrNull() else null
    }

    fun previous(position: Double): QueueItem? {
        val index = items.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return if (position > 3) items[index] else items.getOrNull(index - 1)
            ?: if (repeat == RepeatMode.ALL) items.lastOrNull() else null
    }

    fun move(id: String, delta: Int) {
        val index = items.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in items.indices) return
        val item = items.removeAt(index)
        items.add(target, item)
    }

    fun moveBefore(draggedId: String, destinationId: String) {
        if (draggedId == destinationId) return
        val sourceIndex = items.indexOfFirst { it.id == draggedId }
        if (sourceIndex < 0) return
        val item = items.removeAt(sourceIndex)
        val destIndex = items.indexOfFirst { it.id == destinationId }
        if (destIndex < 0) {
            items.add(sourceIndex.coerceAtMost(items.size), item)
        } else {
            items.add(destIndex, item)
        }
    }

    /** Drag-and-drop: takes the target's place, landing after it when moving down and before it when moving up. */
    fun moveOnto(draggedId: String, targetId: String) {
        if (draggedId == targetId) return
        val source = items.indexOfFirst { it.id == draggedId }
        val target = items.indexOfFirst { it.id == targetId }
        if (source < 0 || target < 0) return
        val item = items.removeAt(source)
        items.add(target, item)
    }

    fun updateMetadata(id: String, metadata: MediaMetadata?, probeFailed: Boolean = false) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(metadata = metadata ?: items[index].metadata, probeFailed = probeFailed)
    }

    fun remove(ids: Set<String>): QueueItem? {
        val oldIndex = items.indexOfFirst { it.id == currentId }
        val wasCurrent = currentId in ids
        items.removeAll { it.id in ids }
        collapsedGroups.retainAll(items.mapTo(mutableSetOf()) { it.group })
        return if (wasCurrent) items.getOrNull(oldIndex.coerceAtMost(items.lastIndex)) else current
    }

    /** Replaces a torrent placeholder with its resolved files, keeping its place in the queue. */
    fun replace(id: String, replacements: List<QueueItem>): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items.removeAt(index)
        val fresh = replacements.distinctBy { it.key }.filter { candidate -> items.none { it.key == candidate.key } }
        items.addAll(index, fresh)
        return true
    }

    fun clear() {
        items.clear()
        currentId = null
        collapsedGroups.clear()
    }
}

/**
 * Matches macmpv's PlaybackCompletion:
 * mpv keeps the last frame open at EOF. Both the property and lifecycle event
 * may report completion; consume it once, and rearm after a seek (eof=false) or a new load.
 */
class PlaybackCompletion {
    private var loaded = false
    private var completed = false
    fun beginLoad() { loaded = false; completed = false }
    fun fileLoaded() { loaded = true; completed = false }
    fun observe(eof: Boolean): Boolean {
        if (!loaded) return false
        if (!eof) {
            completed = false
            return false
        }
        if (completed) return false
        completed = true
        return true
    }
}

fun resumePosition(position: Double, duration: Double): Double? = when {
    !position.isFinite() || !duration.isFinite() -> null
    duration > 0 && position >= duration - 10 -> null
    position >= 5 -> position
    else -> null
}

fun speedLabel(speed: Double): String =
    if (abs(speed - speed.roundToInt()) < 0.001) "${speed.roundToInt()}×"
    else "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×"

fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(java.util.Locale.US, bytesPerSecond / 1048576.0)
    bytesPerSecond >= 1024 -> "${bytesPerSecond / 1024} KB/s"
    else -> "$bytesPerSecond B/s"
}

/** "+1:05" style offsets for gesture feedback. */
fun signedPlaybackTime(seconds: Double): String = (if (seconds < 0) "−" else "+") + playbackTime(abs(seconds))

fun playbackTime(seconds: Double): String {
    val value = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    return if (value >= 3600) "%d:%02d:%02d".format(value / 3600, value % 3600 / 60, value % 60)
    else "%02d:%02d".format(value / 60, value % 60)
}

fun formatByteSize(bytes: Long?): String? {
    val b = bytes ?: return null
    if (b <= 0) return null
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        b >= gb -> "%.1f GB".format(b / gb)
        b >= mb -> "%.1f MB".format(b / mb)
        b >= kb -> "${(b / kb).roundToInt()} KB"
        else -> "$b B"
    }
}

/** Numeric chunks are compared without integer overflow (episode 2 precedes 10). */
val naturalOrder = Comparator<String> { left, right ->
    val pattern = Regex("\\d+|\\D+")
    val a = pattern.findAll(left.lowercase()).map { it.value }.toList()
    val b = pattern.findAll(right.lowercase()).map { it.value }.toList()
    var result = 0
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]; val y = b[i]
        result = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y)
        if (result != 0) break
    }
    if (result != 0) result else a.size.compareTo(b.size)
}

data class ByteRange(val start: Long, val end: Long) {
    val length get() = end - start + 1
    companion object {
        fun parse(header: String?, size: Long): ByteRange? {
            if (size <= 0) return null
            if (header == null) return ByteRange(0, size - 1)
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
            val (a, b) = match.destructured
            if (a.isEmpty()) {
                val suffix = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val start = a.toLongOrNull() ?: return null
            val end = if (b.isEmpty()) size - 1 else b.toLongOrNull() ?: return null
            if (start >= size || end < start) return null
            return ByteRange(start, end.coerceAtMost(size - 1))
        }
    }
}
