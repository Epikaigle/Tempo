# Privacy Policy for Tempo

**Last Updated:** December 31, 2025

## 1. Overview

Tempo operates as a local-first application. It does not require user accounts, host central tracking servers, or collect data for advertising or analytics.

Listening history and settings remain stored on your device in a local database.

## 2. Device Permissions

Tempo requests the following Android permissions to detect media playback:

| Permission | Function |
| :--- | :--- |
| **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Reads active media notifications to log playing tracks from supported music apps. Non-music notifications (messages, emails, system alerts) are ignored. |
| **Foreground Service** (`FOREGROUND_SERVICE`) | Maintains background playback tracking without process termination by Android memory limits. |
| **Internet Access** (`INTERNET`) | Fetches metadata (album artwork, artist descriptions, track genres) from external APIs. Personal listening logs are not uploaded. |
| **Media Control** (`MEDIA_CONTENT_CONTROL`) | Reads active media session states to verify playback status and track positions. |

## 3. Data Storage and Control

- **Local Database**: Data is stored in an encrypted SQLite database (Room) on internal device storage.
- **Export & Import**: You can export database backups to local files or Google Drive, and restore from those backups at any time.
- **Data Deletion**: Clearing app data or uninstalling Tempo removes all local records.

## 4. External APIs and Data Sharing

Tempo queries external APIs to fetch artwork and track metadata. Search terms (song title, artist name) are sent to these services:

### 4.1 Spotify
- **Usage**: Fetches album art, audio features (danceability, valence, energy), and artist genres.
- **Data Sent**: Search terms (track title, artist). If connected, authentication tokens stay stored on your device.

### 4.2 iTunes (Apple Music)
- **Usage**: Fallback source for high-resolution artwork.
- **Data Sent**: Search terms (artist name, album title).

### 4.3 MusicBrainz & Cover Art Archive
- **Usage**: Standardized genre tags and album metadata.
- **Data Sent**: Search terms (artist name, track title).

### 4.4 ReccoBeats
- **Usage**: Audio characteristic analysis when Spotify data is unavailable.
- **Data Sent**: Search terms (artist name, track title). If a track is missing from their database, Tempo sends Spotify's public 30-second preview URL for analysis. Local audio files are never transmitted.

### 4.5 Last.fm & Deezer
- **Usage**: Fallback source for artist biographies and cover art.
- **Data Sent**: Search terms.

## 5. Network Requests

Network calls originate directly from your device to the target service API. Requests are not proxied through external intermediate servers.

## 6. Policy Updates

This policy will be updated if application functionality changes. Updates will be reflected in this file and in the application settings.

## 7. Contact

**Developer**: Avinash  
**Email**: hi@avinash.im  
**GitHub**: [https://github.com/avinaxhroy/Tempo](https://github.com/avinaxhroy/Tempo)
