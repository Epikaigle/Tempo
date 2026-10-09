package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.ListeningEventOrigin

/**
 * Offline backups can contain multiple producer aliases for one real play.
 * Re-importing it must not create a second listening row when any alias is
 * already bound, and conflicting claims must fail rather than corrupt history.
 */
internal fun shouldImportOfflinePlayback(
    aliases: List<ListeningEventOrigin>,
    existingByOrigin: Map<String, Long>,
): Boolean {
    val targets = aliases.mapNotNull { existingByOrigin[it.originEventId] }.distinct()
    check(targets.size <= 1) {
        "Cannot safely restore aliases: producer IDs point to different listening events"
    }
    return targets.isEmpty()
}
