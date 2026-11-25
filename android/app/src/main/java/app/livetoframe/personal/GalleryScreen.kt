@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.livetoframe.personal

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role.Companion.Checkbox
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val galleryTextStyle = TextStyle(
    fontSize = 16.sp,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

internal val galleryTypography = Typography(
    bodyLarge = galleryTextStyle,
    labelLarge = galleryTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
private fun softDividerColor(): Color =
    if (MaterialTheme.colorScheme.surface == Color.Black) Color(0xFF38383A) else Color(0xFFF0F0F0)

internal fun galleryColors(dark: Boolean): ColorScheme {
    val brand = Color(0xFF0066CC)
    return if (dark) darkColorScheme(
        primary = Color(0xFF70B5FF), onPrimary = Color(0xFF002D55),
        primaryContainer = Color(0xFF142C47), onPrimaryContainer = Color(0xFFB8DAFF),
        surface = Color.Black, onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFFA8A8AE),
        surfaceContainer = Color(0xFF2C2C2E), outlineVariant = Color(0xFF38383A),
        error = Color(0xFFFF9CA1), errorContainer = Color(0xFF412125),
    ) else lightColorScheme(
        primary = brand, onPrimary = Color.White,
        primaryContainer = Color(0xFFEAF3FF), onPrimaryContainer = brand,
        surface = Color.White, onSurface = Color(0xFF1D1D1F),
        surfaceVariant = Color(0xFFF5F5F7), onSurfaceVariant = Color(0xFF66666C),
        surfaceContainer = Color(0xFFEEEEF1), outlineVariant = Color(0xFFE0E0E0),
        error = Color(0xFFB4232D), errorContainer = Color(0xFFFFF0F1),
    )
}

@Composable
internal fun GalleryScreen(
    state: GalleryState,
    model: GalleryModel,
    requestAccess: () -> Unit,
    refresh: () -> Unit,
    save: (resumeBatch: Boolean) -> Unit,
) {
    var byDay by rememberSaveable { mutableStateOf(true) }
    var onlySelected by remember { mutableStateOf(false) }
    var reviewTrash by rememberSaveable { mutableStateOf(false) }
    var trashSelection by remember { mutableStateOf(emptySet<String>()) }
    var showResults by rememberSaveable { mutableStateOf(false) }
    var showCopies by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var draftProtectFavorites by rememberSaveable { mutableStateOf(state.protectFavorites) }
    var showGalleryHint by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<GalleryPreviewSource?>(null) }
    var stillPreview by remember { mutableStateOf<DiagnosticResult?>(null) }
    var scrollTouchActive by remember { mutableStateOf(false) }
    val available = remember(state.photos, state.protectFavorites) {
        state.photos.filterNot { state.protectFavorites && it.favorite }
    }
    val selected = remember(available, state.selected) { available.filter { it.key in state.selected } }
    val showingSelected = onlySelected && selected.isNotEmpty()
    val visible = if (showingSelected) selected else available
    val groups = remember(byDay, visible) { if (byDay) visible.groupBy { it.date } else emptyMap() }
    val scrollRows = remember(byDay, groups, visible) {
        buildList {
            if (byDay) {
                var itemIndex = 0
                for ((date, photos) in groups) {
                    add(GalleryScrollRow(itemIndex++, date))
                    for (offset in photos.indices step 5) add(GalleryScrollRow(itemIndex + offset, date))
                    itemIndex += photos.size
                }
            } else {
                for (index in visible.indices step 5) add(GalleryScrollRow(index, visible[index].date))
            }
        }
    }
    val listState = rememberLazyGridState()
    val showBatch = showResults || state.saving
    val initialLoading = state.scanning && state.photos.isEmpty() && state.access != PhotoAccess.NONE
    val colors = MaterialTheme.colorScheme
    val copyResults = remember(state.results) {
        state.results.filter { it.status == VERIFIED_COPY && it.copy != null }.distinctBy { it.copy }
    }

    LaunchedEffect(selected.isEmpty(), preview != null) {
        if (selected.isEmpty()) onlySelected = false
        if (selected.isNotEmpty() || preview != null) showGalleryHint = false
    }
    LaunchedEffect(state.saving) {
        if (state.saving) {
            showCopies = false
            showResults = true
        }
    }
    val back: () -> Unit = { if (showCopies) showCopies = false else showResults = false }
    BackHandler(showBatch) { if (!state.saving && !state.checkingTrash) back() }

    Scaffold(
        modifier = if (stillPreview != null) Modifier.blur(9.dp) else Modifier,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp)
                .padding(start = if (showBatch && !state.saving) 4.dp else 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (showBatch && !state.saving) {
                    IconButton(onClick = back, enabled = !state.checkingTrash,
                        modifier = Modifier.size(44.dp).semantics { contentDescription = "返回" }) {
                        Mark(MarkKind.BACK, colors.onSurface, 20)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (showCopies) "静态副本" else "本次结果", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp)
                } else {
                    if (state.saving) {
                        Mark(MarkKind.LIVE, colors.onSurfaceVariant, 17)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("实况转静态", fontWeight = FontWeight.SemiBold,
                        fontSize = if (state.saving) 13.sp else 21.sp, lineHeight = if (state.saving) 18.sp else 25.sp,
                        color = if (state.saving) colors.onSurfaceVariant else colors.onSurface,
                        modifier = Modifier.semantics { heading() })
                }
            }
        },
        bottomBar = {
            when {
                !showBatch && selected.isNotEmpty() -> Dock(gallerySelection = true) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已选 ${selected.size} 张", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("保存后，原件仍在相册", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    val summary = estimate(selected)
                    val split = summary.indexOf(" 约 ").takeIf { it >= 0 } ?: summary.indexOf(" 至少 ")
                    Text(buildAnnotatedString {
                        if (split < 0) append(summary) else {
                            append(summary.substring(0, split))
                            withStyle(SpanStyle(color = colors.onSurface, fontWeight = FontWeight.SemiBold)) {
                                append(summary.substring(split).substringBefore(" · "))
                            }
                            if (" · " in summary) append(" · " + summary.substringAfter(" · "))
                        }
                    }, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::clearSelection,
                            modifier = Modifier.weight(2f).heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant,
                                contentColor = colors.onSurface)) {
                            Text("取消选择", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PrimaryAction("保存静态副本", modifier = Modifier.weight(3f),
                            onClick = { save(false) })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                state.saving -> ProgressPage(state)
                showBatch && showCopies -> CopiesPage(copyResults, model) { stillPreview = it }
                showBatch -> BatchResults(state, model, copyResults.size, { showCopies = true }, { save(true) }) {
                    trashSelection = state.trashItems.mapTo(linkedSetOf()) { it.operation }
                    reviewTrash = true
                }
                state.access == PhotoAccess.NONE -> EmptyPage(
                    "选择要访问的照片", "授权后可浏览允许访问的实况照片。",
                    "选择照片权限", requestAccess, primary = true,
                )
                else -> {
                    if (initialLoading) LoadingGalleryControls()
                    if (state.photos.isNotEmpty()) {
                        GalleryControls(byDay, showingSelected, selected.isNotEmpty(), !state.protectFavorites,
                            visible.size, available.size, state, showGalleryHint, requestAccess, { day ->
                                if (byDay != day) {
                                    listState.requestScrollToItem(0)
                                    byDay = day
                                }
                            },
                            { onlySelected = !showingSelected }, {
                                draftProtectFavorites = state.protectFavorites
                                filters = true
                            })
                    }
                    if (state.canResumeBatch) {
                        Link("继续未完成的保存", { showResults = true }, Modifier.padding(horizontal = 16.dp))
                    }
                    state.galleryNotice?.let {
                        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.error, fontSize = 13.sp)
                        Link("刷新相册", refresh, Modifier.padding(horizontal = 16.dp))
                    }
                    when {
                        initialLoading -> LoadingGallery(byDay)
                        state.photos.isEmpty() -> EmptyPage("没有可用的实况照片",
                            "拍摄新的实况照片，或检查照片访问范围。", "管理照片权限", requestAccess)
                        visible.isEmpty() -> EmptyPage("没有符合筛选的照片", "调整筛选可查看其他实况照片。")
                        else -> Box(Modifier.weight(1f).fillMaxWidth().pointerInput(listState) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                scrollTouchActive = true
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                    }
                                } finally {
                                    scrollTouchActive = false
                                }
                            }
                        }) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5), state = listState,
                                modifier = Modifier.fillMaxSize().then(galleryRangeSelection(
                                    listState, visible, state.selected, !showingSelected, model::selectRange)),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (byDay) {
                                    groups.forEach { (date, photos) ->
                                        item(key = "day:$date", span = { GridItemSpan(maxLineSpan) }) {
                                            Box(Modifier.padding(top = if (date == groups.keys.first()) 0.dp else 6.dp)) {
                                                DayHeader(date?.let(::galleryDate) ?: "日期未知", photos,
                                                    state.selected, state.partialDay == photos.first().dayKey) {
                                                    model.toggle(photos.map { it.key })
                                                }
                                            }
                                        }
                                        items(photos, key = { it.key }) { photo ->
                                            GalleryTile(photo, photo.key in state.selected, model, state.scanning) { preview = it }
                                        }
                                    }
                                } else items(visible, key = { it.key }) { photo ->
                                    GalleryTile(photo, photo.key in state.selected, model, state.scanning) { preview = it }
                                }
                            }
                            GalleryDateScrollbar(scrollRows, listState, Modifier.align(Alignment.CenterEnd),
                                scrollTouchActive) { date ->
                                date?.format(fullDayFormat) ?: "日期未知"
                            }
                        }
                    }
                }
            }
        }
    }

    if (filters) {
        val protectedSelected = if (draftProtectFavorites) state.photos.count { it.favorite && it.key in state.selected } else 0
        val draftAvailable = state.photos.count { !draftProtectFavorites || !it.favorite }
        val draftSelected = state.photos.count { it.key in state.selected && (!draftProtectFavorites || !it.favorite) }
        val draftCount = if (showingSelected && draftSelected > 0) draftSelected else draftAvailable
        GallerySheet("筛选照片", { filters = false }, footer = {
            PrimaryAction("显示 $draftCount 张照片", onClick = {
                if (draftProtectFavorites != state.protectFavorites) model.protectFavorites(draftProtectFavorites)
                filters = false
            })
        }) {
            ToggleRow("保护收藏", "排除收藏照片", draftProtectFavorites, true) { draftProtectFavorites = it }
            if (protectedSelected > 0) {
                Text("应用后，将撤选 $protectedSelected 张收藏照片。",
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(colors.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = colors.onPrimaryContainer, fontSize = 13.sp)
            }
        }
    }
    if (reviewTrash) GallerySheet("移入回收站前核对", { reviewTrash = false }, skipPartial = true, footer = {
        val title = if (trashSelection.isEmpty()) "移入回收站" else "移入回收站（${trashSelection.size} 张）"
        PrimaryAction(title, destructive = true,
            enabled = trashSelection.size in 1..2000,
            onClick = { reviewTrash = false; model.requestTrash(trashSelection) })
    }) {
        val verified = state.results.associateBy { it.operation }
        val selectedSources = state.trashItems.filter { it.operation in trashSelection }
            .mapNotNull { verified[it.operation]?.source }
            .toSet()
        val sources = remember(state.photos, selectedSources) { state.photos.filter { it.uri in selectedSources } }
        LazyColumn(Modifier.heightIn(max = 350.dp)) {
            item {
                Text(if (trashSelection.isEmpty()) "请选择要移入回收站的原件"
                    else estimate(sources, trashSelection.size),
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(11.dp)).padding(12.dp),
                    fontSize = 13.sp, lineHeight = 20.sp)
                Text("下列照片的静态副本已核验。原件移入后可在系统相册的「最近删除」或「回收站」恢复。",
                    Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = colors.onSurfaceVariant)
                if (trashSelection.size > 2000) Text("请分批确认，每次最多 2000 张", color = colors.error)
            }
            items(state.trashItems, key = { it.operation }) { item ->
                val result = verified[item.operation]
                val changeSelection: (Boolean) -> Unit = { checked ->
                    trashSelection = if (checked) trashSelection + item.operation else trashSelection - item.operation
                }
                PhotoRow(item.name, result?.captureTime, result?.source, model) {
                    Checkbox(item.operation in trashSelection, changeSelection,
                        modifier = Modifier.semantics { contentDescription = "处理 ${item.name}" })
                }
            }
        }
    }
    preview?.let { source -> GalleryLivePreview(source, model) { preview = null } }
    stillPreview?.let { photo ->
        photo.copy?.let { uri -> StillPhotoPreview(uri, photo.name, model) { stillPreview = null } }
    }
}

