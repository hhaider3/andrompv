package dev.andrompv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User preferences that have no macOS counterpart, plus the shared player defaults. */
data class PlayerSettings(
    /** Emulator copy-back codecs can stall, so they start with software decoding. */
    val hardwareDecoding: Boolean = MPVEngine.preferHardwareDecoding(),
    /** mpv's high-quality scalers; emulator GL renders them as black frames. */
    val highQualityScaling: Boolean = MPVEngine.preferHardwareDecoding(),
    val backgroundPlayback: Boolean = false,
    val autoPictureInPicture: Boolean = true,
    val seekStep: Int = 10,
    val swipeGestures: Boolean = true,
    val holdForSpeed: Boolean = true,
    val rememberPosition: Boolean = true,
    val rotateInFullscreen: Boolean = true
) {
    companion object {
        val seekSteps = listOf(5, 10, 15, 30)
    }
}

class PlayerStore(context: Context) {
    private val preferences = context.getSharedPreferences("andrompv", Context.MODE_PRIVATE)
    private fun objectFor(key: String) = runCatching { JSONObject(preferences.getString(key, "{}")!!) }.getOrDefault(JSONObject())
    private val positions = loadPositions()
    private val markers = objectFor("playback.markers.v1")

    var volume: Double
        get() = preferences.getFloat("volume", 80f).toDouble().coerceIn(0.0, 100.0)
        set(value) { preferences.edit().putFloat("volume", value.toFloat()).apply() }

    var sidebarVisible: Boolean
        get() = preferences.getBoolean("sidebar.visible.v1", false)
        set(value) { preferences.edit().putBoolean("sidebar.visible.v1", value).apply() }

    var style: SubtitleStyle
        get() {
            val o = objectFor("subtitles.settings.v1")
            return SubtitleStyle(
                o.optDouble("scale", 1.0),
                o.optDouble("outline", 1.65),
                o.optBoolean("bold"),
                o.optDouble("delay", 0.0),
                o.optString("color", "#FFFFFF"),
                o.optDouble("background", 0.0)
            )
        }
        set(s) {
            preferences.edit().putString(
                "subtitles.settings.v1",
                JSONObject()
                    .put("scale", s.scale)
                    .put("outline", s.outline)
                    .put("bold", s.bold)
                    .put("delay", s.delay)
                    .put("color", s.color)
                    .put("background", s.background)
                    .toString()
            ).apply()
        }

    var settings: PlayerSettings
        get() {
            val o = objectFor("player.settings.v1")
            val defaults = PlayerSettings()
            return PlayerSettings(
                hardwareDecoding = o.optBoolean("hardwareDecoding", defaults.hardwareDecoding),
                highQualityScaling = o.optBoolean("highQualityScaling", defaults.highQualityScaling),
                backgroundPlayback = o.optBoolean("backgroundPlayback", defaults.backgroundPlayback),
                autoPictureInPicture = o.optBoolean("autoPictureInPicture", defaults.autoPictureInPicture),
                seekStep = o.optInt("seekStep", defaults.seekStep).takeIf { it in PlayerSettings.seekSteps } ?: defaults.seekStep,
                swipeGestures = o.optBoolean("swipeGestures", defaults.swipeGestures),
                holdForSpeed = o.optBoolean("holdForSpeed", defaults.holdForSpeed),
                rememberPosition = o.optBoolean("rememberPosition", defaults.rememberPosition),
                rotateInFullscreen = o.optBoolean("rotateInFullscreen", defaults.rotateInFullscreen)
            )
        }
        set(s) {
            preferences.edit().putString(
                "player.settings.v1",
                JSONObject()
                    .put("hardwareDecoding", s.hardwareDecoding)
                    .put("highQualityScaling", s.highQualityScaling)
                    .put("backgroundPlayback", s.backgroundPlayback)
                    .put("autoPictureInPicture", s.autoPictureInPicture)
                    .put("seekStep", s.seekStep)
                    .put("swipeGestures", s.swipeGestures)
                    .put("holdForSpeed", s.holdForSpeed)
                    .put("rememberPosition", s.rememberPosition)
                    .put("rotateInFullscreen", s.rotateInFullscreen)
                    .toString()
            ).apply()
        }

