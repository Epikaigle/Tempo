# Artwork identity: research and plan

Status: research complete, nothing implemented. Findings were verified twice, once by direct inspection and once by an independent read-only pass over the tracking, enrichment and artist-image paths. Covers the two complaints on record (wrong creator images, embedded art being replaced), the cover-art upload idea, and where AcoustID fits.

## The complaints, restated precisely

1. "The app gets the wrong profile of the music creators most of the time."
2. "I use music locally and the thumbnail is embedded correctly, it just grabs one from the internet, even though I have one already."
3. "It would be better if we can upload the cover art." (shipped: gallery cover picker)

## Findings

### F1. Matching accepts the wrong release, and the validation that would stop it already exists

The automatic enrichment path picks a release with `releases?.firstOrNull()` and compares nothing before accepting it. Not duration, not the local album name, not track count, not release year, not track or disc number, not the artist's MusicBrainz ID, not ISRC. MusicBrainz's own track length is copied *into* local metadata rather than checked against it.

Every signal needed is already in hand. Duration, track number, disc number, track count, year and genre are read from the player in `LocalMediaMetadata.kt` and then unused in matching.

Worse, the validation exists and is not wired in:

- `musicBrainzReleaseScore` scores releases by album-title agreement, release-group agreement, an existing front cover, official status and release type, with penalties for compilations and live releases. It is called only by the user-driven cover picker, never by automatic enrichment.
- `isSafeCoverArtistMatch` exists precisely to stop a short artist name validating a longer one. `CoverArtMatching.kt` documents the hazard: "so 'Drake' cannot validate 'Drake Bell'". The automatic path uses the fuzzy `ArtistParser.isSameArtist` instead, whose containment shortcut makes exactly that match succeed.

Other doors left open on the automatic path:

- The query list includes a title-only strategy with no artist clause at all.
- A relaxed retry drops both the score floor and the title-similarity floor, so a remix or a different edition can win.
- A track that already carries a MusicBrainz recording ID is re-searched by name instead of being looked up by that ID. The picker does the lookup correctly.

Consequence: a live album, a compilation, a remaster, a regional edition or a similarly titled song can supply the release, and with it the album title, the release year, the cover art, and the artist rows.

### F2. Local art loses to internet art by design

`AlbumArtSource` in `EnrichedMetadata.kt`:

```
LOCAL(2) < DEEZER(3) < ITUNES(4) < MUSICBRAINZ(5) < SPOTIFY(6)
```

`shouldBeReplacedBy` enforces that ordering and is called from the iTunes, Deezer, MusicBrainz and Spotify enrichment paths. Art the player handed over from a local file is therefore scheduled to be overwritten by any remote provider. Complaint 2 is this line of code, not a missing feature.

### F3. Artist photos are chosen by name with no identity check

Priority is Spotify, then iTunes, then Last.fm, then Deezer (`getBestArtistImageUrl`, mirrored in SQL). Only Spotify-by-ID during enrichment uses a real identity, and only when a Spotify artist ID already exists. Everything else is a name search, and `Artist.imageUrl` has no source column and no manual lock, so one wrong write stays visible on Home, in Spotlight, and on the artist screen.

The specific holes:

- Spotify's fallback passes accept a containment match, or the first result above a 0.5 Jaccard score, and then write the inferred Spotify ID onto a row that was found by name.
- Deezer validates nothing. It takes the first search result that has a preview URL.
- iTunes is the only provider with a strict name gate, and it does not persist the artist ID it matched, so the verification cannot be reused on a later run.
- Last.fm's artist-image function is dead code with no callers, and its column is never written, yet it holds the third slot in the priority list.
- `refreshArtistImageDirectly` clears the existing image before re-searching by name, so a failed refresh can leave the artist with no image and a wrong match sticks.
- The artist row's `imageUrl` is returned unconditionally ahead of every verified source.

No MusicBrainz ID is ever used to validate a photo.

### F4. Tempo never opens an audio file, and never reads the audio library