@Composable
private fun StillPhotoPreview(uri: Uri, name: String, model: GalleryModel, dismiss: () -> Unit) {
    var cover by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    LaunchedEffect(uri) {
        cover = model.previewImage(uri)
        loading = false
    }
    PreviewDialog(cover?.aspectRatio ?: 1f, name, dismiss = dismiss) {
        PreviewImage(cover, loading)
    }
}

private val Bitmap.aspectRatio: Float get() = width.toFloat() / height

@Composable
private fun PreviewImage(bitmap: Bitmap?, loading: Boolean) {
    when {
        bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        loading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        else -> Text("暂时无法读取照片", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun PreviewDialog(
    aspectRatio: Float,
    name: String,
    dismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val width = (maxWidth - 24.dp).coerceAtLeast(1.dp)
            val height = minOf(width / aspectRatio, maxHeight * 0.55f).coerceAtLeast(1.dp)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.82f))
                .pointerInput(dismiss) { detectTapGestures { dismiss() } })
            Column(Modifier.width(width).clip(RoundedCornerShape(18.dp)).background(Color(0xFF292929))
                .pointerInput(Unit) { detectTapGestures { } }) {
                PreviewTitle(name, dismiss)
                Box(Modifier.fillMaxWidth().height(height).background(Color.Black),
                    contentAlignment = Alignment.Center, content = content)
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Text("静态副本", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PreviewTitle(name: String, close: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), color = Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(close, modifier = Modifier.size(44.dp).semantics { contentDescription = "关闭预览" }) {
            Mark(MarkKind.CLOSE, Color.White, 18)
        }
    }
}

@Composable
private fun GalleryControls(
    byDay: Boolean, onlySelected: Boolean, hasSelection: Boolean, filterActive: Boolean,
    count: Int, availableCount: Int, state: GalleryState, showGalleryHint: Boolean, requestAccess: () -> Unit,
    setDay: (Boolean) -> Unit, toggleSelected: () -> Unit, openFilters: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val enlarged = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.35f
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (enlarged) {
            Segments(byDay, setDay, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewFilters(onlySelected, hasSelection, filterActive, toggleSelected, openFilters)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Segments(byDay, setDay, Modifier.widthIn(max = 156.dp))
                }
                ViewFilters(onlySelected, hasSelection, filterActive, toggleSelected, openFilters)
            }
        }
        val protected = state.photos.size - availableCount
        val countMessage = if (state.scanning) "正在读取相册… · $count 张" else "$count 张"
        val meta = countMessage + if (protected > 0) " · ${protected} 张收藏已保护" else ""
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(meta, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
            if (state.access == PhotoAccess.LIMITED) Link("管理照片权限", requestAccess)
            else if (!enlarged && showGalleryHint) Text("横划多选 · 长按预览", fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = softDividerColor())
}

@Composable
private fun Segments(byDay: Boolean, setDay: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.background(colors.surfaceContainer, RoundedCornerShape(11.dp)).padding(3.dp)) {
        listOf(true to "按日", false to "全部").forEach { (day, title) ->
            val active = day == byDay
            val segment = Modifier.weight(1f).heightIn(min = 44.dp)
            val shape = RoundedCornerShape(8.dp)
            Box(segment.then(if (active) Modifier.shadow(2.dp, shape) else Modifier.clip(shape))
                .background(if (active) colors.surface else Color.Transparent)
                .selectable(active, role = Role.Tab, onClick = { setDay(day) })
                .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            }
        }
    }
}

