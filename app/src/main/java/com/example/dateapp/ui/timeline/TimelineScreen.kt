package com.example.dateapp.ui.timeline

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.LocalTaxi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.dateapp.data.route.RouteTransportMode
import com.example.dateapp.data.route.TimelineRoutePlan
import com.example.dateapp.ui.components.springPressScale
import com.example.dateapp.ui.theme.DateAppTheme
import com.example.dateapp.ui.theme.DateAppThemeDefaults

@Composable
fun TimelineScreen(
    uiState: TimelineUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DateAppThemeDefaults.ScreenPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TimelineCapsuleButton(
                text = "返回刮刮乐",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null
                    )
                },
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start)
            )

            Text(
                text = "今天的行程",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "把刚刚揭晓的答案，安静地排进这一晚。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                uiState.isLoading -> {
                    TimelineLoadingState(modifier = Modifier.fillMaxWidth())
                }

                uiState.routePlan != null -> {
                    TimelineContent(
                        routePlan = uiState.routePlan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> {
                    TimelineEmptyState(
                        onBack = onBack,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    routePlan: TimelineRoutePlan,
    modifier: Modifier = Modifier
) {
    val railColors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val railX = 26.dp.toPx()
            drawLine(
                brush = Brush.verticalGradient(colors = railColors),
                start = Offset(x = railX, y = 28.dp.toPx()),
                end = Offset(x = railX, y = size.height - 28.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TimelineNodeCard(
                nodeTitle = "现在",
                title = routePlan.originLabel,
                subtitle = "从这里出发",
                supportingBadge = routePlan.sourceBadge,
                modifier = Modifier.fillMaxWidth()
            )

            TimelineTransitRow(
                transportMode = routePlan.transportMode,
                durationLabel = routePlan.durationLabel,
                distanceLabel = routePlan.distanceLabel,
                arrivalLabel = routePlan.arrivalLabel,
                modifier = Modifier.fillMaxWidth()
            )

            TimelineDestinationCard(
                routePlan = routePlan,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TimelineNodeCard(
    nodeTitle: String,
    title: String,
    subtitle: String,
    supportingBadge: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TimelineMarker(
            label = nodeTitle,
            modifier = Modifier.padding(top = 12.dp)
        )

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 0.dp,
            shadowElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimelinePill(text = nodeTitle, emphasized = true)
                    TimelinePill(text = supportingBadge, emphasized = false)
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimelineTransitRow(
    transportMode: RouteTransportMode,
    durationLabel: String,
    distanceLabel: String,
    arrivalLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimelineMarker(
            label = "路上",
            modifier = Modifier.padding(top = 2.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (transportMode) {
                        RouteTransportMode.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
                        RouteTransportMode.DRIVE -> Icons.Outlined.LocalTaxi
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${transportMode.emoji} $durationLabel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "$distanceLabel · 约 $arrivalLabel 到达",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimelineDestinationCard(
    routePlan: TimelineRoutePlan,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TimelineMarker(
            label = "终点",
            modifier = Modifier.padding(top = 12.dp)
        )

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPressScale(interactionSource)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { openAmapRouteCompat(context, routePlan) }
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimelinePill(text = "今日目的地", emphasized = true)
                        TimelinePill(text = routePlan.sourceLabel, emphasized = false)
                    }

                    routePlan.tag?.let {
                        TimelinePill(text = it, emphasized = false)
                    }
                }

                Text(
                    text = routePlan.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = routePlan.destinationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "轻触打开高德地图",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun openAmapRouteCompat(
    context: Context,
    routePlan: TimelineRoutePlan
) {
    if (!TimelineExternalLaunchDebouncer.tryAcquire("amap:${routePlan.destinationLatitude},${routePlan.destinationLongitude}")) {
        return
    }
    val transportType = if (routePlan.transportMode == RouteTransportMode.WALK) "2" else "0"
    val appUri = Uri.parse("amapuri://route/plan/").buildUpon()
        .appendQueryParameter("sourceApplication", "DateApp")
        .appendQueryParameter("sname", routePlan.originLabel)
        .appendQueryParameter("slat", routePlan.originLatitude.toString())
        .appendQueryParameter("slon", routePlan.originLongitude.toString())
        .appendQueryParameter("dname", routePlan.destinationLabel)
        .appendQueryParameter("dlat", routePlan.destinationLatitude.toString())
        .appendQueryParameter("dlon", routePlan.destinationLongitude.toString())
        .appendQueryParameter("dev", "0")
        .appendQueryParameter("t", transportType)
        .build()

    val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
        setPackage("com.autonavi.minimap")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    val webMode = if (routePlan.transportMode == RouteTransportMode.WALK) "walk" else "car"
    val webUri = Uri.parse("https://uri.amap.com/navigation").buildUpon()
        .appendQueryParameter(
            "to",
            "${routePlan.destinationLongitude},${routePlan.destinationLatitude},${routePlan.destinationLabel}"
        )
        .appendQueryParameter("mode", webMode)
        .appendQueryParameter("src", "DateApp")
        .build()
    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(webIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的高德地图入口", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "暂时无法打开高德地图", Toast.LENGTH_SHORT).show()
        }
    } catch (_: SecurityException) {
        Toast.makeText(context, "暂时无法打开高德地图", Toast.LENGTH_SHORT).show()
    }
}

private object TimelineExternalLaunchDebouncer {
    private const val WINDOW_MS = 1_500L
    private var lastKey: String? = null
    private var lastLaunchAtMs: Long = 0L

    fun tryAcquire(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastKey == key && now - lastLaunchAtMs < WINDOW_MS) {
            return false
        }
        lastKey = key
        lastLaunchAtMs = now
        return true
    }
}

@Composable
private fun TimelineLoadingState(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TimelinePill(text = "整理路线中", emphasized = true)
            Text(
                text = "正在结合当前位置与目的地，计算今天最顺手的一段路。",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
            )
        }
    }
}

@Composable
private fun TimelineEmptyState(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "还没有行程",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "先回到刮刮乐，把今天的答案抽出来。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TimelineCapsuleButton(
                text = "回去抽一个",
                onClick = onBack
            )
        }
    }
}

@Composable
private fun TimelineMarker(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(52.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                .border(
                    width = 4.dp,
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                    shape = CircleShape
                )
        )

        Text(
            text = label,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 28.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimelinePill(
    text: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
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
private fun TimelineCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .springPressScale(interactionSource)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                it()
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TimelineScreenPreview() {
    DateAppTheme {
        TimelineScreen(
            uiState = TimelineUiState(
                routePlan = TimelineRoutePlan(
                    title = "湖锦酒楼（万松园店）",
                    category = "meal",
                    sourceLabel = "AI探索",
                    tag = "高分",
                    originLabel = "武汉市江汉区",
                    destinationLabel = "湖北省武汉市江汉区万松园路 1 号",
                    transportMode = RouteTransportMode.DRIVE,
                    durationMinutes = 18,
                    durationLabel = "打车约 18 分钟",
                    distanceMeters = 3200,
                    distanceLabel = "3.2 km",
                    arrivalLabel = "19:42",
                    previewImageUrl = null,
                    sourceBadge = "高德路线",
                    originLatitude = 30.5928,
                    originLongitude = 114.3055,
                    destinationLatitude = 30.5925,
                    destinationLongitude = 114.2768
                )
            ),
            onBack = {}
        )
    }
}
