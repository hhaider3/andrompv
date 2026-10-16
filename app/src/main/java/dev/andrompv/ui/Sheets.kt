package dev.andrompv.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.andrompv.MPVEngine
import dev.andrompv.updater
import dev.andrompv.UpdateState
import dev.andrompv.AppUpdater
import dev.andrompv.MediaSupport
import dev.andrompv.PlayerController
import dev.andrompv.PlayerSettings
import dev.andrompv.SubtitleStyle
import dev.andrompv.TorrentStream
import dev.andrompv.formatByteSize
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

private val subtitleColors = listOf(
    "#FFFFFF", "#FFE66D", "#FFD60A", "#7FDBFF", "#9BE38A", "#FF9ECD", "#FFB347", "#C8C8C8"
)

/**
 * macmpv's non-modal subtitle popover: every change applies live and persists. It floats
 * above the controls so the video (and the subtitles being tuned) stays visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleSettingsPanel(player: PlayerController, ui: PlayerUiState, host: PlayerHost, wide: Boolean) {
    HoldControls(ui, ui.subtitlePanel)
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        val controls: Dp = with(density) { ui.controlsHeightPx.toDp() }
        val room = maxHeight - controls - 72.dp
        val aboveControls = room >= 240.dp
        val alignment = when {
            !aboveControls -> Alignment.TopEnd
            wide -> Alignment.BottomStart
            else -> Alignment.BottomCenter
        }
        AnimatedVisibility(
            ui.subtitlePanel,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier
                .align(alignment)
                .padding(horizontal = if (wide) 20.dp else 10.dp)
                .padding(top = if (aboveControls) 0.dp else 10.dp, bottom = if (aboveControls) controls + 10.dp else 10.dp)
        ) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .heightIn(max = if (aboveControls) room else maxHeight - 20.dp)
                    .glass(RoundedCornerShape(20.dp), fill = Palette.glassStrong, opacity = 0.95f)
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                SubtitleSettingsContent(player, ui, host)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleSettingsContent(player: PlayerController, ui: PlayerUiState, host: PlayerHost) {
    val style = player.subtitleStyle
    fun update(change: (SubtitleStyle) -> SubtitleStyle) = player.updateSubtitleStyle(change(player.subtitleStyle))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Subtitles", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        PillButton("Open File…", icon = Icons.Rounded.FolderOpen, background = Palette.fill, height = 32.dp, fontSize = 12) {
            host.openSubtitleFile()
        }
        Spacer(Modifier.width(6.dp))
        PlayerIconButton(Icons.Rounded.Close, "Close subtitle settings", size = 32.dp, iconSize = 16.dp, background = Palette.fill) {
            ui.subtitlePanel = false
        }
    }
    Spacer(Modifier.height(14.dp))
    LabeledSlider("Size", "${(style.scale * 100).roundToInt()}%", style.scale.toFloat(), 0.5f..2.5f) { value ->
        update { it.copy(scale = (value * 20).roundToInt() / 20.0) }
    }
    LabeledSlider("Outline", String.format(Locale.US, "%.1f", style.outline), style.outline.toFloat(), 0f..8f) { value ->
        update { it.copy(outline = (value * 10).roundToInt() / 10.0) }
    }
    LabeledSlider("Background", "${(style.background * 100).roundToInt()}%", style.background.toFloat(), 0f..1f) { value ->
        update { it.copy(background = (value * 100).roundToInt() / 100.0) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Bold", color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = style.bold,
            onCheckedChange = { checked -> update { it.copy(bold = checked) } },
            colors = switchColors(),
            modifier = Modifier.semantics { contentDescription = "Bold subtitles" }
        )
    }
    Text("Text Color", color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        subtitleColors.forEach { hex ->
            val selected = style.color.equals(hex, ignoreCase = true)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(hex)))
                    .border(if (selected) 3.dp else 1.dp, if (selected) Palette.accent else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable(onClickLabel = "Subtitle color $hex") { update { it.copy(color = hex) } }
                    .semantics { contentDescription = "Subtitle color $hex" }
            )
        }
    }
    HorizontalDivider(color = Palette.stroke, modifier = Modifier.padding(vertical = 14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Delay", color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            String.format(Locale.US, "%+.1fs", style.delay),
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerIconButton(Icons.Rounded.Remove, "Subtitles earlier", size = 34.dp, iconSize = 18.dp, background = Palette.fill) {
            update { it.copy(delay = ((it.delay - 0.1) * 10).roundToInt() / 10.0) }
        }
        ThinSlider(
            value = style.delay.toFloat(),
            onValueChange = { value -> update { it.copy(delay = (value * 10).roundToInt() / 10.0) } },
            valueRange = -10f..10f,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics { contentDescription = "Subtitle delay" }
        )
        PlayerIconButton(Icons.Rounded.Add, "Subtitles later", size = 34.dp, iconSize = 18.dp, background = Palette.fill) {
            update { it.copy(delay = ((it.delay + 0.1) * 10).roundToInt() / 10.0) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { player.resetSubtitleStyle() }) {
            Text("Reset All", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Row {
            Text(label, color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        ThinSlider(
            value = current,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = "Subtitle $label" }
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.playGlyph,
    checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
    uncheckedTrackColor = Palette.fill,
    uncheckedBorderColor = Palette.strokeStrong
)

@Composable
fun Sheets(player: PlayerController, ui: PlayerUiState) {
    val updater = LocalContext.current.updater
    val manual = updater.manualResult
    LaunchedEffect(manual) {
        manual?.let { message -> player.showNotice(message); updater.consumeManualResult() }
    }
    if (updater.dialogVisible) UpdateDialog(updater)
    when (val sheet = ui.sheet) {
        Sheet.OpenUrl -> OpenUrlDialog(player) { ui.sheet = null }
        Sheet.Info -> MediaInfoDialog(player) { ui.sheet = null }
        Sheet.Downloads -> DownloadsDialog(player) { ui.sheet = null }
        Sheet.Settings -> SettingsDialog(player) { ui.sheet = null }
        Sheet.ClearQueue -> ConfirmDialog(
            title = "Clear queue?",
            message = "Removes the queue entries only. Your files and torrent downloads are kept.",
            confirm = "Clear Queue",
            onConfirm = { player.clearQueue() },
            onDismiss = { ui.sheet = null }
        )
        is Sheet.Delete -> ConfirmDialog(
            title = if (sheet.items.size == 1) "Delete “${sheet.title}”?" else "Delete ${sheet.items.size} items?",
            message = "Android has no app-wide Trash. This permanently deletes the selected documents or app-owned " +
                "torrent downloads and removes them from the queue. It cannot be undone.",
            confirm = "Delete",
            onConfirm = { player.deleteItems(sheet.items) },
            onDismiss = { ui.sheet = null }
        )
        null -> Unit
    }
}

@Composable
private fun UpdateDialog(updater: AppUpdater) {
    val state = updater.state
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> { updater.dismissDialog(); return }
    }
    GlassDialog(
        title = if (state is UpdateState.Ready) "Ready to install" else "Update available",
        subtitle = "andrompv ${release.version} · you have ${updater.currentVersion}",
        icon = Icons.Rounded.SystemUpdate,
        onDismiss = updater::dismissDialog,
        actions = {
            when (state) {
                is UpdateState.Available -> {
                    TextButton(onClick = updater::skipThisVersion) { Text("Skip this version", color = Palette.textSecondary, fontSize = 13.sp) }
                    TextButton(onClick = updater::dismissDialog) { Text("Later", color = Palette.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    PillButton(
                        "Download${formatByteSize(release.apkSize)?.let { " · $it" } ?: ""}",
                        icon = Icons.Rounded.Download,
                        background = Color.White,
                        contentColor = Palette.playGlyph,
                        border = false,
                        onClick = updater::download
                    )
                }
                is UpdateState.Downloading -> TextButton(onClick = updater::cancelDownload) { Text("Cancel", color = Palette.textSecondary) }
                is UpdateState.Ready -> {
                    TextButton(onClick = updater::dismissDialog) { Text("Later", color = Palette.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    PillButton(
                        "Install",
                        icon = Icons.Rounded.SystemUpdate,
                        background = Color.White,
                        contentColor = Palette.playGlyph,
                        border = false,
                        onClick = updater::install
                    )
                }
                else -> Unit
            }
        }
    ) {
        if (state is UpdateState.Downloading) {
            val fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
            Text(
                "Downloading… ${formatByteSize(state.bytes) ?: "0 B"}${formatByteSize(state.total)?.let { " of $it" } ?: ""}",
                color = Color.White,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                color = Color.White,
                trackColor = Palette.fillStrong,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.height(8.dp))
            Text("You can close this and keep using the app; the download continues.", color = Palette.textTertiary, fontSize = 11.sp)
        } else {
            if (state is UpdateState.Ready) {
                Text(
                    "The update was downloaded and checked. Android will ask you to confirm the install, and your queue and settings are kept.",
                    color = Palette.textSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
            }
            if (release.notes.isNotBlank()) {
                SectionCaption("What's new")
                Spacer(Modifier.height(6.dp))
                Text(release.notes, color = Palette.textSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 400.dp,
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.textSecondary) }
            Spacer(Modifier.width(8.dp))
            PillButton(confirm, background = Palette.danger, contentColor = Color.White, border = false) {
                onConfirm(); onDismiss()
            }
        }
    ) {
        Text(message, color = Palette.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun OpenUrlDialog(player: PlayerController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var value by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val clip = remember {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            ?.takeIf(MediaSupport::isNetwork)
    }
    fun submit() {
        if (player.openNetworkUrl(value)) onDismiss() else invalid = true
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassDialog(
        title = "Open Network Stream",
        subtitle = "HTTP, HTTPS, RTMP, RTSP, HLS and magnet links are supported.",
        icon = Icons.Rounded.Public,
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.textSecondary) }
            Spacer(Modifier.width(8.dp))
            PillButton(
                "Open",
                background = Color.White,
                contentColor = Palette.playGlyph,
                border = false,
                enabled = value.isNotBlank(),
                onClick = ::submit
            )
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; invalid = false },
            placeholder = { Text("https://example.com/video.m3u8", color = Palette.textTertiary) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text("Enter a valid HTTP, HTTPS, RTMP, RTSP or magnet URL") }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.accent,
                unfocusedBorderColor = Palette.strokeStrong,
                cursorColor = Palette.accent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .semantics { contentDescription = "Stream URL" }
        )
        if (clip != null && clip != value) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                "Paste ${clip.take(42)}${if (clip.length > 42) "…" else ""}",
                icon = Icons.Rounded.ContentPaste,
                background = Palette.fill,
                height = 32.dp,
                fontSize = 12
            ) { value = clip; invalid = false }
        }
    }
}

@Composable
private fun MediaInfoDialog(player: PlayerController, onDismiss: () -> Unit) {
    GlassDialog(title = "Media Info", icon = Icons.Rounded.Info, onDismiss = onDismiss) {
        player.mediaInfo().forEach { (label, value) ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text(label, color = Palette.textTertiary, fontSize = 12.sp, modifier = Modifier.width(104.dp))
                Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Palette.stroke)
        }
    }
}

@Composable
private fun DownloadsDialog(player: PlayerController, onDismiss: () -> Unit) {
    var downloads by remember { mutableStateOf<List<TorrentStream.Download>?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { player.torrentDownloads { downloads = it } }
    val list = downloads
    GlassDialog(
        title = "Torrent Downloads",
        subtitle = list?.let { "${it.size} ${if (it.size == 1) "torrent" else "torrents"} · ${formatByteSize(it.sumOf(TorrentStream.Download::bytes)) ?: "0 B"}" },
        icon = Icons.Rounded.CloudDownload,
        onDismiss = onDismiss
    ) {
        if (player.current?.isTorrent == true && player.torrentStatus != null) {
            Text(
                "Now streaming · ${player.torrentStatus}",
                color = Palette.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        when {
            list == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(16.dp).size(24.dp))
            list.isEmpty() -> Text("No downloads yet. Torrent media you stream is kept here so it can be reused.", color = Palette.textSecondary, fontSize = 13.sp)
            else -> list.forEach { download ->
                val key = download.infoHash ?: download.sourceHash
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(download.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val total = formatByteSize(download.totalBytes)
                        Text(
                            "${formatByteSize(download.bytes) ?: "0 B"}${if (total != null) " of $total" else ""} · ${download.files} ${if (download.files == 1) "file" else "files"}",
                            color = Palette.textTertiary,
                            fontSize = 11.sp
                        )
                    }
                    if (confirming == key) {
                        TextButton(onClick = {
                            confirming = null
                            player.deleteTorrentDownload(download) { refresh++ }
                        }) { Text("Delete", color = Palette.danger, fontWeight = FontWeight.SemiBold) }
                    } else {
                        PlayerIconButton(Icons.Rounded.DeleteOutline, "Delete ${download.name}", size = 36.dp, iconSize = 20.dp, tint = Palette.textSecondary) {
                            confirming = key
                        }
                    }
                }
                HorizontalDivider(color = Palette.stroke)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Downloads are stored privately by the app, reused when you reopen a torrent, and removed when you uninstall.",
            color = Palette.textTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingsDialog(player: PlayerController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = player.settings
    fun update(change: (PlayerSettings) -> PlayerSettings) = player.updateSettings(change)
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    GlassDialog(title = "Settings", icon = Icons.Rounded.Settings, onDismiss = onDismiss) {
        SettingsGroup("Playback")
        SettingSwitch(
            "Hardware decoding",
            if (MPVEngine.preferHardwareDecoding()) "MediaCodec with copy-back. Turn off if a video stutters or shows artifacts."
            else "Emulators default to software decoding; their codecs can stall.",
            settings.hardwareDecoding
        ) { on -> update { it.copy(hardwareDecoding = on) } }
        SettingSwitch(
            "High-quality scaling",
            "mpv's sharper scalers. Turn off if video shows black or garbled frames, or to save battery.",
            settings.highQualityScaling
        ) { on -> update { it.copy(highQualityScaling = on) } }
        SettingSwitch("Resume where you left off", "Remembers the position of the 500 most recent files.", settings.rememberPosition) { on ->
            update { it.copy(rememberPosition = on) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Skip interval", color = Color.White, fontSize = 14.sp)
                Text("Seek buttons, double-tap and arrow keys", color = Palette.textTertiary, fontSize = 11.sp)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.fill)
                    .padding(3.dp)
            ) {
                PlayerSettings.seekSteps.forEach { step ->
                    val selected = settings.seekStep == step
                    Text(
                        "${step}s",
                        color = if (selected) Palette.playGlyph else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable(onClickLabel = "Skip $step seconds") { update { it.copy(seekStep = step) } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
        TextButton(onClick = { player.clearResumeHistory() }) {
            Text("Clear saved positions", color = Palette.accent, fontSize = 13.sp)
        }

        SettingsGroup("When you leave the app")
        SettingSwitch("Picture-in-picture", "Keep watching in a small window when you go Home during playback.", settings.autoPictureInPicture) { on ->
            update { it.copy(autoPictureInPicture = on) }
        }
        SettingSwitch("Background audio", "Keep the sound playing with a media notification instead of pausing.", settings.backgroundPlayback) { on ->
            update { it.copy(backgroundPlayback = on) }
        }

        SettingsGroup("Gestures")
        SettingSwitch("Swipe gestures", "Swipe sideways to seek, on the right edge for volume, on the left for brightness.", settings.swipeGestures) { on ->
            update { it.copy(swipeGestures = on) }
        }
        SettingSwitch("Hold for 2× speed", "Touch and hold the video to play faster until you let go.", settings.holdForSpeed) { on ->
            update { it.copy(holdForSpeed = on) }
        }
        SettingSwitch("Rotate in full screen", "Full screen turns the screen to match the video.", settings.rotateInFullscreen) { on ->
            update { it.copy(rotateInFullscreen = on) }
        }

        SettingsGroup("Updates")
        SettingSwitch(
            "Check for updates when opening the app",
            "Looks for a newer release on GitHub. Updates are only downloaded when you ask.",
            settings.checkForUpdates
        ) { on -> update { it.copy(checkForUpdates = on) } }
        UpdateRow()

        Spacer(Modifier.height(12.dp))
        Text(
            "andrompv $version · Android port of macmpv · mpv, FFmpeg and libtorrent",
            color = Palette.textTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun UpdateRow() {
    val updater = LocalContext.current.updater
    val state = updater.state
    val (status, action) = when (state) {
        UpdateState.Idle -> "Version ${updater.currentVersion}" to "Check now"
        UpdateState.Checking -> "Checking…" to null
        is UpdateState.Available -> "Version ${state.release.version} is available" to "View"
        is UpdateState.Downloading -> "Downloading version ${state.release.version}…" to "View"
        is UpdateState.Ready -> "Version ${state.release.version} is ready to install" to "Install"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(status, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = { if (state is UpdateState.Idle) updater.check(manual = true) else updater.showDialog() }) {
                Text(action, color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String) {
    SectionCaption(title, Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = title) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(detail, color = Palette.textTertiary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors(), modifier = Modifier.semantics { contentDescription = title })
    }
}
