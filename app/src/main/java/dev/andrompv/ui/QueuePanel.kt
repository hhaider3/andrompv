package dev.andrompv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.andrompv.GroupKind
import dev.andrompv.PlayerController
import dev.andrompv.QueueGroup
import dev.andrompv.QueueItem
import dev.andrompv.RepeatMode
import dev.andrompv.playbackTime

private sealed interface QueueEntry {
    val key: String
    data class GroupHeader(val group: QueueGroup, val expanded: Boolean, val containsCurrent: Boolean) : QueueEntry {
        override val key get() = "group:${group.id}"
    }
    data class Row(val item: QueueItem, val grouped: Boolean, val lastInGroup: Boolean, val groupHasCurrent: Boolean) : QueueEntry {
        override val key get() = "item:${item.id}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueuePanel(player: PlayerController, ui: PlayerUiState, host: PlayerHost, wide: Boolean) {
    val visible = player.isSidebarVisible
    if (!wide) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .blockTouchesThen { player.setSidebarVisible(false) }
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = if (wide) 340.dp else (maxWidth - 44.dp).coerceAtMost(360.dp)
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            // Like macmpv, the sidebar stops above the control bar while the controls are up.
            val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
            val density = LocalDensity.current
            val navigation = with(density) { insets.getBottom(this).toDp() }
            val controls = with(density) { ui.controlsHeightPx.toDp() }
            val bottom by animateDpAsState(
                if (wide && ui.controlsVisible && player.hasMedia && controls > 0.dp) controls + 8.dp else navigation + 10.dp,
                label = "sidebarBottom"
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = bottom)
            ) {
                Column(
                    Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .glass(RoundedCornerShape(22.dp), fill = Palette.glassStrong, opacity = if (wide) 0.94f else 1f)
                        .blockTouches()
                ) {
                    QueueHeader(player, ui, host)
                    if (player.items.isEmpty()) {
                        EmptyQueue(host, Modifier.weight(1f))
                    } else {
                        QueueList(player, ui, Modifier.weight(1f))
                    }
                    player.current?.let { NowPlayingCard(player, it) }
                }
            }
        }
    }
}

private fun Modifier.blockTouchesThen(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    detectTapGestures(onTap = { onTap() })
}

@Composable
private fun QueueHeader(player: PlayerController, ui: PlayerUiState, host: PlayerHost) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIconButton(Icons.Rounded.ViewSidebar, "Hide queue", size = 34.dp, iconSize = 18.dp, background = Palette.fill) {
            player.setSidebarVisible(false)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            SectionCaption("Up Next")
            Text(queueSummary(player), color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        PlayerIconButton(
            if (player.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            player.repeat.label,
            size = 34.dp,
            iconSize = 19.dp,
            tint = if (player.repeat == RepeatMode.OFF) Palette.textSecondary else Palette.accent
        ) { player.cycleRepeat() }
        PlayerIconButton(Icons.Rounded.Add, "Add media", size = 34.dp, iconSize = 20.dp, background = Palette.fill) {
            host.openMedia()
        }
        Box {
            PlayerIconButton(Icons.Rounded.MoreHoriz, "Queue options", size = 34.dp, iconSize = 20.dp) { menu = true }
            GlassMenu(menu, { menu = false }) {
                MenuEntry("Open Network Stream…", icon = Icons.Rounded.Link, onClick = { menu = false; ui.sheet = Sheet.OpenUrl })
                MenuEntry("Torrent Downloads", icon = Icons.Rounded.CloudDownload, onClick = { menu = false; ui.sheet = Sheet.Downloads })
                if (player.items.isNotEmpty()) {
                    MenuDivider()
                    MenuEntry("Clear Queue", destructive = true, onClick = { menu = false; ui.sheet = Sheet.ClearQueue })
                }
            }
        }
    }
}

private fun queueSummary(player: PlayerController): String {
    val count = player.items.size
    val collections = player.items.groupBy { it.group }.count { (_, members) ->
        members.size > 1 || members.first().groupKind != GroupKind.STANDALONE
    }
    return buildString {
        append("$count ${if (count == 1) "item" else "items"}")
        if (collections > 0) append(" • $collections ${if (collections == 1) "collection" else "collections"}")
    }
}

@Composable
private fun EmptyQueue(host: PlayerHost, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Palette.textTertiary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text("Your queue is empty", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Add several files to play them in order.",
            color = Palette.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        PillButton("Add Media", background = Palette.fill, height = 34.dp) { host.openMedia() }
    }
}

@Composable
private fun QueueList(player: PlayerController, ui: PlayerUiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    val entries = remember(player.items, player.collapsedGroups, player.currentId) { buildEntries(player) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartOffset by remember { mutableIntStateOf(0) }
    var dragDelta by remember { mutableFloatStateOf(0f) }

    fun info(id: String) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "item:$id" }

    /** Collection members reorder within their collection; standalone files among each other. */
    fun canSwap(a: QueueItem, b: QueueItem): Boolean {
        if (a.group == b.group) return true
        fun standalone(item: QueueItem) = item.groupKind == GroupKind.STANDALONE && player.items.count { it.group == item.group } == 1
        return standalone(a) && standalone(b)
    }

    fun dragBy(id: String, amount: Float) {
        dragDelta += amount
        val dragged = info(id) ?: return
        val item = player.items.firstOrNull { it.id == id } ?: return
        val center = dragStartOffset + dragDelta + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            val key = other.key as? String ?: return@firstOrNull false
            if (!key.startsWith("item:") || key == "item:$id" || center < other.offset || center > other.offset + other.size) {
                return@firstOrNull false
            }
            val candidate = player.items.firstOrNull { "item:${it.id}" == key } ?: return@firstOrNull false
            canSwap(item, candidate)
        } ?: return
        player.moveItemOnto(id, (target.key as String).removePrefix("item:"))
    }

    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp)
    ) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is QueueEntry.GroupHeader -> GroupHeaderRow(player, ui, entry, Modifier.padding(top = 6.dp))
                is QueueEntry.Row -> {
                    val dragging = draggingId == entry.item.id
                    val translation = if (dragging) {
                        val current = info(entry.item.id)?.offset ?: dragStartOffset
                        dragStartOffset + dragDelta - current
                    } else 0f
                    QueueRow(
                        player = player,
                        ui = ui,
                        entry = entry,
                        dragging = dragging,
                        onDragStart = {
                            draggingId = entry.item.id
                            dragStartOffset = info(entry.item.id)?.offset ?: 0
                            dragDelta = 0f
                        },
                        onDrag = { dragBy(entry.item.id, it) },
                        onDragEnd = { draggingId = null; dragDelta = 0f },
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = translation }
                            .then(if (dragging) Modifier else Modifier.animateItem())
                    )
                }
            }
        }
    }
}

