package com.example.dateapp

import android.content.Context
import com.example.dateapp.data.AiRepository
import com.example.dateapp.data.WishRepository
import com.example.dateapp.data.decision.DecisionEngine
import com.example.dateapp.data.environment.DecisionEnvironmentRepository
import com.example.dateapp.data.local.AppDatabase
import com.example.dateapp.data.place.PlaceResolver
import com.example.dateapp.data.remote.AiNetworkModule
import com.example.dateapp.data.remote.WeatherNetworkModule
import com.example.dateapp.data.recommendation.RecommendationFeedbackStore
import com.example.dateapp.data.recommendation.RecommendationTopicProvider
import com.example.dateapp.data.route.RoutePlanningRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)

    val wishRepository: WishRepository by lazy {
        WishRepository(database.wishDao())
    }

    val aiRepository: AiRepository by lazy {
        AiRepository(AiNetworkModule.apiService)
    }

    val environmentRepository: DecisionEnvironmentRepository by lazy {
        DecisionEnvironmentRepository(
            appContext = appContext,
            weatherApiService = WeatherNetworkModule.apiService
        )
    }

    val routePlanningRepository: RoutePlanningRepository by lazy {
        RoutePlanningRepository(
            context = appContext,
            environmentRepository = environmentRepository
        )
    }

    val placeResolver: PlaceResolver by lazy {
        PlaceResolver(routePlanningRepository)
    }

    val decisionEngine: DecisionEngine by lazy {
        DecisionEngine(
            aiRepository = aiRepository,
            placeResolver = placeResolver,
            routePlanningRepository = routePlanningRepository
        )
    }

    val recommendationFeedbackStore: RecommendationFeedbackStore by lazy {
        RecommendationFeedbackStore(appContext)
    }

    val recommendationTopicProvider: RecommendationTopicProvider by lazy {
        RecommendationTopicProvider()
    }
}
