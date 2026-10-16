package dev.andrompv

import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    private val sha = "e7524e458208ce9a7128978f9d84f1d1b6978eebe9837c093edba04c70c140ad"

    private fun release(
        tag: String = "v2.2.0",
        digest: String? = "sha256:$sha",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "# andrompv\n\n## What's new since v2.1.0\n\n**Fixes**\n\n- **Seek bar works.** Dragging follows the finger.\n- Magnet links open.\n\n## Downloads\n\n| Asset | Size |\n| --- | --- |\n| `a.apk` | 1 |\n\n## Install\n\nadb install",
        assets: String = """
            {"name":"andrompv-debug.apk","size":152901320,"browser_download_url":"https://github.com/hhaider3/andrompv/releases/download/$tag/andrompv-debug.apk"${if (digest != null) ""","digest":"$digest"""" else ""}},
            {"name":"andrompv-debug.apk.sha256","size":85,"browser_download_url":"https://github.com/hhaider3/andrompv/releases/download/$tag/andrompv-debug.apk.sha256"}
        """.trimIndent()
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/hhaider3/andrompv/releases/tag/$tag","body":${org.json.JSONObject.quote(body)},"assets":[$assets]}"""

    @Test fun versionsCompareNumericallyNotAlphabetically() {
        assertTrue(Updates.isNewer("v2.10.0", "2.9.9"))
        assertTrue(Updates.isNewer("2.1.1", "2.1.0"))
        assertTrue(Updates.isNewer("3.0", "2.99.99"))
        assertFalse(Updates.isNewer("2.1.0", "2.1.0"))
        assertFalse(Updates.isNewer("v2.1", "2.1.0"))
        assertFalse(Updates.isNewer("2.0.9", "2.1.0"))
        assertFalse(Updates.isNewer("latest", "2.1.0"))
        assertFalse(Updates.isNewer("2.2.0", "unknown"))
        assertEquals(listOf(2, 10, 1), Updates.versionParts("v2.10.1-beta"))
    }

    @Test fun parsesTheLatestReleaseWithItsDigest() {
        val parsed = Updates.parseRelease(release())!!
        assertEquals("v2.2.0", parsed.tag)
        assertEquals("2.2.0", parsed.version)
        assertEquals(sha, parsed.sha256)
        assertEquals(152901320L, parsed.apkSize)
        assertTrue(parsed.apkUrl.startsWith("https://github.com/hhaider3/andrompv/releases/download/v2.2.0/"))
    }

    @Test fun fallsBackToTheSidecarChecksumFile() {
        var asked: String? = null
        val parsed = Updates.parseRelease(release(digest = null)) { url -> asked = url; "$sha  andrompv-debug.apk\n" }!!
        assertEquals(sha, parsed.sha256)
        assertTrue(asked!!.endsWith("andrompv-debug.apk.sha256"))
        assertNull(Updates.parseRelease(release(digest = null)) { "not a checksum" }!!.sha256)
    }

    @Test fun ignoresDraftsPrereleasesAndReleasesWithoutAnApk() {
        assertNull(Updates.parseRelease(release(draft = true)))
        assertNull(Updates.parseRelease(release(prerelease = true)))
        assertNull(Updates.parseRelease(release(assets = """{"name":"notes.txt","browser_download_url":"https://example.com/notes.txt"}""")))
        assertNull(Updates.parseRelease(release(assets = """{"name":"a.apk","browser_download_url":"http://example.com/a.apk"}""")))
        assertNull(Updates.parseRelease("not json"))
    }

    @Test fun releaseNotesKeepOnlyWhatIsNew() {
        val notes = Updates.parseRelease(release())!!.notes
        assertEquals("Fixes\n• Seek bar works. Dragging follows the finger.\n• Magnet links open.", notes)
        assertFalse(notes.contains("adb install"))
        assertFalse(notes.contains("|"))
        assertTrue(Updates.releaseNotes("- a\n".repeat(600), limit = 100).length <= 102)
    }
}