private fun buildEntries(player: PlayerController): List<QueueEntry> {
    val result = mutableListOf<QueueEntry>()
    val groups = linkedMapOf<String, MutableList<QueueItem>>()
    player.items.forEach { groups.getOrPut(it.group) { mutableListOf() }.add(it) }
    groups.forEach { (id, members) ->
        val first = members.first()
        val group = QueueGroup(id, first.groupTitle, first.groupKind, members)
        val containsCurrent = members.any { it.id == player.currentId }
        if (group.isCollapsible) {
            val expanded = id !in player.collapsedGroups
            result += QueueEntry.GroupHeader(group, expanded, containsCurrent)
            if (expanded) members.forEachIndexed { index, item ->
                result += QueueEntry.Row(item, grouped = true, lastInGroup = index == members.lastIndex, groupHasCurrent = containsCurrent)
            }
        } else {
            result += QueueEntry.Row(first, grouped = false, lastInGroup = true, groupHasCurrent = containsCurrent)
        }
    }
    return result
}

private fun groupFill(containsCurrent: Boolean) = Color.White.copy(alpha = if (containsCurrent) 0.055f else 0.028f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeaderRow(player: PlayerController, ui: PlayerUiState, entry: QueueEntry.GroupHeader, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val group = entry.group
    val rotation by animateFloatAsState(if (entry.expanded) 0f else -90f, label = "chevron")
    val shape: Shape = if (entry.expanded) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(12.dp)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(groupFill(entry.containsCurrent))
                .combinedClickable(
                    onClickLabel = if (entry.expanded) "Collapse" else "Expand",
                    onLongClickLabel = "Collection options",
                    onLongClick = { menu = true },
                    onClick = { player.toggleGroup(group.id) }
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.ExpandMore, null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp).rotate(rotation))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = if (entry.containsCurrent) 0.12f else 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (group.kind == GroupKind.TORRENT) Icons.Rounded.Sensors else Icons.Rounded.Layers,
                    null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(group.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.items.size} ${if (group.items.size == 1) "item" else "items"}",
                    color = Palette.textTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (entry.containsCurrent) {
                Icon(Icons.Rounded.GraphicEq, "Contains the playing item", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            PlayerIconButton(Icons.Rounded.MoreHoriz, "Options for ${group.title}", size = 30.dp, iconSize = 18.dp, tint = Palette.textSecondary) {
                menu = true
            }
        }
        GlassMenu(menu, { menu = false }) {
            val deletable = group.items.any { it.isTorrent || it.uri.startsWith("content:") }
            MenuEntry("Remove Collection", icon = Icons.Rounded.RemoveCircleOutline, onClick = {
                menu = false; player.removeItems(group.items.map { it.id }.toSet())
            })
            if (deletable) {
                MenuEntry(
                    if (group.kind == GroupKind.TORRENT) "Delete Downloads…" else "Delete Files…",
                    icon = Icons.Rounded.DeleteOutline,
                    destructive = true,
                    onClick = { menu = false; ui.sheet = Sheet.Delete(group.items, group.title) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    player: PlayerController,
    ui: PlayerUiState,
    entry: QueueEntry.Row,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier
) {
    val item = entry.item
    val isCurrent = item.id == player.currentId
    var menu by remember { mutableStateOf(false) }
    val outerShape: Shape = when {
        !entry.grouped -> RoundedCornerShape(12.dp)
        entry.lastInGroup -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RoundedCornerShape(0.dp)
    }
    val resume = remember(item.key, player.queueVersion, isCurrent) { player.store.resume(item) }
    val duration = if (isCurrent && player.duration > 0) player.duration else item.metadata?.duration
    Box(
        modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(if (entry.grouped) groupFill(entry.groupHasCurrent) else Color.Transparent)
            .padding(start = if (entry.grouped) 10.dp else 0.dp, end = if (entry.grouped) 4.dp else 0.dp, bottom = if (entry.grouped && entry.lastInGroup) 6.dp else 0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .graphicsLayer {
                    if (dragging) {
                        scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f
                    }
                }
                .clip(RoundedCornerShape(11.dp))
                .background(
                    when {
                        dragging -> Palette.fillStrong
                        isCurrent -> Color.White.copy(alpha = 0.09f)
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    onClickLabel = "Play",
                    onLongClickLabel = "Options",
                    onLongClick = { menu = true },
                    onClick = { player.playItem(item.id) }
                )
                .padding(start = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 42.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCurrent) Palette.currentBadge else Palette.idleBadge),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isCurrent) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                    null,
                    tint = Color.White.copy(alpha = if (isCurrent) 1f else 0.55f),
                    modifier = Modifier.size(if (isCurrent) 18.dp else 15.dp)
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    item.title,
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.86f),
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    rowSubtitle(item),
                    color = Palette.textTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (resume > 0 && duration != null && duration > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth(0.9f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Palette.fill)) {
                        Box(
                            Modifier
                                .fillMaxWidth((resume / duration).toFloat().coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(Palette.accent.copy(alpha = 0.8f))
                        )
                    }
                }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Reorder ${item.title}" }
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.DragHandle, null, tint = Palette.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
        GlassMenu(menu, { menu = false }) {
            MenuEntry("Play", icon = Icons.Rounded.PlayArrow, onClick = { menu = false; player.playItem(item.id) })
            MenuEntry("Move Up", icon = Icons.Rounded.ArrowUpward, onClick = { menu = false; player.moveItem(item.id, -1) })
            MenuEntry("Move Down", icon = Icons.Rounded.ArrowDownward, onClick = { menu = false; player.moveItem(item.id, 1) })
            MenuDivider()
            MenuEntry("Remove from Queue", icon = Icons.Rounded.RemoveCircleOutline, onClick = {
                menu = false; player.removeItems(setOf(item.id))
            })
            if (item.isTorrent || item.uri.startsWith("content:")) {
                MenuEntry(
                    if (item.isTorrent) "Delete Download…" else "Delete File…",
                    icon = Icons.Rounded.DeleteOutline,
                    destructive = true,
                    onClick = { menu = false; ui.sheet = Sheet.Delete(listOf(item), item.title) }
                )
            }
        }
    }
}

private fun rowSubtitle(item: QueueItem): String {
    val parts = mutableListOf<String>()
    parts += if (item.isTorrent && item.torrentIndex == null) "READING FILES…" else item.fileExtension.ifEmpty { "STREAM" }
    val size = item.sizeLabel
    val duration = item.metadata?.duration?.takeIf { it > 0 }
    if (item.isTorrent && size != null) parts += size
    else if (duration != null) parts += playbackTime(duration)
    else if (size != null) parts += size
    return parts.joinToString(" • ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingCard(player: PlayerController, item: QueueItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .padding(12.dp)
    ) {
        SectionCaption("Now Playing")
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        val badges = (player.metadata ?: item.metadata)?.badges.orEmpty()
        when {
            badges.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { MetadataPill(it) }
            }
            item.isTorrent -> StatusLine(
                if (item.torrentIndex == null) "Reading torrent contents…" else player.torrentStatus ?: "Streaming from BitTorrent",
                spinning = item.torrentIndex == null
            )
            item.probeFailed -> StatusLine("Media info unavailable", spinning = false)
            else -> StatusLine("Reading media info…", spinning = true)
        }
    }
}

@Composable
private fun StatusLine(text: String, spinning: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spinning) {
            CircularProgressIndicator(color = Palette.textSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Palette.textSecondary, fontSize = 11.sp, maxLines = 2)
    }
}
