package me.avinas.tempo.data.analytics

import com.squareup.moshi.JsonClass

/**
 * An event as it sits in the on-disk queue, before being shaped into an ingest payload.
 *
 * String and int properties are held in separate maps so the queue round-trips exactly.
 * The ingest API accepts only those two value types, so splitting them here makes that
 * rule structural rather than a convention someone has to remember.
 */
@JsonClass(generateAdapter = true)
data class QueuedAnalyticsEvent(
    val timestampMillis: Long,
    val name: String,
    val stringProps: Map<String, String>,
    val intProps: Map<String, Int>
)

/** Converts a schema event into its queue form. Non-String/Int values are dropped. */
fun AnalyticsEvent.toQueued(timestampMillis: Long): QueuedAnalyticsEvent {
    val strings = mutableMapOf<String, String>()
    val ints = mutableMapOf<String, Int>()
    props.forEach { (key, value) ->
        when (value) {
            is String -> strings[key] = value
            is Int -> ints[key] = value
        }
    }
    return QueuedAnalyticsEvent(
        timestampMillis = timestampMillis,
        name = name,
        stringProps = strings,
        intProps = ints
    )
}

/**
 * Pure queue rules, kept free of Android and file access so they are unit-testable.
 */
object AnalyticsQueue {

    /** Server-enforced ceiling on events per request. */
    const val MAX_BATCH = 25

    /**
     * Hard cap on stored events, so a device that is offline for a long time cannot grow the
     * queue without bound. The newest events are the ones worth keeping.
     */
    const val MAX_STORED = 200

    /**
     * The server rejects anything created more than 24h ago, so retrying older events is
     * pointless — they are dropped rather than replayed.
     */
    const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun isExpired(event: QueuedAnalyticsEvent, nowMillis: Long): Boolean =
        nowMillis - event.timestampMillis > MAX_AGE_MILLIS

    fun prune(events: List<QueuedAnalyticsEvent>, nowMillis: Long): List<QueuedAnalyticsEvent> {
        val fresh = events.filterNot { isExpired(it, nowMillis) }
        return if (fresh.size <= MAX_STORED) fresh else fresh.takeLast(MAX_STORED)
    }

    /** Oldest-first batch, capped at the per-request limit. */
    fun nextBatch(events: List<QueuedAnalyticsEvent>, nowMillis: Long): List<QueuedAnalyticsEvent> =
        prune(events, nowMillis).take(MAX_BATCH)
}