@Composable
private fun RowScope.ViewFilters(
    onlySelected: Boolean, enabled: Boolean, filterActive: Boolean,
    toggleSelected: () -> Unit, openFilters: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (onlySelected) colors.primaryContainer else Color.Transparent)
        .selectable(onlySelected, enabled = enabled, role = Role.Button, onClick = toggleSelected)
        .padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text("只看已选", fontSize = 13.sp, color = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = if (onlySelected) FontWeight.SemiBold else FontWeight.Normal)
    }
    val ink = if (filterActive) colors.primary else colors.onSurface
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (filterActive) colors.primaryContainer else colors.surfaceVariant)
        .clickable(role = Role.Button, onClickLabel = "筛选照片", onClick = openFilters)
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Mark(MarkKind.FILTER, ink, 16)
        Text("筛选", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
private fun DayHeader(title: String, photos: List<GalleryPhoto>, selection: Set<String>, partial: Boolean, toggle: () -> Unit) {
    val count = photos.count { it.key in selection }
    val all = count == photos.size
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f).padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
            Text(if (count > 0) "$count 已选 · ${photos.size} 张" else "${photos.size} 张",
                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        }
        val label = when {
            partial && all -> "取消已加载"
            partial -> "全选已加载"
            all -> "取消当天全选"
            else -> "全选当天"
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
            .clickable(interactionSource = null, indication = null, role = Checkbox, onClick = toggle)
            .semantics {
                contentDescription = label
                stateDescription = if (all) "全选" else if (count > 0) "部分选中" else "未选"
        }, contentAlignment = Alignment.CenterEnd) {
            val shape = RoundedCornerShape(6.dp)
            val fill = if (all) Color(0xFF0066CC) else if (count > 0) colors.primaryContainer else Color.Transparent
            val stroke = if (all) fill else if (count > 0) colors.primary else colors.outline
            Box(Modifier.size(20.dp).background(fill, shape).border(1.5.dp, stroke, shape),
                contentAlignment = Alignment.Center) {
                if (all) Mark(MarkKind.CHECK, Color.White, 13)
                else if (count > 0) Box(Modifier.size(9.dp, 2.dp).background(colors.primary, CircleShape))
            }
        }
    }
}

