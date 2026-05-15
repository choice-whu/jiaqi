package com.example.dateapp.ui.decision

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.dateapp.ui.components.springPressScale
import com.example.dateapp.ui.theme.DateAppTheme
import com.example.dateapp.ui.theme.DateAppThemeDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun DecisionScreen(
    uiState: DecisionUiState,
    onDrawAnotherWish: () -> Unit,
    onDecisionModeSelected: (DecisionMode) -> Unit,
    onInsertDemoWishes: () -> Unit,
    onSwipeToWishPool: (DecisionCardUiModel) -> Unit,
    onSwipeNotInterested: (DecisionCardUiModel) -> Unit,
    onExternalSearch: (DecisionCardUiModel) -> Unit,
    onAddToRoute: (DecisionCardUiModel) -> Unit,
    onBackToWish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isRouteActionVisible by remember(uiState.selectedCard?.id) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onDrawAnotherWish()
    }
    val handleDrawAnotherWish = {
        if (hasLocationPermission(context)) {
            onDrawAnotherWish()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DateAppThemeDefaults.ScreenPadding, vertical = 24.dp)
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DecisionIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回心愿清单",
                onClick = onBackToWish,
                modifier = Modifier.align(Alignment.Start)
            )

            Text(
                text = "交给这一刻",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "答案不用解释太多，刚好想去就好。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DecisionModeSwitch(
                selectedMode = uiState.decisionMode,
                onModeSelected = onDecisionModeSelected,
                enabled = !uiState.isAiSearching,
                modifier = Modifier.fillMaxWidth()
            )

            Crossfade(
                targetState = uiState.selectedCard?.id,
                animationSpec = tween(durationMillis = 280),
                modifier = Modifier.fillMaxWidth(),
                label = "decision_content"
            ) { selectedCardId ->
                if (selectedCardId == null) {
                    if (uiState.isAiSearching) {
                        SearchingDecisionState(
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        EmptyDecisionState(
                            onInsertDemoWishes = onInsertDemoWishes,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val selectedCard = uiState.selectedCard ?: return@Crossfade
                    key(selectedCard.id) {
                        ScratchCard(
                            card = selectedCard,
                            isAiSearching = uiState.isAiSearching,
                            isSavedToWishPool = selectedCard.source == DecisionSource.LOCAL ||
                                uiState.savedCardIds.contains(selectedCard.id),
                            onRevealStateChanged = { isRouteActionVisible = it },
                            onSwipeToWishPool = {
                                onSwipeToWishPool(selectedCard)
                                Toast.makeText(
                                    context,
                                    if (selectedCard.source == DecisionSource.LOCAL) "已在心愿池" else "已加入心愿池",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onSwipeNotInterested = {
                                onSwipeNotInterested(selectedCard)
                                Toast.makeText(
                                    context,
                                    "已减少类似推荐",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            uiState.selectedCard?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DecisionActionButton(
                        text = "生成新建议",
                        onClick = handleDrawAnotherWish,
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isAiSearching
                    )
                    DecisionMetaPill(
                        text = "待选 ${uiState.availableCount} 条",
                        emphasized = true
                    )
                }
            }
        }

        uiState.selectedCard?.let { selectedCard ->
            AnimatedVisibility(
                visible = isRouteActionVisible && !uiState.isAiSearching,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(
                    animationSpec = spring(
                        dampingRatio = 0.84f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    expandFrom = Alignment.Bottom
                ),
                exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = 0.92f,
                        stiffness = Spring.StiffnessMedium
                    ),
                    shrinkTowards = Alignment.Bottom
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(
                            horizontal = DateAppThemeDefaults.ScreenPadding,
                            vertical = 20.dp
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DecisionActionButton(
                            text = "小红书搜",
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onExternalSearch(selectedCard)
                                openXiaohongshuSearch(context, selectedCard)
                            },
                            modifier = Modifier.weight(0.92f)
                        )
                        DecisionActionButton(
                            text = "高德导航",
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Map,
                                    contentDescription = null
                                )
                            },
                            onClick = { onAddToRoute(selectedCard) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private val searchingDecisionCard = DecisionCardUiModel(
    id = "searching_decision_card",
    title = "正在把答案慢慢收拢",
    category = "play",
    locationLabel = null,
    routeKeyword = null,
    distanceDescription = null,
    tag = "稍等",
    imageUrl = null,
    latitude = null,
    longitude = null,
    source = DecisionSource.AI,
    sourceLabel = "AI探索",
    momentLabel = "生成中",
    supportingText = "正在结合现在的时间、天气和附近可去的地方，挑一张更合适的卡片。",
    contextLine = null
)

@Composable
private fun SearchingDecisionState(
    modifier: Modifier = Modifier
) {
    ScratchCard(
        card = searchingDecisionCard,
        isAiSearching = true,
        isSavedToWishPool = true,
        onRevealStateChanged = {},
        onSwipeToWishPool = {},
        onSwipeNotInterested = {},
        modifier = modifier
    )
}

private fun openXiaohongshuSearch(
    context: Context,
    card: DecisionCardUiModel
) {
    val keyword = buildXiaohongshuSearchKeyword(card)
    val webUri = Uri.parse("https://www.xiaohongshu.com/search_result")
        .buildUpon()
        .appendQueryParameter("keyword", keyword)
        .build()
    val appUri = Uri.parse("xhsdiscover://search/result")
        .buildUpon()
        .appendQueryParameter("keyword", keyword)
        .build()

    val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
        setPackage(XIAOHONGSHU_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val packagedWebIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
        setPackage(XIAOHONGSHU_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val opened = listOf(appIntent, packagedWebIntent, webIntent).any { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { throwable ->
            throwable !is ActivityNotFoundException && throwable !is SecurityException && false
        }
    }

    if (!opened) {
        Toast.makeText(context, "暂时打不开小红书搜索", Toast.LENGTH_SHORT).show()
    }
}

private fun buildXiaohongshuSearchKeyword(card: DecisionCardUiModel): String {
    val placeName = (card.routeKeyword ?: card.locationLabel ?: card.title)
        .trim()
        .ifBlank { card.title.trim() }
    return if (placeName.contains("武汉") || placeName.contains("湖北")) {
        placeName
    } else {
        "$placeName 武汉"
    }
}

private const val XIAOHONGSHU_PACKAGE = "com.xingin.xhs"

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun EmptyDecisionState(
    onInsertDemoWishes: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "心愿池还空着",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "先放进几条想一起完成的小事，再让命运替你们选一张。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DecisionActionButton(
                text = "放入示例心愿",
                onClick = onInsertDemoWishes
            )
        }
    }
}

@Composable
private fun DecisionModeSwitch(
    selectedMode: DecisionMode,
    onModeSelected: (DecisionMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DecisionMode.entries.forEach { mode ->
                DecisionModeChip(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DecisionModeChip(
    mode: DecisionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "decision_mode_chip_selection"
    )
    val chipColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f + backgroundColor * 0.58f)
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .springPressScale(interactionSource)
            .clip(RoundedCornerShape(20.dp))
            .background(chipColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mode.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun ScratchCard(
    card: DecisionCardUiModel,
    isAiSearching: Boolean,
    isSavedToWishPool: Boolean,
    onRevealStateChanged: (Boolean) -> Unit,
    onSwipeToWishPool: () -> Unit,
    onSwipeNotInterested: () -> Unit,
    modifier: Modifier = Modifier,
    revealThreshold: Float = 0.6f
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val swipeScope = rememberCoroutineScope()
    val brushRadiusPx = with(density) { 26.dp.toPx() }
    val scratchStrokeWidth = brushRadiusPx * 2.25f
    val overlayCornerRadiusPx = with(density) { 32.dp.toPx() }
    val overlayTextPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(215, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 20.sp.toPx() }
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
            letterSpacing = 0f
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "decision_loading_shimmer")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing)
        ),
        label = "decision_loading_shimmer_progress"
    )
    val breathProgress by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "decision_loading_breath"
    )

    val scratchPath = remember(card.id) { Path() }
    val scratchPoints = remember(card.id) { mutableStateListOf<Offset>() }
    var scratchCardSize by remember(card.id) { mutableStateOf(IntSize.Zero) }
    var coverageGrid by remember(card.id) { mutableStateOf<ScratchCoverageGrid?>(null) }
    var lastScratchPoint by remember(card.id) { mutableStateOf<Offset?>(null) }
    var scratchRevision by remember(card.id) { mutableIntStateOf(0) }
    var clearedRatio by remember(card.id) { mutableFloatStateOf(0f) }
    var isFullyRevealed by remember(card.id) { mutableStateOf(false) }
    var lastHapticTimestamp by remember(card.id) { mutableLongStateOf(0L) }
    var revealSwipeOffset by remember(card.id) { mutableStateOf(Offset.Zero) }
    var hasHandledWishPoolSwipe by remember(card.id) { mutableStateOf(false) }
    var hasHandledNotInterestedSwipe by remember(card.id) { mutableStateOf(false) }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (isFullyRevealed) 0f else 1f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "scratch_overlay_alpha"
    )
    val swipeTranslationX by animateFloatAsState(
        targetValue = revealSwipeOffset.x,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "decision_card_swipe_x"
    )
    val swipeTranslationY by animateFloatAsState(
        targetValue = revealSwipeOffset.y * 0.16f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "decision_card_swipe_y"
    )
    val swipeProgress = if (scratchCardSize.width <= 0) {
        0f
    } else {
        (abs(swipeTranslationX) / (scratchCardSize.width * 0.28f)).coerceIn(0f, 1f)
    }
    val swipeRotation = if (scratchCardSize.width <= 0) {
        0f
    } else {
        (swipeTranslationX / scratchCardSize.width.toFloat() * 9f).coerceIn(-8f, 8f)
    }
    val swipeScale = 1f - swipeProgress * 0.025f

    fun maybeEmitHaptic() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHapticTimestamp >= 40L) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticTimestamp = now
        }
    }

    fun addScratchSegment(from: Offset, to: Offset) {
        if (scratchCardSize == IntSize.Zero || isFullyRevealed || isAiSearching) {
            return
        }

        if (lastScratchPoint == null) {
            scratchPath.moveTo(from.x, from.y)
            scratchPoints += from
            coverageGrid?.markCleared(from, brushRadiusPx)
        }

        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = max(1f, kotlin.math.hypot(dx, dy))
        val samplingStep = max(brushRadiusPx * 0.32f, 8f)
        val segmentCount = max(1, ceil(distance / samplingStep).toInt())

        repeat(segmentCount) { index ->
            val progress = (index + 1) / segmentCount.toFloat()
            val point = Offset(
                x = from.x + dx * progress,
                y = from.y + dy * progress
            )
            scratchPath.lineTo(point.x, point.y)

            val previousPoint = scratchPoints.lastOrNull()
            if (previousPoint == null || kotlin.math.hypot(
                    point.x - previousPoint.x,
                    point.y - previousPoint.y
                ) >= samplingStep * 0.72f
            ) {
                scratchPoints += point
            }
            coverageGrid?.markCleared(point, brushRadiusPx)
        }

        scratchRevision += 1
        lastScratchPoint = to
        clearedRatio = coverageGrid?.clearedRatio ?: 0f
        maybeEmitHaptic()

        if (clearedRatio >= revealThreshold && !isFullyRevealed) {
            onRevealStateChanged(true)
            isFullyRevealed = true
            maybeEmitHaptic()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onSizeChanged { newSize ->
                    scratchCardSize = newSize
                    coverageGrid = ScratchCoverageGrid(
                        width = newSize.width.toFloat(),
                        height = newSize.height.toFloat(),
                        cellSize = max(brushRadiusPx * 0.5f, 10f)
                    )
                    scratchPath.rewind()
                    scratchPoints.clear()
                    lastScratchPoint = null
                    scratchRevision = 0
                    clearedRatio = 0f
                    isFullyRevealed = false
                    lastHapticTimestamp = 0L
                    revealSwipeOffset = Offset.Zero
                    hasHandledWishPoolSwipe = false
                    hasHandledNotInterestedSwipe = false
                    onRevealStateChanged(false)
                }
                .pointerInput(card.id, isFullyRevealed, isAiSearching, isSavedToWishPool) {
                    if (!isFullyRevealed || isAiSearching) {
                        return@pointerInput
                    }

                    detectDragGestures(
                        onDragStart = {
                            revealSwipeOffset = Offset.Zero
                        },
                        onDragCancel = {
                            revealSwipeOffset = Offset.Zero
                        },
                        onDragEnd = {
                            val leftEnough =
                                revealSwipeOffset.x < -scratchCardSize.width * 0.22f
                            val rightEnough =
                                revealSwipeOffset.x > scratchCardSize.width * 0.22f
                            val mostlyHorizontal =
                                abs(revealSwipeOffset.x) > abs(revealSwipeOffset.y) * 1.35f
                            if (
                                leftEnough &&
                                mostlyHorizontal &&
                                !isSavedToWishPool &&
                                !hasHandledWishPoolSwipe
                            ) {
                                hasHandledWishPoolSwipe = true
                                maybeEmitHaptic()
                                revealSwipeOffset = Offset(
                                    x = -scratchCardSize.width * 0.34f,
                                    y = revealSwipeOffset.y * 0.1f
                                )
                                swipeScope.launch {
                                    delay(150L)
                                    onSwipeToWishPool()
                                    revealSwipeOffset = Offset.Zero
                                }
                            } else if (
                                rightEnough &&
                                mostlyHorizontal &&
                                !hasHandledNotInterestedSwipe
                            ) {
                                hasHandledNotInterestedSwipe = true
                                maybeEmitHaptic()
                                revealSwipeOffset = Offset(
                                    x = scratchCardSize.width * 1.22f,
                                    y = revealSwipeOffset.y * 0.18f
                                )
                                swipeScope.launch {
                                    delay(170L)
                                    onSwipeNotInterested()
                                    revealSwipeOffset = Offset.Zero
                                }
                            } else {
                                revealSwipeOffset = Offset.Zero
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            revealSwipeOffset += dragAmount
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = swipeTranslationX
                        translationY = swipeTranslationY
                        rotationZ = swipeRotation
                        scaleX = swipeScale
                        scaleY = swipeScale
                    }
                    .clip(RoundedCornerShape(32.dp))
            ) {
            DecisionRevealCard(
                card = card,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = overlayAlpha
                    }
                    .pointerInput(card.id, isFullyRevealed, scratchCardSize, isAiSearching) {
                        if (isFullyRevealed || isAiSearching) {
                            return@pointerInput
                        }

                        detectDragGestures(
                            onDragStart = { offset ->
                                addScratchSegment(offset, offset)
                            },
                            onDragCancel = {
                                lastScratchPoint = null
                            },
                            onDragEnd = {
                                lastScratchPoint = null
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val currentPoint = change.position
                                val previousPoint = lastScratchPoint ?: currentPoint
                                addScratchSegment(previousPoint, currentPoint)
                            }
                        )
                    }
            ) {
                val overlayBrush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFD9CEC6),
                        Color(0xFFC6B3A7),
                        Color(0xFFE9DED5)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                val overlayGlow = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.28f, size.height * 0.22f),
                    radius = size.minDimension * 0.85f
                )
                val cornerRadius = CornerRadius(overlayCornerRadiusPx, overlayCornerRadiusPx)

                drawRoundRect(
                    brush = overlayBrush,
                    size = size,
                    cornerRadius = cornerRadius
                )
                drawRoundRect(
                    brush = overlayGlow,
                    size = size,
                    cornerRadius = cornerRadius
                )

                val stripeStep = size.height / 11f
                repeat(10) { index ->
                    val startY = stripeStep * (index + 0.85f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(x = -size.width * 0.12f, y = startY),
                        end = Offset(x = size.width * 1.12f, y = startY - stripeStep * 0.7f),
                        strokeWidth = 2.4f
                    )
                }

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        "轻轻刮开",
                        size.width / 2f,
                        size.height / 2f + overlayTextPaint.textSize * 0.32f,
                        overlayTextPaint
                    )
                }

                if (scratchRevision > 0) {
                    drawPath(
                        path = scratchPath,
                        color = Color.Transparent,
                        style = Stroke(
                            width = scratchStrokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        ),
                        blendMode = BlendMode.Clear
                    )
                    scratchPoints.forEach { point ->
                        drawCircle(
                            color = Color.Transparent,
                            radius = brushRadiusPx,
                            center = point,
                            blendMode = BlendMode.Clear
                        )
                    }
                    }
            }

            if (isFullyRevealed && !isAiSearching && revealSwipeOffset != Offset.Zero) {
                DecisionCardSwipeFeedback(
                    offsetX = revealSwipeOffset.x,
                    cardWidth = scratchCardSize.width.toFloat(),
                    isSavedToWishPool = isSavedToWishPool,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isAiSearching) {
                DecisionSearchingEdgeGlow(
                    shimmerProgress = shimmerProgress,
                    breathProgress = breathProgress,
                    cornerRadiusPx = overlayCornerRadiusPx,
                    modifier = Modifier.fillMaxSize()
                )
            }
            }
        }
    }
}

@Composable
private fun DecisionCardSwipeFeedback(
    offsetX: Float,
    cardWidth: Float,
    isSavedToWishPool: Boolean,
    modifier: Modifier = Modifier
) {
    val isRightSwipe = offsetX > 0f
    val progress = if (cardWidth <= 0f) {
        0f
    } else {
        (abs(offsetX) / (cardWidth * 0.22f)).coerceIn(0f, 1f)
    }
    val backgroundColor = if (isRightSwipe) {
        Color(0xFFE9B8B0)
    } else {
        Color(0xFFCFE5D5)
    }
    val text = when {
        isRightSwipe -> "不感兴趣"
        isSavedToWishPool -> "已在心愿池"
        else -> "加入心愿池"
    }
    val alignment = if (isRightSwipe) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = progress * 0.92f }
            .background(backgroundColor.copy(alpha = 0.48f))
            .padding(horizontal = 28.dp),
        contentAlignment = alignment
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DecisionSearchingEdgeGlow(
    shimmerProgress: Float,
    breathProgress: Float,
    cornerRadiusPx: Float,
    modifier: Modifier = Modifier
) {
    val themePrimary = MaterialTheme.colorScheme.primary
    val outerGlow = themePrimary.copy(alpha = 0.16f + breathProgress * 0.08f)
    val pearlGlow = Color(0xFFF8F4FF).copy(alpha = 0.32f + breathProgress * 0.08f)
    val skyGlow = Color(0xFFD8E8FF).copy(alpha = 0.20f + breathProgress * 0.06f)

    Canvas(modifier = modifier) {
        val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
        val glowStrokeWidth = size.minDimension * (0.030f + breathProgress * 0.010f)
        val sweepStrokeWidth = size.minDimension * 0.014f
        val hairlineStrokeWidth = size.minDimension * 0.004f

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    pearlGlow,
                    themePrimary.copy(alpha = 0.34f + breathProgress * 0.08f),
                    skyGlow,
                    Color.Transparent
                ),
                start = Offset(x = size.width * (shimmerProgress - 1f), y = 0f),
                end = Offset(x = size.width * shimmerProgress, y = size.height)
            ),
            cornerRadius = cornerRadius,
            style = Stroke(width = glowStrokeWidth)
        )

        drawRoundRect(
            color = outerGlow,
            cornerRadius = cornerRadius,
            style = Stroke(width = sweepStrokeWidth)
        )

        drawRoundRect(
            color = Color.White.copy(alpha = 0.07f + breathProgress * 0.03f),
            cornerRadius = cornerRadius,
            style = Stroke(width = hairlineStrokeWidth)
        )
    }
}

