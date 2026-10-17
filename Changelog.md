# Changelog

All notable changes to Tempo are documented in this file.

## [Unreleased]

### Added
- **Settings → Import listening history → Import Musicolet Data**, so moving to Tempo from Musicolet does not mean starting your history over. Musicolet cannot simply be read. It keeps no timestamp per play, and every member of its backup ZIP — the `DB_SONGS_LOG` song database and the `PCs_M_/W_/Y_` play-count databases beside it — is Blowfish-ECB encrypted under a key hard-coded in the app itself (`JSTMUSIC_2`). Tempo decrypts the ZIP, joins each period file's counts to its song by audio path, and rebuilds a timeline from what Musicolet actually knows. Each month's play count is spread across that month; the weekly and yearly files are read only where no finer file covers the period, because they describe the same plays and counting more than one layer would multiply the history rather than recover it. The single exact timestamp Musicolet does store — the song's last-played time — is kept exactly, replacing the estimate for that period's final play instead of adding a play to it, and a last-played time that no period file covers (a play since the last file was written) becomes a play of its own. Because the reconstruction is deterministic, the same backup always yields the same events, so re-importing it is a complete no-op through the existing content fingerprints. Every imported play is written with `in.krosbits.musicolet.import.reconstructed` as its source, which `SourceAuthority` ranks at 40 — below every measured source — so an import can never overwrite or delete a play Tempo watched happen; a play whose song has since been deleted from the device has no title to attach to and is counted in the summary rather than guessed at; and the plays Musicolet counted before it began keeping monthly files have no date at all, so they are reported and left out unless **Include undated plays** is switched on, which anchors them to the start of each song's known history so lifetime totals match Musicolet's. Estimated times fall inside 07:00–23:59 rather than spreading across the whole day: a uniform spread would invent overnight listening and inflate Night Owl and the hour histogram with plays that never happened, so hour-of-day figures for imported plays read neutral instead of fabricated. A bare `DB_SONGS_LOG` file works too — importable on its own for anyone who already decrypted theirs — and says plainly that it carried no play counts rather than quietly importing last-played times alone.
- **Settings → Advanced → Hide streaks**, for anyone who would rather not be shown how many days in a row they have listened. A streak is the one number in Tempo that measures an obligation rather than a habit — a missed day is a loss — and no amount of tuning makes that read differently for someone who does not want the pressure of it. The switch takes streaks out of the interface wherever they appear: the streak counter and personal best on the profile masthead together with the "streak at risk" banner and its countdown, streak insight cards on the home feed, the streak page in Spotlight, the "N days in a row" milestone on artist pages, and the streak line on shared artist and milestone cards, where the artist pill falls back to the discovery date it already knew. Only the display changes: plays are still recorded, streak history is still computed, and badge progress and the streak badges already earned are untouched, so turning the setting back off restores the same numbers instead of starting over. It is stored as `user_preferences.hideStreaks` (schema 58, default off, so nobody upgrading loses a streak to the update) and published to the composition from one place, so the screens, share canvases and celebration overlays that never load preferences themselves all agree on it.

### Changed
- Playback tracking is now allow-by-default instead of deny-by-default. Tempo used to track every app that produced a media session unless it matched a blocklist, which meant it could only ever subtract known non-music apps — and every messaging app that plays voice notes had to be added to that list one user report at a time. An app is now tracked only when it is on an allowlist (the curated music, podcast and audiobook lists, plus anything you add in **Settings → Manage apps**), and an app Tempo does not recognize is left untracked. Blocking still wins over everything, so nothing you had deliberately blocked changes behaviour.
- Denying everything unrecognized would otherwise be a dead end, so rejected media sessions are now recorded in a **Detected — not tracked** inbox in Manage apps, showing what the app was playing and how many times it was rejected, with one-tap **Allow** and **Block**. The inbox is seeded on upgrade from the apps already present in your listening history, so an unlisted player you have been using reappears there to re-allow rather than silently going quiet. A **Track unrecognized apps** switch in Tracking rules restores the old permissive behaviour for anyone who would rather not review apps individually.
- Manage apps is now the app permission surface rather than a fixed list. Search reaches every app installed on the device, not only the ones Tempo ships knowledge of, and each result can be allowed or blocked in place. A new **Not supported** section lists installed voice-note, dialler and recorder apps with the reason they are never tracked — this is where Viber and Signal now appear, instead of nowhere.
- The app taxonomy now lives in one place. `MusicTrackingService` carried a second, larger copy of the music and blocked lists that had drifted from the lists used to seed the database, so a package could be blocked by one and allowed by the other. The service asks a single policy object instead.