    fun resume(item: QueueItem) = positions.optJSONObject(item.key)?.optDouble("position", 0.0)
        ?.takeIf { it.isFinite() } ?: 0.0

    fun remember(item: QueueItem, position: Double, duration: Double) {
        val resume = resumePosition(position, duration)
        // An early pause must not erase a previously saved resume point.
        if (resume != null) {
            positions.put(item.key, JSONObject().put("position", resume).put("updated", System.currentTimeMillis()))
            prunePositions(positions, MAX_REMEMBERED_POSITIONS)
        } else if (duration > 0 && position >= duration - 10) {
            positions.remove(item.key)
        }
        persistPositions()
    }

    fun finished(item: QueueItem) {
        positions.remove(item.key)
        persistPositions()
    }

    fun clearPositions() {
        positions.keys().asSequence().toList().forEach(positions::remove)
        persistPositions()
    }

    val rememberedPositionCount: Int get() = positions.length()

    private fun persistPositions() {
        preferences.edit().putString(POSITIONS_KEY, positions.toString()).apply()
    }

    /** v1 stored bare numbers; v2 stores `{position, updated}` so the oldest entries can be pruned. */
    private fun loadPositions(): JSONObject {
        objectFor(POSITIONS_KEY).takeIf { it.length() > 0 }?.let { return it }
        val legacy = objectFor(LEGACY_POSITIONS_KEY)
        val migrated = JSONObject()
        legacy.keys().forEach { key ->
            val value = legacy.optDouble(key)
            if (value.isFinite()) migrated.put(key, JSONObject().put("position", value).put("updated", 0L))
        }
        if (migrated.length() > 0) {
            prunePositions(migrated, MAX_REMEMBERED_POSITIONS)
            preferences.edit().putString(POSITIONS_KEY, migrated.toString()).remove(LEGACY_POSITIONS_KEY).apply()
        }
        return migrated
    }

    fun markers(item: QueueItem): Markers {
        val o = markers.optJSONObject(item.key) ?: return Markers()
        return Markers(
            o.optDouble("intro").takeIf(Double::isFinite),
            o.optDouble("outro").takeIf(Double::isFinite)
        )
    }

    fun saveMarkers(item: QueueItem, value: Markers) {
        if (value == markers(item)) return
        if (value.intro == null && value.outro == null) markers.remove(item.key)
        else markers.put(
            item.key,
            JSONObject()
                .put("intro", value.intro ?: JSONObject.NULL)
                .put("outro", value.outro ?: JSONObject.NULL)
        )
        preferences.edit().putString("playback.markers.v1", markers.toString()).apply()
    }

    fun save(queue: PlaybackQueue) {
        val array = JSONArray()
        queue.items.forEach { item ->
            val obj = JSONObject()
                .put("uri", item.uri)
                .put("title", item.title)
                .put("id", item.id)
                .put("group", item.group)
                .put("groupTitle", item.groupTitle)
                .put("groupKind", item.groupKind.name)
                .put("torrentIndex", item.torrentIndex ?: JSONObject.NULL)
                .put("torrentPath", item.torrentPath ?: JSONObject.NULL)
                .put("length", item.length)
                .put("probeFailed", item.probeFailed)
            item.metadata?.let { meta ->
                obj.put(
                    "metadata",
                    JSONObject()
                        .put("duration", meta.duration ?: JSONObject.NULL)
                        .put("width", meta.width ?: JSONObject.NULL)
                        .put("height", meta.height ?: JSONObject.NULL)
                        .put("frameRate", meta.frameRate ?: JSONObject.NULL)
                        .put("videoCodec", meta.videoCodec ?: JSONObject.NULL)
                        .put("audioCodec", meta.audioCodec ?: JSONObject.NULL)
                        .put("audioChannels", meta.audioChannels ?: JSONObject.NULL)
                        .put("formatName", meta.formatName ?: JSONObject.NULL)
                        .put("fileSize", meta.fileSize ?: JSONObject.NULL)
                )
            }
            array.put(obj)
        }
        preferences.edit().putString(
            "player.queue.v1",
            JSONObject()
                .put("items", array)
                .put("repeat", queue.repeat.name)
                .put("currentId", queue.currentId ?: JSONObject.NULL)
                .put("collapsed", JSONArray(queue.collapsedGroups.toList()))
                .toString()
        ).apply()
    }