@Composable
private fun DecisionRevealCard(
    card: DecisionCardUiModel,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
                        ),
                        start = Offset.Zero,
                        end = Offset(900f, 1200f)
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.44f),
                    shape = RoundedCornerShape(32.dp)
                )
                .padding(horizontal = 26.dp, vertical = 28.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DecisionMetaPill(
                        text = card.momentLabel,
                        emphasized = true
                    )
                    DecisionMetaPill(
                        text = card.sourceLabel,
                        emphasized = false
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = card.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                card.contextLine?.let { contextLine ->
                    Text(
                        text = contextLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (
                    card.locationLabel != null ||
                    card.distanceDescription != null ||
                    card.tag != null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        card.locationLabel?.let { location ->
                            DecisionMetaPill(text = location, emphasized = false)
                        }
                        card.distanceDescription?.let { distance ->
                            DecisionMetaPill(text = distance, emphasized = false)
                        }
                        card.tag?.let { tag ->
                            DecisionMetaPill(text = tag, emphasized = false)
                        }
                    }
                }

                Text(
                    text = card.supportingText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                DecisionMetaPill(
                    text = if (card.category == "meal") "餐饮" else "游玩",
                    emphasized = true
                )
            }
        }
    }
}

@Composable
private fun DecisionMetaPill(
    text: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DecisionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Row(
        modifier = modifier
            .springPressScale(interactionSource)
            .clip(RoundedCornerShape(22.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        icon?.let {
            Spacer(modifier = Modifier.size(8.dp))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                it()
            }
        }
    }
}

@Composable
private fun DecisionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .springPressScale(interactionSource)
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
    }
}

