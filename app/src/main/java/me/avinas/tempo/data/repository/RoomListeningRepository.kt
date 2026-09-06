package me.avinas.tempo.data.repository

import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomListeningRepository @Inject constructor(
    private val dao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val manualContentMarkDao: ManualContentMarkDao
) : ListeningRepository {
    override fun eventsForTrack(trackId: Long): Flow<List<ListeningEvent>> = dao.eventsForTrack(trackId)
    override fun all(): Flow<List<ListeningEvent>> = dao.all()
    override fun recentEvents(limit: Int): Flow<List<ListeningEvent>> = dao.recentEvents(limit)
    override fun eventsInRange(startTime: Long, endTime: Long): Flow<List<ListeningEvent>> = dao.eventsInRange(startTime, endTime)
    
    override suspend fun insert(event: ListeningEvent): Long = dao.insert(event)
    override suspend fun insertAll(events: List<ListeningEvent>): List<Long> = dao.insertAll(events)
    override suspend fun delete(event: ListeningEvent) = dao.delete(event)
    override suspend fun deleteById(id: Long): Int = dao.deleteById(id)
    override suspend fun deleteByTrackId(trackId: Long) = dao.deleteByTrackId(trackId)
    override suspend fun deleteByArtist(artistName: String): Int = dao.deleteByArtist(artistName)
    override suspend fun getEventsForTrack(trackId: Long): List<ListeningEvent> = dao.getEventsForTrack(trackId)
    override suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent> = dao.getEventsInRange(startTime, endTime)

    /**
     * Resolve the current Room-backed manual override using the same specificity order as
     * MusicTrackingService. Reading synchronously here is intentional: this is the final
     * persistence boundary for events that may have been queued before a rule changed.
     */
    override suspend fun shouldPersist(event: ListeningEvent): Boolean {
        val track = trackDao.getTrackById(event.track_id) ?: return true
        val cleanTitle = track.title.trim()
        val cleanArtist = track.artist.trim()
        if (cleanTitle.isBlank() && cleanArtist.isBlank()) return true

        val matchingMark = manualContentMarkDao.getAllSync()
            .asSequence()
            .mapNotNull { mark ->
                val patternType = mark.patternType.uppercase()
                val matches = when (patternType) {
                    "TITLE_ARTIST" ->
                        mark.originalTitle.equals(cleanTitle, ignoreCase = true) &&
                            mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    "TITLE" -> mark.originalTitle.equals(cleanTitle, ignoreCase = true)
                    "ARTIST" -> mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    else -> false
                }

                if (!matches) {
                    null
                } else {
                    val specificity = when (patternType) {
                        "TITLE_ARTIST" -> 3
                        "TITLE" -> 2
                        "ARTIST" -> 1
                        else -> 0
                    }
                    mark to specificity
                }
            }
            .maxByOrNull { it.second }
            ?.first

        return when (matchingMark?.contentType?.uppercase()) {
            "NON_MUSIC", "VIDEO" -> false
            else -> true
        }
    }
    
    // Enhanced engagement queries
    override suspend fun getSkipCountForTrack(trackId: Long): Int = dao.getSkipCountForTrack(trackId)
    override suspend fun getReplayCountForTrack(trackId: Long): Int = dao.getReplayCountForTrack(trackId)
    override suspend fun getAverageCompletionForTrack(trackId: Long): Float? = dao.getAverageCompletionForTrack(trackId)
    override suspend fun getFullPlayCountForTrack(trackId: Long): Int = dao.getFullPlayCountForTrack(trackId)
    override suspend fun getLastPlayTimestampForTrack(trackId: Long): Long? = dao.getLastPlayTimestampForTrack(trackId)
    override suspend fun getFirstPlayTimestampForTrack(trackId: Long): Long? = dao.getFirstPlayTimestampForTrack(trackId)
    override suspend fun wasRecentlyPlayed(trackId: Long, sinceTimestamp: Long): Boolean = dao.wasRecentlyPlayed(trackId, sinceTimestamp)
    override suspend fun getEventsBySessionId(sessionId: String): List<ListeningEvent> = dao.getEventsBySessionId(sessionId)
    override suspend fun getTotalListeningTime(startTime: Long, endTime: Long): Long = dao.getTotalListeningTime(startTime, endTime)
    override suspend fun getSkipRate(startTime: Long, endTime: Long): Float? = dao.getSkipRate(startTime, endTime)
    override suspend fun getAverageCompletion(startTime: Long, endTime: Long): Float? = dao.getAverageCompletion(startTime, endTime)
}