### Fixed
- Listening streaks collapsing during a trip, and timing badges arriving and vanishing with the flight. Streaks, Night Owl and Early Bird, and every day-of-week and hourly statistic were derived at query time with SQLite's `'localtime'` modifier, which re-renders all of history through the offset the phone is in *now*. A UK play at 23:00 became 05:00 the next morning in Thailand, so listening days collapsed into each other — a reported 110-day streak read as 40 — evening plays fell inside the 00:00–05:00 window Night Owl counts, and coming home silently restored the old reading because nothing had been wrong with the data. Each play now stores the UTC offset it was recorded in (`listening_events.tz_offset_minutes`, schema 58), and the 29 day and hour groupings in `StatsDao` and `GamificationDao` read that value instead, so history renders the same wherever the phone is and only a play made after landing can move the day boundary. Schema 58 backfills existing rows with exactly the offset `'localtime'` resolved for them, DST included and derived per row from the OS zone rules, so no streak or badge changes on the upgrade itself; a row written without an offset falls back to the device offset, which is the previous behaviour rather than a dropped row. A streak's "today" is also read in the zone of the most recent play, so crossing a date line neither drops the newest day nor skips one. None of it costs anything at runtime: the offset comes from zone rules the system has already loaded at the moment a play is written, with no receiver, alarm, permission or wake lock.
- Tempo crashing every time a Last.fm import was started, then crashing on every launch afterwards. The import worker promoted itself to a foreground service with a notification whose channel it never created: `createNotificationChannel` was only reachable from `getForegroundInfo()`, and WorkManager calls that for expedited work, not for the ordinary one-time request this import uses. On Android 13–16 the system posts the notification on the app's behalf and kills the process when it cannot, as `RemoteServiceException$CannotPostForegroundServiceNotificationException` — thrown on the system's main thread, so no `try/catch` around `setForeground()` can intercept it, and the import died before its first API call. The channel is now created and verified inside the notification builder immediately before every promotion (as every other foreground worker in Tempo already did), the notification requests immediate display so the system's deferred re-post path is not used, and a channel that cannot be created skips the promotion instead of inviting the crash.
- A failing Last.fm import re-running, and re-crashing the app, on every launch indefinitely. The worker answered an unexpected exception with `Result.retry()` unconditionally, and WorkManager persists a work request across process death, so a deterministic failure was retried at every app start with no ceiling. Attempts are now bounded, and the bound is checked before any work begins so it also covers a process that is *killed* rather than throwing — in that case the worker's own `catch` never runs. Giving up publishes a terminal failure so the progress screen does not wait on a run that is already over, and closes the import row as FAILED so the next attempt starts from the newest page instead of resuming a page range that never completed.
- A Last.fm import that imported nothing reporting success, which the completion screen rendered as "0 scrobbles imported" and "Active Events 0" while the account's history sat untouched. Two causes. A resume page left behind by an interrupted import can point past the last page of the account's history, and Last.fm answers such a page with an empty track list that the loop read as "history exhausted"; an out-of-range resume page now restarts from the newest page. And a walk that fetched scrobbles but inserted none — every play already in the library, which is the normal outcome of a re-run — was reported as a successful empty import; it now says nothing new was found, and a walk that read no scrobbles at all is reported as the failure it is. Resumed attempts also carry the previous attempt's counters forward — the running totals are now recorded as the walk proceeds, not only when it finishes — so the progress screen no longer falls back to "Active 0" on every retry and the final report describes the whole import rather than the last fragment of it.
- Voice notes being journaled as songs. Viber voice notes and Signal voice messages were detected as music and written to listening history whenever they lasted past the minimum play time, and then sat in the permanent notification as `Contact — date, time`. Neither app was on any list, and "on no list" used to mean "track it": the notification path accepted any unrecognized app whose notification carried a transport category and a `MediaSession` token, and the session it created was hardcoded as music. A voice note has a title, a creator and a duration, so every media-type heuristic matched it. Voice notes, calls, voicemail and voice recorders are now refused in code regardless of settings.
- The permanent notification staying pinned to a finished item for hours, surviving even a force-stop of the app that produced it. The removal callback re-ran the "is this a music notification" test on the *removed* notification to decide whether to handle the removal, and a removed notification frequently no longer carries the transport category or media-session token that qualified it when posted — so the removal was skipped and the session stayed in memory indefinitely, because a paused session is deliberately kept on screen. Removal is now handled whenever Tempo holds a session for that package, a paused session is only shown when its app is still tracked, and a reaper evicts sessions whose app has left behind neither a controller nor a notification, along with paused sessions too short to be saved.
- Searches in Manage apps returning "No apps found" for apps the device had installed. The query only filtered Tempo's curated lists, so the one app you needed to act on — the one Tempo did not already know about — could never match. Search now covers installed apps, and the installed-app scan runs once per screen instead of a `PackageManager` round-trip on every keystroke.
- Unblocking an app in Manage apps silently doing nothing. The built-in blocklist was consulted ahead of the user's own preference and returned unconditionally, so unblocking YouTube or an OEM video player recorded the choice and then ignored it. User choices are now evaluated before the built-in defaults.
- A session recovered after the app was killed could be replayed into history from an app that is no longer tracked; recovery now drops those sessions.
- The app no longer crashes on launch for users upgrading from schema 53 on Android 8, 8.1 and 9. Migration 53→54 deduped `daily_challenges` with a `ROW_NUMBER() OVER (PARTITION BY ...)` statement, but window functions need SQLite 3.25+ and those releases ship SQLite 3.18–3.22, so the migration threw `SQLiteException` every time Room opened the database and the app could not start until its data was cleared. The dedupe is now a correlated `EXISTS` (SQLite 3.7+ syntax) that keeps the identical row per challenge — completed first, then higher progress, then higher id — and the migration is individually idempotent (probe before `ALTER TABLE`, `IF NOT EXISTS` for the index) so an interrupted upgrade can be re-entered safely.
- Foreground-service notification crashes (`CannotPostForegroundServiceNotificationException` / `BadForegroundServiceNotificationException`) on Android 13–16. The system posts the tracking notification on the app's behalf and kills the process asynchronously — not catchable around `startForeground()` — when the notification is invalid or its channel is missing. Tempo now verifies both channels exist and recreates them before every promotion, falls back to a minimal notification if the rich one cannot be built, requests immediate display so the system's deferred re-post path is not used, and promotes to foreground *before* the blocking database reads in `onCreate` that could otherwise delay or prevent it. Five workers also posted their notifications with the 108dp launcher vector as the status-bar small icon; they now use the dedicated notification icon.
- A framework race could crash the process on the QueuedWork thread with `IllegalStateException: Broadcast already finished`. When `SharedPreferences.apply()` writes are still pending, `BroadcastReceiver.PendingResult.finish()` defers its completion to `QueuedWork`, and a second finish of the same result then throws outside any receiver's `try/catch` (AOSP b/257513022). Tempo's receivers never finish a broadcast themselves, so the duplicate comes from a library receiver; an uncaught-exception filter now drops exactly this bookkeeping race — matching only that message *and* the `PendingResult.sendFinished` frame — while every other crash still terminates and is reported as before.
- Songs followed by ad-flagged content could be counted two or more times with inflated listening time: the MediaSession track-change path saved the outgoing song's session but left it in the active-session map when the incoming track was classified as an ad, so every later ad callback (and the next real track change, pause, stop, or service shutdown) saved the same session again — each repeat landing outside the queue's 5-second dedup window and including the ad's playback time. The outgoing session is now detached from the map before it is saved (in both the MediaSession and notification track-change paths), making the handoff atomic, and a terminal-save guard on the session itself blocks any second save of the same session with a warning in the logs.
- Duplicate plays are now also blocked at the database boundary, not just in memory: the tracking manager refuses to write a second event for the same (session, track, start time) — in the batch writer, the direct-save fallback, and the offline retry queue — because in-memory guards cannot see across process restarts and the queue's time-window dedup expires long before a leaked re-save lands. Lookup failures fail open so listening data is never lost to a guard error.
- Migration 55 removes existing duplicate plays written by the bug above, keeping the earliest (accurate) record of each session; later re-saves were the inflated ones. Legitimate plays are untouched: replays use a fresh session, imports carry no session id, and synthesized import history reuses session ids only with distinct timestamps.
- Editing a song's title appearing to do nothing, on every song and every attempt, even though the new title had been written to the database each time. Detail screens and every top-tracks list are served through `RoomStatsRepository`'s memoised cache, `getTrackDetails` is keyed by track id with a five-minute TTL, and the rename path wrote `tracks.title` without invalidating it — so the screen's own reload read the pre-rename row straight back and repainted the old title, and because the edit dialog is seeded from that same cached row, every retry opened on the old title and looked identical to the first. Artist renames, album merges and track merges all invalidated the cache; only the track title did not. The title write, the alias that keeps later plays and imports of the old title pointed at this row, and the invalidation are now one transactional operation (`TrackAliasRepository.renameTrack`), the new title is painted as soon as it is saved rather than waiting on the reload, and the bare `TrackRepository.updateTitle` passthrough that could skip the invalidation is gone. `UPDATE tracks SET title` also reports the rows it changed, so a rename aimed at a row that has since been deleted or merged away reports a failure instead of closing the dialog as though it had saved.
- Tapping **Merge** on the duplicate-title prompt silently doing nothing. `mergeTracks` returns `false` rather than throwing when it fails, and the caller ignored that result and cleared the track details — which dismissed the dialog and navigated back, making a merge that changed nothing indistinguishable from one that worked. The result is now checked, and a failure is shown in the dialog that asked the question.
- A renamed or merged song coming back under its old name after an import, with its listening split across two entries. The alias table that records "this old name means that song" was read by live tracking alone. Every import resolved identity by provider id, then by exact title, then by a fuzzy title match — all of which stop matching the moment the title changes — and then created a second track. Last.fm hit it whenever a scrobble carried no MusicBrainz id (the common case for regional catalogues and anything played from YouTube), and the desktop and browser-extension ingest has no provider ids at all, so it hit it every time. The duplicate is not merely clutter: the play lands on a new track id, the content fingerprint includes that id, and cross-source reconciliation only ever compares plays *within* one track, so the repeat is counted for real and the play counts, streaks and badges belonging to one song end up split between two rows. `TrackResolver` — which all of those imports route through — now consults the alias table between the exact and fuzzy steps, `DesktopPlayIngestionService` (a second, forked copy of the same matching rules) does the same, and the alias lookup matches case-insensitively like the track lookup beside it, so a title differing only in capitalisation no longer misses either. Exact title still outranks the alias, so duplicates created before this change are left for the user to merge rather than silently absorbed. The compressed long tail is the one play store keyed by title instead of track id, and a rename now re-keys it exactly as a merge already did — without that, the old name kept appearing in All History and `prunePromotedArchives` could never recognise the row as already promoted.

