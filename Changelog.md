# Changelog

All notable changes to Tempo are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [4.8.3] - 2026-08-19

### Added
- Live search in the ranking screen with debounced SQL and in-memory filtering, showing global rank badges for matched tracks, artists, and albums.
- Split Artist tool in the artist details menu to move misassigned tracks to a new or existing artist profile.
- Reworked share theme palette with six themes (Midnight, Rose, Aurora, Mono, Daylight, and Ocean) featuring theme-specific geometry across stats, track, and artist export cards.
- Russian and Hungarian in-app language selections.
- Minimum playback threshold for Personal Favorite tags with human-readable reason tags.

### Fixed
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
