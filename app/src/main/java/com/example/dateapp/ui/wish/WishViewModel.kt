package com.example.dateapp.ui.wish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dateapp.data.AiRepository
import com.example.dateapp.data.WishRepository
import com.example.dateapp.data.local.WishItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WishUiState(
    val wishItems: List<WishItem> = emptyList(),
    val isParsing: Boolean = false
)

class WishViewModel(
    private val repository: WishRepository,
    private val aiRepository: AiRepository
) : ViewModel() {

    private val isParsing = MutableStateFlow(false)

    val uiState: StateFlow<WishUiState> = combine(
        repository.getAllWishItems(),
        isParsing
    ) { wishItems, parsing ->
        WishUiState(
            wishItems = wishItems,
            isParsing = parsing
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = WishUiState()
        )

    fun addMockWish() {
        viewModelScope.launch {
            repository.insertWishItem(
                mockWishTemplates.random().copy(
                    addedTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun addWishFromRawText(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty() || isParsing.value) return

        viewModelScope.launch {
            isParsing.value = true
            try {
                val parsedWish = aiRepository.parseWishIntent(normalizedText).getOrNull()
                repository.insertWishItem(
                    WishItem(
                        title = parsedWish?.title ?: normalizedText,
                        category = parsedWish?.category ?: "play",
                        locationKeyword = parsedWish?.locationKeyword,
                        latitude = null,
                        longitude = null,
                        isVisited = false,
                        addedTimestamp = System.currentTimeMillis(),
                        source = "manual_nlp"
                    )
                )
            } finally {
                isParsing.value = false
            }
        }
    }

    fun deleteWish(wish: WishItem) {
        viewModelScope.launch {
            repository.deleteWishItem(wish)
        }
    }

    fun toggleWishVisitedState(wish: WishItem) {
        viewModelScope.launch {
            repository.updateWishItem(
                wish.copy(isVisited = !wish.isVisited)
            )
        }
    }

    companion object {
        private val mockWishTemplates = listOf(
            WishItem(
                title = "\u53bb\u6c5f\u6c49\u8def\u5403\u4e00\u6b21\u6b63\u5b97\u70e4\u8089",
                category = "meal",
                locationKeyword = "\u6c5f\u6c49\u8def",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            ),
            WishItem(
                title = "\u508d\u665a\u53bb\u4e1c\u6e56\u8fb9\u6563\u6b65\u5439\u98ce",
                category = "play",
                locationKeyword = "\u4e1c\u6e56",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            ),
            WishItem(
                title = "\u53bb\u4e07\u677e\u56ed\u627e\u4e00\u5bb6\u70ed\u4e4e\u7684\u5c0f\u9986\u5b50",
                category = "meal",
                locationKeyword = "\u4e07\u677e\u56ed",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            )
        )

        fun provideFactory(
            repository: WishRepository,
            aiRepository: AiRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WishViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return WishViewModel(repository, aiRepository) as T
                }
            }
        }
    }
}
