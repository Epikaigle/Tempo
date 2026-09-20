package me.avinas.tempo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.analytics.AnalyticsConsent
import me.avinas.tempo.data.analytics.AnalyticsGate
import javax.inject.Inject

@HiltViewModel
class AnalyticsDisclosureViewModel
    @Inject
    constructor(
        private val consent: AnalyticsConsent,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AnalyticsDisclosureUiState())
        val uiState: StateFlow<AnalyticsDisclosureUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                // Visibility follows the dismissal flag, not `disclosureSeen`: the dialog is what
                // marks the notice as seen, so gating on that flag would hide it in the frame it
                // appeared and leave the user no chance to read it or tap the opt-out.
                consent.isNoticeDismissed.collect { dismissed ->
                    val configured =
                        AnalyticsGate.isBuildConfigured(
                            appKey = BuildConfig.APTABASE_APP_KEY,
                            isDebug = BuildConfig.DEBUG,
                            debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
                        )
                    _uiState.value =
                        _uiState.value.copy(
                            isConfigured = configured,
                            isVisible =
                                AnalyticsGate.isNoticeVisible(
                                    isConfigured = configured,
                                    noticeDismissed = dismissed,
                                ),
                        )
                }
            }
        }

        /**
         * Records that the notice has been rendered. Called as soon as the dialog shows, not
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
                consent.dismissNotice()
            }
        }

        fun onAcknowledge() {
            viewModelScope.launch {
                consent.markDisclosureSeen()
                consent.dismissNotice()
            }
        }
    }

data class AnalyticsDisclosureUiState(
    val isConfigured: Boolean = false,
    val isVisible: Boolean = false,
) {
    val shouldShow: Boolean get() = isConfigured && isVisible
}