## [4.8.10] - 2026-09-20

### Added
- Anonymous app-health statistics, so crashes and broken features can actually be found and fixed. They cover crashes, errors, which screens and features are used, and whether background music detection is alive. Counts are sent as ranges rather than exact figures, and there is no account, no device identifier, and never any track, artist or listening history. On by default, off in one tap at **Settings → Your Data**, and completely inert in any build compiled from source.
- **Settings → Your Data → Data & diagnostics**, one screen answering both data questions. "What we collect" lists every event Tempo can send and the exact data attached to each one; the diagnostics report is a user-initiated summary of how Tempo is running on your device — versions, library counts, music detection health and background work. Both stay collapsed until opened, and neither leaves your device unless you choose to share it, which is why the report can be far more detailed than the anonymous statistics and why it is the most useful thing to attach to a bug report.
- Configurable tracking rules in the Supported apps screen: "Count a listen after" (1 second to 10 minutes, default 25 seconds), a default maximum music duration (default 20 minutes) with a per-app override or no limit, and a content-exceptions list that forces matching media to always count as music or always be excluded as video/non-music.
- History entries gained "Always music" and "Video / non-music" actions per track and per artist. Corrections apply to matching history in place without deleting the track, and a more specific Always Music exception survives a later artist-wide correction.