Verified: zero uses of `MediaStore.Audio`, and `READ_MEDIA_AUDIO` is not in the manifest. Artwork arrives as a Bitmap in the player's media metadata, not from the file. No library scan exists. Storage Access Framework access exists only for the backup folder (`LocalBackupFolderPickerActivity`, `OpenDocumentTree` plus a persisted grant) and for single import files.

One hook exists, but only in memory: `LocalMediaMetadata.mediaUri` is captured from `METADATA_KEY_MEDIA_URI` onto an in-memory object and cached in an `LruCache`. It is never written to disk, there is no column for it, and nothing reads it. The only occurrences in the app are the declaration and the assignment. Whether such a URI would even be readable depends on the player granting access, which varies by player.

Consequence: AcoustID cannot be attached to the notification-listener flow as it stands. Fingerprinting needs access to the audio, which today does not exist anywhere in the app.

### F5. AcoustID facts

- Lookup: `GET https://api.acoustid.org/v2/lookup?client=<key>&duration=<seconds>&fingerprint=<fp>&meta=recordings+releasegroups+releaseids`
- The response includes recordings with release-group ids and titles, which is exactly what is needed to choose the right artwork.
- Rate limit: 3 requests per second. Free for non-commercial use only. The app must be registered for a key, and AcoustID asks to be told in advance about significant traffic.
- Submitting fingerprints requires a per-user key. Lookups do not, so Tempo would never need one.
- Tempo is non-commercial by its own licence (the README prohibits monetisation), so the free tier applies.

### F6. Chromaprint on Android is a real integration, not a dependency

Chromaprint is a C library built with CMake, currently at 1.6.2. It does not decode audio: "your application needs to find a way to decode audio files (MP3, MP4, FLAC, etc.) and feed the uncompressed data to Chromaprint." Upstream publishes no Android binaries.

That means NDK plus CMake (the project has neither today), a JNI wrapper, a PCM decoder feeding 11025 Hz mono samples, and an extra native library per ABI. Licence: Chromaprint's own code is MIT, the bundled FFmpeg parts are LGPL 2.1, so as a whole it is LGPL 2.1. Shipping it as a separate dynamically loaded `.so` is the usual compliance route and needs a notice and a source offer.

### F7. Reading tags is far cheaper and probably covers most libraries

Files tagged by Picard or beets carry MusicBrainz identifiers directly (`MUSICBRAINZ_TRACKID`, `MUSICBRAINZ_ALBUMID`, `MUSICBRAINZ_ARTISTID`, `MUSICBRAINZ_RELEASEGROUPID`), and Picard also writes `AcoustID Id` and `AcoustID Fingerprint`.

Media3 1.9.0 is already a dependency. `androidx.media3:media3-inspector` at the same version (Apache 2.0) provides `MetadataRetriever` for reading tags without playback, and Media3's tag parsers expose arbitrary ID3 `TXXX` and Vorbis comment keys.

So a folder-scoped tag pass can produce exact MBIDs with no NDK, no fingerprinting, no rate limits, and no third-party service call. A stored AcoustID fingerprint, when present, turns into a single lookup with no decoding at all.

### F8. No art store accepts programmatic submissions

Cover Art Archive is read-only (probed live: `allow: GET, HEAD, OPTIONS`). Discogs and Fanart.tv take images through their websites only. TheAudioDB and Last.fm are read-only. Automatic sharing between users therefore only exists if Tempo hosts it, which is a separate decision with takedown, policy and privacy consequences.

## Plan

### Phase 0: correctness, no new capability, ships the complaints

**0.1 Prefer the art the user already has.** Either raise `LOCAL` above the API sources, or gate replacement at the enrichment call sites: skip the overwrite when the current source is `LOCAL` and the file still exists. The second is narrower and easier to verify. Cover with cases in `ArtworkPersistenceIntegrationTest`.

**0.2 Use the release ranker that already exists.** Replace `releases?.firstOrNull()` with `musicBrainzReleaseScore` on the automatic path, so album-title and release-group agreement decide, compilations and live releases are penalised, and an existing front cover acts as a tiebreaker.

