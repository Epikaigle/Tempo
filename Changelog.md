# Changelog

All notable changes to Tempo are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.3.0] - 2026-01-03

### Added
- **Google Drive Backup**: Added full backup and restore system with onboarding restore flow and automatic token refresh.
- **Metadata Management**: Added "Merge Live/Remix Versions" toggle and manual track merging tool in Song Details.
- **Player Support**: Added playback tracking for nugs.net and nugs.net multiband.

### Changed
- **Spotify Authentication**: Added CSRF `state` parameter validation and persistent Code Verifier storage.
- **Onboarding**: Updated `RestoreScreen` layout and added edge-to-edge system bar handling for smaller displays.

### Fixed
- Fixed startup crash on Android 8.1 (Oreo).
- Fixed database migration crash (Schema 17 to 18) for `user_preferences`.
- Fixed key serialization errors, duplicate file creation, and cache cleanup in Google Drive backups.
- Fixed duplicate "Replay Back to back" card rendering in Song Details.

## [3.2.0] - 2026-01-01

### Added
- Added non-intrusive app rating prompt based on usage frequency.
- Added FAQ and Privacy Policy sections to web landing page.
- Added `PRIVACY.md` documentation.

### Changed
- Refined onboarding flow across `WelcomeScreen` and `HowItWorksScreen`.
- Expanded artist and song detail documentation in README.

### Fixed
- Fixed Tidal package name detection in background listener.
- Fixed infinite API polling loop on Stats screen.
- Optimized artist image caching and local artwork fallback.

## [3.0.0] - 2025-12-30

### Added
- **Spotlight Story**: Added annual listening recap with shareable cards and adaptive layouts.
- **Web Landing Page**: Added 3D phone preview, feature grid, and entrance animations.
- **Cloud Backup**: Added Google Drive automatic background backup with 7-day retention policy.
- **Data Export/Import**: Updated export format to date-structured JSON for faster parsing.

### Fixed
- Removed `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` permissions to match Android 14 target requirements.
- Fixed hardware bitmap `IllegalArgumentException` during view captures.
- Resolved transparent background artifact on glass cards.
- Fixed alignment and transparency issues on Spotlight filter bar and top lists.
- Optimized `RoomStatsRepository` cache invalidation logic.

## [2.1.0] - 2025-11-15

### Added
- **Spotify Enrichment**: Added audio feature fetching (danceability, energy, tempo).
- **MusicBrainz**: Added album artwork and genre tag lookups.
- **Stats Dashboard**: Added weekly and monthly trend charts.

### Changed
- Migrated data storage layer to Room SQLite.
- Improved handling of missing artist names in `NotificationListenerService`.

## [1.0.0] - 2025-06-01

### Added
- Initial release with background playback tracking via notification listener.
- Local SQLite database storage.
- Listening history timeline view.
- Dark theme support.
