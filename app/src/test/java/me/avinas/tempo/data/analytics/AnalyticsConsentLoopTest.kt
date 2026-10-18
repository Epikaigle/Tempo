package me.avinas.tempo.data.analytics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guard for the consent observer that fed itself.
 *
 * `TempoApplication.observeAnalyticsConsent` reacts to
 * [AnalyticsConsent.collectionAllowed] by deleting the install id — a write to the very
 * DataStore that flow reads from. DataStore re-emits on every value-changing write (`Data`
 * carries a new version, so its shared flow pushes the new value), which turned the pair into a
 * loop that wrote to disk hundreds of times a second, for as long as reporting stayed off:
 * every opted-out user, every debug build, every from-source build.
 *
 * This drives the real [AnalyticsConsent] and [AnalyticsInstallId] against a real file-backed
 * store, because the defect only exists in that composition. It fails if
 * `distinctUntilChanged` is removed from `collectionAllowed` — the handler runs once per
 * emission, so the count balloons instead of staying at one.
 */
class AnalyticsConsentLoopTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the opt-out handler does not re-trigger itself through the store it writes`() = runBlocking {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempFolder.root, "settings.preferences_pb") },
            )
        val consent = AnalyticsConsent(dataStore)
        val installId = AnalyticsInstallId(dataStore)

        val optOutRuns = AtomicInteger(0)
        val optInRuns = AtomicInteger(0)

        val observer =
            launch(Dispatchers.IO) {
                consent.collectionAllowed.collect { allowed ->
                    if (allowed) {
                        optInRuns.incrementAndGet()
                    } else {
                        // The half of TempoApplication's handler that writes back into the store.
                        optOutRuns.incrementAndGet()
                        installId.reset()
                    }
                }
            }

        // Long enough for thousands of laps if the loop is back: the measured rate was ~500
        // writes a second, so even a modest regression cannot hide inside this window.
        delay(500)

        assertEquals(
            "the opt-out branch re-triggered itself through its own write",
            1,
            optOutRuns.get(),
        )

        // The de-duplication must not swallow a real transition: the notice rendering is what
        // arms reporting, and that still has to reach the handler.
        consent.markDisclosureSeen()
        delay(300)

        assertEquals("a real consent change must still reach the handler", 1, optInRuns.get())
        assertEquals("no further opt-out runs after consent opens", 1, optOutRuns.get())

        observer.cancelAndJoin()
    }
}
