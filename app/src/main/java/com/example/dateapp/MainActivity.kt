package com.example.dateapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.dateapp.data.route.RouteTargetRequest
import com.example.dateapp.ui.decision.DecisionScreen
import com.example.dateapp.ui.decision.DecisionViewModel
import com.example.dateapp.ui.timeline.TimelineScreen
import com.example.dateapp.ui.timeline.TimelineViewModel
import com.example.dateapp.ui.timeline.openAmapRouteCompat
import com.example.dateapp.ui.theme.DateAppTheme
import com.example.dateapp.ui.wish.WishListScreen
import com.example.dateapp.ui.wish.WishViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val appContainer = AppContainer(applicationContext)
        val decisionViewModel = ViewModelProvider(
            this,
            DecisionViewModel.provideFactory(
                repository = appContainer.wishRepository,
                decisionEngine = appContainer.decisionEngine,
                environmentRepository = appContainer.environmentRepository,
                recommendationFeedbackStore = appContainer.recommendationFeedbackStore,
                recommendationTopicProvider = appContainer.recommendationTopicProvider
            )
        )[DecisionViewModel::class.java]
        val wishViewModel = ViewModelProvider(
            this,
            WishViewModel.provideFactory(
                repository = appContainer.wishRepository,
                aiRepository = appContainer.aiRepository
            )
        )[WishViewModel::class.java]
        val timelineViewModel = ViewModelProvider(
            this,
            TimelineViewModel.provideFactory(
                routePlanningRepository = appContainer.routePlanningRepository
            )
        )[TimelineViewModel::class.java]

        setContent {
            DateAppTheme {
                val wishUiState by wishViewModel.uiState.collectAsStateWithLifecycle()
                val decisionUiState by decisionViewModel.uiState.collectAsStateWithLifecycle()
                val timelineUiState by timelineViewModel.uiState.collectAsStateWithLifecycle()
                var currentScreen by remember { mutableStateOf(AppScreen.Wish) }
                val composeContext = LocalContext.current
                val coroutineScope = rememberCoroutineScope()
                val openDecisionScreen = {
                    decisionViewModel.drawAnotherWish()
                    currentScreen = AppScreen.Decision
                }
                val decisionPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    openDecisionScreen()
                }
                val requestOrOpenDecision = {
                    if (hasLocationPermission(composeContext)) {
                        openDecisionScreen()
                    } else {
                        decisionPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }

                BackHandler(enabled = currentScreen != AppScreen.Wish) {
                    when (currentScreen) {
                        AppScreen.Wish -> Unit
                        AppScreen.Decision -> {
                            currentScreen = AppScreen.Wish
                        }
                        AppScreen.Timeline -> {
                            timelineViewModel.clearRoute()
                            currentScreen = AppScreen.Decision
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(240)) +
                                scaleIn(
                                    initialScale = 0.985f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.84f,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )) togetherWith
                                (fadeOut(animationSpec = tween(180)) +
                                    scaleOut(
                                        targetScale = 0.992f,
                                        animationSpec = tween(180)
                                    ))
                        },
                        label = "app_screen"
                    ) { screen ->
                        when (screen) {
                            AppScreen.Wish -> {
                                WishListScreen(
                                    uiState = wishUiState,
                                    onAddWishFromRawText = wishViewModel::addWishFromRawText,
                                    onDeleteWish = wishViewModel::deleteWish,
                                    onToggleWishVisitedState = wishViewModel::toggleWishVisitedState,
                                    onOpenDecision = requestOrOpenDecision,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            AppScreen.Decision -> {
                                DecisionScreen(
                                    uiState = decisionUiState,
                                    onDrawAnotherWish = decisionViewModel::drawAnotherWish,
                                    onDecisionModeSelected = decisionViewModel::setDecisionMode,
                                    onInsertDemoWishes = decisionViewModel::insertDemoWishes,
                                    onSwipeToWishPool = decisionViewModel::saveDecisionCardToWishPool,
                                    onSwipeNotInterested = decisionViewModel::markDecisionNotInterested,
                                    onExternalSearch = decisionViewModel::markDecisionExternalSearch,
                                    onAddToRoute = { selectedCard ->
                                        decisionViewModel.markDecisionNavigation(selectedCard)
                                        coroutineScope.launch {
                                            Toast.makeText(
                                                composeContext,
                                                "正在打开高德地图",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            runCatching {
                                                appContainer.routePlanningRepository.planRoute(
                                                    RouteTargetRequest(
                                                        title = selectedCard.title,
                                                        category = selectedCard.category,
                                                        displayLocation = selectedCard.locationLabel,
                                                        searchKeyword = selectedCard.routeKeyword,
                                                        latitude = selectedCard.latitude,
                                                        longitude = selectedCard.longitude,
                                                        sourceLabel = selectedCard.sourceLabel,
                                                        tag = selectedCard.tag
                                                    )
                                                )
                                            }.onSuccess { routePlan ->
                                                openAmapRouteCompat(composeContext, routePlan)
                                            }.onFailure {
                                                Toast.makeText(
                                                    composeContext,
                                                    "暂时无法生成导航路线",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onBackToWish = {
                                        currentScreen = AppScreen.Wish
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            AppScreen.Timeline -> {
                                TimelineScreen(
                                    uiState = timelineUiState,
                                    onBack = {
                                        timelineViewModel.clearRoute()
                                        currentScreen = AppScreen.Decision
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private enum class AppScreen {
        Wish,
        Decision,
        Timeline
    }
}

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
