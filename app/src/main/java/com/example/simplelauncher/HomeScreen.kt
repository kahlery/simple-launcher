package com.example.simplelauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// Ana ekran ızgarası: istersen buradan değiştir.
private const val COLUMNS = 5
// Ekranda aynı anda görünen satır sayısı; fazlası aşağı kaydırılarak görülür.
private const val ROWS = 7

private val ICON_SIZE = 52.dp
private val ICON_SHAPE = RoundedCornerShape(percent = (ICON_CORNER_FRACTION * 100).toInt())

// İkon merkezi, hücre merkezinin bu kadar üstünde (altındaki isim yüzünden).
private val ICON_CENTER_SHIFT = 9.dp

// Parmak bir hücrede bu kadar bekleyince diğerleri yer açar; bu sayede bir ikonun
// ortasına gelip klasör oluşturmak mümkün olur.
private const val REORDER_DELAY_MS = 180L

private const val FOLDER_COLUMNS = 4
private const val MENU_ANIM_MS = 150
private const val FOLDER_ANIM_MS = 220
private val FOLDER_CELL_HEIGHT = 92.dp

// Izgaranın üstünde, listeyle birlikte kayan hava durumu, ezan vakti ve banka satırlarının en az yükseklikleri.
// Hava durumu sığmazsa iki satıra çıkar; gerçek yükseklik ölçülür.
private val BANNER_HEIGHT = 32.dp
private val PRAYER_ROW_HEIGHT = 28.dp

// Üst şerit en fazla 5 satır: hava durumu (dikey boşluğuyla) + 4 satır. Fazlası şeridin içinde kayar.
private val BANNER_PAGE_HEIGHT = BANNER_HEIGHT + 8.dp + PRAYER_ROW_HEIGHT * 4

// Sayfa noktalarının kapladığı yükseklik (üst boşluk + nokta + alt boşluk).
private val PAGE_DOTS_HEIGHT = 6.dp + 5.dp + 4.dp

/** Uzun basılan uygulamanın menüsü: [anchor] ikon hücresinin kök koordinatlarındaki yeri. */
private data class AppMenuState(val pkg: String, val anchor: Rect, val folderKey: String?)

/** Sürükleme sırasında parmağın altındaki hücre; ikonun tam üstündeyse [mergeKey] doludur. */
private data class DropTarget(val index: Int, val mergeKey: String?)

