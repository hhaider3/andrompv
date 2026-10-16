package dev.andrompv.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.andrompv.MPVEngine
import dev.andrompv.PlayerController
import dev.andrompv.QueueItem
import dev.andrompv.playbackTime
import dev.andrompv.signedPlaybackTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Things only the activity can do: pickers, window modes, brightness. */
interface PlayerHost {
    val isFullscreen: Boolean
    val supportsPictureInPicture: Boolean
    fun openMedia()
    fun openSubtitleFile()
    fun takeScreenshot()
    fun toggleFullscreen()
    fun enterPictureInPicture()
    fun setSystemBarsHidden(hidden: Boolean)
    fun brightness(): Float
    fun setBrightness(value: Float)
}

sealed interface Sheet {
    data object OpenUrl : Sheet
    data object Info : Sheet
    data object Downloads : Sheet
    data object Settings : Sheet
    data object ClearQueue : Sheet
    data class Delete(val items: List<QueueItem>, val title: String) : Sheet
}

data class Hud(
    val icon: ImageVector,
    val text: String,
    val level: Float? = null,
    val sticky: Boolean = false,
    val id: Long = SystemClock.uptimeMillis()
)

data class SeekRipple(val forward: Boolean, val seconds: Int, val id: Long = SystemClock.uptimeMillis())

/** Transient UI state that does not belong to the player itself. */
@Stable
class PlayerUiState {
    var controlsVisible by mutableStateOf(true)
    var subtitlePanel by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var scrubbing by mutableStateOf(false)
    var holds by mutableIntStateOf(0)
    var interactions by mutableLongStateOf(0L)
    var hud by mutableStateOf<Hud?>(null)
    var ripple by mutableStateOf<SeekRipple?>(null)
    var gestureSeek by mutableStateOf<Pair<Double, Double>?>(null)
    var controlsHeightPx by mutableIntStateOf(0)

    fun interact() {
        interactions++
        controlsVisible = true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    player: PlayerController,
    ui: PlayerUiState,
    host: PlayerHost,
    inPictureInPicture: Boolean,
    video: @Composable () -> Unit
) {
    // No background here: the window background is the stage, and anything opaque drawn in
    // this window would cover the SurfaceView that mpv renders into beneath it.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val rootHeightPx = constraints.maxHeight
        video()
        if (inPictureInPicture) return@BoxWithConstraints

        val showControls = ui.controlsVisible && player.hasMedia
        val showHeader = ui.controlsVisible || !player.hasMedia

        LaunchedEffect(
            ui.controlsVisible, player.isPlaying, ui.interactions, ui.holds, ui.scrubbing,
            player.isSidebarVisible, ui.subtitlePanel, ui.sheet
        ) {
            if (ui.controlsVisible && player.isPlaying && ui.holds == 0 && !ui.scrubbing &&
                !player.isSidebarVisible && !ui.subtitlePanel && ui.sheet == null
            ) {
                delay(3500)
                ui.controlsVisible = false
            }
        }
        LaunchedEffect(player.isPaused, player.hasMedia) {
            if (player.isPaused || !player.hasMedia) ui.controlsVisible = true
        }
        LaunchedEffect(showControls) {
            if (!showControls) player.setControlsInset(0)
        }
        LaunchedEffect(host.isFullscreen, showControls, landscape, player.hasMedia) {
            host.setSystemBarsHidden(host.isFullscreen || (landscape && player.hasMedia && !showControls))
        }

        GestureLayer(player, ui, host, Modifier.fillMaxSize())

        AnimatedVisibility(showHeader && player.hasMedia, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            )
        }
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            )
        }

        if (!player.hasMedia) {
            WelcomeView(player, ui, host, Modifier.align(Alignment.Center))
        }

        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
        ) {
            AnimatedVisibility(
                showHeader,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Header(player, ui, host, wide)
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = if (wide) 20.dp else 10.dp)
                    .padding(bottom = if (wide) 16.dp else 8.dp)
                    .widthIn(max = 960.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NoticePill(player)
                ContextualSkip(player, ui, Modifier.align(Alignment.End))
                AnimatedVisibility(
                    showControls,
                    enter = fadeIn(tween(180)) + slideInVertically { it / 3 },
                    exit = fadeOut(tween(220)) + slideOutVertically { it / 3 }
                ) {
                    ControlsBar(
                        player, ui, host, wide,
                        Modifier.onGloballyPositioned { coordinates ->
                            val top = coordinates.boundsInRoot().top
                            ui.controlsHeightPx = (rootHeightPx - top).roundToInt().coerceAtLeast(0)
                            if (ui.controlsVisible) player.setControlsInset(ui.controlsHeightPx)
                        }
                    )
                }
            }

            ErrorToast(player, Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp))
        }

        if (player.isLoading && player.hasMedia) LoadingBadge(player, Modifier.align(Alignment.Center))
        else if (player.hasMedia && !player.hasFile && player.torrentStatus != null) LoadingBadge(player, Modifier.align(Alignment.Center))

        SeekRippleOverlay(ui, Modifier.fillMaxSize())
        HudOverlay(player, ui, Modifier.align(Alignment.Center))

        QueuePanel(player, ui, host, wide)
        SubtitleSettingsPanel(player, ui, host, wide)
        Sheets(player, ui)
    }
}

