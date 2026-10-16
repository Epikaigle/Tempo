package me.avinas.tempo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the default-deny precedence ladder in [AppTrackingPolicy].
 *
 * The regression this guards against: user reports of Viber voice notes and Signal voice
 * messages being journaled as songs and pinned to the foreground notification. Both apps were
 * absent from every list, and "absent from every list" used to mean "track it".
 */
class AppTrackingPolicyTest {

    private val noUserChoices = emptySet<String>()

    private fun decide(
        packageName: String,
        enabled: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        disabled: Set<String> = emptySet(),
        trackUnrecognized: Boolean = false,
    ) = AppTrackingPolicy.decide(
        packageName = packageName,
        userEnabledPackages = enabled,
        userBlockedPackages = blocked,
        userDisabledPackages = disabled,
        trackUnrecognizedApps = trackUnrecognized,
    )

    // ---- The reported bugs -------------------------------------------------

    @Test
    fun `viber voice notes are never tracked`() {
        assertEquals(TrackingDecision.NEVER_TRACKED, decide("com.viber.voip"))
    }

    @Test
    fun `signal voice messages are never tracked`() {
        assertEquals(TrackingDecision.NEVER_TRACKED, decide("org.thoughtcrime.securesms"))
    }

    @Test
    fun `messaging apps stay rejected even when the user allowlists them`() {
        // Rule 1 outranks rule 4: an accidental "allow" must not resurrect the bug.
        val decision = decide(
            "com.viber.voip",
            enabled = setOf("com.viber.voip"),
            trackUnrecognized = true,
        )
        assertEquals(TrackingDecision.NEVER_TRACKED, decision)
        assertFalse(decision.shouldTrack)
    }

    @Test
    fun `voice recorders and dialers are never tracked`() {
        listOf(
            "com.android.soundrecorder",
            "com.google.android.dialer",
            "com.android.voicemail",
            "com.samsung.android.incallui",
        ).forEach { packageName ->
            assertEquals(
                "expected $packageName to be never-tracked",
                TrackingDecision.NEVER_TRACKED,
                decide(packageName),
            )
        }
    }

    @Test
    fun `unknown apps are denied by default and offered in the inbox`() {
        val decision = decide("com.example.niche.player")
        assertEquals(TrackingDecision.UNKNOWN, decision)
        assertFalse(decision.shouldTrack)
        assertTrue(decision.recordAsDetected)
    }

    @Test
    fun `blocked and never-tracked apps are not offered as suggestions`() {
        // Suggesting an app the user already rejected would defeat the point of blocking it.
        assertFalse(decide("com.google.android.youtube").recordAsDetected)
        assertFalse(decide("com.viber.voip").recordAsDetected)
    }

    // ---- The allowlist --------------------------------------------------

