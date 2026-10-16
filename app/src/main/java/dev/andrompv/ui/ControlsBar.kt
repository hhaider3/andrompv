package dev.andrompv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.andrompv.PlayerController
import dev.andrompv.RepeatMode
import dev.andrompv.SeekPreview
import dev.andrompv.VideoFit
import dev.andrompv.playbackTime
import dev.andrompv.speedLabel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

private val speeds = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

/**
 * macmpv's PlayerControls: a time row with the seek bar, then the queue/subtitle/audio
 * cluster, the transport cluster, and the volume/speed/screenshot/fullscreen cluster. Phones
 * stack the transport above a compact tool row.
 */
@Composable
fun ControlsBar(
    player: PlayerController,
    ui: PlayerUiState,
    host: PlayerHost,
    wide: Boolean,
    modifier: Modifier = Modifier
) {
    var outer by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var bar by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var scrubSeconds by remember { mutableStateOf<Double?>(null) }
    val density = LocalDensity.current

    Box(modifier.onGloballyPositioned { outer = it }) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp))
                .padding(start = 16.dp, end = 16.dp, top = if (wide) 12.dp else 10.dp, bottom = if (wide) 12.dp else 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(scrubSeconds ?: player.position)
                SeekBar(
                    position = scrubSeconds ?: player.position,
                    duration = player.duration,
                    bufferedAhead = player.bufferedAhead,
                    enabled = player.hasFile && player.duration > 0,
                    onScrubStart = { seconds ->
                        scrubSeconds = seconds
                        ui.scrubbing = true
                        player.beginScrub()
                    },
                    onScrub = { seconds ->
                        scrubSeconds = seconds
                        player.scrub(seconds)
                    },
                    onScrubEnd = { seconds ->
                        player.endScrub(seconds)
                        scrubSeconds = null
                        ui.scrubbing = false
                        ui.interact()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .onGloballyPositioned { bar = it }
                )
                TimeLabel(player.duration)
            }
            Spacer(Modifier.height(if (wide) 8.dp else 4.dp))
            if (wide) WideButtons(player, ui, host) else CompactButtons(player, ui, host)
        }

        val seconds = scrubSeconds
        val o = outer
        val b = bar
        if (seconds != null && o != null && b != null && o.isAttached && b.isAttached) {
            val frame = rememberScrubFrame(player, seconds)
            val barOrigin = o.localPositionOf(b, Offset.Zero)
            val fraction = if (player.duration > 0) (seconds / player.duration).coerceIn(0.0, 1.0).toFloat() else 0f
            val popoverWidth = with(density) { (if (frame != null) 196.dp else 76.dp).toPx() }
            val popoverHeight = with(density) { (if (frame != null) 138.dp else 30.dp).toPx() }
            val x = (barOrigin.x + fraction * b.size.width - popoverWidth / 2)
                .coerceIn(0f, (o.size.width - popoverWidth).coerceAtLeast(0f))
            val y = barOrigin.y - popoverHeight - with(density) { 14.dp.toPx() }
            PreviewCard(
                frame = frame,
                seconds = seconds,
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            )
        }
    }
}

@Composable
private fun TimeLabel(seconds: Double) {
    Text(
        playbackTime(seconds),
        color = Palette.textSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun WideButtons(player: PlayerController, ui: PlayerUiState, host: PlayerHost) {
    // Each side cluster gets half of what the transport leaves. Narrow rows (phones in
    // landscape) move the camera into the ⋮ menu and drop the volume slider, keeping mute.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showCamera = maxWidth >= 790.dp
        val showVolumeSlider = maxWidth >= 960.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                QueueToggle(player, ui)
                SubtitleMenu(player, ui, host)
                SubtitleStyleButton(player, ui)
                AudioMenu(player, ui)
            }
            Transport(player, ui, compact = false)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                MuteButton(player, ui)
                if (showVolumeSlider) VolumeSlider(player, ui, Modifier.widthIn(min = 64.dp, max = 96.dp).weight(1f, fill = false))
                SpeedMenu(player, ui)
                if (showCamera) {
                    PlayerIconButton(Icons.Rounded.PhotoCamera, "Save screenshot", enabled = player.hasFile) {
                        ui.interact(); host.takeScreenshot()
                    }
                }
                FullscreenButton(ui, host)
                MoreMenu(player, ui, host, includeTools = !showCamera)
                RotateButton(ui, host)
            }
        }
    }
}

