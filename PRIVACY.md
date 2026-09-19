# Privacy Policy for Tempo

**Last Updated:** September 16, 2026

## 1. Introduction

Tempo ("we", "our", or "the App") is designed with a **"Local-First"** philosophy. We believe your music listening habits are personal data that belongs to you.

Tempo does **not** have a central server of its own, does **not** create user accounts, and does **not** use your data for advertising. We never sell it, and there is no advertising or attribution SDK anywhere in the app.

There is exactly one thing Tempo sends about itself rather than about your music: **anonymous app-health statistics**. These cover crashes, errors, and which features are used, and they are described in full in [section 5](#5-anonymous-app-health-reporting). They contain no account, no identifier, and never anything you listened to, and you can turn them off at any time in **Settings → Your Data**.

## 2. Data Collection & Permissions

To function as a music tracker, Tempo requires specific permissions on your Android device. Here is a transparent breakdown of what we access and why:

| Permission | Usage |
| :--- | :--- |
| **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) | **Core Feature.** Used to detect what music is playing on your device (Spotify, YouTube Music, etc.) by reading the media notifications. We strictly filter for music apps and ignore all other notifications (like messages or emails). |
| **Foreground Service** (`FOREGROUND_SERVICE`) | Ensures the app can faithfully track music in the background without being killed by the Android system. |
| **Internet Access** (`INTERNET`) | Required to fetch metadata (album art, genres, artist info) from public APIs, and to send the anonymous app-health statistics described in section 5. Your personal listening history is **never** uploaded. |
| **Media Control** (`MEDIA_CONTENT_CONTROL`) | specific access to the active media session to get accurate playback status (Paused/Playing) and timeline positions. |

## 3. Data Storage

- **Local Database:** All data is stored in an encrypted SQLite (`Room`) database on your device's internal storage.
- **Your Data Stays Yours:** Tempo operates no server that stores your listening history. If you lose your phone and have not made your own backup, that history is gone — we cannot recover it, because we never had it.
- **Backups You Control:** You can export your database to a file, or — if you choose to connect a Google Account — let Tempo write encrypted backups to **your own** Google Drive. Those backups go to your Drive, not to us.
- **User Control:**
    - **Export:** You can export your entire database as a backup file.
    - **Import:** You can restore your data from a backup file.
    - **Clear Data:** You can wipe all data from the app settings at any time.

## 4. External Services & Data Sharing

Tempo uses third-party APIs to enrich your experience with album art, genres, and artist details. We share the minimum amount of data necessary (typically just search queries) to get this information.

### 4.1 Spotify
- **Usage:** To fetch high-resolution album art, audio features (danceability, energy), and artist genres.
- **Data Shared:** Search queries (Artist Name, Song Title).
- **Authentication:** If you choose to link your Spotify account, the authentication token is stored locally and used only for these API calls.

### 4.2 iTunes (Apple Music)
- **Usage:** As a fallback source for high-quality album artwork and artist images.
- **Data Shared:** Search queries (Artist Name, Album Title) sent to the public iTunes Search API.
- **Web Scraping:** The app may access public Apple Music artist pages to extract high-quality artist images that are not available via the API.

### 4.3 MusicBrainz & Cover Art Archive
- **Usage:** To fetch accurate metadata and standardized tags.
- **Data Shared:** Search queries (Artist Name, Song Title).

### 4.4 ReccoBeats
- **Usage:** To analyze the "mood" and "energy" of tracks when Spotify data is unavailable.
- **Data Shared:**
    - Search queries (Artist Name, Song Title).
    - **Public Preview Clips:** In rare cases where a song is not in their database, the app may send a public 30-second preview URL (provided by Spotify) to ReccoBeats for audio analysis. **We never upload your personal local audio files.**

### 4.5 Last.fm & Deezer
- **Usage:** Fallback sources for artist biographies, tags, and cover art.
- **Data Shared:** Search queries.

## 5. Anonymous App-Health Reporting

Tempo sends anonymous statistics about the app itself so that crashes and broken features can be found and fixed. This is the only data about you that leaves your device.

**What is sent.** Crash signatures (an obfuscated class name and line, never an error message), failure counts by category, which screens and features are reached, whether onboarding was completed, and whether background music tracking is alive. Counts are sent as ranges, not exact figures. The complete and exact list is published in the app at **Settings → Your Data → What we collect**, and in [`docs/ANALYTICS.md`](docs/ANALYTICS.md).

**What is never sent.** Track, artist, album or playlist names. Search queries. Notification content. File paths. Your listening timestamps. Your Google, Spotify or Last.fm account details. Any device identifier — not your advertising ID, not `ANDROID_ID`. Crash *messages* are excluded on purpose, because a parse error can echo the notification text Tempo read.

**How you are not identified.** We never set a user ID. The only identifier is a random per-session value that is held in memory, regenerated every time the app starts, and rotated after an hour of inactivity — it is never written to disk, so it cannot become a fingerprint of your device. No cookies are used.

**Who receives it.** [Aptabase](https://aptabase.com), in the **European Union**. Aptabase is open source. Their SDKs send no identifiers, and on receipt they derive a temporary hash from your IP address and user agent against a salt that **rotates every 24 hours**, with old salts purged on a schedule — so events cannot be linked across days. One honest caveat: that same IP is used to derive a **coarse country/region**, which is stored with the event. It is not precise and is not linked to anything else about you, but we would rather say so than claim we collect nothing about where you are.

**When it is sent.** Only over unmetered (Wi-Fi) connections, so it never spends your mobile data. Nothing at all is collected until the in-app notice explaining this has been shown to you.

**How to turn it off.** **Settings → Your Data → Anonymous app-health stats.** Turning it off stops all reporting immediately and deletes anything buffered but not yet sent. This takes effect regardless of which screen you are on, and no further data is collected.

**Builds compiled from source** send nothing at all. The reporting key is intentionally not committed to the repository, so anyone who builds Tempo themselves gets a version with these features inert.

### 5.1 The diagnostics report

Separately from the automatic reporting above, Tempo can produce a **diagnostics report** on request from **Settings → Your Data → Diagnostics report**. It summarises build and database versions, library counts, metadata-enrichment status, tracking health and background work states, and it is shown on screen for you to read before doing anything with it.

Nothing is uploaded in order to produce it. It leaves your device only if you explicitly share it — for example by attaching it to a bug report. Because it is user-initiated and reviewable, it can contain more detail than the automatic reporting described above; it still contains no track, artist or album names, no file paths, and no account or device identifiers.

## 6. Network Communication

All network requests are made directly from your device to the services listed above. Tempo does not route traffic through any intermediate proxy or server owned by us.

## 7. Children's Privacy

Tempo is a general utility app and is not directed at children under the age of 13. We do not knowingly collect personal information from children.

## 8. Changes to This Policy

We may update this Privacy Policy to reflect changes in our app's functionality. Since we do not collect user emails, we cannot notify you directly. Please check this file or the "About" section in the app for updates.

## 9. Contact

If you have questions about privacy or technical details:

**Developer:** Avinash
**Email:** hi@avinas.me
**GitHub:** [https://github.com/avinaxhroy/Tempo](https://github.com/avinaxhroy/Tempo)
