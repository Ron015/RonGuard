package dev.ronguard.logs

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import androidx.core.content.FileProvider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import android.content.Intent
import android.content.Context
import kotlinx.coroutines.launch
import java.util.Locale
import java.text.SimpleDateFormat
import dev.ronguard.data.History
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Icon
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import dev.ronguard.design.ScreenTitle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ronguard.design.EmptyState
import dev.ronguard.design.FilterChip
import dev.ronguard.design.IconBadge
import dev.ronguard.design.BarSpacer
import dev.ronguard.design.Spacing
import dev.ronguard.design.Radii
import dev.ronguard.design.Warden
import dev.ronguard.design.formatBytes
import dev.ronguard.design.tabular
import dev.ronguard.engine.DeviceMem
import dev.ronguard.engine.NetEvent
import dev.ronguard.design.ChoiceChips
import dev.ronguard.rules.DomainChoice
import dev.ronguard.rules.Rules
import java.text.DateFormat
import java.util.Date
import dev.ronguard.design.R as DesignR
import androidx.compose.foundation.ExperimentalFoundationApi

private enum class Filter(val label: Int, val keep: (NetEvent) -> Boolean) {
    All(R.string.log_filter_all, { true }),
    Blocked(R.string.log_filter_blocked, { it.blocked }),
    Dns(R.string.log_filter_dns, { it.kind == "dns" }),
    Connections(R.string.log_filter_connections, { it.kind != "dns" }),
}