@Composable
private fun CompactButtons(player: PlayerController, ui: PlayerUiState, host: PlayerHost) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Transport(player, ui, compact = true)
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueToggle(player, ui, size = 38)
            SubtitleMenu(player, ui, host, size = 38)
            SubtitleStyleButton(player, ui, size = 38)
            AudioMenu(player, ui, size = 38)
            Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
            SpeedMenu(player, ui)
            FullscreenButton(ui, host, size = 38)
            MoreMenu(player, ui, host, includeTools = true, size = 38)
            RotateButton(ui, host, size = 38)
        }
    }
}

@Composable
private fun Transport(player: PlayerController, ui: PlayerUiState, compact: Boolean) {
    val step = player.settings.seekStep
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIconButton(Icons.Rounded.SkipPrevious, "Previous", enabled = player.canGoPrevious, iconSize = 26.dp) {
            ui.interact(); player.previous()
        }
        PlayerIconButton(replayIcon(step), "Back $step seconds", enabled = player.hasFile, size = 44.dp, iconSize = 26.dp) {
            ui.interact(); player.seekBy(-step.toDouble())
        }
        PlayButton(player, ui, size = if (compact) 56 else 50)
        PlayerIconButton(forwardIcon(step), "Forward $step seconds", enabled = player.hasFile, size = 44.dp, iconSize = 26.dp) {
            ui.interact(); player.seekBy(step.toDouble())
        }
        PlayerIconButton(Icons.Rounded.SkipNext, "Next", enabled = player.canGoNext, iconSize = 26.dp) {
            ui.interact(); player.next()
        }
    }
}

fun replayIcon(step: Int): ImageVector = when (step) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

fun forwardIcon(step: Int): ImageVector = when (step) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.Forward
}

@Composable
fun PlayButton(player: PlayerController, ui: PlayerUiState, size: Int = 50) {
    val playing = !player.isPaused
    Box(
        Modifier
            .size(size.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClickLabel = if (playing) "Pause" else "Play") { ui.interact(); player.togglePlayback() }
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Palette.playGlyph,
            modifier = Modifier.size((size * 0.56f).dp)
        )
    }
}

@Composable
private fun QueueToggle(player: PlayerController, ui: PlayerUiState, size: Int = 40) {
    PlayerIconButton(
        Icons.Rounded.ViewSidebar,
        if (player.isSidebarVisible) "Hide queue" else "Show queue",
        size = size.dp,
        iconSize = 21.dp,
        tint = if (player.isSidebarVisible) Palette.accent else Color.White.copy(alpha = 0.92f)
    ) {
        ui.interact(); player.setSidebarVisible(!player.isSidebarVisible)
    }
}

@Composable
private fun SubtitleStyleButton(player: PlayerController, ui: PlayerUiState, size: Int = 40) {
    PlayerIconButton(
        Icons.Rounded.TextFields,
        "Subtitle settings",
        enabled = player.hasMedia,
        size = size.dp,
        iconSize = 21.dp,
        tint = if (ui.subtitlePanel) Palette.accent else Color.White.copy(alpha = 0.92f)
    ) {
        ui.interact(); ui.subtitlePanel = !ui.subtitlePanel
    }
}

@Composable
private fun MuteButton(player: PlayerController, ui: PlayerUiState, size: Int = 40) {
    val silent = player.isMuted || player.volume <= 0.0
    PlayerIconButton(
        if (silent) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
        if (player.isMuted) "Unmute" else "Mute",
        size = size.dp,
        iconSize = 21.dp
    ) {
        ui.interact(); player.toggleMuted()
    }
}

@Composable
private fun VolumeSlider(player: PlayerController, ui: PlayerUiState, modifier: Modifier) {
    ThinSlider(
        value = if (player.isMuted) 0f else player.volume.toFloat(),
        onValueChange = { ui.interact(); player.setVolume(it.toDouble()) },
        valueRange = 0f..100f,
        modifier = modifier.semantics { contentDescription = "Volume" }
    )
}

/** macmpv-style slider: a thin white track and a small round thumb (not Material 3's bar thumb). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val colors = whiteSliderColors()
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = colors,
        modifier = modifier.height(32.dp),
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                modifier = Modifier.height(4.dp)
            )
        }
    )
}

@Composable
fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

@Composable
private fun FullscreenButton(ui: PlayerUiState, host: PlayerHost, size: Int = 40) {
    PlayerIconButton(
        if (host.isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
        if (host.isFullscreen) "Exit full screen" else "Enter full screen",
        size = size.dp,
        iconSize = 24.dp
    ) {
        ui.interact(); host.toggleFullscreen()
    }
}

@Composable
private fun RotateButton(ui: PlayerUiState, host: PlayerHost, size: Int = 40) {
    PlayerIconButton(Icons.Rounded.ScreenRotation, "Rotate screen", size = size.dp, iconSize = 22.dp) {
        ui.interact(); host.rotateScreen()
    }
}

/** Keeps the auto-hiding controls up while a menu attached to them is open. */
@Composable
fun HoldControls(ui: PlayerUiState, active: Boolean) {
    DisposableEffect(active) {
        if (active) ui.holds++
        onDispose { if (active) ui.holds-- }
    }
}

