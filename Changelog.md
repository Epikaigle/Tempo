# Changelog

All notable changes to Tempo are documented in this file.

## [Unreleased]

### Added
- Anonymous app-health statistics, so crashes and broken features can actually be found and fixed. They cover crashes, errors, which screens and features are used, and whether background tracking is alive. Counts are sent as ranges rather than exact figures, and there is no account, no device identifier, and never any track, artist or listening history. On by default, off in one tap at **Settings → Your Data**, and completely inert in any build compiled from source.
- **Settings → Your Data → What we collect**, listing every event Tempo can send and the exact data attached to each one.
- **Settings → Your Data → Diagnostics report**, a user-initiated summary of how Tempo is running on your device — versions, library counts, tracking health and background work. It is produced on the device and only leaves it if you choose to share it, which is why it can be far more detailed than the anonymous statistics and why it is the most useful thing to attach to a bug report.

### Changed
- The privacy policy now describes app-health reporting in full, including that a coarse country/region is derived from the request IP, and that the app never sends an identifier of any kind.

### Fixed
- YouTube Music Takeout imports mis-reading the newer watch-history layout: the first subtitle — the release link literally named "Release" — was stored as the artist for hundreds of songs (making "Release" a top-3 artist), albums were lost ("unknown"), and plays split across duplicate track rows so all-time counts stayed wrong. Artists and albums are now resolved by link role (channel URLs vs. playlist/release URLs) with a `details` fallback, and placeholder labels ("Release", "Song", "Playlist", etc.) can never be stored as an artist.
- Repairs for installs already affected by the "Release" artifact: re-importing the same Takeout ZIP now detects those rows, fixes their artist/album in place (or merges them into the correct-artist track row), re-queues them for album/artwork/genre enrichment, and reports the count as "Tracks repaired" in the import summary. Already-imported plays are deduplicated by content fingerprint, so nothing is double-counted.
- Split Artist silently doing nothing when the only group's default target name resolved to the source artist itself: the split now fails with a clear message instead of reporting a success with zero moves, and the source artist's name is no longer pre-filled as the move target.
- Manual song merges keeping the bogus artist when merging into a placeholder-artist row (e.g. the "Release" copy): the surviving track now adopts the source track's real artist and is re-linked through the artist pipeline so stats, details, and junction rows all follow.
- Albums showing no cover art in rankings, artist pages and album search even though their songs had artwork. The album-grouped stats queries read the art column without an aggregate, so SQLite resolved it from an arbitrary song in the group — an album whose first-scanned song had no art reported none, and an album whose first-scanned song had only local art hid the richer online cover. Artwork is now aggregated across the album, preferring the enriched URL over the on-device fallback.
- Album details showing a blank cover while its own track list showed art, for albums that were never enriched at album level (imports, Takeout, desktop scrobbles). The header now falls back to a cover from the album's songs, preferring an online URL over a local file.
- Cover art extraction blocking the UI thread on every new track: the bitmap downscale, JPEG encode and file write for the local fallback ran inline in the notification and MediaSession callbacks, which could stutter or ANR when skipping tracks quickly. That work now runs off the main thread.

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
