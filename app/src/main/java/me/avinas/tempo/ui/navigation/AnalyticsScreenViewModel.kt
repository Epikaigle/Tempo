package me.avinas.tempo.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import me.avinas.tempo.data.analytics.AnalyticsScreen
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.ScreenViewed
import javax.inject.Inject

/**
 * Observes navigation changes and reports detail-screen visits to [AnalyticsTracker].
 *
 * Scoped to [TRACKED_SCREENS] to minimize analytics event volume.
 */
@HiltViewModel
class AnalyticsScreenViewModel @Inject constructor(
    private val tracker: AnalyticsTracker
) : ViewModel() {

    private var lastScreen: AnalyticsScreen? = null

    fun onRouteChanged(route: String?) {
        val screen = routeToAnalyticsScreen(route)

        // Ignore duplicate consecutive routes caused by recomposition or configuration changes.
        if (screen == lastScreen) return
        lastScreen = screen

        // Non-detail screens are ignored to stay within analytics limits.
        if (screen in TRACKED_SCREENS) {
            tracker.track(ScreenViewed(screen))
        }
    }

    companion object {
        val TRACKED_SCREENS: Set<AnalyticsScreen> =
            setOf(
                AnalyticsScreen.SONG_DETAILS,
                AnalyticsScreen.ARTIST_DETAILS,
                AnalyticsScreen.ALBUM_DETAILS,
            )
    }
}
