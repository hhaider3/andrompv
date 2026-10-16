package dev.andrompv

import org.json.JSONObject

/** A GitHub release that carries an installable APK. */
data class UpdateRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lowercase hex SHA-256 of the APK, from the release's own metadata. */
    val sha256: String?,
    val pageUrl: String
)

object Updates {
    const val REPOSITORY = "hhaider3/andrompv"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    /** "v2.10.1-beta" → [2, 10, 1]. Anything that is not a version yields an empty list. */
    fun versionParts(text: String): List<Int> =
        Regex("\\d+").findAll(text.trim().removePrefix("v").removePrefix("V").substringBefore('-'))
            .mapNotNull { it.value.toIntOrNull() }.toList()

    /** True when [candidate] is a strictly higher version than [current] (missing parts count as 0). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = versionParts(candidate)
        val b = versionParts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Reads GitHub's "latest release" JSON. Drafts and pre-releases are ignored, and so is any
     * release without an APK. The checksum comes from the asset's `digest`, falling back to the
     * contents of a `<apk>.sha256` file via [fetchText].
     */
    fun parseRelease(json: String, fetchText: (String) -> String? = { null }): UpdateRelease? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name").takeIf(String::isNotBlank) ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        val list = (0 until assets.length()).mapNotNull(assets::optJSONObject)
        val apk = list.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        val digest = apk.optString("digest").removePrefix("sha256:").lowercase().takeIf(::isSha256)
        val sidecar = digest ?: list
            .firstOrNull { it.optString("name").equals(apk.optString("name") + ".sha256", ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
            ?.let(fetchText)
            ?.trim()?.substringBefore(' ')?.lowercase()?.takeIf(::isSha256)
        return UpdateRelease(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            notes = releaseNotes(root.optString("body")),
            apkUrl = url,
            apkSize = apk.optLong("size"),
            sha256 = sidecar,
            pageUrl = root.optString("html_url")
        )
    }

    private fun isSha256(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

    /** The "What's new" part of a release body as plain text, without install and legal sections. */
    fun releaseNotes(body: String, limit: Int = 900): String {
        val text = body.replace("\r\n", "\n")
        val start = Regex("(?im)^##\\s*what.s new.*$").find(text)?.range?.last?.plus(1)
        val section = if (start != null) text.substring(start).substringBefore("\n## ") else text.substringAfter('\n', "")
        return section.lines()
            .map { it.trim().replace(Regex("^[-*]\\s+"), "• ").replace("**", "").replace("`", "") }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("|") }
            .joinToString("\n")
            .let { if (it.length > limit) it.take(limit).substringBeforeLast('\n') + "\n…" else it }
    }
}
