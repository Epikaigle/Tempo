package me.avinas.tempo.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import me.avinas.tempo.data.analytics.AnalyticsScreen
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.ScreenViewed
import javax.inject.Inject

/**
 * Reports which screens are reached.
 *
 * One observer at the NavHost level rather than a call in every screen, so a new screen
 * cannot forget to report itself and no screen can invent its own event name.
 */
@HiltViewModel
class AnalyticsScreenViewModel @Inject constructor(
    private val tracker: AnalyticsTracker
) : ViewModel() {

    private var lastScreen: AnalyticsScreen? = null

    fun onRouteChanged(route: String?) {
        val screen = routeToAnalyticsScreen(route)

        // Collapse consecutive repeats: recomposition and config changes would otherwise
        // report the same screen many times over.
        if (screen == lastScreen) return
        lastScreen = screen

        // An unmapped route means the mapping is out of date, not a real destination.
        // AnalyticsScreenMappingTest fails before this can ship, so stay silent.
        if (screen != AnalyticsScreen.UNKNOWN) {
            tracker.track(ScreenViewed(screen))
        }
    }
}
