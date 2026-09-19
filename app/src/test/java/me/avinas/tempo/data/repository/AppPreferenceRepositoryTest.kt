package me.avinas.tempo.data.repository

import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.DefaultMusicApps
import me.avinas.tempo.data.local.dao.AppPreferenceDao
import me.avinas.tempo.data.local.entities.AppPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guards the seeding contract `TempoApplication` now relies on: every default app
 * must be inserted (fresh install) or re-passed with IGNORE (existing install).
 * If an app is added to [DefaultMusicApps] but dropped from either seeding branch,
 * users who never open the Supported Apps screen run with an unseeded table and
 * tracking silently stops — this test is what fails first.
 */
class AppPreferenceRepositoryTest {
    private inline fun <reified T : Any> createProxy(crossinline handler: (methodName: String, args: Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, args ->
            val result = handler(method.name, args)
            result ?: when (method.returnType) {
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as T

    private fun seededRepository(existingCount: Int): Pair<AppPreferenceRepository, MutableList<AppPreference>> {
        val inserted = mutableListOf<AppPreference>()
        val dao =
            createProxy<AppPreferenceDao> { name, args ->
                when (name) {
                    "getCount" -> {
                        existingCount
                    }

                    "insertAll" -> {
                        @Suppress("UNCHECKED_CAST")
                        inserted.addAll(args?.get(0) as List<AppPreference>)
                        null
                    }

                    else -> {
                        null
                    }
                }
            }
        return AppPreferenceRepository(dao) to inserted
    }

    @Test
    fun `fresh install seeds every default music app as enabled`() =
        runTest {
            val (repo, inserted) = seededRepository(existingCount = 0)

            repo.seedDefaultAppsIfNeeded()

            val insertedPkgs = inserted.map { it.packageName }.toSet()
            assertTrue(
                "Every MUSIC_APPS entry must be seeded, missing: " +
                    (DefaultMusicApps.MUSIC_APPS.map { it.packageName } - insertedPkgs),
                DefaultMusicApps.MUSIC_APPS.all { it.packageName in insertedPkgs },
            )
            assertTrue(
                "Every BLOCKED_APPS entry must be seeded, missing: " +
                    (DefaultMusicApps.BLOCKED_APPS.map { it.packageName } - insertedPkgs),
                DefaultMusicApps.BLOCKED_APPS.all { it.packageName in insertedPkgs },
            )

            // Curated apps whose package names look like video apps to the service's
            // heuristics ("player" without "music") — if these aren't seeded enabled,
            // they're only trackable via the static-whitelist fallback.
            val playerNamed =
                listOf(
                    "com.miui.player",
                    "com.maxmpz.audioplayer",
                    "player.phonograph.plus",
                    "com.kodarkooperativet.blackplayerfree",
                    "it.ncaferra.pixelplayerfree",
                )
            playerNamed.forEach { pkg ->
                val pref = inserted.first { it.packageName == pkg }
                assertTrue("$pkg must be seeded enabled", pref.isEnabled)
                assertFalse("$pkg must not be blocked", pref.isBlocked)
            }

            val blocked = inserted.filter { DefaultMusicApps.BLOCKED_APPS.map { b -> b.packageName }.contains(it.packageName) }
            blocked.forEach {
                assertFalse("Blocked app ${it.packageName} must be seeded disabled", it.isEnabled)
                assertTrue("Blocked app ${it.packageName} must be seeded blocked", it.isBlocked)
            }
        }

    @Test
    fun `existing install backfills the full default lists`() =
        runTest {
            val (repo, inserted) = seededRepository(existingCount = 42)

            repo.seedDefaultAppsIfNeeded()

            val insertedPkgs = inserted.map { it.packageName }.toSet()
            assertTrue(
                "Backfill must pass every default app (INSERT OR IGNORE no-ops on the DAO side), missing: " +
                    ((DefaultMusicApps.MUSIC_APPS + DefaultMusicApps.BLOCKED_APPS).map { it.packageName } - insertedPkgs),
                (DefaultMusicApps.MUSIC_APPS + DefaultMusicApps.BLOCKED_APPS)
                    .all { it.packageName in insertedPkgs },
            )
        }
}