/** Live stream of DNS answers and connections. Long-press selects; tap opens full details. */
@Composable
fun LogScreen(
    running: Boolean,
    appLabel: (Int) -> String,
    appIcon: (Int) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val c = Warden.colors
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var detailEvent by remember { mutableStateOf<NetEvent?>(null) }
    val pageSize = DeviceMem.logPageSize
    // True DB paging: only pages the user has scrolled to are held in memory.
    var items by remember { mutableStateOf<List<NetEvent>>(emptyList()) }
    var nextOffset by remember { mutableIntStateOf(0) }
    var endReached by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val totalCount by remember { History.totalCount() }.collectAsState(0)
    // Small live window so new events appear without reloading the whole history.
    val live by remember { History.recent(pageSize) }.collectAsState(emptyList())
    val selecting = selectedIds.isNotEmpty()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun shouldLoadMore(extra: Boolean): Boolean = extra && !endReached && !loadingMore

    fun matches(e: NetEvent, q: String): Boolean {
        if (q.isEmpty()) return true
        return (e.domain?.lowercase(Locale.getDefault())?.contains(q) == true) ||
            e.target.lowercase(Locale.getDefault()).contains(q) ||
            appLabel(e.uid).lowercase(Locale.getDefault()).contains(q) ||
            e.kind.lowercase(Locale.getDefault()).contains(q)
    }

    val shown = remember(items, filter, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        items.asSequence().filter(filter.keep).filter { matches(it, q) }.toList()
    }
    val blocked = remember(items) { items.count { it.blocked } }

    suspend fun loadMore() {
        if (loadingMore || endReached) return
        loadingMore = true
        try {
            val batch = History.loadPage(nextOffset, pageSize)
            if (batch.isEmpty()) {
                endReached = true
            } else {
                val have = items.mapTo(HashSet(items.size * 2)) { it.id }
                val add = batch.filter { it.id !in have }
                items = items + add
                nextOffset += batch.size
                if (batch.size < pageSize) endReached = true
            }
        } finally {
            loadingMore = false
        }
    }

    // Initial page + reset when filter/search changes (still pages the full DB).
    LaunchedEffect(filter, searchQuery) {
        items = emptyList()
        nextOffset = 0
        endReached = false
        loadMore()
    }

    // Prepend brand-new live events (by id) without dropping paged history.
    LaunchedEffect(live) {
        if (live.isEmpty()) return@LaunchedEffect
        val have = items.mapTo(HashSet()) { it.id }
        val fresh = live.filter { it.id !in have }
        if (fresh.isNotEmpty()) items = fresh + items
    }

    // Near the end of the list → fetch the next DB page (only on-screen rows are composed).
    LaunchedEffect(listState, shown.size, endReached, loadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, total) ->
            val nearEnd = total > 0 && last >= total - 5
            if (shouldLoadMore(nearEnd)) loadMore()
        }
    }

    // If a filter leaves few matches, keep paging until we find more or hit the end.
    LaunchedEffect(shown.size, endReached, loadingMore, items.size) {
        val needMoreMatches = shown.size < pageSize && items.size >= pageSize
        if (shouldLoadMore(needMoreMatches)) loadMore()
    }

    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }

    // Title, live line and filters stay put; only the stream below scrolls.
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(top = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Text(
                        stringResource(R.string.log_selected_count, selectedIds.size),
                        style = Warden.type.title,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.log_select_all),
                        style = Warden.type.caption,
                        color = c.accentInk,
                        modifier = Modifier
                            .clickable {
                                selectedIds = if (selectedIds.size == shown.size) emptySet()
                                else shown.map { it.id }.toSet()
                            }
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                    Text(
                        stringResource(R.string.log_delete_selected),
                        style = Warden.type.caption,
                        color = c.blocked,
                        modifier = Modifier
                            .clickable {
                                val ids = selectedIds
                                History.deleteByCids(ids)
                                selectedIds = emptySet()
                            }
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                    Text(
                        stringResource(R.string.log_cancel_select),
                        style = Warden.type.caption,
                        color = c.muted,
                        modifier = Modifier
                            .clickable { selectedIds = emptySet() }
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                } else {
                    ScreenTitle(stringResource(R.string.log_title), Modifier.weight(1f))
                    Text(
                        stringResource(R.string.log_clear_all),
                        style = Warden.type.caption,
                        color = c.accentInk,
                        modifier = Modifier
                            .clickable { scope.launch { History.clear() } }
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                    ExportButton(appLabel)
                }
            }
            if (!selecting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    LiveDot(running)
                    Text(
                        stringResource(
                            if (running) R.string.log_live else R.string.log_paused,
                            totalCount.coerceAtLeast(items.size),
                            blocked,
                        ).uppercase(),
                        style = Warden.type.caption.tabular(),
                        color = c.muted,
                    )
                }
                // Search bar
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.s)
                        .clip(RoundedCornerShape(Radii.small))
                        .background(c.surfaceVariant)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                    singleLine = true,
                    textStyle = Warden.type.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accentInk),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { field ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(DesignR.drawable.ph_magnifying_glass),
                                contentDescription = null,
                                tint = c.muted,
                                modifier = Modifier.size(18.dp).padding(end = Spacing.s),
                            )
                            Box(Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(stringResource(R.string.log_search_hint), style = Warden.type.body, color = c.muted)
                                }
                                field()
                            }
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    "×",
                                    style = Warden.type.body,
                                    color = c.muted,
                                    modifier = Modifier
                                        .clickable { searchQuery = "" }
                                        .padding(start = Spacing.s),
                                )
                            }
                        }
                    },
                )
            }
        }
        if (!selecting) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Filter.entries.forEach { FilterChip(stringResource(it.label), filter == it, { filter = it }) }
            }
        }
        HorizontalDivider(color = c.outline)
        LogList(
            page = shown,
            totalMatched = if (searchQuery.isBlank() && filter == Filter.All) totalCount else shown.size,
            noEvents = totalCount == 0 && items.isEmpty(),
            listState = listState,
            time = time,
            appLabel = appLabel,
            appIcon = appIcon,
            selectedIds = selectedIds,
            selecting = selecting,
            onToggleSelect = { id ->
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            },
            onStartSelect = { id -> selectedIds = setOf(id) },
            onOpenDetail = { detailEvent = it },
        )
    }

    detailEvent?.let { event ->
        LogDetailSheet(
            event = event,
            appLabel = appLabel(event.uid),
            appIcon = appIcon(event.uid),
            dateTime = dateTime,
            onDismiss = { detailEvent = null },
        )
    }
}

