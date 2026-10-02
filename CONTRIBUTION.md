# Contributing to Tempo

Thanks for your interest in Tempo. Tempo is a local-first music companion and scrobbler for Android, and contributions of all sizes are welcome.

This guide gets a debug build running, then walks through the few constraints that keep user data on the device.

## Quickstart

You need Android Studio Ladybug (2024.2.1) or newer, JDK 17, Android SDK 36 (min 26, target 36, compile 36), and Node.js 18+ only if you work on the browser extension.

Clone and build the app:

```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
./gradlew assembleDebug
```

Check your change before you push:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The app builds and runs with no API keys. For features that need third-party services, add keys to `local.properties` in the project root:

```properties
SPOTIFY_CLIENT_ID=your_spotify_client_id
LASTFM_API_KEY=your_lastfm_api_key
GOOGLE_WEB_CLIENT_ID=your_google_client_id
```

Leave `APTABASE_APP_KEY` unset unless you are testing analytics. Without a key the app sends nothing and shows no analytics UI. That silence is the expected state for a dev build and for anyone building from source. Debug builds never report, even with a key set.

## Rules that shape every change

These matter most, so please skim them before you start coding. Tempo keeps listening history, stats, and metadata on the device in local SQLite databases through Room. It never sends listening content, search queries, notification text, or device identifiers to a server.

The only thing that leaves the device is anonymous crash, error, and feature-usage statistics, and those follow a closed schema. Every event and property is defined in `data/analytics/AnalyticsEvent.kt`. There are no free-form string properties, so no caller can pass a track title, artist name, or file path. Please don't add a property that could carry listening content or an identifier — `AnalyticsSchemaTest` fails the build if one shows up, to protect that promise. Reporting is on by default with a one-tap opt-out in Settings, shows an in-app notice before the first event is sent, and stays inert in any build without an `APTABASE_APP_KEY`. You can read the full background in [docs/ANALYTICS.md](docs/ANALYTICS.md).

If you need detail to debug something, put it in `DiagnosticsInput` (shown at Settings → Your Data → Diagnostics report) instead of adding it to the analytics schema. The report is user-initiated and reviewable before it is shared, so it can carry detail the automatic events never should.

Core tracking, stats, and library browsing work offline. Calls to Last.fm, Spotify, MusicBrainz, and Deezer are opt-in only, for metadata enrichment and imports.

The project ships under a modified AGPLv3 license that prohibits monetization, advertisements, paid subscriptions, and rebranding.

## Free core and possible paid extras

Contributions are what keep Tempo moving, and all of them are welcome. The core app stays free forever. Tracking, stats, library browsing, and imports are core, and anything you contribute to core stays free too. Community work will not be moved behind a paywall.

Tempo will not be exploited for cash. No ads, no paywalling existing features, no charging for what contributors built for free.

If small paid extras ever appear in the future, they would only be optional add-ons to help sustain development, kept separate from core. They would add something new without taking anything away from the free app.

If your pull request overlaps with a planned paid extra, we may ask you to reshape it, narrow it to core, or hold it for now. We will explain why in the pull request. For larger features, opening an issue first is the easiest way to check direction before you write code.

## How to submit a change

It helps to open an issue first when your proposal touches a new major feature or background service, a database schema change or structural refactor, UI navigation or a visual redesign, a new third-party dependency, or an analytics event or property. The analytics schema is a privacy commitment published to users, so thanks for letting it change through discussion.

Feel free to skip the issue for bug fixes and crash resolutions, parser fixes or new media player support in `DefaultMusicApps.kt`, translation updates in `app/src/main/res/values-*/strings.xml`, and documentation or test additions — just send the pull request.

To send a pull request:

1. Fork the repo and branch from `main`.
2. Keep the pull request to one change or one fix.
3. Run `./gradlew testDebugUnitTest` and `./gradlew lintDebug`.
4. Open the pull request against `main` and include a summary of the problem and the fix, reproduction steps or test coverage for bug fixes, before and after screenshots or recordings for UI changes, and the issue number if there is one (for example, `Fixes #42`).

## Code and architecture

Tempo uses MVVM with Clean Architecture. Put business logic in `domain/`, persistence and network calls in `data/`, and Jetpack Compose screens and ViewModels in `ui/`.

Use Hilt for dependency injection across ViewModels, repositories, workers, and background services.

Write UI in Jetpack Compose with Material 3 components. Support light and dark themes. Keep interactive touch targets at least 48dp.

Use `WorkManager` for periodic or deferrable work such as enrichment and daily stats. Use a foreground service only where continuous playback listening needs Android service lifecycle management.

Downsample large cover art bitmaps before writing to SQLite. Full-size art risks `TransactionTooLargeException` and memory pressure. Release broadcast receivers, database cursors, and coroutine scopes on teardown.

Avoid adding a dependency when existing libraries or platform APIs do the job.

For analytics, please don't call the SDK directly. Inject `AnalyticsTracker` and pass an event from `data/analytics/AnalyticsEvent.kt`. Keep properties free of `String` types, and bucket counts instead of sending exact numbers.

## Room schemas and release builds

Room exports database schemas to `app/schemas/`. When you add or change entities and migrations, confirm the schema export succeeds during compilation, add migration tests in `app/src/test/java/me/avinas/tempo/data/local/`, and commit the updated JSON schema files with the migration code.

Release builds are obfuscated with R8. A crash report then holds only an obfuscated class name and one frame with a line number, readable only with the `mapping.txt` for that exact version. Generate both with:

```bash
./gradlew assembleRelease archiveReleaseMapping
```

This writes `mappings/mapping-<version>.txt`. That directory is gitignored. Copy the file somewhere durable or the crash report becomes unreadable and Play Console is your only fallback.

## Browser extension

The companion lives in `browser-extension/`:

```bash
cd browser-extension
npm install
npm run build
npm run typecheck
```

Load the unpacked output (`dist/chrome` or `dist/firefox`) into your browser. In Chrome open `chrome://extensions`, enable Developer mode, and select Load unpacked. In Firefox open `about:debugging#/runtime/this-firefox` and select Load Temporary Add-on.

## Localization

String resources live in `app/src/main/res/`:

- Default English in `values/strings.xml`
- German in `values-de/strings.xml`
- French in `values-fr/strings.xml`
- Hungarian in `values-hu/strings.xml`
- Portuguese in `values-pt/strings.xml`
- Russian in `values-ru/strings.xml`

For new user-facing text, please add the string to `values/strings.xml` with a descriptive key and reference it with `stringResource(R.string.your_key)` in Compose instead of hardcoding the text. For existing keys, update the matching `values-*/strings.xml` file.

Privacy copy has to stay accurate in every language. If you change a string that makes a privacy claim, please delete the now-stale translations for that key in `values-*/` instead of leaving a claim that is no longer true. Falling back to English is better than showing users something false, and someone will re-translate it.

## License terms for contributions

Tempo is licensed under the [GNU Affero General Public License v3 (AGPLv3) with Custom Limitations](LICENSE).

By submitting a pull request you agree your contribution uses these same terms. If your change would need commercial relicensing, closed-source distribution, or removal of project attribution, we won't be able to accept it — thanks for understanding.

Thanks again for contributing.