### Changed
- Badge star tiers rebalanced so no badge is effectively impossible. Star 5 used to be a fixed 50× the unlock threshold for every badge, which scaled the largest badges into nonsense: *Legendary* (10,000 plays) needed 500,000 plays (~45 years) and *The Centennial* (Level 100) needed Level 5,000 (~400 years) for five stars. Each badge now has its own explicit, reachable five-star target — interpolated smoothly across the intermediate tiers — capping the hardest MYTHIC badge at roughly two years of dedicated listening while leaving ★1 unlocks untouched. Existing users can only keep or gain stars, never lose one.
- Daily challenges stopped repeating the same family two days running and learned the user's schedule. The time challenge is now a 3-hour window around the typical first-listen hour (Early Bird, Prime Time, or Night Owl, with a 7–10 AM fallback), discovery targets scale with past discovery instead of sitting at the cap, and XP rewards are fixed per date so regenerating a day keeps the same payout.
- The privacy policy now describes app-health reporting in full, including that a coarse country/region is derived from the request IP, and that the app never sends an identifier of any kind.

### Fixed
- Anonymous app-health statistics never being uploaded: the upload worker's first run was scheduled a full interval ahead (5 hours for the 6-hour cadence), and there was no one-shot upload anywhere, so a fresh install showed an empty dashboard until that window elapsed. Collection now triggers an immediate upload the moment the notice makes reporting legal, and on every app start.
- Statistics uploads being limited to unmetered networks, which silenced reporting for anyone on mobile data only — and because events expire after 24 hours, a user who was never on Wi-Fi sent nothing at all. Uploads now use any available connection; the payload is a few hundred bytes per event and roughly 16 KB on a busy day.
- The Home notice explaining anonymous app-health reporting removing itself in the frame it appeared: it marked itself as seen on first composition, and visibility was driven by that same flag. It now stays on screen until acknowledged or turned off, so the opt-out beside it is actually reachable.
- YouTube Music Takeout imports mis-reading the newer watch-history layout: the first subtitle — the release link literally named "Release" — was stored as the artist for hundreds of songs (making "Release" a top-3 artist), albums were lost ("unknown"), and plays split across duplicate track rows so all-time counts stayed wrong. Artists and albums are now resolved by link role (channel URLs vs. playlist/release URLs) with a `details` fallback, and placeholder labels ("Release", "Song", "Playlist", etc.) can never be stored as an artist.
- Repairs for installs already affected by the "Release" artifact: re-importing the same Takeout ZIP now detects those rows, fixes their artist/album in place (or merges them into the correct-artist track row), re-queues them for album/artwork/genre enrichment, and reports the count as "Tracks repaired" in the import summary. Already-imported plays are deduplicated by content fingerprint, so nothing is double-counted.
- Split Artist silently doing nothing when the only group's default target name resolved to the source artist itself: the split now fails with a clear message instead of reporting a success with zero moves, and the source artist's name is no longer pre-filled as the move target.
- Manual song merges keeping the bogus artist when merging into a placeholder-artist row (e.g. the "Release" copy): the surviving track now adopts the source track's real artist and is re-linked through the artist pipeline so stats, details, and junction rows all follow.
- Albums showing no cover art in rankings, artist pages and album search even though their songs had artwork. The album-grouped stats queries read the art column without an aggregate, so SQLite resolved it from an arbitrary song in the group — an album whose first-scanned song had no art reported none, and an album whose first-scanned song had only local art hid the richer online cover. Artwork is now aggregated across the album, preferring the enriched URL over the on-device fallback.
- Album details showing a blank cover while its own track list showed art, for albums that were never enriched at album level (imports, Takeout, desktop scrobbles). The header now falls back to a cover from the album's songs, preferring an online URL over a local file.
- Cover art extraction blocking the UI thread on every new track: the bitmap downscale, JPEG encode and file write for the local fallback ran inline in the notification and MediaSession callbacks, which could stutter or ANR when skipping tracks quickly. That work now runs off the main thread.
- Duplicate daily challenges double-counting XP when the midnight worker and the Profile screen generated the same day at once. Generation now runs in one transaction against a unique (challenge_id, date) index, and migration 54 dedupes existing rows keeping the most-progressed copy.
- Pruning challenges older than 90 days subtracting their XP on the next recompute. Completed XP is now banked into user_level before deletion, so total XP and level survive pruning.
- Genre challenges miscounting progress: combos like "rock|||pop" counted as one genre (or only the first segment) and tags were ignored. Genre progress now splits combos, falls back to tags, and "new genres" only counts genres never heard before today.
- Explore-artist and explore-genre challenge IDs used String.hashCode and could collide, merging two artists into one row. IDs are now stable slugs, so different names can no longer share a challenge.