@Composable
private fun ColumnScope.LogList(
    page: List<NetEvent>,
    totalMatched: Int,
    noEvents: Boolean,
    listState: LazyListState,
    time: DateFormat,
    appLabel: (Int) -> String,
    appIcon: (Int) -> ImageBitmap?,
    selectedIds: Set<String>,
    selecting: Boolean,
    onToggleSelect: (String) -> Unit,
    onStartSelect: (String) -> Unit,
    onOpenDetail: (NetEvent) -> Unit,
) {
    val c = Warden.colors
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
        if (page.isEmpty()) {
            item {
                EmptyState(
                    DesignR.drawable.ph_pulse_light,
                    stringResource(if (noEvents) R.string.log_empty_title else R.string.log_filtered_title),
                    stringResource(if (noEvents) R.string.log_empty_body else R.string.log_filtered_body),
                    Modifier.fillMaxWidth(),
                )
            }
        }
        items(page, key = { it.id }) { e ->
            val selected = e.id in selectedIds
            @OptIn(ExperimentalFoundationApi::class)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) c.accentInk.copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            if (selecting) onToggleSelect(e.id) else onOpenDetail(e)
                        },
                        onLongClick = {
                            if (selecting) onToggleSelect(e.id) else onStartSelect(e.id)
                        },
                    )
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .border(2.dp, if (selected) c.accentInk else c.outline, CircleShape)
                            .background(if (selected) c.accentInk else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Text("✓", style = Warden.type.caption, color = c.onPrimary)
                        }
                    }
                }
                val icon = appIcon(e.uid)
                if (icon != null) Image(icon, null, Modifier.size(36.dp)) else IconBadge(DesignR.drawable.ph_squares_four, c.muted)
                Column(Modifier.weight(1f)) {
                    Text(
                        e.domain ?: e.target,
                        style = Warden.type.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (e.blocked) c.blocked else c.text,
                    )
                    Text(
                        listOf(appLabel(e.uid), e.kind, time.format(Date(e.time))).joinToString(" · ").uppercase(),
                        style = Warden.type.caption.tabular(),
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    e.blocked -> Text(
                        stringResource(R.string.log_blocked).uppercase(),
                        Modifier
                            .background(c.blocked.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = Spacing.s, vertical = 3.dp),
                        style = Warden.type.caption,
                        color = c.blocked,
                    )
                    e.bytesUp + e.bytesDown > 0 -> Text(
                        "↑${formatBytes(e.bytesUp)}\n↓${formatBytes(e.bytesDown)}",
                        style = Warden.type.caption.tabular(),
                        color = c.muted,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = Spacing.xl + 36.dp + Spacing.m), color = c.outline)
        }
        if (page.isNotEmpty() && page.size < totalMatched) {
            item(key = "log-more") {
                Text(
                    stringResource(R.string.log_showing, page.size, totalMatched),
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.m),
                    style = Warden.type.caption,
                    color = c.muted,
                )
            }
        }
        item { BarSpacer() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailSheet(
    event: NetEvent,
    appLabel: String,
    appIcon: ImageBitmap?,
    dateTime: DateFormat,
    onDismiss: () -> Unit,
) {
    val c = Warden.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (appIcon != null) Image(appIcon, null, Modifier.size(40.dp))
                else IconBadge(DesignR.drawable.ph_squares_four, c.muted)
                Column(Modifier.weight(1f)) {
                    Text(appLabel, style = Warden.type.title)
                    Text(dateTime.format(Date(event.time)), style = Warden.type.caption, color = c.muted)
                }
                if (event.blocked) {
                    Text(
                        stringResource(R.string.log_blocked).uppercase(),
                        Modifier
                            .background(c.blocked.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = Spacing.s, vertical = 3.dp),
                        style = Warden.type.caption,
                        color = c.blocked,
                    )
                }
            }
            DetailRow(stringResource(R.string.log_detail_host), event.domain ?: "—")
            DetailRow(stringResource(R.string.log_detail_target), event.target)
            DetailRow(stringResource(R.string.log_detail_kind), event.kind.uppercase())
            DetailRow(
                stringResource(R.string.log_detail_status),
                when {
                    event.blocked -> stringResource(R.string.log_blocked)
                    event.failed -> stringResource(R.string.log_detail_failed)
                    else -> stringResource(R.string.log_detail_allowed)
                },
            )
            if (event.latencyMs != null) {
                DetailRow(stringResource(R.string.log_detail_latency), "${event.latencyMs} ms")
            }
            if (event.bytesUp + event.bytesDown > 0) {
                DetailRow(
                    stringResource(R.string.log_detail_bytes),
                    "↑ ${formatBytes(event.bytesUp)}  ·  ↓ ${formatBytes(event.bytesDown)}",
                )
            }
            event.domain?.let { domain ->
                HorizontalDivider(color = c.outline)
                Text(stringResource(R.string.log_detail_rules), style = Warden.type.label, color = c.muted)
                DomainRuleChips(event.uid, domain, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = Warden.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = Warden.type.caption, color = c.muted)
        Text(value, style = Warden.type.body)
    }
}