@Composable
private fun Header(player: PlayerController, ui: PlayerUiState, host: PlayerHost, wide: Boolean) {
    val item = player.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (wide) 18.dp else 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!player.isSidebarVisible) {
            PlayerIconButton(
                Icons.Rounded.ViewSidebar, "Show queue",
                size = 38.dp, iconSize = 20.dp, background = Color.Black.copy(alpha = 0.32f)
            ) { ui.interact(); player.setSidebarVisible(true) }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                item?.title ?: "andrompv",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = when {
                item == null -> if (MPVEngine.preferHardwareDecoding() && player.settings.hardwareDecoding)
                    "Ready · hardware-accelerated mpv" else "Ready · mpv software decoding"
                item.isTorrent && player.torrentStatus != null -> player.torrentStatus
                player.metadata?.badges?.isNotEmpty() == true -> player.metadata!!.badges.joinToString("  •  ")
                player.isLoading -> "Loading…"
                else -> item.fileExtension.ifEmpty { "STREAM" }
            }
            if (meta != null) {
                Text(
                    meta,
                    color = Palette.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        PillButton("Open", icon = Icons.Rounded.Add, contentDescription = "Open media files") { ui.interact(); host.openMedia() }
        PlayerIconButton(
            Icons.Rounded.Link, "Open network stream",
            size = 38.dp, iconSize = 20.dp, background = Color.Black.copy(alpha = 0.32f)
        ) { ui.interact(); ui.sheet = Sheet.OpenUrl }
        Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.32f))) {
            MoreMenu(player, ui, host, includeTools = !wide && player.hasMedia, size = 38)
        }
    }
}

