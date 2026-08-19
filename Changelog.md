# Changelog

All notable changes to **Tempo** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 🎉 Released as v4.8.3

> This release reconciles the app with the public v4.8.2 lineage. If you installed
> the Play Store v4.8.2 build, this update is safe to install: your database is
> migrated (schema 52) with a pre-migration backup snapshot taken on device, and
> artist data repaired by 4.8.2's fix is preserved (the repair does not re-run).

### 🚀 New Features (backported from the 4.8.2 lineage)
- **Split Artist**: a new "Split artist…" action in the artist details menu lets
    you move wrongly grouped tracks to the correct artist — the manual escape
    hatch for artists that were collapsed together by the old name-matching.
- **Rename now keeps track credits in sync**: renaming an artist also rewrites
    the artist name stored on each track (exact and multi-artist credits), and
    refreshes stats, so track views no longer show the old name after a rename.
- **Russian localization** (plus the updated Hungarian translations) and a
    per-app language entry for Русский.
- **Engagement volume cap**: "Personal Favorite"-style tags now require real,
    repeated listening — a single imported play can no longer earn a top tag,
    and each tag shows a human-readable "because…" reason.

### 🐛 Bug Fixes
- **Japanese/Korean/Cyrillic artists no longer collapse into one entry**: artist
    name normalization is now Unicode-aware (NFKC + letters/numbers of any
    script). A one-time background repair re-separates previously collapsed
    artists (it safely does nothing if 4.8.2 already repaired your data).
- **Database migration safety (schema 52)**: devices upgrading from the public
    4.8.2 build no longer hit a schema mismatch crash on first launch. Before
    migrating, the app takes a one-time raw snapshot of the database files for
    emergency recovery.
- **Foreground-service crash fixes**: battery-state receiver registration now
    uses RECEIVER_NOT_EXPORTED (Android 13+/14+), the foreground state is
    re-asserted in onStartCommand, notification channel creation is guaranteed
    before startForeground, and failures fall back to a clean stop instead of
    crashing (BadForegroundServiceNotificationException).
- **Album art memory**: cover art is downsampled before saving, preventing
    OOM/TransactionTooLarge failures with very large artwork.
- **Desktop Satellite**: battery broadcasts no longer block their receiver
    thread (runBlocking removed), fixing "Broadcast already finished" crashes.
- **Google Drive 403**: authorization now validates the drive.file scope and
    never persists a broken token; a 403 surfaces the real Google reason and
    forces a clean re-sign-in instead of a dead-end error.
- **Spotify import**: legacy "StreamingHistory" timestamps without seconds
    ("yyyy-MM-dd HH:mm") now parse correctly.