@Composable
private fun Dock(
    gallerySelection: Boolean = false,
    insideSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = if (insideSheet) softDividerColor() else MaterialTheme.colorScheme.outlineVariant)
        val insets = if (insideSheet) Modifier else Modifier.navigationBarsPadding()
        Column(insets.padding(start = 16.dp, end = 16.dp,
            top = if (insideSheet) 9.dp else 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (gallerySelection) 4.dp else 5.dp), content = content)
    }
}

@Composable
private fun PrimaryAction(
    title: String, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true,
    destructive: Boolean = false, icon: MarkKind? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(onClick, modifier.heightIn(min = 46.dp), enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) colors.errorContainer else Color(0xFF0066CC),
            contentColor = if (destructive) colors.error else Color.White,
            disabledContainerColor = colors.surfaceContainer, disabledContentColor = disabledInk(),
        )) {
        if (icon != null) {
            Mark(icon, Color.White, 20)
            Spacer(Modifier.width(8.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp)
    }
}

@Composable
private fun disabledInk() =
    if (MaterialTheme.colorScheme.surface == Color.Black) Color(0xFFA8A8AE) else Color(0xFF75757C)

@Composable
private fun Link(title: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(title, fontSize = 14.sp, color = if (enabled) MaterialTheme.colorScheme.primary else disabledInk())
    }
}