@Composable
fun HomeScreen(
    vm: LauncherViewModel,
    onRemoveApp: (String) -> Unit,
    onRequestLocation: () -> Unit,
    onSwipeDown: () -> Unit,
    onConnectBank: (BankSlot) -> Unit,
) {
    val order = vm.order
    val apps = vm.apps
    val folders = vm.folders
    val density = LocalDensity.current
    val dots = NotificationDots.packages

    val scroll = rememberScrollState()

    var editMode by remember { mutableStateOf(false) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var openFolderKey by remember { mutableStateOf<String?>(null) }
    val openFolder = openFolderKey?.let { folders[it] }
    // Açık klasörün içindeki düzenleme modu (titreyen ikonlar, silme düğmeleri).
    var folderEditMode by remember { mutableStateOf(false) }
    LaunchedEffect(openFolderKey) { folderEditMode = false }
    var menu by remember { mutableStateOf<AppMenuState?>(null) }
    // Eşik ayarları açık olan banka.
    var thresholdSlot by remember { mutableStateOf<BankSlot?>(null) }
    val bannerPager = rememberPagerState { 2 }
    // Şerit sayfalarının kendi dikey kaydırmaları.
    val infoScroll = rememberScrollState()
    val tableScroll = rememberScrollState()

    BackHandler(enabled = editMode || openFolder != null || menu != null || thresholdSlot != null) {
        when {
            thresholdSlot != null -> thresholdSlot = null
            menu != null -> menu = null
            openFolder != null && folderEditMode -> folderEditMode = false
            openFolder != null -> openFolderKey = null
            else -> editMode = false
        }
    }

    // Home tuşu: klasörü ve düzenleme modunu kapat, en üste dön.
    LaunchedEffect(vm.homeSignal) {
        if (vm.homeSignal > 0) {
            openFolderKey = null
            editMode = false
            menu = null
            thresholdSlot = null
            if (bannerPager.currentPage != 0) bannerPager.animateScrollToPage(0)
            if (scroll.value != 0) scroll.animateScrollTo(0)
        }
    }

    // Ekran açıkken hava durumunu ara ara tazele (sık istek atmamak için VM kendisi süzer).
    LaunchedEffect(Unit) {
        while (true) {
            delay(10 * 60 * 1000L)
            vm.refreshWeather()
            vm.refreshBank()
            vm.refreshMarkets()
        }
    }

    val mergeRadiusPx = with(density) { (ICON_SIZE * 0.4f).toPx() }
    var bannerPx by remember { mutableIntStateOf(with(density) { (BANNER_HEIGHT + PRAYER_ROW_HEIGHT * 2).roundToPx() }) }
    val iconShiftPx = with(density) { ICON_CENTER_SHIFT.toPx() }
    // Hücre yüksekliği üstteki şeridin gerçek boyuna bağlı değil: ekrandan yalnızca sabit bir pay düşülür.
    // Böylece şeride satır (banka, piyasa tablosu) eklendikçe ikonlar ezilmez; ızgara aşağı kayar.
    val reservedPx = with(density) { (BANNER_PAGE_HEIGHT + PAGE_DOTS_HEIGHT).roundToPx() }
    fun cellHeight(): Int = (gridSize.height - reservedPx) / ROWS

    // Parmağın altındaki hücre ve (ikonun ortasındaysa) birleşme hedefi.
    // [pointer] görünen alan koordinatında; ızgaraya göre kaydırma ve şerit yüksekliği kadar kaymış.
    val dropTarget by remember {
        derivedStateOf {
            val dragKey = draggedKey ?: return@derivedStateOf null
            if (gridSize.width == 0 || gridSize.height <= reservedPx) return@derivedStateOf null
            val cellW = gridSize.width / COLUMNS.toFloat()
            val cellH = cellHeight()
            val contentY = pointer.y + scroll.value - bannerPx
            val col = (pointer.x / cellW).toInt().coerceIn(0, COLUMNS - 1)
            val row = (contentY / cellH).toInt().coerceAtLeast(0)
            val index = (row * COLUMNS + col).coerceAtMost(order.lastIndex)
            val occupant = order.getOrNull(index)
            val nearCenter = abs(pointer.x - (col + 0.5f) * cellW) < mergeRadiusPx &&
                abs(contentY - ((row + 0.5f) * cellH - iconShiftPx)) < mergeRadiusPx
            val canMerge = occupant != null && occupant != dragKey && !isFolderKey(dragKey) && nearCenter
            DropTarget(index, if (canMerge) occupant else null)
        }
    }

    // Parmak bir hücrenin kenarında kısa süre bekleyince listeyi yeniden diz.
    LaunchedEffect(dropTarget) {
        val target = dropTarget ?: return@LaunchedEffect
        if (target.mergeKey != null) return@LaunchedEffect
        delay(REORDER_DELAY_MS)
        draggedKey?.let { vm.move(it, target.index) }
    }

    // Sürüklerken parmak ızgaranın üst/alt kenarına gelince liste kendiliğinden kayar. Kenar satırdaki
    // bir ikona basılı tutmak listeyi kaydırmasın diye ancak parmak biraz yol aldıktan sonra devreye girer.
    val edgePx = with(density) { 56.dp.toPx() }
    val minTravelPx = with(density) { 24.dp.toPx() }
    val autoScrollStepPx = with(density) { 10.dp.toPx() }
    val edgeDir by remember {
        derivedStateOf {
            when {
                draggedKey == null || gridSize.height == 0 -> 0
                (pointer - dragStart).getDistance() < minTravelPx -> 0
                pointer.y < edgePx -> -1
                pointer.y > gridSize.height - edgePx -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(edgeDir) {
        while (edgeDir != 0) {
            scroll.scrollBy(edgeDir * autoScrollStepPx)
            delay(16)
        }
    }

    // Verilen noktanın (görünen alan koordinatı) altındaki öğenin listedeki indeksi (yoksa -1).
    fun hitIndex(p: Offset): Int {
        if (gridSize.width == 0 || gridSize.height <= reservedPx) return -1
        val contentY = p.y + scroll.value - bannerPx
        if (contentY < 0) return -1
        val col = (p.x / (gridSize.width / COLUMNS.toFloat())).toInt()
        val row = (contentY / (cellHeight())).toInt()
        if (col !in 0 until COLUMNS) return -1
        val index = row * COLUMNS + col
        return if (index < order.size) index else -1
    }

    // Izgaradaki bir hücrenin kök koordinatlarındaki yeri (menüyü ikonun yanına yerleştirmek için).
    fun cellRect(index: Int): Rect {
        val coords = gridCoords ?: return Rect.Zero
        val cellW = gridSize.width / COLUMNS
        val cellH = cellHeight()
        val topLeft = coords.localToRoot(
            Offset((index % COLUMNS * cellW).toFloat(), (index / COLUMNS * cellH + bannerPx - scroll.value).toFloat()),
        )
        return Rect(topLeft, Size(cellW.toFloat(), cellH.toFloat()))
    }

    val swipePx = with(density) { 48.dp.toPx() }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDown by remember { mutableStateOf(Offset.Zero) }

    // Kapanırken de içeriği çizebilmek için son açılan klasör akılda tutulur.
    var shownFolderKey by remember { mutableStateOf<String?>(null) }
    var folderOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    LaunchedEffect(openFolderKey) {
        val key = openFolderKey ?: return@LaunchedEffect
        shownFolderKey = key
        if (rootSize.width > 0 && rootSize.height > 0) {
            folderOrigin = TransformOrigin(lastDown.x / rootSize.width, lastDown.y / rootSize.height)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            // Liste en üstteyken herhangi bir yerden aşağı kaydırınca bildirim paneli açılır.
            // Liste aşağıdaysa aynı hareket listeyi yukarı kaydırır.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    lastDown = down.position
                    if (scroll.value != 0) return@awaitEachGesture
                    // Şeridin içi kaydırılmışsa aşağı kaydırma önce onu geri sarar, bildirim panelini açmaz.
                    val bannerScroll = if (bannerPager.currentPage == 0) infoScroll else tableScroll
                    if (bannerScroll.value != 0) return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || draggedKey != null) break
                        val d = change.position - down.position
                        if (d.y > swipePx && d.y > abs(d.x) * 2) {
                            if (openFolderKey == null && menu == null) onSwipeDown()
                            break
                        }
                        if (abs(d.x) > swipePx || d.y < -swipePx) break
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { gridSize = it }
                    .onGloballyPositioned { gridCoords = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (scroll.isScrollInProgress) return@awaitEachGesture
                            val hit = hitIndex(down.position)
                            if (hit < 0) return@awaitEachGesture
                            val itemKey = order[hit]

                            // Uzun basma: hareket etmeden, başka biri tüketmeden süre dolarsa.
                            var cancelled = false
                            val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (!cancelled) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed || change.isConsumed ||
                                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        cancelled = true
                                    }
                                }
                            } == null
                            if (!longPressed) return@awaitEachGesture

                            // Uygulamada önce seçenek menüsü açılır; parmak kayınca menü kapanıp sürükleme başlar.
                            // Klasörde menü yok, doğrudan düzenleme moduna geçilir.
                            if (isFolderKey(itemKey)) {
                                editMode = true
                            } else {
                                menu = AppMenuState(itemKey, cellRect(hit), folderKey = null)
                            }
                            draggedKey = itemKey
                            pointer = down.position
                            dragStart = down.position
                            var released = false
                            try {
                                while (true) {
                                    // Initial geçişte tüketiyoruz ki liste kaydırıcı hareketi görmesin.
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        released = true
                                        break
                                    }
                                    pointer = change.position
                                    if (menu != null &&
                                        (pointer - down.position).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        menu = null
                                        editMode = true
                                    }
                                }
                            } finally {
                                val target = dropTarget
                                draggedKey = null
                                val mergeKey = target?.mergeKey
                                if (released && mergeKey != null) {
                                    // Yeni klasör oluştuysa isim verebilmek için hemen aç.
                                    vm.merge(itemKey, mergeKey)?.let { openFolderKey = it }
                                } else if (target != null) {
                                    vm.move(itemKey, target.index)
                                }
                                vm.commitOrder()
                            }
                        }
                    },
            ) {
                if (gridSize.width > 0 && gridSize.height > reservedPx) {
                    val cellW = gridSize.width / COLUMNS
                    val cellH = cellHeight()
                    val totalRows = maxOf((order.size + COLUMNS - 1) / COLUMNS, ROWS)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll, enabled = draggedKey == null),
                    ) {
                        // Hava durumu şeridi ve ezan vakti ızgaranın ilk satırı gibi listeyle birlikte kayar.
                        // Üst şerit yatay sayfalar: 1) hava durumu, ezan, bankalar 2) piyasa performansı.
                        // Yüksekliği sabit 5 satır; uzun sayfa kendi içinde dikey kayar.
                        Column(modifier = Modifier.onSizeChanged { bannerPx = it.height }) {
                            HorizontalPager(
                                state = bannerPager,
                                modifier = Modifier.height(BANNER_PAGE_HEIGHT),
                                verticalAlignment = Alignment.Top,
                            ) { page ->
                                when (page) {
                                    0 -> InfoPage(
                                        vm = vm,
                                        scroll = infoScroll,
                                        onRequestLocation = onRequestLocation,
                                        onConnectBank = onConnectBank,
                                        onThresholds = { thresholdSlot = it },
                                    )
                                    else -> PerformanceTable(
                                        data = vm.markets,
                                        scroll = tableScroll,
                                        rowHeight = PRAYER_ROW_HEIGHT,
                                        onLongPress = { vm.refreshMarkets(force = true) },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            PageDots(
                                count = bannerPager.pageCount,
                                current = bannerPager.currentPage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 4.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { (totalRows * cellH).toDp() })
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { editMode = false },
                        ) {
                            for (i in order.indices) {
                                val itemKey = order[i]
                                val folder = if (isFolderKey(itemKey)) folders[itemKey] ?: continue else null
                                val entry = if (folder == null) apps[itemKey] ?: continue else null
                                key(itemKey) {
                                    GridCell(
                                        label = folder?.displayName ?: entry!!.label,
                                        seed = itemKey.hashCode(),
                                        x = (i % COLUMNS) * cellW,
                                        y = (i / COLUMNS) * cellH,
                                        cellW = cellW,
                                        cellH = cellH,
                                        editMode = editMode,
                                        hidden = itemKey == draggedKey,
                                        highlighted = itemKey == dropTarget?.mergeKey,
                                        badge = if (folder != null) folder.apps.any { it in dots } else itemKey in dots,
                                        onClick = {
                                            when {
                                                folder != null -> openFolderKey = itemKey
                                                !editMode -> vm.launch(itemKey)
                                            }
                                        },
                                        onRemove = {
                                            if (folder != null) vm.dissolveFolder(itemKey) else onRemoveApp(itemKey)
                                        },
                                    ) {
                                        ItemIcon(itemKey, apps, folders, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }

                // Parmağı takip eden sürüklenen ikon.
                val dragged = draggedKey
                if (dragged != null) {
                    val halfPx = with(density) { (ICON_SIZE / 2).toPx() }
                    val lift = remember(dragged) { Animatable(1f) }
                    LaunchedEffect(dragged) { lift.animateTo(1.2f, spring(dampingRatio = 0.6f)) }
                    ItemIcon(
                        itemKey = dragged,
                        apps = apps,
                        folders = folders,
                        modifier = Modifier
                            .size(ICON_SIZE)
                            .offset {
                                IntOffset(
                                    (pointer.x - halfPx).roundToInt(),
                                    (pointer.y - halfPx).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                scaleX = lift.value
                                scaleY = lift.value
                            }
                            .alpha(0.92f),
                    )
                }
            }
        }

        // Klasör dokunulan ikondan büyüyerek açılır, aynı yere küçülerek kapanır.
        val folderKey = shownFolderKey
        val shownFolder = folderKey?.let { folders[it] }
        AnimatedVisibility(
            visible = openFolder != null,
            enter = fadeIn(tween(FOLDER_ANIM_MS)),
            exit = fadeOut(tween(FOLDER_ANIM_MS)),
        ) {
            if (shownFolder != null && folderKey != null) {
                FolderOverlay(
                    folderKey = folderKey,
                    folder = shownFolder,
                    apps = apps,
                    panelModifier = Modifier.animateEnterExit(
                        enter = scaleIn(
                            animationSpec = tween(FOLDER_ANIM_MS, easing = FastOutSlowInEasing),
                            initialScale = 0.2f,
                            transformOrigin = folderOrigin,
                        ),
                        exit = scaleOut(
                            animationSpec = tween(FOLDER_ANIM_MS, easing = FastOutSlowInEasing),
                            targetScale = 0.2f,
                            transformOrigin = folderOrigin,
                        ),
                    ),
                    editMode = folderEditMode,
                    onEditModeChange = { folderEditMode = it },
                    onRename = { vm.renameFolder(folderKey, it) },
                    onRemove = onRemoveApp,
                    onShowMenu = { pkg, anchor -> menu = AppMenuState(pkg, anchor, folderKey) },
                    onHideMenu = { menu = null },
                    onLaunch = {
                        openFolderKey = null
                        vm.launch(it)
                    },
                    onMove = { pkg, to -> vm.moveInFolder(folderKey, pkg, to) },
                    onCommit = { vm.commitOrder() },
                    onTakeOut = { vm.removeFromFolder(folderKey, it) },
                    onDismiss = { openFolderKey = null },
                )
            }
        }

        // Banka satırına dokununca açılan eşik ayarları.
        var shownThresholdSlot by remember { mutableStateOf<BankSlot?>(null) }
        LaunchedEffect(thresholdSlot) { thresholdSlot?.let { shownThresholdSlot = it } }
        AnimatedVisibility(
            visible = thresholdSlot != null,
            enter = fadeIn(tween(MENU_ANIM_MS)),
            exit = fadeOut(tween(MENU_ANIM_MS)),
        ) {
            val slot = shownThresholdSlot ?: return@AnimatedVisibility
            ThresholdDialog(
                bank = slot.name,
                amounts = slot.balance?.amounts.orEmpty(),
                thresholdsFor = { vm.thresholdsFor(slot.name, it) },
                onChange = { currency, value -> vm.setThresholds(slot.name, currency, value) },
                hidden = vm.bankHidden,
                onToggleHidden = vm::toggleBankHidden,
                onDismiss = { thresholdSlot = null },
            )
        }

        // Uzun basınca açılan uygulama menüsü: kısayollar, uygulama bilgisi, kaldır.
        var shownMenu by remember { mutableStateOf<AppMenuState?>(null) }
        LaunchedEffect(menu) { menu?.let { shownMenu = it } }
        AnimatedVisibility(
            visible = menu != null,
            enter = fadeIn(tween(MENU_ANIM_MS)),
            exit = fadeOut(tween(MENU_ANIM_MS)),
        ) {
            val m = shownMenu ?: return@AnimatedVisibility
            val shortcuts by produceState(emptyList<AppShortcut>(), m.pkg) { value = vm.shortcuts(m.pkg) }
            AppMenu(
                anchor = m.anchor,
                shortcuts = shortcuts,
                inFolder = m.folderKey != null,
                onDismiss = { menu = null },
                onShortcut = {
                    menu = null
                    openFolderKey = null
                    vm.startShortcut(it)
                },
                onAppInfo = {
                    menu = null
                    vm.openAppInfo(m.pkg)
                },
                onTakeOut = {
                    menu = null
                    m.folderKey?.let { vm.removeFromFolder(it, m.pkg) }
                },
                onUninstall = {
                    menu = null
                    onRemoveApp(m.pkg)
                },
            )
        }
    }
}

private val Folder.displayName: String
    get() = name.ifBlank { "Folder" }

private val labelStyle = TextStyle(
    fontFamily = LauncherFont,
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f),
)

/** Bir uygulamanın ikonu ya da bir klasörün önizlemesi. */
@Composable
private fun ItemIcon(
    itemKey: String,
    apps: Map<String, AppEntry>,
    folders: Map<String, Folder>,
    modifier: Modifier = Modifier,
) {
    if (isFolderKey(itemKey)) {
        folders[itemKey]?.let { FolderPreview(it, apps, modifier) }
    } else {
        apps[itemKey]?.let { Image(bitmap = it.icon, contentDescription = it.label, modifier = modifier) }
    }
}

/** Klasör ikonu: yarı saydam kare içinde ilk dokuz uygulamanın küçük ikonları. */
@Composable
private fun FolderPreview(folder: Folder, apps: Map<String, AppEntry>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(ICON_SHAPE)
            .background(Color.White.copy(alpha = 0.25f))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (r in 0 until 3) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (c in 0 until 3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        folder.apps.getOrNull(r * 3 + c)?.let { apps[it] }?.let {
                            Image(bitmap = it.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    label: String,
    seed: Int,
    x: Int,
    y: Int,
    cellW: Int,
    cellH: Int,
    editMode: Boolean,
    hidden: Boolean,
    highlighted: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // Sıra değişince ikonlar yerine kayarak gider (iPhone'daki gibi).
    val animated by animateIntOffsetAsState(
        targetValue = IntOffset(x, y),
        animationSpec = spring<IntOffset>(stiffness = Spring.StiffnessMediumLow),
        label = "iconPosition",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Üstüne bir uygulama bırakılmak üzereyken büyür (bırakınca klasör olur); basılıyken hafifçe küçülür.
    val scale by animateFloatAsState(
        targetValue = when {
            highlighted -> 1.15f
            pressed && !editMode -> 0.88f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "iconScale",
    )
    // Sürüklenip bırakılan ikon yerinde yumuşakça belirir.
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(if (hidden) 0 else 180), label = "iconAlpha")
    val angle = jiggleAngle(enabled = editMode && !hidden && !highlighted, seed = seed)

    Box(
        modifier = Modifier
            .offset { animated }
            .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = angle
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !hidden,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(ICON_SIZE)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                if (highlighted) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.3f), ICON_SHAPE),
                    )
                }
                icon()
                NotificationDot(visible = badge && !editMode)
                RemoveBadge(visible = editMode, onClick = onRemove)
            }
            BasicText(
                text = label,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .padding(horizontal = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = labelStyle,
            )
        }
    }
}

/**
 * Açık klasör: üstte düzenlenebilir isim, altında içindeki uygulamalar.
 * Dokun → aç, uzun bas + sürükle → sırala, panelin dışına bırak → klasörden çıkar,
 * dışarı dokun → kapat.
 */
@Composable
private fun FolderOverlay(
    folderKey: String,
    folder: Folder,
    apps: Map<String, AppEntry>,
    panelModifier: Modifier,
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onRemove: (String) -> Unit,
    onShowMenu: (String, Rect) -> Unit,
    onHideMenu: () -> Unit,
    onLaunch: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onCommit: () -> Unit,
    onTakeOut: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var name by remember(folderKey) { mutableStateOf(folder.name) }

    val folderApps by rememberUpdatedState(folder.apps)
    var gridWidth by remember { mutableIntStateOf(0) }
    var draggedPkg by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val cellHPx = with(density) { FOLDER_CELL_HEIGHT.toPx() }
    val rows = ((folder.apps.size + FOLDER_COLUMNS - 1) / FOLDER_COLUMNS).coerceAtLeast(1)

    // Sürüklenen uygulama panelin dışındaysa bırakınca klasörden çıkar.
    val outside by remember {
        derivedStateOf {
            val coords = gridCoords
            draggedPkg != null && coords != null && coords.isAttached &&
                !panelBounds.contains(coords.localToRoot(pointer))
        }
    }

    fun indexAt(p: Offset): Int {
        if (gridWidth == 0) return -1
        val col = (p.x / (gridWidth / FOLDER_COLUMNS.toFloat())).toInt().coerceIn(0, FOLDER_COLUMNS - 1)
        val row = (p.y / cellHPx).toInt().coerceIn(0, rows - 1)
        return minOf(row * FOLDER_COLUMNS + col, folderApps.lastIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusManager.clearFocus()
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Sürüklenen uygulama panelin dışına çıkınca panel soluklaşır: bırakırsan klasörden çıkar.
        val panelAlpha by animateFloatAsState(if (outside) 0.45f else 1f, label = "panelAlpha")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(panelModifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                    .graphicsLayer { alpha = panelAlpha }
                    .background(Color(0xFF202022), RoundedCornerShape(28.dp))
                    // Panelin içine dokunmak klasörü kapatmasın.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        focusManager.clearFocus()
                        onEditModeChange(false)
                    }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(32)
                        onRename(name)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = LauncherFont,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (name.isEmpty()) {
                                BasicText(
                                    text = "Folder name",
                                    style = TextStyle(fontFamily = LauncherFont, color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp),
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, bottom = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.3f)),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FOLDER_CELL_HEIGHT * rows)
                        .onSizeChanged { gridWidth = it.width }
                        .onGloballyPositioned { gridCoords = it }
                        .pointerInput(folderKey) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val hit = indexAt(down.position)
                                if (hit < 0) return@awaitEachGesture
                                val pkg = folderApps[hit]

                                // Uzun basma: hareket etmeden, başka biri tüketmeden süre dolarsa.
                                var cancelled = false
                                val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (!cancelled) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed || change.isConsumed ||
                                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                        ) {
                                            cancelled = true
                                        }
                                    }
                                } == null
                                if (!longPressed) return@awaitEachGesture

                                focusManager.clearFocus()
                                gridCoords?.takeIf { it.isAttached }?.let { coords ->
                                    val cellW = gridWidth / FOLDER_COLUMNS
                                    val topLeft = coords.localToRoot(
                                        Offset((hit % FOLDER_COLUMNS * cellW).toFloat(), hit / FOLDER_COLUMNS * cellHPx),
                                    )
                                    onShowMenu(pkg, Rect(topLeft, Size(cellW.toFloat(), cellHPx)))
                                }
                                var menuOpen = true
                                draggedPkg = pkg
                                pointer = down.position
                                var released = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) {
                                            released = true
                                            break
                                        }
                                        pointer = change.position
                                        if (menuOpen &&
                                            (pointer - down.position).getDistance() > viewConfiguration.touchSlop
                                        ) {
                                            menuOpen = false
                                            onHideMenu()
                                            onEditModeChange(true)
                                        }
                                        if (!outside) onMove(pkg, indexAt(pointer))
                                    }
                                } finally {
                                    val takeOut = released && outside
                                    draggedPkg = null
                                    if (takeOut) onTakeOut(pkg) else onCommit()
                                }
                            }
                        },
                ) {
                    if (gridWidth > 0) {
                        val cellW = gridWidth / FOLDER_COLUMNS
                        val cellH = cellHPx.roundToInt()
                        folder.apps.forEachIndexed { i, pkg ->
                            val entry = apps[pkg] ?: return@forEachIndexed
                            key(pkg) {
                                FolderCell(
                                    entry = entry,
                                    x = (i % FOLDER_COLUMNS) * cellW,
                                    y = (i / FOLDER_COLUMNS) * cellH,
                                    cellW = cellW,
                                    cellH = cellH,
                                    hidden = pkg == draggedPkg,
                                    editMode = editMode,
                                    seed = pkg.hashCode(),
                                    badge = pkg in NotificationDots.packages,
                                    onClick = { if (editMode) onEditModeChange(false) else onLaunch(pkg) },
                                    onRemove = { onRemove(pkg) },
                                )
                            }
                        }
                    }

                    // Parmağı takip eden sürüklenen ikon.
                    draggedPkg?.let { apps[it] }?.let { dragged ->
                        val halfPx = with(density) { (ICON_SIZE / 2).toPx() }
                        val lift = remember(dragged) { Animatable(1f) }
                        LaunchedEffect(dragged) { lift.animateTo(1.2f, spring(dampingRatio = 0.6f)) }
                        Image(
                            bitmap = dragged.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(ICON_SIZE)
                                .offset {
                                    IntOffset(
                                        (pointer.x - halfPx).roundToInt(),
                                        (pointer.y - halfPx).roundToInt(),
                                    )
                                }
                                .graphicsLayer {
                                    scaleX = lift.value
                                    scaleY = lift.value
                                }
                                .alpha(0.92f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCell(
    entry: AppEntry,
    x: Int,
    y: Int,
    cellW: Int,
    cellH: Int,
    hidden: Boolean,
    editMode: Boolean,
    seed: Int,
    badge: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val angle = jiggleAngle(enabled = editMode && !hidden, seed = seed)
    val animated by animateIntOffsetAsState(
        targetValue = IntOffset(x, y),
        animationSpec = spring<IntOffset>(stiffness = Spring.StiffnessMediumLow),
        label = "folderIconPosition",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !editMode) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "folderIconScale",
    )
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(if (hidden) 0 else 180), label = "folderIconAlpha")
    Column(
        modifier = Modifier
            .offset { animated }
            .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                rotationZ = angle
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !hidden,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(ICON_SIZE)) {
            Image(
                bitmap = entry.icon,
                contentDescription = entry.label,
                modifier = Modifier.fillMaxSize(),
            )
            NotificationDot(visible = badge && !editMode)
            RemoveBadge(visible = editMode, onClick = onRemove)
        }
        BasicText(
            text = entry.label,
            modifier = Modifier
                .padding(top = 4.dp)
                .padding(horizontal = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = labelStyle,
        )
    }
}

/** Düzenleme modunda ikonların hafifçe titremesi. */
@Composable
private fun jiggleAngle(enabled: Boolean, seed: Int): Float {
    if (!enabled) return 0f
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 110 + (seed and 7) * 8, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggleAngle",
    )
    return angle
}

/** İkonun sağ üst köşesindeki kırmızı bildirim noktası. */
@Composable
private fun BoxScope.NotificationDot(visible: Boolean) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(3.dp, (-3).dp),
        enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(Color.Black, CircleShape)
                .padding(2.dp)
                .background(Color(0xFFE5483B), CircleShape),
        )
    }
}