## [4.8.7] - 2026-09-04

### Added
- Album merge tool in the album details menu to consolidate split albums, duplicate track history, and scrobble archives into a single target album.
- Fullscreen level-up and badge celebration screens with particle effects and exportable achievement share cards.
- Setting to toggle gamification on or off, hiding levels, badges, and XP counters across the app.
- Listening overview sheet on the home screen with hourly listening distribution charts, period comparisons, and direct share export.
- Top search drawer on the rankings screen for filtering artists, albums, and tracks in place.
- Option to pause playback tracking when device battery drops below 20%.

### Changed
- Reworked share themes into six dedicated visual styles (Midnight, Glass, ASCII, Minimum, Daylight, and Glitch), including artwork-derived motion blur, scanlines, and RGB splits for Glitch.
- Updated the album details screen layout to match the artist view, replacing the 2x2 stat grid with a hero play count and reformatting track rows with track numbers and play counts.
- Redesigned the history screen header with a persistent search bar and a filter sheet for custom time ranges and playback sources.
- Prefetched history pages four items before the end of the list to prevent scrolling hitches.
- Offloaded profile unique artist calculations to IO threads and precomputed badge star progress.

### Fixed
- Switched Google Drive sign-in to Credential Manager to resolve authentication hangs and missing account states.
- Isolated periodic Google Drive backup worker runs across retries to prevent overlapping jobs and state corruption.
- Fixed background backup restore cancellation issues by preserving active backup identity and manual worker triggers.
- Restricted post-restore bulk image pre-caching to known music CDN domains to prevent arbitrary background network requests from untrusted backup archives.
- Restricted restored profile image paths strictly to verified internal storage URIs, dropping arbitrary schemes from backup data.
- Constrained share theme background layers to card boundaries to stop visual overflow in export previews.