@Composable
private fun EmptyPage(
    title: String, subtitle: String, actionTitle: String? = null, action: () -> Unit = {}, primary: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.size(49.dp).background(colors.primaryContainer, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center) { Mark(MarkKind.PHOTO, colors.primary, 24) }
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(subtitle, color = colors.onSurfaceVariant, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionTitle != null) {
            if (primary) PrimaryAction(actionTitle, Modifier.widthIn(max = 280.dp), onClick = action)
            else Link(actionTitle, action)
        }
    }
}

@Composable
private fun LoadingGalleryControls() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(44.dp).background(colors.surfaceContainer, RoundedCornerShape(11.dp)))
            Box(Modifier.width(92.dp).height(44.dp).background(colors.surfaceContainer, CircleShape))
        }
        Text("正在读取相册…", Modifier.heightIn(min = 19.dp),
            fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun LoadingGallery(byDay: Boolean) {
    val placeholder = placeholderColor()
    val pulse = rememberInfiniteTransition(label = "album skeleton")
    val opacity by pulse.animateFloat(
        initialValue = 0.57f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(tween(980), RepeatMode.Reverse),
        label = "placeholder alpha",
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)
        .graphicsLayer { alpha = opacity }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { group ->
            if (byDay) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp, 12.dp).background(placeholder, RoundedCornerShape(6.dp)))
                }
            }
            repeat(if (group == 0) 3 else 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) {
                        Box(Modifier.weight(1f).aspectRatio(1f).background(placeholder, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun GallerySheet(
    title: String, close: () -> Unit, skipPartial: Boolean = false,
    footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial),
        shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
        containerColor = colors.surface, contentColor = colors.onSurface,
        tonalElevation = 0.dp, scrimColor = Color(0x6616161B),
        dragHandle = null) {
        Column(Modifier.fillMaxWidth().semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceVariant)
                    .clickable(role = Role.Button, onClick = close).semantics { contentDescription = "关闭" },
                    contentAlignment = Alignment.Center) { Mark(MarkKind.CLOSE, colors.onSurface, 17) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                content = content)
            Dock(insideSheet = true) { footer() }
        }
    }
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
            Text(description, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        }
        Box(Modifier.size(43.dp, 26.dp).background(
            if (checked) Color(0xFF0066CC) else colors.surfaceContainer, CircleShape,
        ).padding(2.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(22.dp).background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = if (enabled) 0.10f else 0.04f), CircleShape))
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun GalleryTile(
    photo: GalleryPhoto,
    checked: Boolean,
    model: GalleryModel,
    scanning: Boolean,
    preview: (GalleryPreviewSource) -> Unit,
) {
    var image by remember(photo.key, photo.generation) { mutableStateOf<Bitmap?>(null) }
    var coordinates by remember(photo.key) { mutableStateOf<LayoutCoordinates?>(null) }
    val photoAlpha by animateFloatAsState(if (image == null) 0f else 1f,
        animationSpec = tween(if (scanning) 155 else 0), label = "thumbnail")
    LaunchedEffect(photo.key, photo.generation) { image = model.thumbnail(photo, 192) }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(placeholderColor())
        .onGloballyPositioned { coordinates = it }
        .semantics {
            contentDescription = "实况照片，${photo.name}，${captureDate(photo.captureTime)}"
            selected = checked
            stateDescription = if (checked) "已选中" else "未选中"
        }.combinedClickable(onClick = { model.toggle(listOf(photo.key)) }, onLongClick = {
            val bounds = coordinates?.takeIf { it.isAttached }?.let {
                val position = it.positionOnScreen()
                if (position.isSpecified) Rect(position.x, position.y,
                    position.x + it.size.width, position.y + it.size.height) else null
            }
            preview(GalleryPreviewSource(photo, bounds, image))
        }, role = Role.Button, onLongClickLabel = "预览实况")) {
        image?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = photoAlpha },
                contentScale = ContentScale.Crop)
        }
        if (checked) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.26f)))
        val shade = Color(0x75191919)
        TileMark(MarkKind.LIVE, shade, Modifier.align(Alignment.TopStart).padding(3.dp))
        if (photo.favorite) TileMark(MarkKind.HEART, shade, Modifier.align(Alignment.BottomStart).padding(3.dp))
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(15.dp)
            .background(if (checked) Color(0xFF0066CC) else Color(0x33000000), CircleShape)
            .border(1.2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Mark(MarkKind.CHECK, Color.White, 10)
        }
    }
}

