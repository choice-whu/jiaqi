package com.example.dateapp.ui.timeline

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dateapp.data.route.RoutePlanningRepository
import com.example.dateapp.data.route.RouteTargetRequest
import com.example.dateapp.data.route.TimelineRoutePlan
import com.example.dateapp.ui.decision.DecisionCardUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineUiState(
    val isLoading: Boolean = false,
    val routePlan: TimelineRoutePlan? = null
)

class TimelineViewModel(
    private val routePlanningRepository: RoutePlanningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    fun prepareRoute(decisionCard: DecisionCardUiModel) {
        _uiState.update {
            it.copy(
                isLoading = true,
                routePlan = null
            )
        }

        viewModelScope.launch {
            val routePlan = routePlanningRepository.planRoute(
                RouteTargetRequest(
                    title = decisionCard.title,
                    category = decisionCard.category,
                    displayLocation = decisionCard.locationLabel,
                    searchKeyword = decisionCard.routeKeyword,
                    latitude = decisionCard.latitude,
                    longitude = decisionCard.longitude,
                    sourceLabel = decisionCard.sourceLabel,
                    tag = decisionCard.tag
                )
            )

            Log.d(
                TAG,
                "timeline ready title=${routePlan.title} transport=${routePlan.transportMode.name} duration=${routePlan.durationMinutes}"
            )

            _uiState.update {
                it.copy(
                    isLoading = false,
                    routePlan = routePlan
                )
            }
        }
    }

    fun clearRoute() {
        _uiState.value = TimelineUiState()
    }

    companion object {
        private const val TAG = "TimelineViewModel"

        fun provideFactory(
            routePlanningRepository: RoutePlanningRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(TimelineViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return TimelineViewModel(
                        routePlanningRepository = routePlanningRepository
                    ) as T
                }
            }
        }
    }
}