@Composable
private fun SubtitleMenu(player: PlayerController, ui: PlayerUiState, host: PlayerHost, size: Int = 40) {
    var open by remember { mutableStateOf(false) }
    HoldControls(ui, open)
    val active = player.subtitleTracks.firstOrNull { it.selected }
    Box {
        PlayerIconButton(
            if (active != null) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled,
            if (active != null) "Subtitles (${active.label})" else "Subtitles (Off)",
            enabled = player.hasMedia,
            size = size.dp,
            iconSize = 22.dp,
            tint = if (active != null) Palette.accent else Color.White.copy(alpha = 0.92f)
        ) { ui.interact(); open = true }
        GlassMenu(open, { open = false }) {
            MenuLabel("Subtitles")
            MenuEntry("Off", checked = active == null, onClick = { open = false; player.selectSubtitleTrack(null) })
            player.subtitleTracks.forEach { track ->
                MenuEntry(track.label, checked = track.selected, detail = track.detail, onClick = {
                    open = false; player.selectSubtitleTrack(track.id)
                })
            }
            MenuDivider()
            MenuEntry("Open Subtitle File…", icon = Icons.Rounded.FolderOpen, onClick = { open = false; host.openSubtitleFile() })
            MenuEntry("Subtitle Settings…", icon = Icons.Rounded.Tune, onClick = { open = false; ui.subtitlePanel = true })
        }
    }
}

@Composable
private fun AudioMenu(player: PlayerController, ui: PlayerUiState, size: Int = 40) {
    var open by remember { mutableStateOf(false) }
    HoldControls(ui, open)
    Box {
        PlayerIconButton(Icons.Rounded.GraphicEq, "Audio tracks", enabled = player.hasMedia, size = size.dp, iconSize = 22.dp) {
            ui.interact(); open = true
        }
        GlassMenu(open, { open = false }) {
            MenuLabel("Audio")
            if (player.audioTracks.isEmpty()) {
                MenuEntry("No audio tracks", enabled = false, onClick = {})
            }
            player.audioTracks.forEach { track ->
                MenuEntry(track.label, checked = track.selected, detail = track.detail, onClick = {
                    open = false; player.selectAudioTrack(track.id)
                })
            }
        }
    }
}