@Composable
private fun TileMark(kind: MarkKind, background: Color, modifier: Modifier) {
    Box(modifier.size(15.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Mark(kind, Color.White, 10)
    }
}

@Composable
private fun ProgressPage(state: GalleryState) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.size(49.dp).background(colors.primaryContainer, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center) { Mark(MarkKind.PHOTO, colors.primary, 24) }
            Text("正在保存副本", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 13.dp, bottom = 7.dp).semantics { heading() })
            Text(state.activePhoto?.let { "${state.saveStage.label} · $it" } ?: state.saveStage.label,
                color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
            val track = Modifier.fillMaxWidth().padding(top = 19.dp, bottom = 10.dp).height(5.dp)
            if (state.total > 0) {
                LinearProgressIndicator({ state.results.size.toFloat() / state.total }, track,
                    color = Color(0xFF0066CC), trackColor = colors.surfaceContainer,
                    gapSize = 0.dp, drawStopIndicator = {})
                Text("已完成 ${state.results.size} / ${state.total} 张", Modifier.padding(bottom = 4.dp),
                    color = colors.onSurfaceVariant, fontSize = 14.sp)
            } else LinearProgressIndicator(track, color = Color(0xFF0066CC),
                trackColor = colors.surfaceContainer, gapSize = 0.dp)
            Text("先完成保存和核验，实况原件仍在。", color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun BatchResults(
    state: GalleryState, model: GalleryModel, copyCount: Int, openCopies: () -> Unit,
    resume: () -> Unit, openTrashReview: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val originalStatuses = state.results.mapNotNull { state.trashStatuses[it.operation] }
    val moved = if (state.checkingTrash) 0 else originalStatuses.count { it == TrashStatus.IN_TRASH }
    val unresolved = originalStatuses.count { it == TrashStatus.NEEDS_CHECK || it == TrashStatus.REQUESTED }
    var details by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = "本次结果" },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
        item {
            Text(if (copyCount > 0) "已保存 $copyCount 张静态副本" else "本次未确认静态副本",
                fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.55).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            val summary = when {
                state.checkingTrash -> "正在核对原件状态"
                moved > 0 || unresolved > 0 -> listOfNotNull(
                    "$moved 张原件已移入回收站".takeIf { moved > 0 },
                    "$unresolved 张原件待核对".takeIf { unresolved > 0 },
                ).joinToString("，") + "。"
                else -> "原件仍在相册。"
            }
            if (summary.isNotEmpty()) {
                Text(summary, Modifier.padding(top = 8.dp, bottom = 16.dp), fontSize = 14.sp,
                    lineHeight = 21.sp, color = colors.onSurfaceVariant)
            }
            state.notice?.let { Text(it, Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = colors.error) }
            state.trashNotice?.let { Text(it, Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = colors.onSurfaceVariant) }
            if (unresolved > 0 && !state.checkingTrash) {
                Link("重新核对原件状态", model::recheckTrash, Modifier.padding(bottom = 8.dp))
            }
            if (copyCount > 0) {
                PrimaryAction("查看静态副本", enabled = !state.checkingTrash, icon = MarkKind.PHOTO, onClick = openCopies)
            }
            if (state.trashItems.isNotEmpty() || moved > 0) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
                    Text("原件处理", Modifier.padding(top = 12.dp).semantics { heading() },
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
                    if (state.trashItems.isNotEmpty()) {
                        ResultAction("移入回收站前核对", "${state.trashItems.size} 张原件可复核",
                            MarkKind.TRASH, openTrashReview, destructive = true, enabled = !state.checkingTrash)
                    }
                    if (moved > 0) {
                        if (state.trashItems.isNotEmpty()) HorizontalDivider(color = colors.outlineVariant)
                        Text("原件可在系统相册的最近删除中恢复", Modifier.padding(vertical = 12.dp),
                            color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.padding(top = 20.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("逐张结果", Modifier.alignByBaseline(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("${state.results.size} 张", Modifier.alignByBaseline(), color = colors.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        items(state.results, key = { it.operation ?: it.source ?: it.copy ?: it.name }) { result ->
            val trash = state.trashStatuses[result.operation]?.let {
                if (state.checkingTrash) TrashStatus.REQUESTED else it
            }
            val (label, detail) = when (trash) {
                TrashStatus.IN_TRASH -> "已移入回收站" to "静态副本已保留"
                TrashStatus.PRESENT -> "原件仍在" to "尚未移入回收站"
                TrashStatus.RESTORED -> "原件仍在" to "原件已从回收站恢复"
                TrashStatus.NEEDS_CHECK, TrashStatus.REQUESTED -> "待核对" to "请在系统相册核对原件状态"
                null -> when (result.status) {
                    VERIFIED_COPY -> "已核验" to "相册已读回静态副本"
                    "处理失败" -> "保存失败" to result.detail
                    else -> result.status to result.detail
                }
            }
            PhotoRow(result.name, result.captureTime, if (trash == TrashStatus.IN_TRASH) result.copy else result.source ?: result.copy,
                model, detail) {
                StatusPill(label, result.status == VERIFIED_COPY && trash != TrashStatus.NEEDS_CHECK && trash != TrashStatus.REQUESTED)
            }
        }
        if (state.results.isNotEmpty() || state.canResumeBatch) item {
            Link(if (details) "收起详细信息" else "详细信息", { details = !details }, Modifier.padding(top = 5.dp))
            if (details) {
                state.results.forEach { result ->
                    Text("${result.name} · ${result.detail}", Modifier.padding(bottom = 7.dp),
                        color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            if (state.canResumeBatch) Link("继续未完成批次", resume,
                enabled = state.access != PhotoAccess.NONE && !state.checkingTrash)
        }
    }
}

@Composable
private fun ResultAction(
    title: String, subtitle: String, icon: MarkKind, action: () -> Unit, destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (destructive) colors.error else colors.primary
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Mark(icon, ink, 20)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        }
        Mark(MarkKind.NEXT, colors.onSurfaceVariant, 15)
    }
}

@Composable
private fun PhotoRow(
    name: String, captured: Long?, uri: Uri?, model: GalleryModel, detail: String? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 59.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteThumbnail(uri, model, 128, Modifier.size(43.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            Text(captureDate(captured) + (detail?.let { " · $it" } ?: ""),
                fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
        }
        trailing()
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun StatusPill(label: String, good: Boolean) {
    val dark = MaterialTheme.colorScheme.surface == Color.Black
    val fill = if (good) if (dark) Color(0xFF193A27) else Color(0xFFE9F7EE)
        else if (dark) Color(0xFF3C2C16) else Color(0xFFFFF3DF)
    val text = if (good) if (dark) Color(0xFF8DE3AF) else Color(0xFF216242)
        else if (dark) Color(0xFFFFD18D) else Color(0xFF79500B)
    Text(label, Modifier.widthIn(max = 95.dp).background(fill, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = text)
}

@Composable
private fun CopiesPage(results: List<DiagnosticResult>, model: GalleryModel, preview: (DiagnosticResult) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("本次保存 ${results.size} 张", fontSize = 34.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                Text("系统相册 → 图集 → LiveToFrame", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(results, key = { it.copy.toString() }) { result ->
            RemoteThumbnail(result.copy, model, 256, Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClickLabel = "查看静态副本") { preview(result) }, result.name)
        }
    }
}

@Composable
private fun RemoteThumbnail(
    uri: Uri?, model: GalleryModel, pixels: Int, modifier: Modifier, description: String? = null,
    scale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(uri, pixels) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, pixels) { bitmap = uri?.let { model.thumbnail(it, pixels) } }
    Box(modifier.background(placeholderColor()), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = scale) }
            ?: Mark(MarkKind.PHOTO, MaterialTheme.colorScheme.onSurfaceVariant, 19)
    }
}

@Composable
private fun placeholderColor() =
    if (MaterialTheme.colorScheme.surface == Color.Black) Color(0xFF282A2E) else Color(0xFFE4E8EB)

private enum class MarkKind { LIVE, CHECK, HEART, FILTER, BACK, CLOSE, NEXT, TRASH, PHOTO }

@Composable
private fun Mark(kind: MarkKind, color: Color, length: Int) {
    Canvas(Modifier.size(length.dp)) {
        val u = size.width / 24
        val width = 1.8f * u
        fun line(x: Float, y: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x * u, y * u), Offset(x2 * u, y2 * u), width, StrokeCap.Round)
        }
        when (kind) {
            MarkKind.LIVE -> {
                drawCircle(color, 9 * u, style = Stroke(width))
                drawCircle(color, 5 * u, style = Stroke(width))
                drawCircle(color, 1.6f * u)
            }
            MarkKind.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
            MarkKind.HEART -> drawPath(Path().apply {
                moveTo(12 * u, 20 * u)
                cubicTo(3 * u, 14 * u, 1 * u, 8 * u, 6 * u, 5 * u)
                cubicTo(9 * u, 3 * u, 11 * u, 5 * u, 12 * u, 7 * u)
                cubicTo(13 * u, 5 * u, 15 * u, 3 * u, 18 * u, 5 * u)
                cubicTo(23 * u, 8 * u, 21 * u, 14 * u, 12 * u, 20 * u)
                close()
            }, color)
            MarkKind.FILTER -> { line(3f, 6f, 21f, 6f); line(6f, 12f, 18f, 12f); line(9f, 18f, 15f, 18f) }
            MarkKind.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 12f, 5f); line(5f, 12f, 12f, 19f) }
            MarkKind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            MarkKind.NEXT -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            MarkKind.TRASH -> {
                line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                line(6f, 6f, 8f, 21f); line(18f, 6f, 16f, 21f); line(8f, 21f, 16f, 21f)
                line(10f, 10f, 11f, 17f); line(14f, 10f, 13f, 17f)
            }
            MarkKind.PHOTO -> {
                drawRoundRect(color, Offset(3 * u, 4 * u), androidx.compose.ui.geometry.Size(18 * u, 16 * u),
                    androidx.compose.ui.geometry.CornerRadius(2 * u), style = Stroke(width))
                drawCircle(color, 1.5f * u, Offset(8 * u, 9 * u))
                line(4f, 17f, 10f, 12f); line(10f, 12f, 14f, 15f); line(14f, 15f, 20f, 10f)
            }
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val fullDayFormat = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.SIMPLIFIED_CHINESE)
private fun galleryDate(date: LocalDate): String =
    date.format(if (date.year == LocalDate.now().year) dayFormat else fullDayFormat)
private val captureFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
private fun captureDate(time: Long?): String = time?.takeIf { it > 0 }?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(captureFormat)
} ?: "拍摄时间未知"

private fun estimate(photos: List<GalleryPhoto>, total: Int = photos.size): String {
    val known = photos.mapNotNull { it.estimate }
    val label = "清空回收站后预计节省"
    if (known.isEmpty()) return "可节省的空间暂未知"
    val mb = known.sum() / 1_000_000.0
    val amount = if (mb >= 1000) String.format(Locale.ROOT, "%.2f GB", mb / 1000)
        else String.format(Locale.ROOT, "%.1f MB", mb)
    val unknown = total - known.size
    return if (unknown == 0) "$label 约 $amount" else "$label 至少 $amount · ${unknown} 张未知"
}