/** Düzenleme modunda ikonun sol üst köşesindeki "−" düğmesi (uygulamayı kaldırır / klasörü dağıtır). */
@Composable
private fun BoxScope.RemoveBadge(visible: Boolean, onClick: () -> Unit) {
    // Kapsayan Column'un ColumnScope sürümü değil, Box içinde konumlanabilen genel sürüm.
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset((-4).dp, (-4).dp),
        enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(Color(0xFF5A5A5A), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "−",
                style = TextStyle(fontFamily = LauncherFont, color = Color.White, fontSize = 16.sp),
            )
        }
    }
}

/**
 * Uygulamanın uzun basma menüsü (Pixel başlatıcısındaki gibi): üstte kısayollar, altta uygulama bilgisi,
 * klasördeyse klasörden çıkar ve kaldır. İkonun üstüne sığarsa üstte, sığmazsa altında açılır.
 */
@Composable
private fun AppMenu(
    anchor: Rect,
    shortcuts: List<AppShortcut>,
    inFolder: Boolean,
    onDismiss: () -> Unit,
    onShortcut: (AppShortcut) -> Unit,
    onAppInfo: () -> Unit,
    onTakeOut: () -> Unit,
    onUninstall: () -> Unit,
) {
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    val bottomInset = WindowInsets.systemBars.getBottom(density)
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        content = {
            Column(
                modifier = Modifier
                    .width(MENU_WIDTH)
                    .background(Color(0xFF202022), RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .padding(vertical = 6.dp),
            ) {
                for (shortcut in shortcuts) {
                    MenuRow(label = shortcut.label, onClick = { onShortcut(shortcut) }) {
                        shortcut.icon?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                if (shortcuts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.12f)),
                    )
                }
                MenuRow(label = "App info", onClick = onAppInfo) { MenuGlyph("i") }
                if (inFolder) MenuRow(label = "Remove from folder", onClick = onTakeOut) { MenuGlyph("↗") }
                MenuRow(label = "Uninstall", onClick = onUninstall, color = DANGER_RED) { MenuGlyph("×", DANGER_RED) }
            }
        },
    ) { measurables, constraints ->
        val panel = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val margin = 12.dp.roundToPx()
            val gap = 4.dp.roundToPx()
            val x = (anchor.center.x - panel.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - panel.width - margin).coerceAtLeast(margin))
            val above = anchor.top.roundToInt() - gap - panel.height
            val below = anchor.bottom.roundToInt() + gap
            val y = when {
                above >= topInset + margin -> above
                below + panel.height <= constraints.maxHeight - bottomInset - margin -> below
                else -> (constraints.maxHeight - bottomInset - margin - panel.height).coerceAtLeast(topInset)
            }
            panel.place(x, y)
        }
    }
}

