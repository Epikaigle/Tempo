package me.avinas.tempo.data.analytics

import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Coarse, non-identifying context attached to every event.
 *
 * There is deliberately no device id, install id, advertising id or account id here — and
 * no field to put one in.
 */
data class AnalyticsSystemContext(
    val locale: String,
    val osVersion: String,
    val deviceModel: String,
    val appVersion: String
)

/**
 * Builds the ingest request body.
 *
 * This is written with an explicit [JsonWriter] rather than reflection so that **exactly**
 * which bytes leave the device is visible in one short, readable function. If you want to
 * audit what Tempo sends, this is the file to read.
 */
object AptabasePayload {

    const val PATH = "/api/v0/events"
    const val SDK_VERSION = "tempo-android@1"

    /**
     * Fixed pattern rather than [java.time.Instant.toString]: the latter omits the fractional
     * part when the millis happen to be exactly zero, which would make the wire format vary
     * roughly one time in a thousand.
     */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneOffset.UTC)

    /**
     * Renders events as a JSON array, as the ingest API expects. Callers must pass at most
     * [AnalyticsQueue.MAX_BATCH] events — that limit is the server's, not ours.
     */
    fun build(
        events: List<QueuedAnalyticsEvent>,
        sessionId: String,
        system: AnalyticsSystemContext
    ): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginArray()
            events.forEach { event ->
                writer.beginObject()
                writer.name("timestamp").value(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis)))
                writer.name("sessionId").value(sessionId)
                writer.name("eventName").value(event.name)

                writer.name("systemProps").apply {
                    beginObject()
                    name("locale").value(system.locale)
                    name("osName").value("Android")
                    name("osVersion").value(system.osVersion)
                    name("deviceModel").value(system.deviceModel)
                    name("isDebug").value(false)
                    name("appVersion").value(system.appVersion)
                    name("sdkVersion").value(SDK_VERSION)
                    endObject()
                }

                writer.name("props").apply {
                    beginObject()
                    event.stringProps.forEach { (key, value) -> name(key).value(value) }
                    event.intProps.forEach { (key, value) -> name(key).value(value.toLong()) }
                    endObject()
                }

                writer.endObject()
            }
            writer.endArray()
        }
        return buffer.readUtf8()
    }
}