@Composable
private fun WelcomeView(player: PlayerController, ui: PlayerUiState, host: PlayerHost, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 28.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A radial gradient instead of Modifier.blur: a RenderEffect anywhere in this
            // window stops the mpv SurfaceView beneath it from showing through.
            Box(
                Modifier
                    .size(140.dp)
                    .background(
                        Brush.radialGradient(
                            0f to Palette.indigo.copy(alpha = 0.85f),
                            0.45f to Palette.cyan.copy(alpha = 0.35f),
                            1f to Color.Transparent
                        ),
                        CircleShape
                    )
            )
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Play something beautiful", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open a video, audio file, playlist or torrent, or connect to a stream.",
            color = Palette.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                "Open Media",
                icon = Icons.Rounded.FolderOpen,
                background = Color.White,
                contentColor = Palette.playGlyph,
                border = false,
                height = 44.dp,
                horizontalPadding = 18.dp,
                fontSize = 14
            ) { host.openMedia() }
            PillButton(
                "Open URL",
                icon = Icons.Rounded.Link,
                background = Palette.fill,
                height = 44.dp,
                horizontalPadding = 18.dp,
                fontSize = 14
            ) { ui.sheet = Sheet.OpenUrl }
        }
        if (player.items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { player.items.firstOrNull()?.let { player.playItem(it.id) } }) {
                val count = player.items.size
                Text("Play queue · $count ${if (count == 1) "item" else "items"}", color = Palette.accent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LoadingBadge(player: PlayerController, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .glass(CircleShape, opacity = 0.7f),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        }
        val status = player.torrentStatus
        if (player.current?.isTorrent == true && status != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                status,
                color = Palette.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ErrorToast(player: PlayerController, modifier: Modifier) {
    val message = player.error
    AnimatedVisibility(message != null, enter = fadeIn() + slideInVertically(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .glass(RoundedCornerShape(50), fill = Color(0xFF2A1C14), opacity = 0.92f)
                .border(1.dp, Palette.warning.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.WarningAmber, null, tint = Palette.warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                message.orEmpty(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            PlayerIconButton(Icons.Rounded.Close, "Dismiss error", size = 34.dp, iconSize = 16.dp) { player.dismissError() }
        }
    }
}

@Composable
private fun NoticePill(player: PlayerController) {
    val notice = player.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(if (notice.action != null) 5000 else 2800)
            if (player.notice?.id == notice.id) player.dismissNotice()
        }
    }
    AnimatedVisibility(notice != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
        Row(
            Modifier
                .padding(bottom = 10.dp)
                .glass(RoundedCornerShape(50), opacity = 0.92f)
                .padding(start = 16.dp, end = if (notice?.action != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(notice?.message.orEmpty(), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            if (notice?.action != null && notice.actionLabel != null) {
                TextButton(onClick = { notice.action.invoke(); player.dismissNotice() }) {
                    Text(notice.actionLabel, color = Palette.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Offers "Skip Intro" before the intro marker and "Skip Outro" once the outro begins. */
@Composable
private fun ContextualSkip(player: PlayerController, ui: PlayerUiState, modifier: Modifier) {
    val markers = player.markers
    val position = player.position
    val intro = markers.intro?.takeIf { player.hasFile && position < it - 1 }
    val outro = markers.outro?.takeIf { player.hasFile && position >= it && (player.duration <= 0 || position < player.duration - 1) }
    AnimatedVisibility(intro != null || outro != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        SkipChip(
            text = if (intro != null) "Skip Intro" else if (player.canGoNext) "Skip Outro" else "Skip to End",
            onClick = { ui.interact(); if (intro != null) player.skipIntro() else player.skipOutro() },
            modifier = Modifier.padding(bottom = 10.dp, end = 4.dp)
        )
    }
}

@Composable
private fun HudOverlay(player: PlayerController, ui: PlayerUiState, modifier: Modifier) {
    val hud = ui.hud
    LaunchedEffect(hud?.id, hud?.sticky) {
        if (hud != null && !hud.sticky) {
            delay(900)
            if (ui.hud?.id == hud.id) ui.hud = null
        }
    }
    val seek = ui.gestureSeek
    if (seek != null) {
        val (target, delta) = seek
        val frame = rememberScrubFrame(player, target)
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (frame != null) PreviewCard(frame, target)
            Spacer(Modifier.height(10.dp))
            Text(
                "${playbackTime(target)} / ${playbackTime(player.duration)}   ${signedPlaybackTime(delta)}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glass(RoundedCornerShape(50), opacity = 0.8f)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
        return
    }
    AnimatedVisibility(hud != null, enter = fadeIn(tween(100)), exit = fadeOut(tween(220)), modifier = modifier) {
        val shown = hud ?: return@AnimatedVisibility
        Row(
            Modifier
                .glass(RoundedCornerShape(50), opacity = 0.82f)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(shown.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(shown.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (shown.level != null) {
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { shown.level },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                    modifier = Modifier.width(96.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun SeekRippleOverlay(ui: PlayerUiState, modifier: Modifier) {
    val ripple = ui.ripple
    LaunchedEffect(ripple?.id) {
        if (ripple != null) {
            delay(750)
            if (ui.ripple?.id == ripple.id) ui.ripple = null
        }
    }
    Box(modifier) {
        listOf(false, true).forEach { forward ->
            AnimatedVisibility(
                ripple?.forward == forward,
                enter = fadeIn(tween(90)),
                exit = fadeOut(tween(260)),
                modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
            ) {
                val shape = if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(160.dp)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Text("${ui.ripple?.seconds ?: 0} seconds", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS, PINCH }

/**
 * Video gestures. Tap toggles the controls; double-tap the sides to seek or the middle for
 * full screen (macmpv's double-click); hold for 2× speed; swipe sideways to seek, up/down on
 * the right for volume and on the left for brightness; pinch to zoom to fill.
 */
@Composable
private fun GestureLayer(player: PlayerController, ui: PlayerUiState, host: PlayerHost, modifier: Modifier) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        tryAwaitRelease()
                        if (player.isHoldingSpeed) {
                            player.endHoldSpeed()
                            ui.hud = null
                        }
                    },
                    onTap = {
                        when {
                            player.isSidebarVisible -> player.setSidebarVisible(false)
                            ui.subtitlePanel -> ui.subtitlePanel = false
                            player.hasMedia -> {
                                ui.controlsVisible = !ui.controlsVisible
                                if (ui.controlsVisible) ui.interact()
                            }
                        }
                    },
                    onDoubleTap = { offset ->
                        val fraction = offset.x / size.width
                        val step = player.settings.seekStep
                        when {
                            player.hasFile && fraction < 0.35f -> {
                                player.seekBy(-step.toDouble())
                                val previous = ui.ripple?.takeIf { !it.forward && SystemClock.uptimeMillis() - it.id < 900 }
                                ui.ripple = SeekRipple(false, (previous?.seconds ?: 0) + step)
                            }
                            player.hasFile && fraction > 0.65f -> {
                                player.seekBy(step.toDouble())
                                val previous = ui.ripple?.takeIf { it.forward && SystemClock.uptimeMillis() - it.id < 900 }
                                ui.ripple = SeekRipple(true, (previous?.seconds ?: 0) + step)
                            }
                            player.hasMedia -> host.toggleFullscreen()
                        }
                    },
                    onLongPress = {
                        if (player.settings.holdForSpeed && player.isPlaying) {
                            player.beginHoldSpeed()
                            ui.hud = Hud(Icons.Rounded.FastForward, "${dev.andrompv.speedLabel((player.speed * 2).coerceAtMost(4.0))} speed", sticky = true)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = DragMode.NONE
                    var total = Offset.Zero
                    var pinchStart = 0f
                    var pinchHandled = false
                    val startPosition = player.position
                    val startVolume = if (player.isMuted) 0.0 else player.volume
                    val startBrightness = host.brightness()
                    val seekSpan = player.duration.coerceIn(0.0, 120.0)
                    var target = startPosition
                    val trackedId: PointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && mode in setOf(DragMode.NONE, DragMode.PINCH) && player.hasFile) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (mode == DragMode.NONE) {
                                mode = DragMode.PINCH
                                pinchStart = distance
                            } else if (!pinchHandled && pinchStart > 0) {
                                val ratio = distance / pinchStart
                                val fit = when {
                                    ratio > 1.2f -> dev.andrompv.VideoFit.FILL
                                    ratio < 0.83f -> dev.andrompv.VideoFit.FIT
                                    else -> null
                                }
                                if (fit != null) {
                                    pinchHandled = true
                                    player.setVideoFit(fit)
                                    ui.hud = Hud(Icons.Rounded.ZoomOutMap, fit.label)
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == trackedId } ?: break
                        if (mode == DragMode.PINCH) { change.consume(); continue }
                        total += change.positionChange()
                        if (mode == DragMode.NONE && total.getDistance() > touchSlop * 1.5f &&
                            player.settings.swipeGestures && player.hasMedia && !player.isSidebarVisible
                        ) {
                            val edge = with(density) { 24.dp.toPx() }
                            // Leave the screen edges to the system back and notification gestures.
                            val nearEdge = down.position.y < edge * 2 || down.position.x < edge || down.position.x > size.width - edge
                            mode = when {
                                nearEdge -> DragMode.NONE
                                abs(total.x) > abs(total.y) * 1.2f && player.hasFile && player.duration > 0 -> DragMode.SEEK
                                abs(total.y) > abs(total.x) * 1.2f && down.position.x > size.width / 2 -> DragMode.VOLUME
                                abs(total.y) > abs(total.x) * 1.2f -> DragMode.BRIGHTNESS
                                else -> DragMode.NONE
                            }
                            if (mode == DragMode.SEEK) {
                                player.beginScrub()
                                ui.scrubbing = true
                            }
                        }
                        when (mode) {
                            DragMode.SEEK -> {
                                target = (startPosition + total.x / size.width * seekSpan).coerceIn(0.0, player.duration)
                                player.scrub(target)
                                ui.gestureSeek = target to (target - startPosition)
                            }
                            DragMode.VOLUME -> {
                                val volume = (startVolume - total.y / (size.height * 0.6f) * 100).coerceIn(0.0, 100.0)
                                player.setVolume(volume)
                                ui.hud = Hud(
                                    if (volume <= 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                    "${volume.roundToInt()}%", (volume / 100).toFloat(), sticky = true
                                )
                            }
                            DragMode.BRIGHTNESS -> {
                                val brightness = (startBrightness - total.y / (size.height * 0.6f)).coerceIn(0.01f, 1f)
                                host.setBrightness(brightness)
                                ui.hud = Hud(Icons.Rounded.BrightnessMedium, "${(brightness * 100).roundToInt()}%", brightness, sticky = true)
                            }
                            else -> Unit
                        }
                        if (mode != DragMode.NONE) change.consume()
                    }
                    when (mode) {
                        DragMode.SEEK -> {
                            player.endScrub(target)
                            ui.scrubbing = false
                            ui.gestureSeek = null
                        }
                        DragMode.VOLUME, DragMode.BRIGHTNESS -> ui.hud = ui.hud?.copy(sticky = false, id = SystemClock.uptimeMillis())
                        else -> Unit
                    }
                }
            }
    )
}

/** Swallows touches so taps on a panel never reach the video gestures beneath it. */
fun Modifier.blockTouches(): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = {}
)
