# Privacy Policy for Tempo Stats Browser Extension

**Last Updated:** May 27, 2026

Tempo Stats is a browser extension companion for the Tempo Android app. It operates locally without cloud tracking or user account creation.

---

## 1. Data Access and Collection

To track audio playback and sync records, Tempo Stats accesses the following items:

- **Media Metadata**: Reads song title, artist name, album name, total duration, playback position, volume level, and muted state on active media tabs.
- **Tab Domain URLs**: Inspects tab URLs to verify if the site matches supported audio players before executing media probes.
- **Device Connection Credentials**: Stores the local IP address, port, and security token of the paired Android device.

---

## 2. Data Storage and Handling

- **Local Storage**: Playback queues, user settings, and connection credentials are saved using `chrome.storage.local` and IndexedDB.
- **Session State**: Active tab tracking state is held in `chrome.storage.session` and cleared when the browser closes.
- **Remote Infrastructure**: No external database or central collection server is maintained by this project.

---

## 3. Data Transmission

- **Local Network Sync**: Queued plays are transmitted directly to the paired phone over your local Wi-Fi or mobile hotspot.
- **Payload Security**: Requests sent to the phone are signed using HMAC-SHA256 digests with the shared security token.
- **No External Uploads**: Listening data is not sent over public internet servers or cloud infrastructure.

---

## 4. Third-Party Integrations

The extension contains no embedded tracking scripts, telemetry tools, or third-party analytics SDKs.

---

## 5. Controls and Data Management

- **Toggle Scrobbling**: Tracking can be disabled at any time from the extension popup.
- **Queue Operations**: Users can view, edit, or clear pending scrobbles from the popup menu.
- **Unpairing**: Removing paired device credentials deletes the saved IP address, port, and authentication token.
- **Automatic Queue Truncation**: Successfully synced plays are removed from the local IndexedDB queue automatically.

---

## 6. Declared Extension Permissions

- `storage`: Saves settings, channel whitelists, and local queues.
- `alarms`: Schedules sync retries when the target Android device is unreachable.
- `tabs`: Queries tab URLs to detect supported music web applications.
- **Host Permissions** (`http://*:8765/*`, optional `http://*:*/*`): Connects to the Tempo server running on the Android app over local networks.

---

## 7. Contact Information

**Developer**: Avinash  
**Email**: hi@avinash.im  
**GitHub Repository**: [https://github.com/avinaxhroy/Tempo](https://github.com/avinaxhroy/Tempo)
