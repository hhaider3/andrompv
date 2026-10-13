package dev.andrompv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlayerStore(context: Context) {
    private val preferences = context.getSharedPreferences("andrompv", Context.MODE_PRIVATE)
    private fun objectFor(key: String) = runCatching { JSONObject(preferences.getString(key, "{}")!!) }.getOrDefault(JSONObject())
    private val positions = objectFor("playback.positions.v1")
    private val markers = objectFor("playback.markers.v1")
    var volume: Double
        get() = preferences.getFloat("volume", 80f).toDouble().coerceIn(0.0, 100.0)
        set(value) { preferences.edit().putFloat("volume", value.toFloat()).apply() }
    var style: SubtitleStyle
        get() {
            val o = objectFor("subtitles.settings.v1")
            return SubtitleStyle(o.optDouble("scale", 1.0), o.optDouble("outline", 1.65), o.optBoolean("bold"),
                o.optDouble("delay", 0.0), o.optString("color", "#FFFFFF"), o.optDouble("background", 0.0))
        }
        set(s) {
            preferences.edit().putString("subtitles.settings.v1", JSONObject().put("scale", s.scale).put("outline", s.outline)
                .put("bold", s.bold).put("delay", s.delay).put("color", s.color).put("background", s.background).toString()).apply()
        }
    fun resume(item: QueueItem) = positions.optDouble(item.key, 0.0).takeIf { it.isFinite() } ?: 0.0
    fun remember(item: QueueItem, position: Double, duration: Double) {
        val resume = resumePosition(position, duration)
        // An early pause must not erase a previously saved resume point.
        if (resume != null) positions.put(item.key, resume)
        else if (duration > 0 && position >= duration - 10) positions.remove(item.key)
        preferences.edit().putString("playback.positions.v1", positions.toString()).apply()
    }
    fun finished(item: QueueItem) {
        positions.remove(item.key)
        preferences.edit().putString("playback.positions.v1", positions.toString()).apply()
    }
    fun markers(item: QueueItem): Markers {
        val o = markers.optJSONObject(item.key) ?: return Markers()
        return Markers(o.optDouble("intro").takeIf(Double::isFinite), o.optDouble("outro").takeIf(Double::isFinite))
    }
    fun saveMarkers(item: QueueItem, value: Markers) {
        markers.put(item.key, JSONObject().put("intro", value.intro ?: JSONObject.NULL).put("outro", value.outro ?: JSONObject.NULL))
        preferences.edit().putString("playback.markers.v1", markers.toString()).apply()
    }
    fun save(queue: PlaybackQueue) {
        val array = JSONArray()
        queue.items.forEach {
            array.put(JSONObject().put("uri", it.uri).put("title", it.title).put("id", it.id).put("group", it.group)
                .put("groupTitle", it.groupTitle).put("torrentIndex", it.torrentIndex ?: JSONObject.NULL)
                .put("torrentPath", it.torrentPath ?: JSONObject.NULL).put("length", it.length))
        }
        preferences.edit().putString("player.queue.v1", JSONObject().put("items", array).put("repeat", queue.repeat.name).toString()).apply()
    }
    fun restore(queue: PlaybackQueue) {
        val o = objectFor("player.queue.v1")
        queue.repeat = runCatching { RepeatMode.valueOf(o.optString("repeat", "OFF")) }.getOrDefault(RepeatMode.OFF)
        val array = o.optJSONArray("items") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isBlank()) continue
            queue.items.add(QueueItem(uri, item.optString("title"), item.optString("id"), item.optString("group"),
                item.optString("groupTitle"), if (item.isNull("torrentIndex")) null else item.optInt("torrentIndex"),
                if (item.isNull("torrentPath")) null else item.optString("torrentPath"), item.optLong("length")))
        }
    }
}
