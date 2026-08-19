package me.avinas.tempo.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Durable storage for [MusicTrackingManager]'s offline event queue.
 *
 * Events land in the offline queue only when a database insert failed. Keeping
 * them on disk means an OEM kill, crash, or reboot can no longer discard plays
 * that were already recorded but not yet persisted — they are restored into
 * the queue on the next process start and retried.
 *
 * Uses SharedPreferences + Moshi, matching [SessionPersistence].
 */
class OfflineEventStore(context: Context) {

    companion object {
        private const val TAG = "OfflineEventStore"
        private const val PREFS_NAME = "tempo_offline_event_queue"
        private const val KEY_EVENTS = "offline_events"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, PendingEvent::class.java)
    private val adapter = moshi.adapter<List<PendingEvent>>(listType)

    /**
     * Persist the full offline queue (replaces whatever was stored before).
     * An empty collection removes the stored entry.
     */
    fun save(events: Collection<PendingEvent>) {
        try {
            if (events.isEmpty()) {
                prefs.edit().remove(KEY_EVENTS).apply()
                return
            }
            prefs.edit()
                .putString(KEY_EVENTS, adapter.toJson(events.toList()))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save offline events", e)
        }
    }

    /** Load previously persisted events. Returns an empty list on any failure. */
    fun load(): List<PendingEvent> {
        return try {
            val json = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline events", e)
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }
}
