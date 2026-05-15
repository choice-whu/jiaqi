package com.example.dateapp.ui.wish

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dateapp.data.local.WishItem
import com.example.dateapp.ui.components.springPressScale
import com.example.dateapp.ui.theme.DateAppTheme
import com.example.dateapp.ui.theme.DateAppThemeDefaults
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishListScreen(
    uiState: WishUiState,
    onAddWishFromRawText: (String) -> Unit,
    onDeleteWish: (WishItem) -> Unit,
    onToggleWishVisitedState: (WishItem) -> Unit,
    onOpenDecision: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rawText by rememberSaveable { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

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
                .padding(horizontal = DateAppThemeDefaults.ScreenPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "一起要做的事",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "把脑海里闪过的念头，安静地记下来。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextField(
                value = rawText,
                onValueChange = { rawText = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isParsing,
                placeholder = {
                    Text(
                        text = "输入你想去的地方或想吃的美食...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                trailingIcon = {
                    val submit = {
                        val trimmed = rawText.trim()
                        if (trimmed.isNotEmpty() && !uiState.isParsing) {
                            onAddWishFromRawText(trimmed)
                            rawText = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }

                    if (uiState.isParsing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 14.dp)
                                .size(20.dp),
                            strokeWidth = 2.2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .springPressScale(interactionSource)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = submit
                                )
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "发送",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val trimmed = rawText.trim()
                        if (trimmed.isNotEmpty() && !uiState.isParsing) {
                            onAddWishFromRawText(trimmed)
                            rawText = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            AnimatedContent(
                targetState = uiState.wishItems.isEmpty(),
                transitionSpec = {
                    fadeIn(animationSpec = tween(260)) togetherWith
                        fadeOut(animationSpec = tween(180))
                },
                modifier = Modifier.weight(1f),
                label = "wish_list_state"
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyWishState(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = uiState.wishItems,
                            key = { wishItem -> wishItem.id }
                        ) { wishItem ->
                            WishSwipeRow(
                                wish = wishItem,
                                onDeleteWish = onDeleteWish,
                                onToggleWishVisitedState = onToggleWishVisitedState
                            )
                        }
                    }
                }
            }

            WishPrimaryButton(
                text = "把今晚交给运气",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null
                    )
                },
                enabled = uiState.wishItems.isNotEmpty() && !uiState.isParsing,
                onClick = onOpenDecision,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WishSwipeRow(
    wish: WishItem,
    onDeleteWish: (WishItem) -> Unit,
    onToggleWishVisitedState: (WishItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState()
    val canMarkVisited = !wish.isVisited

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = canMarkVisited,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            WishSwipeBackground(
                direction = dismissState.dismissDirection,
                allowVisited = canMarkVisited
            )
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (canMarkVisited) {
                        onToggleWishVisitedState(wish)
                    }
                    scope.launch { dismissState.reset() }
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteWish(wish)
                }

                else -> Unit
            }
        }
    ) {
        WishItemCard(wishItem = wish)
    }
}

@Composable
private fun WishSwipeBackground(
    direction: SwipeToDismissBoxValue,
    allowVisited: Boolean
) {
    val feedback = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeFeedback(
            backgroundColor = Color(0xFFDFF3E8),
            contentColor = Color(0xFF2E6B45),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E6B45)
                )
            },
            label = "已打卡"
        )

        SwipeToDismissBoxValue.EndToStart -> SwipeFeedback(
            backgroundColor = Color(0xFFF8E1E0),
            contentColor = Color(0xFF9B3D3B),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFF9B3D3B)
                )
            },
            label = "删除"
        )

        else -> SwipeFeedback(
            backgroundColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = {
                Icon(
                    imageVector = if (allowVisited) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.DeleteOutline
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            label = if (allowVisited) "已打卡" else "删除"
        )
    }

    val horizontalArrangement = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Arrangement.Start
        SwipeToDismissBoxValue.EndToStart -> Arrangement.End
        else -> Arrangement.Center
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(feedback.backgroundColor)
            .padding(horizontal = 20.dp),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            feedback.icon()
            Text(
                text = feedback.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = feedback.contentColor
            )
        }
    }
}

private data class SwipeFeedback(
    val backgroundColor: Color,
    val contentColor: Color,
    val icon: @Composable () -> Unit,
    val label: String
)

@Composable
private fun EmptyWishState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "还没有心愿",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "先写下一条，今晚就会慢慢有形状。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WishItemCard(
    wishItem: WishItem,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
    }
    val addedTime = remember(wishItem.addedTimestamp) {
        Instant.ofEpochMilli(wishItem.addedTimestamp)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    }

    val textColor = if (wishItem.isVisited) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val contentAlpha = if (wishItem.isVisited) 0.72f else 1f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha))
                )
                Text(
                    text = wishItem.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    textDecoration = if (wishItem.isVisited) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WishMetaPill(
                    text = if (wishItem.category == "meal") "餐饮" else "游玩",
                    visited = wishItem.isVisited
                )
                wishItem.locationKeyword?.let { locationKeyword ->
                    WishMetaPill(text = locationKeyword, visited = wishItem.isVisited)
                }
            }

            Text(
                text = addedTime,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.8f),
                textDecoration = if (wishItem.isVisited) TextDecoration.LineThrough else TextDecoration.None
            )
        }
    }
}

@Composable
private fun WishMetaPill(
    text: String,
    visited: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (visited) 0.72f else 0.84f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WishPrimaryButton(
    text: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.size(8.dp))
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            icon()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WishListScreenPreview() {
    DateAppTheme {
        WishListScreen(
            uiState = WishUiState(
                wishItems = listOf(
                    WishItem(
                        id = 1,
                        title = "去江汉路吃一次正宗烤肉",
                        category = "meal",
                        locationKeyword = "江汉路",
                        latitude = null,
                        longitude = null,
                        isVisited = false,
                        addedTimestamp = 1_746_530_400_000,
                        source = "manual_nlp"
                    ),
                    WishItem(
                        id = 2,
                        title = "傍晚去东湖边散步吹风",
                        category = "play",
                        locationKeyword = "东湖",
                        latitude = null,
                        longitude = null,
                        isVisited = true,
                        addedTimestamp = 1_746_534_000_000,
                        source = "manual_nlp"
                    )
                ),
                isParsing = false
            ),
            onAddWishFromRawText = {},
            onDeleteWish = {},
            onToggleWishVisitedState = {},
            onOpenDecision = {}
        )
    }
}