## [4.8.3] - 2026-08-19

### Added
- Live search in the ranking screen with debounced SQL and in-memory filtering, showing global rank badges for matched tracks, artists, and albums.
- Split Artist tool in the artist details menu to move misassigned tracks to a new or existing artist profile.
- Reworked share theme palette with six themes (Midnight, Rose, Aurora, Mono, Daylight, and Ocean) featuring theme-specific geometry across stats, track, and artist export cards.
- Russian and Hungarian in-app language selections.
- Minimum playback threshold for Personal Favorite tags with human-readable reason tags.

### Fixed
- Google Drive backup crash (`Export failed: java.lang.String cannot be cast to java.util.List`) by making the export JSON codec tolerate malformed `List<String>` data (`Artist.genres`, `EnrichedMetadata.tags/.genres`) and recover `|||`-delimited raw strings instead of aborting the whole export; restore likewise recovers bare-string values where a JSON array is expected instead of failing with `Expected BEGIN_ARRAY but was STRING`.
- Legacy list-column corruption at its source: rows written by older builds stored `artists.genres` and `enriched_metadata.tags/.genres` as JSON-array text (e.g. `["jazz", "cool jazz"]`), which read back as a single garbage element. The Room converter now repairs such values on read and canonicalizes on write, a one-time `ListColumnRepairService` rewrites the affected rows in place on first launch, and genre inference in the enrichment worker understands both storage generations.
- Profile screen crash caused by measuring intrinsic constraints on `SubcomposeLayout`.
- Artist name collapse for Japanese, Korean, Cyrillic, Indic, and Thai scripts by applying Unicode NFKC normalization, script-aware diacritic folding, and an automated background repair.
- Schema 52 migration crash when upgrading from the public 4.8.2 build, supported by an automated pre-migration database file snapshot.
- YouTube Music Takeout import failure on non-English history file names (such as `histórico de músicas ouvidas.json`) and stripped localized "Watched" prefixes from imported track titles.
- Foreground service crashes on Android 13+ by enforcing `RECEIVER_NOT_EXPORTED` on battery broadcast receivers and validating notification channel creation before calling `startForeground`.
- Play loss during clean service shutdowns or OEM background kills by executing shutdown flushes synchronously and persisting pending events to durable storage.
- Permanent listener disablement caused by incomplete process restarts.
- Memory exceptions (TransactionTooLarge / OOM) caused by large cover art by downsampling bitmaps before database writes.
- Google Drive backup 403 authorization failures by verifying `drive.file` scope before saving tokens and prompting re-authentication.
- Parsing error for legacy Spotify takeout timestamps formatted without seconds (`yyyy-MM-dd HH:mm`).
- Accumulation caps on looped playback to match the 3x track duration limit.
- Browser extension listen time calculation errors on YouTube Music caused by non-finite duration properties.
- Browser extension background play session loss during service worker hibernation.
- Browser extension sync retries hammering the device by classifying error types, respecting `Retry-After` headers, and applying exponential backoff.
- Firefox extension rejection caused by duplicate background script declarations in `manifest.json`.