@Composable
private fun SpeedMenu(player: PlayerController, ui: PlayerUiState) {
    var open by remember { mutableStateOf(false) }
    HoldControls(ui, open)
    Box {
        Text(
            speedLabel(player.speed),
            color = if (player.speed != 1.0) Palette.accent else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .height(32.dp)
                .widthIn(min = 42.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.fill)
                .clickable(onClickLabel = "Playback speed") { ui.interact(); open = true }
                .semantics { contentDescription = "Playback speed ${speedLabel(player.speed)}" }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
        GlassMenu(open, { open = false }) {
            MenuLabel("Speed")
            speeds.forEach { value ->
                MenuEntry(speedLabel(value), checked = kotlin.math.abs(value - player.speed) < 0.001, onClick = {
                    open = false; player.setSpeed(value)
                })
            }
        }
    }
}

@Composable
fun MoreMenu(player: PlayerController, ui: PlayerUiState, host: PlayerHost, includeTools: Boolean, size: Int = 40) {
    var open by remember { mutableStateOf(false) }
    HoldControls(ui, open)
    Box {
        PlayerIconButton(Icons.Rounded.MoreVert, "More options", size = size.dp, iconSize = 22.dp) {
            ui.interact(); open = true
        }
        GlassMenu(open, { open = false }) {
            if (includeTools) {
                MenuEntry(
                    if (player.isMuted) "Unmute" else "Mute",
                    icon = if (player.isMuted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    onClick = { open = false; player.toggleMuted() }
                )
                MenuEntry("Save Screenshot", icon = Icons.Rounded.PhotoCamera, enabled = player.hasFile, onClick = {
                    open = false; host.takeScreenshot()
                })
                MenuDivider()
            }
            if (host.supportsPictureInPicture) {
                MenuEntry("Picture-in-Picture", icon = Icons.Rounded.PictureInPictureAlt, enabled = player.hasFile, onClick = {
                    open = false; host.enterPictureInPicture()
                })
            }
            MenuLabel("Video")
            VideoFit.entries.forEach { fit ->
                MenuEntry(fit.label, checked = player.videoFit == fit, onClick = { open = false; player.setVideoFit(fit) })
            }
            MenuLabel("Repeat")
            RepeatMode.entries.forEach { mode ->
                MenuEntry(mode.label, checked = player.repeat == mode, onClick = {
                    open = false
                    while (player.repeat != mode) player.cycleRepeat()
                })
            }
            MenuDivider()
            MenuEntry("Media Info", icon = Icons.Rounded.Info, enabled = player.hasMedia, onClick = { open = false; ui.sheet = Sheet.Info })
            MenuEntry("Torrent Downloads", icon = Icons.Rounded.CloudDownload, onClick = { open = false; ui.sheet = Sheet.Downloads })
            MenuEntry("Settings", icon = Icons.Rounded.Settings, onClick = { open = false; ui.sheet = Sheet.Settings })
        }
    }
}

/**
 * Touch seek bar: drag anywhere on it to scrub, tap to jump. Shows the buffered range and
 * and grows while held.
 */
@Composable
fun SeekBar(
    position: Double,
    duration: Double,
    bufferedAhead: Double,
    enabled: Boolean,
    onScrubStart: (Double) -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (dragging) 6.dp else 4.dp, label = "track")
    val thumbRadius by animateDpAsState(if (dragging) 9.dp else if (enabled) 6.5.dp else 0.dp, label = "thumb")
    val fraction = if (duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val bufferedFraction = if (duration > 0) ((position + bufferedAhead) / duration).coerceIn(0.0, 1.0).toFloat() else 0f

    // The gesture outlives recompositions; read the latest values instead of restarting it.
    val currentDuration by rememberUpdatedState(duration)
    val currentEnabled by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onScrubStart)
    val move by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)

    fun secondsAt(x: Float) = (x / width).coerceIn(0f, 1f) * currentDuration

    Canvas(
        modifier
            .height(32.dp)
            .onGloballyPositioned { width = it.size.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..duration.toFloat().coerceAtLeast(0f))
                if (enabled) setProgress { target ->
                    onScrubStart(position); onScrubEnd(target.toDouble()); true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    var last = secondsAt(down.position.x)
                    start(last)
                    move(last)
                    try {
                        horizontalDrag(down.id) { change ->
                            // Read the position before consuming: a consumed change reports no movement.
                            last = secondsAt(change.position.x)
                            move(last)
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        end(last)
                    }
                }
            }
    ) {
        val h = trackHeight.toPx()
        val y = size.height / 2
        val radius = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(0f, y - h / 2), Size(size.width, h), radius)
        if (bufferedFraction > fraction) {
            drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(0f, y - h / 2), Size(size.width * bufferedFraction, h), radius)
        }
        drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(size.width * fraction, h), radius)
        if (thumbRadius.toPx() > 0f) {
            drawCircle(Color.Black.copy(alpha = 0.25f), thumbRadius.toPx() + 1.5f, Offset(size.width * fraction, y + 1f))
            drawCircle(Color.White, thumbRadius.toPx(), Offset(size.width * fraction, y))
        }
    }
}

/** Thumbnail for the time under the finger: the nearest keyframe first, the exact frame after 300 ms at rest. */
@Composable
fun rememberScrubFrame(player: PlayerController, seconds: Double?): SeekPreview.Frame? {
    var frame by remember { mutableStateOf<SeekPreview.Frame?>(null) }
    val source = player.resolvedSource
    DisposableEffect(Unit) {
        onDispose { player.previews.cancel() }
    }
    LaunchedEffect(seconds, source) {
        val target = seconds ?: return@LaunchedEffect
        if (source == null || !player.previews.supports(source)) return@LaunchedEffect
        player.previews.request(source, target) { if (it != null) frame = it }
        delay(300)
        player.previews.request(source, target, exact = true) { if (it != null) frame = it }
    }
    return frame
}

@Composable
fun PreviewCard(frame: SeekPreview.Frame?, seconds: Double, modifier: Modifier = Modifier) {
    val image = remember(frame) { frame?.bitmap?.asImageBitmap() }
    Column(
        modifier
            .width(if (image != null) 196.dp else 76.dp)
            .shadow(14.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF17181C))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .background(Color.Black)
            )
        }
        Text(
            playbackTime(seconds),
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}