private val MENU_WIDTH = 240.dp

/** Geri alınamaz işlemler (kaldır) için kırmızı. */
private val DANGER_RED = Color(0xFFFF5449)

@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    color: Color = Color.White,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = LauncherFont, color = color, fontSize = 15.sp),
        )
    }
}

@Composable
private fun MenuGlyph(text: String, color: Color = Color.White) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(color.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(fontFamily = LauncherFont, color = color, fontSize = 13.sp, textAlign = TextAlign.Center),
        )
    }
}

/**
 * Bir bankanın eşik ayarları: her para birimi için kırmızı (altında), sarı (altında) ve yeşil (üstünde)
 * sınırları. Boş bırakılan eşik kapalıdır. Altta bakiyeleri gizleme anahtarı.
 */
@Composable
private fun ThresholdDialog(
    bank: String,
    amounts: List<Money>,
    thresholdsFor: (String) -> Thresholds,
    onChange: (String, Thresholds) -> Unit,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusManager.clearFocus()
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .background(Color(0xFF202022), RoundedCornerShape(28.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusManager.clearFocus() }
                .padding(20.dp),
        ) {
            BasicText(
                text = "$bank thresholds",
                style = TextStyle(fontFamily = LauncherFont, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 18.sp),
            )
            // Aynı para biriminde birden çok hesap olabilir; eşik para birimi başınadır.
            for ((currency, items) in amounts.groupBy { it.currency }) {
                key(currency) {
                    BasicText(
                        text = "$currency · " + items.joinToString(" · ") { money(it) },
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                        style = TextStyle(fontFamily = LauncherFont, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp),
                    )
                    val current = thresholdsFor(currency)
                    ThresholdField("Red below", BALANCE_RED, current.red) { onChange(currency, thresholdsFor(currency).copy(red = it)) }
                    ThresholdField("Yellow below", BALANCE_YELLOW, current.yellow) { onChange(currency, thresholdsFor(currency).copy(yellow = it)) }
                    ThresholdField("Green above", BALANCE_GREEN, current.green) { onChange(currency, thresholdsFor(currency).copy(green = it)) }
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleHidden,
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "Hide balances",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = LauncherFont, color = Color.White, fontSize = 15.sp),
                )
                Switch(on = hidden)
            }
        }
    }
}

