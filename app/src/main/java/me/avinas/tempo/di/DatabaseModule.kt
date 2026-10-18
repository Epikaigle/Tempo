package me.avinas.tempo.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.DatabaseTransactionRunner
import me.avinas.tempo.data.local.MigrationSafetyNet
import me.avinas.tempo.data.local.RoomDatabaseTransactionRunner
import me.avinas.tempo.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * Migration from version 1 to 2: Enhanced EnrichedMetadata table
     */
    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new enriched_metadata table with all new columns
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS enriched_metadata_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    track_id INTEGER NOT NULL,
                    musicbrainz_recording_id TEXT,
                    musicbrainz_artist_id TEXT,
                    musicbrainz_release_id TEXT,
                    musicbrainz_release_group_id TEXT,
                    album_title TEXT,
                    release_date TEXT,
                    release_year INTEGER,
                    release_type TEXT,
                    album_art_url TEXT,
                    album_art_url_small TEXT,
                    album_art_url_large TEXT,
                    artist_name TEXT,
                    artist_country TEXT,
                    artist_type TEXT,
                    track_duration_ms INTEGER,
                    isrc TEXT,
                    tags TEXT,
                    genres TEXT,
                    record_label TEXT,
                    audio_features_json TEXT,
                    spotify_id TEXT,
                    enrichment_status TEXT NOT NULL DEFAULT 'PENDING',
                    enrichment_error TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_enrichment_attempt INTEGER,
                    cache_timestamp INTEGER NOT NULL,
                    cache_version INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                )
            """)
            
            // Copy data from old table if it exists
            db.execSQL("""
                INSERT INTO enriched_metadata_new (id, track_id, tags, audio_features_json, cache_timestamp)
                SELECT id, track_id, tags, audio_features_json, cache_timestamp 
                FROM enriched_metadata
            """)
            
            // Drop old table
            db.execSQL("DROP TABLE enriched_metadata")
            
            // Rename new table
            db.execSQL("ALTER TABLE enriched_metadata_new RENAME TO enriched_metadata")
            
            // Create indices
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_enriched_metadata_track_id ON enriched_metadata(track_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_enriched_metadata_musicbrainz_recording_id ON enriched_metadata(musicbrainz_recording_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_enriched_metadata_enrichment_status ON enriched_metadata(enrichment_status)")
        }
    }
    
    /**
     * Migration from version 2 to 3: Add Spotify enrichment status columns
     */
    private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add Spotify enrichment status columns
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_enrichment_status TEXT NOT NULL DEFAULT 'NOT_ATTEMPTED'")
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_enrichment_error TEXT")
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_last_attempt INTEGER")
        }
    }
    
    /**
     * Migration from version 3 to 4: Add MusicBrainz ID and release type to albums
     */
    private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add musicbrainz_id column to albums table
            db.execSQL("ALTER TABLE albums ADD COLUMN musicbrainz_id TEXT")
            // Add release_type column to albums table
            db.execSQL("ALTER TABLE albums ADD COLUMN release_type TEXT")
            // Create index for faster lookups by MusicBrainz ID
            db.execSQL("CREATE INDEX IF NOT EXISTS index_albums_musicbrainz_id ON albums(musicbrainz_id)")
        }
    }
    
    /**
     * Migration from version 4 to 5: Add Spotify artist ID and image URL columns
     */
    private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add spotify_artist_id column for storing the primary artist's Spotify ID
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_id TEXT")
            // Add spotify_artist_image_url column for caching artist profile images
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_image_url TEXT")
        }
    }
    
    /**
     * Migration from version 5 to 6: Add Spotify artist IDs and verified artist name
     */
    private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add spotify_artist_ids for all collab artists (comma-separated)
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_ids TEXT")
            // Add spotify_verified_artist for the correct artist name from Spotify
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_verified_artist TEXT")
        }
    }
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        MigrationSafetyNet.snapshotBeforeMigrationIfNeeded(context)

        return Room.databaseBuilder(context, AppDatabase::class.java, "tempo.db")
            .addMigrations(
                MIGRATION_1_2, 
                MIGRATION_2_3, 
                MIGRATION_3_4, 
                MIGRATION_4_5, 
                MIGRATION_5_6,
                *AppDatabase.ALL_MIGRATIONS
            )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .enableMultiInstanceInvalidation()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Use query() for PRAGMA statements as execSQL fails on some Android SQLite drivers
                    try {
                        db.query("PRAGMA synchronous = NORMAL").close()
                        db.query("PRAGMA cache_size = -8000").close()
                        db.query("PRAGMA temp_store = MEMORY").close()
                        db.query("PRAGMA mmap_size = 268435456").close()
                        db.query("PRAGMA busy_timeout = 30000").close()
                        db.query("PRAGMA wal_autocheckpoint = 1000").close()
                    } catch (e: Exception) {
                        android.util.Log.w("DatabaseModule", "Failed to apply PRAGMA optimizations", e)
                    }
                    ensureTrackLookupIndex(db)
                }
            })
            .build()
    }

    /**
     * Guarantees `index_tracks_title_lower`, the case-insensitive index on `tracks.title` that
     * `TrackDao.findByTitleAndArtist` and friends are served by.
     *
     * **Why this is not a migration.** The index is created by `MIGRATION_8_9`, but it is not
     * declared on the `Track` entity — and it cannot be, because Room's `@Index` has no collation
     * parameter, so there is no annotation that can express `(title COLLATE NOCASE)`. Room's
     * generated `createAllTables` therefore creates only the eight *declared* indices, and because
     * a fresh install builds its schema that way and **never runs migrations**, every
     * freshly-installed database has been missing this index while every upgraded one has it.
     *
     * Measured on a 50k-row `tracks` table: with the index a lookup is an index seek (~0.001 ms);
     * without it SQLite scans the whole table (~1.6 ms for a miss, and a *miss* is the common case
     * during import because every genuinely new track is one). Same app, same version, roughly
     * three orders of magnitude apart depending only on install history.
     *
     * `onOpen` rather than `onCreate` is deliberate: `onCreate` only ever fires for a database
     * that is being created right now, so it would fix future installs but leave everyone who
     * already fresh-installed schema 55 permanently without the index. `onOpen` runs after both
     * `onCreate` and `onUpgrade`, so it repairs existing installs too.
     *
     * The cost is one `CREATE INDEX IF NOT EXISTS` per process start, which is a schema lookup and
     * a no-op wherever the index already exists. Where it does have to build, the table is empty
     * by construction — a fresh install has no tracks yet — so it is instantaneous. It cannot
     * interfere with schema validation: Room's `checkIdentity` runs before `onOpen` callbacks are
     * invoked, and an undeclared *extra* index is not part of the declared schema it compares.
     *
     * A migration is still the tidier long-term home for this (a `58 -> 59` step once the
     * `migration/squash-55-58` work lands). It is kept here for now so the fix stays correct
     * regardless of which schema version a given install is sitting on.
     */
    private fun ensureTrackLookupIndex(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tracks_title_lower ON tracks(title COLLATE NOCASE)",
            )
        } catch (e: Exception) {
            // A missing optimisation index must never stop the database from opening; the
            // affected queries simply fall back to a scan, as they already did without it.
            android.util.Log.w("DatabaseModule", "Failed to ensure track lookup index", e)
        }
    }

    @Provides
    @Singleton
    fun provideDatabaseTransactionRunner(
        runner: RoomDatabaseTransactionRunner
    ): DatabaseTransactionRunner = runner

    @Provides
    fun provideTrackDao(db: AppDatabase) = db.trackDao()

    @Provides
    fun provideListeningEventDao(db: AppDatabase) = db.listeningEventDao()

    @Provides
    fun provideArtistDao(db: AppDatabase) = db.artistDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase) = db.albumDao()

    @Provides
    fun provideEnrichedMetadataDao(db: AppDatabase) = db.enrichedMetadataDao()

    @Provides
    fun provideUserPreferencesDao(db: AppDatabase) = db.userPreferencesDao()

    @Provides
    fun provideStatsDao(db: AppDatabase) = db.statsDao()
    
    @Provides
    fun provideTrackArtistDao(db: AppDatabase) = db.trackArtistDao()
    
    @Provides
    fun provideTrackAliasDao(db: AppDatabase) = db.trackAliasDao()
    
    @Provides
    fun provideManualContentMarkDao(db: AppDatabase) = db.manualContentMarkDao()
    
    @Provides
    fun provideArtistAliasDao(db: AppDatabase) = db.artistAliasDao()
    
    @Provides
    fun provideAppPreferenceDao(db: AppDatabase) = db.appPreferenceDao()
    
    @Provides
    fun provideLastFmImportMetadataDao(db: AppDatabase) = db.lastFmImportMetadataDao()
    
    @Provides
    fun provideScrobbleArchiveDao(db: AppDatabase) = db.scrobbleArchiveDao()
    
    @Provides
    fun provideGamificationDao(db: AppDatabase) = db.gamificationDao()
    
    @Provides
    fun provideUserKnownArtistDao(db: AppDatabase) = db.userKnownArtistDao()

    @Provides
    fun provideDesktopPairingDao(db: AppDatabase) = db.desktopPairingDao()
}
