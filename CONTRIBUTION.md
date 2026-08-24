# Contributing to Tempo

Contributions to Tempo are welcome. This guide covers how to set up your development environment, build and test the app, follow codebase conventions, and submit pull requests.

## Project principles

Tempo is a local-first music companion and scrobbler for Android. Every change must adhere to these core constraints:

- **Local storage first:** Listening history, statistics, and metadata stay on device in local SQLite databases via Room. The app does not transmit user data to remote analytics servers or telemetry services.
- **Offline operation:** Core tracking, stats generation, and library browsing must work without network access. External API calls (Last.fm, Spotify, MusicBrainz, Deezer) are strictly opt-in for metadata enrichment and imports.
- **No commercial features:** The project is released under a modified AGPLv3 license that prohibits monetization, advertisements, paid subscriptions, and rebranding.

## Development setup

### Requirements

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 36 (Minimum SDK: 26, Target SDK: 36, Compile SDK: 36)
- Node.js 18+ (required only for the browser extension)

### Optional API configuration

The app builds and runs without external API keys. To test features that require third-party services, add the relevant keys to `local.properties` in the project root:

```properties
SPOTIFY_CLIENT_ID=your_spotify_client_id
LASTFM_API_KEY=your_lastfm_api_key
GOOGLE_WEB_CLIENT_ID=your_google_client_id
```

## Building and testing

### Android application

Clone the repository and build the debug APK:

```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run lint checks:

```bash
./gradlew lintDebug
```

### Room database schemas

Room entity definitions export database schemas to `app/schemas/`. When adding or modifying entities and database migrations:
1. Ensure the schema export succeeds during compilation.
2. Add migration test cases in `app/src/test/java/me/avinas/tempo/data/local/` to verify forward compatibility.
3. Commit the updated JSON schema files alongside the migration code.

### Browser companion extension

The browser companion lives in `browser-extension/`:

```bash
cd browser-extension
npm install
npm run build
npm run typecheck
```

Load the unpacked extension directory (`dist/chrome` or `dist/firefox`) into your browser:
- Chrome: Open `chrome://extensions`, enable **Developer mode**, and select **Load unpacked**.
- Firefox: Open `about:debugging#/runtime/this-firefox` and select **Load Temporary Add-on**.

## Contribution workflow

### When to open an issue first

Open an issue to discuss your proposal before writing code for:
- New major features or background services
- Database schema changes or structural refactoring
- UI navigation changes or visual redesigns
- Adding new third-party dependencies

You do not need to open an issue before submitting pull requests for:
- Bug fixes and crash resolutions
- Parser fixes or support for new media players in `DefaultMusicApps.kt`
- Translation updates in `app/src/main/res/values-*/strings.xml`
- Documentation fixes and test additions

### Pull request checklist

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on a single change or bug fix.
3. Run `./gradlew testDebugUnitTest` and `./gradlew lintDebug` to verify tests pass and no lint regressions were introduced.
4. Open the pull request against `main` and include:
   - A summary of the problem and the implemented fix.
   - Reproduction steps or test coverage for bug fixes.
   - Before and after screenshots or screen recordings for UI changes.
   - The issue number if one exists (for example, `Fixes #42`).

## Code and architecture standards

- **Architecture:** MVVM with Clean Architecture. Keep business logic in `domain/`, data persistence and network calls in `data/`, and Jetpack Compose screens and ViewModels in `ui/`.
- **Dependency injection:** Use Hilt for dependency injection across ViewModels, repositories, workers, and background services.
- **UI:** Write UI entirely in Jetpack Compose using Material 3 components. Support both light and dark themes. Ensure interactive touch targets meet the 48dp minimum size requirement.
- **Background work:** Use `WorkManager` for periodic or deferrable background tasks (enrichment, daily stats). Use foreground services only where continuous playback listening requires Android service lifecycle management.
- **Memory and performance:** Downsample large cover art bitmaps before writing to SQLite to prevent `TransactionTooLargeException` and memory pressure. Release broadcast receivers, database cursors, and coroutine scopes on component teardown.
- **Dependencies:** Avoid adding new dependencies unless the functionality cannot be reasonably implemented with existing libraries or platform APIs.

## Localization

Tempo supports multiple languages. String resources live in `app/src/main/res/`:

- Default English: `values/strings.xml`
- German: `values-de/strings.xml`
- French: `values-fr/strings.xml`
- Hungarian: `values-hu/strings.xml`
- Portuguese: `values-pt/strings.xml`
- Russian: `values-ru/strings.xml`

When adding user-facing UI text:
1. Add the string to `values/strings.xml` using a descriptive key.
2. Reference the string via `stringResource(R.string.your_key)` in Compose instead of hardcoding text.
3. If providing translations for existing keys, update the corresponding `values-*/strings.xml` file.

## License terms for contributions

Tempo is licensed under the [GNU Affero General Public License v3 (AGPLv3) with Custom Limitations](LICENSE).

By submitting a pull request, you agree that your contributions are licensed under these same terms. Contributions are not accepted if they require commercial relicensing, closed-source distribution, or removal of project attribution.