/** Eşik girişi: renkli nokta, açıklama ve sayı alanı. Boş ya da geçersiz değer eşiği kapatır. */
@Composable
private fun ThresholdField(label: String, color: Color, value: java.math.BigDecimal?, onValue: (java.math.BigDecimal?) -> Unit) {
    var text by remember { mutableStateOf(value?.toPlainString().orEmpty()) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = LauncherFont, color = Color.White, fontSize = 15.sp),
        )
        BasicTextField(
            value = text,
            onValueChange = { input ->
                // Rakam, eksi ve ondalık ayırıcı dışındakileri at; virgülü noktaya çevir.
                val cleaned = input.replace(',', '.').filterIndexed { i, c -> c.isDigit() || c == '.' || (c == '-' && i == 0) }
                text = cleaned.take(12)
                onValue(text.toBigDecimalOrNull())
            },
            modifier = Modifier
                .width(110.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            textStyle = TextStyle(fontFamily = LauncherFont, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) {
                        BasicText(
                            text = "off",
                            style = TextStyle(fontFamily = LauncherFont, color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** Küçük açma/kapama anahtarı (yalnızca görünüm; dokunma üst satırda). */
@Composable
private fun Switch(on: Boolean) {
    val offset by animateFloatAsState(if (on) 1f else 0f, label = "switch")
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset((offset * 16.dp.toPx()).roundToInt(), 0) }
                .size(18.dp)
                .background(if (on) Color(0xFF202022) else Color.White, CircleShape),
        )
    }
}

/** Üst şeridin sayfa noktaları. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val alpha by animateFloatAsState(if (i == current) 0.9f else 0.3f, label = "dot")
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(5.dp)
                    .background(Color.White.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

/** Üst şeridin ilk sayfası: hava durumu, ezan vakti ve banka bakiyeleri. */
@Composable
private fun InfoPage(
    vm: LauncherViewModel,
    scroll: ScrollState,
    onRequestLocation: () -> Unit,
    onConnectBank: (BankSlot) -> Unit,
    onThresholds: (BankSlot) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        WeatherBanner(
            weather = vm.weather,
            status = vm.weatherStatus,
            onClick = {
                if (vm.weatherStatus == WeatherStatus.NeedsPermission) onRequestLocation()
                else vm.refreshWeather(force = true)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BANNER_HEIGHT)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PrayerCountdown(
            schedule = vm.prayers,
            needsPermission = vm.weatherStatus == WeatherStatus.NeedsPermission,
            onStale = { vm.refreshWeather() },
            modifier = Modifier
                .fillMaxWidth()
                .height(PRAYER_ROW_HEIGHT)
                .padding(horizontal = 16.dp),
        )
        // Her banka kendi satırında; adlar aynı genişlikte ki bakiyeler alt alta hizalansın.
        val labelWidth = vm.banks.maxOfOrNull { it.name.length } ?: 0
        for (slot in vm.banks) {
            key(slot.name) {
                BankRow(
                    label = slot.name.padEnd(labelWidth),
                    balance = slot.balance,
                    status = slot.status,
                    hidden = vm.bankHidden,
                    levelOf = { vm.thresholdsFor(slot.name, it.currency).level(it.amount) },
                    onTap = {
                        when {
                            slot.status == BankStatus.NotConfigured -> Unit
                            slot.status == BankStatus.NotConnected ||
                                slot.status == BankStatus.Expired -> onConnectBank(slot)
                            // Bakiye varsa dokunmak eşik ayarlarını açar; yoksa yeniden dener.
                            slot.balance != null -> onThresholds(slot)
                            else -> vm.refreshBank(slot, force = true)
                        }
                    },
                    onLongPress = { vm.refreshBank(slot, force = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PRAYER_ROW_HEIGHT)
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
}
