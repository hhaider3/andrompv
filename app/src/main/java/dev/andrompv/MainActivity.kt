package dev.andrompv

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import dev.andrompv.ui.AndrompvTheme
import dev.andrompv.ui.Hud
import dev.andrompv.ui.PlayerHost
import dev.andrompv.ui.PlayerScreen
import dev.andrompv.ui.PlayerUiState
import dev.andrompv.ui.Sheet
import java.io.File
import kotlin.math.roundToInt

/** Hosts the Compose player UI and the mpv surface; playback state lives in [PlayerController]. */
class MainActivity : ComponentActivity(), PlayerHost {
    lateinit var player: PlayerController
        private set
    val ui = PlayerUiState()
    private var fullscreenMode by mutableStateOf(false)
    private var inPictureInPicture by mutableStateOf(false)
    private var surfaceView: SurfaceView? = null
    private var pendingScreenshot: File? = null
    private val main = Handler(Looper.getMainLooper())

    override val isFullscreen: Boolean get() = fullscreenMode
    override val supportsPictureInPicture: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val openMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) player.enqueueUris(uris)
    }
    private val openSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(player::loadExternalSubtitle)
    }
    private val saveScreenshotLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = pendingScreenshot ?: return@registerForActivityResult
        pendingScreenshot = null
        if (uri != null) player.saveScreenshotTo(file, uri) else file.delete()
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = player.settings.seekStep.toDouble()
            when (intent.getStringExtra(EXTRA_PIP_ACTION)) {
                PIP_TOGGLE -> player.togglePlayback()
                PIP_REWIND -> player.seekBy(-step)
                PIP_FORWARD -> player.seekBy(step)
            }
            updatePictureInPictureParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        player = applicationContext.player
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            AndrompvTheme {
                val view = LocalView.current
                DisposableEffect(player.isPlaying) {
                    view.keepScreenOn = player.isPlaying
                    onDispose { }
                }
                LaunchedEffect(player.isPlaying, player.videoAspect, player.settings.autoPictureInPicture) {
                    updatePictureInPictureParams()
                }
                BackHandler(enabled = ui.subtitlePanel || player.isSidebarVisible || fullscreenMode) {
                    when {
                        ui.subtitlePanel -> ui.subtitlePanel = false
                        player.isSidebarVisible -> player.setSidebarVisible(false)
                        fullscreenMode -> setFullscreen(false)
                    }
                }
                PlayerScreen(player, ui, this, inPictureInPicture) {
                    AndroidView(factory = ::createSurface, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun createSurface(context: Context): SurfaceView = SurfaceView(context).also { view ->
        surfaceView = view
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player.attachSurface(holder.surface, view.width, view.height)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                player.surfaceResized(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player.detachSurface()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        player.onUiShown()
    }

    override fun onStop() {
        player.onUiHidden()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        main.removeCallbacksAndMessages(null)
        pendingScreenshot?.delete()
        surfaceView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(uris::add)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
            }
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(MediaSupport::isNetwork)?.let {
                uris.add(Uri.parse(it))
            }
        }
        if (uris.isNotEmpty()) player.enqueueUris(uris.distinct(), playNow = true)
    }

    // region PlayerHost

    override fun openMedia() = openMediaLauncher.launch(arrayOf("*/*"))

    override fun openSubtitleFile() {
        if (!player.hasMedia) {
            player.showError("Open a media file first")
            return
        }
        openSubtitleLauncher.launch(arrayOf("*/*"))
    }

    override fun toggleFullscreen() = setFullscreen(!fullscreenMode)

    private fun setFullscreen(enter: Boolean) {
        fullscreenMode = enter
        requestedOrientation = when {
            !enter || !player.settings.rotateInFullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            (player.videoAspect ?: 16.0 / 9) >= 1.0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    override fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun brightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    override fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    override fun takeScreenshot() {
        player.captureFrame { file ->
            if (file != null) deliverScreenshot(file) else captureSurface()
        }
    }

    /** Fallback when mpv cannot read the frame back: copy the composited surface instead. */
    private fun captureSurface() {
        val view = surfaceView
        if (view == null || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) {
            player.showError("Could not capture the video frame")
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(view, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                bitmap.recycle()
                player.showError("Could not capture the video frame ($result)")
                return@request
            }
            val target = player.screenshotFile()
            Thread {
                val written = runCatching {
                    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }.isSuccess
                bitmap.recycle()
                main.post {
                    if (written) deliverScreenshot(target) else player.showError("Screenshot failed")
                }
            }.start()
        }, main)
    }

    private fun deliverScreenshot(file: File) {
        if (player.canSaveToGallery) {
            player.saveScreenshotToGallery(file)
        } else {
            pendingScreenshot?.delete()
            pendingScreenshot = file
            saveScreenshotLauncher.launch(file.name)
        }
    }

    override fun enterPictureInPicture() {
        if (!supportsPictureInPicture) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
            .onFailure { player.showError("Picture-in-picture is unavailable") }
    }

    // endregion

    // region Picture-in-picture

    private fun pictureInPictureParams(): PictureInPictureParams {
        val aspect = (player.videoAspect ?: 16.0 / 9).coerceIn(1 / 2.39, 2.39)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 10_000).roundToInt(), 10_000))
            .setActions(
                listOf(
                    pipAction(PIP_REWIND, R.drawable.ic_pip_rewind, "Back ${player.settings.seekStep} seconds", 1),
                    if (player.isPlaying) pipAction(PIP_TOGGLE, R.drawable.ic_pip_pause, "Pause", 2)
                    else pipAction(PIP_TOGGLE, R.drawable.ic_pip_play, "Play", 2),
                    pipAction(PIP_FORWARD, R.drawable.ic_pip_forward, "Forward ${player.settings.seekStep} seconds", 3)
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player.settings.autoPictureInPicture && player.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipAction(action: String, icon: Int, title: String, requestCode: Int): RemoteAction {
        val intent = Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, action)
        val pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pending)
    }

    private fun updatePictureInPictureParams() {
        if (!supportsPictureInPicture) return
        runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically through setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && supportsPictureInPicture &&
            player.settings.autoPictureInPicture && player.isPlaying
        ) {
            enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            ui.subtitlePanel = false
            ui.sheet = null
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The picture-in-picture window was dismissed rather than expanded.
            player.setPlaying(false)
        }
    }

    // endregion

    // region Keyboard (macmpv's shortcuts)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleShortcut(keyCode, event) || super.onKeyDown(keyCode, event)

    private fun handleShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val command = event.isCtrlPressed || event.isMetaPressed
        val step = player.settings.seekStep.toDouble()
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.togglePlayback()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.setPlaying(true)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.setPlaying(false)
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.previous()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(step)
            KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-step)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (command) player.previous() else player.seekBy(-step)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (command) player.next() else player.seekBy(step)
            KeyEvent.KEYCODE_DPAD_UP -> adjustVolume(5.0)
            KeyEvent.KEYCODE_DPAD_DOWN -> adjustVolume(-5.0)
            KeyEvent.KEYCODE_M -> player.toggleMuted()
            KeyEvent.KEYCODE_LEFT_BRACKET -> showSpeed { player.adjustSpeed(-0.25) }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> showSpeed { player.adjustSpeed(0.25) }
            KeyEvent.KEYCODE_BACKSLASH -> showSpeed { player.setSpeed(1.0) }
            KeyEvent.KEYCODE_F -> toggleFullscreen()
            KeyEvent.KEYCODE_ESCAPE -> when {
                ui.subtitlePanel -> ui.subtitlePanel = false
                player.isSidebarVisible -> player.setSidebarVisible(false)
                fullscreenMode -> setFullscreen(false)
                else -> return false
            }
            KeyEvent.KEYCODE_O -> when {
                command && event.isShiftPressed -> openSubtitleFile()
                command -> openMedia()
                else -> return false
            }
            KeyEvent.KEYCODE_S -> when {
                command && event.isShiftPressed -> takeScreenshot()
                command && event.isAltPressed -> player.setSidebarVisible(!player.isSidebarVisible)
                else -> return false
            }
            KeyEvent.KEYCODE_L -> if (command) ui.sheet = Sheet.OpenUrl else return false
            else -> return false
        }
        ui.interact()
        return true
    }

    private fun adjustVolume(delta: Double) {
        player.adjustVolume(delta)
        ui.hud = Hud(
            if (player.volume <= 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            "${player.volume.roundToInt()}%",
            (player.volume / 100).toFloat()
        )
    }

    private fun showSpeed(change: () -> Unit) {
        change()
        ui.hud = Hud(Icons.Rounded.Speed, speedLabel(player.speed))
    }

    // endregion

    companion object {
        private const val ACTION_PIP = "dev.andrompv.PIP_ACTION"
        private const val EXTRA_PIP_ACTION = "action"
        private const val PIP_TOGGLE = "toggle"
        private const val PIP_REWIND = "rewind"
        private const val PIP_FORWARD = "forward"
    }
}