**0.3 Add duration as a hard gate.** Extend the ranker with track-length agreement, starting at a 5 second tolerance and revisited for vinyl and remaster differences. Duration is already captured and MusicBrainz returns it.

**0.4 Close the artist-identity hole.** Use `isSafeCoverArtistMatch` rather than the fuzzy matcher when deciding whether a search result is the same artist. Remove or guard the title-only strategy, and revisit the relaxed thresholds.

**0.5 Reuse stored identifiers.** When a track already has a MusicBrainz recording ID, look it up by ID and verify it, the way the cover picker does, instead of searching by name again.

**0.6 Stop showing faces we are not sure about.** Accept an artist image only when identity is confirmed, and show a monogram otherwise. Do not clear an existing image before a refresh that may fail. Persist the provider artist ID so verification can be reused on later runs.

**0.7 Remove the dead Last.fm artist-image path** rather than leaving a fourth unvalidated provider in the priority list.

**0.8 Give artists the same escape hatch covers have.** An `imageSource` column plus a manual override, mirroring `AlbumArtSource` and `USER_SELECTED`. This one needs a Room migration, so it is the only Phase 0 item with schema risk, and it can follow the rest.

### Phase 1: instrument before building anything heavy

**1.1** Log, per local playback session: whether `mediaUri` was present, its scheme, and whether it can actually be opened. Debug builds and the diagnostics report only. This measures whether fingerprinting could ever run off the detection flow.

**1.2** Record how many files in a real library carry MusicBrainz or AcoustID tags versus none. That number decides whether Phase 3 is worth building at all.

### Phase 2: connect a music folder (no permissions, no NDK)

**2.1** `OpenDocumentTree` with a persisted grant, following the existing backup-folder pattern. A setting along the lines of "Improve artwork using my music files".

**2.2** Read tags with `media3-inspector` from a WorkManager job, batched, on Wi-Fi and idle.

**2.3** Where MBIDs are present, fetch art from the Cover Art Archive by exact release, falling back to release group. Stamp it with a new high-priority source so it beats API guesses.

**2.4** Where a stored AcoustID fingerprint is present, do one lookup instead of decoding.

**2.5** Report what changed, and allow reverting per album.

### Phase 3: AcoustID for files with no tags (only if Phase 1 justifies it)

**3.1** Register a Tempo application for an API key, keep it out of version control like the other keys, and email AcoustID about expected volume before shipping.

**3.2** Chromaprint through NDK and CMake with a JNI wrapper, decoding to 11025 Hz mono via MediaCodec.

**3.3** Cache fingerprints against file size and modification time so a file is fingerprinted once, ever.

**3.4** Token bucket at 3 requests per second, with backoff and a global off switch.

**3.5** Privacy: opt-in, and a new section in `PRIVACY.md`. A fingerprint is derived from the audio and reveals what someone owns. The current policy promises that only artist and title leave the device, so this is a real change in what is sent, not a wording tweak.

**3.6** LGPL compliance for Chromaprint: separate `.so`, licence notice, source offer.

## Decisions taken

1. **No Cover Art Archive contribution flow.** Decided: skip it. The manual flow that was built has been removed, and nothing in the app talks to MusicBrainz for writing. Artwork stays local.
2. **Keep artwork local.** No Tempo-hosted art pool for now.
3. Still open: is a "connect your music folder" feature wanted? It gates every AcoustID benefit, and it is useful on its own for bulk artwork repair.
4. Still open: for Phase 0.1, flip the `LOCAL` priority globally, or add a preference that defaults to preferring local art?

## Deliberately not planned

Hosting a Tempo art service, and any upload path to a third-party art archive. Both were evaluated and dropped. The first is the only route to automatic sharing between users, but it brings takedown handling, moderation, a policy, and a privacy-policy change. The second cannot be automatic at all, because no art archive in the ecosystem exposes a write API.