### Changed
- Artist renames now propagate through individual track credits, multi-artist strings, and cached stats in a single transaction.
- Daily challenge progress now updates via the background gamification worker rather than requiring a profile screen launch.
- Metadata enrichment workers now settle all items to terminal states (`ENRICHED`, `NOT_FOUND`, `SKIPPED`) to prevent infinite retry loops.
- Desktop Satellite battery receiver thread no longer uses blocking calls.

---

## [3.3.0] - 2026-01-03

### Added
- Google Drive backup and restore system with onboarding restore integration.
- Smart metadata options to merge live and remix versions of tracks.
- Manual track merge tool in song details to consolidate split statistics.
- Playback tracking support for nugs.net and nugs.net multiband.

### Fixed
- App startup crash on Android 8.1 (Oreo).
- Database migration crash between schema 17 and 18 for user preferences.
- Key error during Google Drive backup serialization and duplicate file generation.
- Replay card duplication in song details view.

### Changed
- Spotify OAuth flow updated with CSRF state verification and persistent PKCE verifier storage.
- Onboarding and restore screens updated for full edge-to-edge layout on compact displays.

---

## [3.2.0] - 2026-01-01

### Added
- In-app review prompt triggered by listening milestone engagement.
- Web landing page documentation with privacy policy links and FAQ.
- Commercial restriction clauses in project license.

### Fixed
- Media tracking package name detection for Tidal.
- Infinite API query loop on stats view during metadata enrichment.
- Local album cover art fallback failure when network artwork is missing.

### Changed
- Refined welcome and onboarding navigation transitions.
- Updated project `.gitignore` and clean architecture directory boundaries.

---

## [3.0.0] - 2025-12-30

### Added
- Spotlight Story annual listening summary with top artist/track cards.
- Google Drive cloud backup with 7-day snapshot retention.
- Date-partitioned JSON export format for local data portability.

### Fixed
- `IllegalArgumentException` thrown during hardware bitmap capture on share card exports.
- Black border artifact rendered around glassmorphism cards.
- Vertical text alignment in Top 10 ranking lists.

### Changed
- Removed `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` permissions to comply with Android 14 requirements.
- Optimized Room repository query cache invalidation for immediate stats updates.

---

## [2.1.0] - 2025-11-15

### Added
- Spotify API integration for track acoustic metrics (danceability, energy, tempo).
- MusicBrainz integration for high-resolution album cover art and genre tagging.
- Weekly and monthly listening trend charts.

### Changed
- Migrated data layer to Room SQLite for offline caching.
- Improved metadata extraction for unnamed artist broadcasts in `NotificationListenerService`.

---

## [1.0.0] - 2025-06-01

### Added
- Background media playback tracking via `NotificationListenerService`.
- Local database storage for scrobble history.
- Listening history list with track search.
- Dark theme interface.
