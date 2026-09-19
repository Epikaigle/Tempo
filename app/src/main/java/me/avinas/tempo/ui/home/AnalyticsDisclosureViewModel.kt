package me.avinas.tempo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.analytics.AnalyticsConsent
import me.avinas.tempo.data.analytics.AnalyticsGate
import javax.inject.Inject

@HiltViewModel
class AnalyticsDisclosureViewModel @Inject constructor(
    private val consent: AnalyticsConsent
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsDisclosureUiState())
    val uiState: StateFlow<AnalyticsDisclosureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            consent.isDisclosureSeen.collect { seen ->
                _uiState.value = _uiState.value.copy(
                    isConfigured = AnalyticsGate.isBuildConfigured(
                        appKey = BuildConfig.APTABASE_APP_KEY,
                        isDebug = BuildConfig.DEBUG
                    ),
                    isVisible = !seen
                )
            }
        }
    }

    /**
     * Records that the notice has been rendered. Called as soon as the card composes, not
     * when the user taps — presenting a legible notice with the opt-out beside it is what
     * constitutes notice-before-collection.
     */
    fun onDisclosureShown() {
        viewModelScope.launch { consent.markDisclosureSeen() }
    }

    fun onTurnOff() {
        viewModelScope.launch {
            consent.setEnabled(false)
            consent.markDisclosureSeen()
        }
    }

    fun onAcknowledge() {
        viewModelScope.launch { consent.markDisclosureSeen() }
    }
}

data class AnalyticsDisclosureUiState(
    val isConfigured: Boolean = false,
    val isVisible: Boolean = false
) {
    val shouldShow: Boolean get() = isConfigured && isVisible
}