private class ScratchCoverageGrid(
    width: Float,
    height: Float,
    cellSize: Float
) {
    private val safeCellSize = max(cellSize, 1f)
    private val columns = max(1, ceil(width / safeCellSize).toInt())
    private val rows = max(1, ceil(height / safeCellSize).toInt())
    private val clearedCells = BooleanArray(columns * rows)
    private var clearedCount = 0

    val clearedRatio: Float
        get() = clearedCount.toFloat() / clearedCells.size.toFloat()

    fun markCleared(point: Offset, radius: Float) {
        val minColumn = max(0, ((point.x - radius) / safeCellSize).toInt())
        val maxColumn = min(columns - 1, ((point.x + radius) / safeCellSize).toInt())
        val minRow = max(0, ((point.y - radius) / safeCellSize).toInt())
        val maxRow = min(rows - 1, ((point.y + radius) / safeCellSize).toInt())
        val radiusSquared = radius * radius

        for (row in minRow..maxRow) {
            for (column in minColumn..maxColumn) {
                val cellCenter = Offset(
                    x = (column + 0.5f) * safeCellSize,
                    y = (row + 0.5f) * safeCellSize
                )
                val dx = cellCenter.x - point.x
                val dy = cellCenter.y - point.y
                if (dx * dx + dy * dy <= radiusSquared) {
                    val cellIndex = row * columns + column
                    if (!clearedCells[cellIndex]) {
                        clearedCells[cellIndex] = true
                        clearedCount += 1
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DecisionScreenPreview() {
    DateAppTheme {
        DecisionScreen(
            uiState = DecisionUiState(
                selectedCard = DecisionCardUiModel(
                    id = "preview_local_1",
                    localWishId = 1,
                    title = "去江汉路吃一顿热腾腾的寿喜锅",
                    category = "meal",
                    locationLabel = "江汉路",
                    routeKeyword = "江汉路",
                    distanceDescription = null,
                    tag = "一起想过",
                    imageUrl = null,
                    latitude = 30.5800,
                    longitude = 114.2917,
                    source = DecisionSource.LOCAL,
                    sourceLabel = "心愿池",
                    momentLabel = "想吃这家",
                    supportingText = "这是你们亲手记下的一顿想吃的，今天把它认真安排上。",
                    contextLine = null
                ),
                availableCount = 3,
                isAiSearching = false
            ),
            onDrawAnotherWish = {},
            onDecisionModeSelected = {},
            onInsertDemoWishes = {},
            onSwipeToWishPool = {},
            onSwipeNotInterested = {},
            onExternalSearch = {},
            onAddToRoute = {},
            onBackToWish = {}
        )
    }
}
