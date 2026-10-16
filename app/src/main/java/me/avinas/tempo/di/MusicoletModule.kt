package me.avinas.tempo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.musicolet.AndroidMusicoletTableOpener
import me.avinas.tempo.data.musicolet.MusicoletTableOpener
import javax.inject.Singleton

/**
 * Binds the SQLite reader used to open decrypted Musicolet backup databases.
 *
 * Production reads them with Android's `SQLiteDatabase`; the binding exists so the
 * parsing pipeline above it stays testable against a different reader.
 */
@Module
@InstallIn(SingletonComponent::class)
object MusicoletModule {
    @Provides
    @Singleton
    fun provideMusicoletTableOpener(): MusicoletTableOpener = AndroidMusicoletTableOpener()
}