@Composable
private fun LiveDot(running: Boolean) {
    val c = Warden.colors
    if (running && !Warden.reduceMotion) {
        // Alpha is read in the layer, not in composition: the pulse costs a redraw, not a recomposition.
        val alpha = rememberInfiniteTransition("live").animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), "a")
        Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha.value }.background(c.accentInk, CircleShape))
    } else {
        Box(Modifier.size(7.dp).background(if (running) c.accentInk else c.muted, CircleShape))
    }
}

/** Block or allow this domain for this app, or everywhere. Takes effect for new connections. */
@Composable
internal fun DomainRuleChips(uid: Int, domain: String, modifier: Modifier = Modifier) {
    val rules by Rules.domains.collectAsState()
    val current = remember(rules, uid, domain) { Rules.choice(uid, domain) }
    ChoiceChips(
        DomainChoice.entries.map {
            stringResource(
                when (it) {
                    DomainChoice.Default -> R.string.rule_default
                    DomainChoice.AllowHere -> R.string.rule_allow_here
                    DomainChoice.BlockHere -> R.string.rule_block_here
                    DomainChoice.AllowEverywhere -> R.string.rule_allow_everywhere
                    DomainChoice.BlockEverywhere -> R.string.rule_block_everywhere
                },
            )
        },
        current.ordinal,
        { Rules.choose(uid, domain, DomainChoice.entries[it]) },
        modifier,
    )
}

/**
 * Export the whole history (up to 30 days) as CSV: share a copy with another app, or save it on the phone.
 * A PCAP isn't offered: Warden never records packets, only who connected where, so it would be made up.
 */
@Composable
private fun ExportButton(appLabel: (Int) -> String) {
    val c = Warden.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    // Writing the CSV takes a few seconds on a long history: show it's working and ignore taps until done.
    var busy by remember { mutableStateOf(false) }
    val busyText = stringResource(R.string.log_export_busy)
    val name = "ronguard-log-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val rows = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { History.exportCsv(it, appLabel) }
            }.getOrNull()
            busy = false
            toast(context, rows)
        }
    }
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !busy, role = Role.Button) { open = true }
                .semantics { if (busy) stateDescription = busyText },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = c.accentInk, strokeWidth = 2.dp)
            } else {
                Icon(painterResource(DesignR.drawable.ph_export), stringResource(R.string.log_export), Modifier.size(24.dp), tint = c.text)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.log_export_share), style = Warden.type.body) },
                onClick = {
                    open = false
                    busy = true
                    scope.launch {
                        val ok = shareCsv(context, name, appLabel)
                        busy = false
                        if (!ok) toast(context, null)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.log_export_save), style = Warden.type.body) },
                onClick = {
                    open = false
                    save.launch(name)
                },
            )
        }
    }
}

/** Writes the CSV to a private cache folder and opens the share sheet on a copy of it. */
private suspend fun shareCsv(context: Context, name: String, appLabel: (Int) -> String): Boolean {
    val file = runCatching {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // one export at a time; no history piling up in the cache
            File(dir, name).also { f -> f.outputStream().use { History.exportCsv(it, appLabel) } }
        }
    }.getOrNull() ?: return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
    return true
}

private fun toast(context: Context, rows: Int?) {
    val text = if (rows == null) {
        context.getString(R.string.log_export_failed)
    } else {
        context.resources.getQuantityString(R.plurals.log_exported, rows, rows)
    }
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Its own FileProvider subclass, since an app can declare the plain class only once (the report card has it). */
class ExportFileProvider : FileProvider()
