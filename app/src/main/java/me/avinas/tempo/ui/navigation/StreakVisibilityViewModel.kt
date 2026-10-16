package me.avinas.tempo.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.avinas.tempo.data.repository.PreferencesRepository
import javax.inject.Inject

/**
 * Publishes the stored hide-streaks preference to the composition.
 *
 * Held at the navigation root so every screen and overlay sees the same value, including the
 * ones (share canvases, celebrations) that never load preferences themselves. Scoped to the
 * activity's ViewModel store, so the database is observed once rather than per screen.
 */
@HiltViewModel
class StreakVisibilityViewModel
    @Inject
    constructor(
        preferencesRepository: PreferencesRepository,
    ) : ViewModel() {
        val hideStreaks: StateFlow<Boolean> =
            preferencesRepository
                .preferences()
                .map { it?.hideStreaks ?: false }
                .stateIn(
                    scope = viewModelScope,
                    // Eagerly, not WhileSubscribed: a screen that stops observing for a few seconds
                    // (switching tabs, a share sheet) must not fall back to the default and flash
                    // streaks back on before the stored value arrives again.
                    started = SharingStarted.Eagerly,
                    initialValue = false,
                )
    }