### 🚀 New Features
- **Stats search**: the ranking screen now has a live search field — type an
    artist, song, or album name and the chart filters as you type, showing each
    match with its global rank in the current ranking (e.g. #47). Tapping a
    result opens its details screen, where rename/merge/edit actions live.
    Search runs in SQL (tracks/albums) or a bounded in-memory scan (artists),
    is debounced and cached, and never loads the full list into memory.

### 🐛 Bug Fixes
- **Tracking reliability — plays no longer lost after background kills**:
    - Fixed a bug where the final batch of listening events was silently dropped
      on every clean service shutdown (reboot, app update, service restart): the
      shutdown flush ran on a coroutine scope that was cancelled immediately
      after launch. The flush now completes synchronously (bounded at 5s) before
      the scope is torn down, so accrued play time always reaches the database.
    - Events that fail to save (database busy/locked) are now kept in durable
      storage and retried after a process restart instead of living only in
      memory and vanishing with an OEM kill.
    - Session recovery after an unclean shutdown no longer drops sessions whose
      track ID had not been resolved yet, and no longer double-counts sessions
      whose event had already been saved.
    - The event batcher now flushes on a timeout even when no new events arrive,
      so the last events of a quiet listening period can no longer sit in memory
      indefinitely.
- **Tracking reliability — service no longer dies permanently**:
    - Fixed a failure where the automatic listener restart could leave the
      tracking component disabled forever (tracking dead until reinstall) if the
      process died mid-restart. The re-enable now always runs, the final state
      is verified, and both the app process start and the 15-minute health check
      self-heal a stuck-disabled component.
- **Challenges & badges**: daily challenge progress is now refreshed by the
    background gamification worker, not only when the Profile screen is opened,
    so plays recorded in the background advance and complete challenges.
- **Looped playback**: heavily looped tracks were undercounted because the
    in-session accumulation cap (1.5x track duration) was stricter than the
    save-time cap (3x). Both now agree, so looped plays are counted correctly.

- **Enrichment Report**: Fixed "Enrich all songs" freezing and never finishing.
    - Tracks that no source could match stayed `PENDING` forever, so the bulk sweep
      re-processed them in an infinite loop. Every processed track now settles to a
      terminal status (`ENRICHED` / `NOT_FOUND` / `SKIPPED`), and the sweep has a
      stall guard that stops instead of spinning.
    - Tracks with no metadata row at all are now queued and included in the sweep,
      and shown as "Not yet queued" in the breakdown.
    - MusicBrainz "not found" no longer wipes data already gathered by other sources.
    - The sweep starts immediately (expedited) and can be restarted after a cancel;
      previously a finished/cancelled run blocked every future enqueue.
    - Stats refresh when the sweep finishes or is cancelled; a waiting-on-constraints
      state is shown instead of a frozen "Starting enrichment…".
- **YouTube Music Import**: Improved Takeout guide and error messages.
    - Guide now explains that large exports are split into multiple ZIPs and that
      all of them can be selected at once.
    - Fixed an error message that dropped the split-archive hint for ZIPs with
      more than 15 files.
    - Guide now sets expectations: Takeout only includes plays recorded while
      Watch history was on, and albums/art/genres are filled in by background
      enrichment after import.
    - Fixed stats not refreshing right after import: the stats cache is now
      invalidated when new listening events are created, matching the Spotify and
      Last.fm import paths.
- **Browser Extension — YouTube Music tracking**:
    - Fixed a 3-minute song being logged as 15+ minutes: invalid/non-finite
      duration or position values from the page no longer disable the listen
      cap or let wall-clock time accumulate unchecked.
    - A position reset to ~0 while playing (YTMusic advancing to the next
      track) now finalizes the previous song and starts a fresh session,
      instead of merging multiple songs into one oversized play.
    - Media elements are now chosen by preferring one that is actually playing
      and reports a sane duration, and all time/volume/rate values are
      sanitized before use.
- **Browser Extension — background tracking without the popup**:
    - Plays now finalize and queue even when the popup is never opened: a
      periodic stale-session sweep finalizes sessions that went silent while
      marked playing (tab killed, lid closed, worker hibernated).
    - Tracker state is flushed to session storage immediately at every
      finalization point, so a service-worker hibernation can no longer lose
      an accrued session or restore a pre-finalization state that re-counts it.
    - Media updates are gated behind tracker restore, so an update arriving
      right after a worker restart can't clobber restored listen time.
    - Now-playing snapshots are rebuilt from restored tracker state after
      hibernation, so the popup shows the current track immediately.
- **Browser Extension — sync reliability (401 / 502 / Phone Unreachable /
  RateLimited)**:
    - Sync errors are now classified (rate-limited, auth, rejected, server,
      network, unreachable, battery) and each gets the right reaction.
    - Rate limits honor `Retry-After` with a cooldown gate; plays stay queued
      and are not retried until the cooldown elapses.
    - Transient failures (502, unreachable, network) keep plays queued and
      back off exponentially instead of hammering on a fixed 5-minute loop.
    - A 401 triggers a one-time token refresh and a near-term retry.
    - Backoff/cooldown state survives service-worker hibernation, so a restart
      no longer resets it and immediately re-hammers the phone.
    - The retry alarm no longer overrides the exponential backoff with a fixed
      10-minute delay.
- **Browser Extension — popup UI**:
    - Sync status now shows a human-readable error, HTTP status, rate-limit
      countdown, and next-retry time instead of a raw error string.
    - Added the missing live-connection indicator in the header.
    - Now-playing card shows the track's actual duration next to listened time.
    - Version footer is read from the manifest instead of hardcoded.
- **Browser Extension — Firefox build**: the Firefox manifest no longer ships
  both `background.service_worker` and `background.scripts` (Firefox rejects
  manifests containing both); it now emits `scripts` only.

## [3.3.0] - 2026-01-03

### 🚀 New Features

- **Google Drive Backup (Gold)**:
    - Full backup and restore system.
    - Added "Restore from Backup" flow in Onboarding.
    - Smart error handling and token refresh for reliable operation.
- **Smart Metadata Strategy**:
    - "Merge Live/Remix Versions" toggle to unify different versions of the same song.
    - "Merge Tracks" manual tool in Song Details to fix split stats.
- **New Player Support**:
    - Added support for **nugs.net** and **nugs.net multiband**.

### ✨ Improvements

- **Spotify Authentication**:
    - Enhanced security with CSRF `state` parameter validation.
    - Improved reliability with persistent Code Verifier storage.
    - Better error messaging for auth failures.
- **Onboarding Polish**:
    - Fully responsive design for all screen sizes (including small devices).
    - Improved handling of system bars (status/nav) with edge-to-edge support.
    - Refined `RestoreScreen` layout for better usability.

### 🐛 Bug Fixes

- **Critical**: Fixed startup crash on Android 8.1 (Oreo) devices.
- **Database**: Fixed migration crash (Schema 17->18) related to `user_preferences`.
- **Drive Backup**:
    - Fixed "Key error" during backup serialization.
    - Fixed duplicate file creation and cache cleanup issues.
- **UI**: Fixed duplication of "Replay Back to back" card in Song Details.

## [3.2.0] - 2026-01-01

### 🚀 New Features

- **Rate App Popup**:
    - Non-intrusive bottom sheet implementation.
    - Smart engagement criteria for timing.
- **Web Landing Page**:
    - Added FAQ section with common user queries.
    - Integrated Privacy Policy links.
- **Privacy & Security**:
    - Detailed `PRIVACY.md` for data handling.
    - Commercial usage restrictions in License.

### ✨ Improvements

- **Onboarding**: Refined `WelcomeScreen` and `HowItWorksScreen` flow.
- **Documentation**: Detailed breakdown of Artist and Song detail screens in README.
- **Project Structure**: Updated `.gitignore` for better development hygiene.

### 🐛 Bug Fixes

- **Music Tracking**: Fixed Tidal package name detection.
- **Enrichment**:
    - Fixed infinite API call loops in Stats screen.
    - Optimized Artist Image persistence and caching.
    - Reliable local cover art fallback mechanism.

## [3.0.0] - 2025-12-30

### 🚀 New Features

- **Spotlight Story**: A "Wrapped" style annual recap featuring:
    - Top Artists and Tracks with glassmorphism UI.
    - Animated "Share your story" cards.
    - Adaptive layout for all screen sizes.
- **Dynamic Web Landing Page**:
    - Interactive 3D Phone Mockup in the Hero section.
    - "Bento Grid" layout for feature showcasing.
    - Animated entrance effects and mesh gradients.
- **Cloud Backup**:
    - Secure Google Drive backups with 7-day retention policy.
    - Automatic background backups with retry logic.
    - Play Store AAB signing support for Drive API authentication.
- **Export/Import**:
    - New date-wise JSON structure for better data portability.
    - Improved large dataset handling during import.

### 🐛 Bug Fixes & Improvements

- **Permissions**: Removed `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` to comply with Google Play policies (Android 14+).
- **Rendering**: Fixed `IllegalArgumentException` with hardware bitmaps in View Capture.
- **UI Polish**:
    - Fixed "black box" artifact in Glass Cards.
    - Improved Spotlight Filter Bar transparency and positioning.
    - Corrected vertical alignment in Top 10 lists.
- **Performance**: Optimized `RoomStatsRepository` cache invalidation for instant stats updates.

## [2.1.0] - 2025-11-15

### Added
- **Spotify Enrichment**: Fetch audio features (danceability, energy, tempo) for tracks.
- **MusicBrainz Integration**: High-resolution album art and genre tagging.
- **Stats Dashboard**: Weekly and Monthly listening trends charts.

### Changed
- Migrated database layer to Room for better offline caching.
- Updated `NotificationListenerService` to handle "Unknown Artist" cases better.

## [1.0.0] - 2025-06-01

### Initial Release
- Basic music tracking via NotificationListener.
- Local database storage.
- Simple listening history list.
- Dark mode support.
