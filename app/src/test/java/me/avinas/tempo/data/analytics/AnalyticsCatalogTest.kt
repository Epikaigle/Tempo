package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keeps the user-facing "What we collect" list honest.
 *
 * `WhatWeCollectScreen` renders [AnalyticsCatalog], and this test asserts it matches the
 * events that actually exist — same names, same property keys. Without it, the published
 * list could quietly fall behind the code and understate what Tempo sends.
 */
class AnalyticsCatalogTest {

    @Test
    fun `the published catalog covers exactly the events that exist`() {
        assertEquals(
            AnalyticsEventSamples.all.map { it.name }.toSet(),
            AnalyticsCatalog.entries.map { it.event }.toSet()
        )
    }

    @Test
    fun `each entry lists exactly the properties its event sends`() {
        // Union across variants: ImportRun only carries `error_class` when it failed, and the
        // catalog should still declare it.
        val keysByEvent = AnalyticsEventSamples.all
            .groupBy { it.name }
            .mapValues { (_, events) -> events.flatMap { it.props.keys }.toSet() }

        AnalyticsCatalog.entries.forEach { entry ->
            assertEquals(
                "properties for '${entry.event}' drifted from the code",
                keysByEvent.getValue(entry.event),
                entry.properties.toSet()
            )
        }
    }

    @Test
    fun `every entry explains itself in plain language`() {
        AnalyticsCatalog.entries.forEach { entry ->
            assertEquals(
                "'${entry.event}' has a blank description",
                false,
                entry.what.isBlank()
            )
        }
    }
}
