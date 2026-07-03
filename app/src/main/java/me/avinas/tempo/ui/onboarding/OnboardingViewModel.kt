package me.avinas.tempo.ui.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

val Context.dataStore by preferencesDataStore(name = "settings")

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
    private val XIAOMI_GUIDANCE_SHOWN_KEY = booleanPreferencesKey("xiaomi_guidance_shown")

    init {
        viewModelScope.launch {
            // Load completion status from DataStore
            val onboardingCompleted = context.dataStore.data.map { 
                it[ONBOARDING_COMPLETED_KEY] ?: false 
            }.first()

            val xiaomiGuidanceShown = context.dataStore.data.map {
                it[XIAOMI_GUIDANCE_SHOWN_KEY] ?: false
            }.first()
            
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isOnboardingCompleted = onboardingCompleted,
                xiaomiGuidanceShown = xiaomiGuidanceShown
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[ONBOARDING_COMPLETED_KEY] = true
            }
        }
    }

    fun markXiaomiGuidanceShown() {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[XIAOMI_GUIDANCE_SHOWN_KEY] = true
            }
            _uiState.value = _uiState.value.copy(xiaomiGuidanceShown = true)
        }
    }
}

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val isOnboardingCompleted: Boolean = false,
    val xiaomiGuidanceShown: Boolean = false
)