    @Test
    fun `curated music apps are tracked without user action`() {
        listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.maxmpz.audioplayer",
            "com.dd3boh.outertune",
        ).forEach { packageName ->
            assertEquals(
                "expected $packageName to be allowed",
                TrackingDecision.ALLOW,
                decide(packageName),
            )
        }
    }

    @Test
    fun `podcast and audiobook apps stay tracked so content filtering can judge them`() {
        assertEquals(TrackingDecision.ALLOW, decide("au.com.shiftyjelly.pocketcasts"))
        assertEquals(TrackingDecision.ALLOW, decide("com.audible.application"))
    }

    @Test
    fun `user-added apps are tracked`() {
        assertEquals(
            TrackingDecision.ALLOW,
            decide("com.example.niche.player", enabled = setOf("com.example.niche.player")),
        )
    }

    @Test
    fun `disabling a curated app is honoured over the curated allowlist`() {
        // Spotify is on the allowlist, but the user switched it off in Manage Apps.
        val decision = decide("com.spotify.music", disabled = setOf("com.spotify.music"))
        assertEquals(TrackingDecision.BLOCKED, decision)
        assertFalse(decision.shouldTrack)
    }

    // ---- Blocklist interplay ---------------------------------------------

    @Test
    fun `user block outranks user enable`() {
        assertEquals(
            TrackingDecision.BLOCKED,
            decide(
                "com.example.player",
                enabled = setOf("com.example.player"),
                blocked = setOf("com.example.player"),
            ),
        )
    }

    @Test
    fun `built-in blocked apps are denied when the user has no preference`() {
        assertEquals(TrackingDecision.BLOCKED, decide("com.netflix.mediaclient"))
        assertEquals(TrackingDecision.BLOCKED, decide("com.android.chrome"))
    }

    @Test
    fun `long-tail OEM video players are blocked without cluttering the inbox`() {
        // These live only in EXTENDED_BLOCKED_PACKAGES (enforced, never seeded or displayed).
        // A regression here would flood the detected-apps inbox with video players.
        listOf("com.kmplayer", "com.mxtech.videoplayer.ad", "com.oneplus.gallery").forEach { pkg ->
            val decision = decide(pkg)
            assertEquals("expected $pkg to be blocked", TrackingDecision.BLOCKED, decision)
            assertFalse("$pkg must not be offered as a suggestion", decision.recordAsDetected)
        }
    }

    @Test
    fun `unblocking a long-tail blocked app is possible`() {
        assertEquals(
            TrackingDecision.ALLOW,
            decide("com.kmplayer", enabled = setOf("com.kmplayer")),
        )
    }

    @Test
    fun `unblocking a built-in blocked app actually allows it`() {
        // Manage Apps has always shown an Unblock button for these; before the policy existed
        // the static set won unconditionally and the button silently did nothing.
        assertEquals(
            TrackingDecision.ALLOW,
            decide("com.google.android.youtube", enabled = setOf("com.google.android.youtube")),
        )
    }

    // ---- Escape hatch ------------------------------------------------------

    @Test
    fun `opt-in tracking admits unrecognized apps without inboxing them`() {
        val decision = decide("com.example.niche.player", trackUnrecognized = true)
        assertEquals(TrackingDecision.ALLOW, decision)
        assertTrue(decision.shouldTrack)
        assertFalse(decision.recordAsDetected)
    }

    @Test
    fun `opt-in tracking never overrides a hard block`() {
        assertEquals(
            TrackingDecision.NEVER_TRACKED,
            decide("org.thoughtcrime.securesms", trackUnrecognized = true),
        )
        assertEquals(
            TrackingDecision.BLOCKED,
            decide("com.netflix.mediaclient", trackUnrecognized = true),
        )
    }

    @Test
    fun `blank package names are unrecognized rather than allowed`() {
        assertEquals(TrackingDecision.UNKNOWN, decide(""))
        assertEquals(TrackingDecision.UNKNOWN, decide("   "))
    }

    // ---- Taxonomy sanity ---------------------------------------------------

    @Test
    fun `never-track list does not collide with the curated allowlist`() {
        val overlap = DefaultMusicApps.NEVER_TRACK_PACKAGES intersect
            DefaultMusicApps.CURATED_TRACKABLE_PACKAGES
        assertTrue("allowlist and never-track lists overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `curated allowlist does not collide with the built-in blocklist`() {
        val overlap = DefaultMusicApps.CURATED_TRACKABLE_PACKAGES intersect
            DefaultMusicApps.BLOCKED_PACKAGES
        assertTrue("allowlist and blocklist overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `curated allowlist does not collide with the long-tail blocklist`() {
        val overlap = DefaultMusicApps.CURATED_TRACKABLE_PACKAGES intersect
            DefaultMusicApps.EXTENDED_BLOCKED_PACKAGES
        assertTrue("allowlist and extended blocklist overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `podcast and audiobook package sets match their info lists`() {
        // shouldFilterContent switched from service-local sets to these, so a mismatch here
        // would silently stop filtering podcasts or audiobooks.
        assertEquals(
            DefaultMusicApps.PODCAST_APPS.map { it.packageName }.toSet(),
            DefaultMusicApps.PODCAST_PACKAGES,
        )
        assertEquals(
            DefaultMusicApps.AUDIOBOOK_APPS.map { it.packageName }.toSet(),
            DefaultMusicApps.AUDIOBOOK_PACKAGES,
        )
    }

    @Test
    fun `no user choices leaves the curated allowlist intact`() {
        // Guards against a refactor that accidentally treats "no DB rows" as "nothing allowed".
        assertEquals(
            TrackingDecision.ALLOW,
            decide(
                "com.spotify.music",
                enabled = noUserChoices,
                blocked = noUserChoices,
                disabled = noUserChoices,
            ),
        )
    }
}