    fun restore(queue: PlaybackQueue) {
        val o = objectFor("player.queue.v1")
        queue.repeat = runCatching { RepeatMode.valueOf(o.optString("repeat", "OFF")) }.getOrDefault(RepeatMode.OFF)
        val array = o.optJSONArray("items") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isBlank()) continue
            val tIdx = if (item.isNull("torrentIndex")) null else item.optInt("torrentIndex")
            val tPath = if (item.isNull("torrentPath")) null else item.optString("torrentPath")
            val title = item.optString("title")
            val defaultKind = if (tIdx != null || MediaSupport.isTorrent(uri, title)) GroupKind.TORRENT else GroupKind.STANDALONE
            val kind = runCatching { GroupKind.valueOf(item.optString("groupKind", defaultKind.name)) }.getOrDefault(defaultKind)
            val metaObj = item.optJSONObject("metadata")
            val meta = metaObj?.let { m ->
                MediaMetadata(
                    duration = m.optDouble("duration").takeIf(Double::isFinite),
                    width = if (m.isNull("width")) null else m.optInt("width"),
                    height = if (m.isNull("height")) null else m.optInt("height"),
                    frameRate = m.optDouble("frameRate").takeIf(Double::isFinite),
                    videoCodec = m.optString("videoCodec").takeIf(String::isNotBlank),
                    audioCodec = m.optString("audioCodec").takeIf(String::isNotBlank),
                    audioChannels = if (m.isNull("audioChannels")) null else m.optInt("audioChannels"),
                    formatName = m.optString("formatName").takeIf(String::isNotBlank),
                    fileSize = if (m.isNull("fileSize")) null else m.optLong("fileSize")
                )
            }
            queue.items.add(
                QueueItem(
                    uri = uri,
                    title = title,
                    id = item.optString("id"),
                    group = item.optString("group"),
                    groupTitle = item.optString("groupTitle"),
                    torrentIndex = tIdx,
                    torrentPath = tPath,
                    length = item.optLong("length"),
                    groupKind = kind,
                    metadata = meta,
                    probeFailed = item.optBoolean("probeFailed", false)
                )
            )
        }
        val savedCurrent = if (o.isNull("currentId")) null else o.optString("currentId").takeIf { it.isNotBlank() }
        if (savedCurrent != null && queue.items.any { it.id == savedCurrent }) queue.currentId = savedCurrent
        o.optJSONArray("collapsed")?.let { collapsed ->
            val groups = queue.items.mapTo(mutableSetOf()) { it.group }
            for (i in 0 until collapsed.length()) collapsed.optString(i).takeIf { it in groups }?.let(queue.collapsedGroups::add)
        }
    }

    companion object {
        const val MAX_REMEMBERED_POSITIONS = 500
        private const val POSITIONS_KEY = "playback.positions.v2"
        private const val LEGACY_POSITIONS_KEY = "playback.positions.v1"

        /** Keeps the [limit] most recently updated entries; undated (migrated) entries go first. */
        fun prunePositions(positions: JSONObject, limit: Int) {
            if (positions.length() <= limit) return
            positions.keys().asSequence().toList()
                .sortedBy { positions.optJSONObject(it)?.optLong("updated", 0L) ?: 0L }
                .take(positions.length() - limit)
                .forEach(positions::remove)
        }
    }
}
