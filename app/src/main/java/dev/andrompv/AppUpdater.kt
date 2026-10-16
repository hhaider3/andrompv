package dev.andrompv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val bytes: Long, val total: Long) : UpdateState
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState
}

/**
 * Looks for a newer GitHub release each time the app is opened, downloads its APK, checks the
 * checksum and the package, and hands it to Android's installer. Android itself refuses an APK
 * that is not signed with the same key as the installed app, so an update cannot swap in
 * another publisher's build. App-scoped; state is observable from Compose.
 */
class AppUpdater(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val directory = File(app.cacheDir, "updates")
    private val cancelled = AtomicBoolean(false)
    private var lastCheck = 0L

    var state by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    /** Whether the update dialog is up. Closing it ("Later") leaves the update available in Settings. */
    var dialogVisible by mutableStateOf(false); private set
    /** The result of the latest check the user asked for, shown once. */
    var manualResult by mutableStateOf<String?>(null); private set

    val currentVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val currentCode: Long = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    init {
        // Whatever was left from an earlier download is either installed by now or stale.
        worker.execute { directory.listFiles()?.forEach { runCatching { it.delete() } } }
    }

    /** Automatic checks run at most every ten minutes, so reopening the app repeatedly is polite to the API. */
    fun checkOnLaunch() {
        if (SystemClock.elapsedRealtime() - lastCheck < MIN_INTERVAL_MS && lastCheck != 0L) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val busy = state
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading || busy is UpdateState.Ready) {
            if (manual && busy !is UpdateState.Checking) dialogVisible = true
            return
        }
        lastCheck = SystemClock.elapsedRealtime()
        if (manual) manualResult = null
        state = UpdateState.Checking
        worker.execute {
            val result = runCatching { fetchLatest() }
            main.post {
                result.fold(
                    onSuccess = { release ->
                        val skipped = preferences.getString(SKIPPED_KEY, null)
                        when {
                            release == null || !Updates.isNewer(release.version, currentVersion) -> {
                                state = UpdateState.Idle
                                if (manual) manualResult = "andrompv $currentVersion is up to date."
                            }
                            !manual && release.version == skipped -> state = UpdateState.Idle
                            else -> {
                                state = UpdateState.Available(release)
                                dialogVisible = true
                            }
                        }
                    },
                    onFailure = {
                        state = UpdateState.Idle
                        android.util.Log.w("andrompv-update", "Update check failed", it)
                        if (manual) manualResult = "Couldn't check for updates. Check your connection and try again."
                    }
                )
            }
        }
    }

    fun consumeManualResult(): String? = manualResult.also { manualResult = null }

    fun showDialog() { if (state !is UpdateState.Idle && state !is UpdateState.Checking) dialogVisible = true }
    fun dismissDialog() { dialogVisible = false }

    fun skipThisVersion() {
        (state as? UpdateState.Available)?.release?.let { preferences.edit().putString(SKIPPED_KEY, it.version).apply() }
        state = UpdateState.Idle
        dialogVisible = false
    }

    fun download() {
        val release = (state as? UpdateState.Available)?.release ?: return
        cancelled.set(false)
        state = UpdateState.Downloading(release, 0, release.apkSize)
        worker.execute {
            val result = runCatching { downloadApk(release) }
            main.post {
                result.fold(
                    onSuccess = { file -> state = UpdateState.Ready(release, file) },
                    onFailure = {
                        state = UpdateState.Available(release)
                        if (!cancelled.get()) {
                            android.util.Log.w("andrompv-update", "Update download failed", it)
                            manualResult = it.message ?: "The download failed."
                        }
                    }
                )
            }
        }
    }

    fun cancelDownload() { cancelled.set(true) }

    /** Opens Android's installer for the downloaded APK, or the "allow installs" screen first when needed. */
    fun install() {
        val ready = state as? UpdateState.Ready ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            manualResult = "Allow installs from andrompv, then come back and tap Install."
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "andrompv/$currentVersion")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection
    }

    private fun fetchText(url: String): String? = runCatching {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) null else connection.inputStream.use { it.readBytesLimited(64 * 1024).toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun fetchLatest(): UpdateRelease? {
        val connection = open(Updates.LATEST_URL)
        try {
            // 404 means the project has no releases yet, which is not an error.
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
            val body = connection.inputStream.use { it.readBytesLimited(1024 * 1024).toString(Charsets.UTF_8) }
            return Updates.parseRelease(body, ::fetchText)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApk(release: UpdateRelease): File {
        directory.mkdirs()
        val target = File(directory, "andrompv-${release.version}.apk")
        val partial = File(directory, "andrompv-${release.version}.apk.part")
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != 200) throw IOException("The download failed (${connection.responseCode}).")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            if (total > 0 && directory.usableSpace < total + 32 * 1024 * 1024) {
                throw IOException("Not enough free storage for the update (${formatByteSize(total)} needed).")
            }
            var received = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled.get()) throw IOException("Download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 150) {
                            lastReport = now
                            val bytes = received
                            main.post { if (state is UpdateState.Downloading) state = UpdateState.Downloading(release, bytes, total) }
                        }
                    }
                }
            }
            if (total > 0 && received != total) throw IOException("The download was cut short. Try again.")
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !actual.equals(release.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("The downloaded file did not match its checksum, so it was discarded.")
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Could not save the update.")
        verifyPackage(target, release)
        return target
    }

    /** Refuses anything that is not a newer build of this very app. */
    private fun verifyPackage(file: File, release: UpdateRelease) {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        val problem = when {
            info == null -> "The downloaded file is not a valid app."
            info.packageName != app.packageName -> "The downloaded app is not andrompv."
            info.longVersionCode <= currentCode -> "The downloaded version is not newer than the installed one."
            else -> null
        }
        if (problem != null) {
            file.delete()
            throw IOException(problem)
        }
        android.util.Log.i("andrompv-update", "Verified ${release.tag} (${file.length()} bytes)")
    }

    private companion object {
        const val SKIPPED_KEY = "skipped.version"
        const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
