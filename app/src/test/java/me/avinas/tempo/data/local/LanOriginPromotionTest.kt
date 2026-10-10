package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * SQLite regression fixture for the two Room UPDATE statements which
 * reconcile an unverified LAN capture with the same verified Drive origin.
 */
class LanOriginPromotionTest {
    @Test fun `verified Drive origin adopts exact LAN capture and its alias only`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE listening_events (" +
                    "id INTEGER PRIMARY KEY, source TEXT NOT NULL, " +
                    "content_fingerprint TEXT, drive_account_subject TEXT)")
                sql.execute("CREATE TABLE listening_event_origins (" +
                    "accountSubject TEXT NOT NULL, originEventId TEXT NOT NULL, " +
                    "listeningEventId INTEGER NOT NULL, sourceDeviceId TEXT NOT NULL, " +
                    "PRIMARY KEY(accountSubject, originEventId))")
                val origin = "a".repeat(64)
                val fingerprint = "drive:v1:$origin"
                sql.execute("INSERT INTO listening_events VALUES " +
                    "(1, 'lan:desktop-one:desktop:spotify', '$fingerprint', 'lan-unverified')," +
                    "(2, 'lan:other:desktop:spotify', 'drive:v1:${"b".repeat(64)}', 'lan-unverified')," +
                    "(3, 'drive:desktop-one:desktop:spotify', '$fingerprint', 'google-a')")
                sql.execute("INSERT INTO listening_event_origins VALUES " +
                    "('lan-unverified', '$origin', 1, 'desktop-one')")
                // Same producer event in A should not prevent attribution to B,
                // nor should this operation reassign A's already verified row.
                val count = db.prepareStatement(
                    "UPDATE listening_events SET drive_account_subject = ? " +
                        "WHERE source LIKE 'lan:%' AND drive_account_subject = 'lan-unverified' " +
                        "AND content_fingerprint IN (?) AND NOT EXISTS (" +
                        "SELECT 1 FROM listening_events owned " +
                        "WHERE owned.drive_account_subject = ? " +
                        "AND owned.content_fingerprint = listening_events.content_fingerprint)"
                ).use { stmt ->
                    stmt.setString(1, "google-b")
                    stmt.setString(2, fingerprint)
                    stmt.setString(3, "google-b")
                    stmt.executeUpdate()
                }
                assertEquals(1, count)
                db.prepareStatement(
                    "UPDATE listening_event_origins SET accountSubject = ? " +
                        "WHERE accountSubject = 'lan-unverified' AND listeningEventId IN (" +
                        "SELECT id FROM listening_events WHERE drive_account_subject = ? " +
                        "AND source LIKE 'lan:%' AND content_fingerprint IN (?)) " +
                        "AND NOT EXISTS (SELECT 1 FROM listening_event_origins claimed " +
                        "WHERE claimed.accountSubject = ? " +
                        "AND claimed.originEventId = listening_event_origins.originEventId)"
                ).use { stmt ->
                    stmt.setString(1, "google-b")
                    stmt.setString(2, "google-b")
                    stmt.setString(3, fingerprint)
                    stmt.setString(4, "google-b")
                    assertEquals(1, stmt.executeUpdate())
                }
                sql.executeQuery("SELECT id, drive_account_subject FROM listening_events ORDER BY id").use { rows ->
                    val owners = mutableListOf<String>()
                    while (rows.next()) owners += "${rows.getInt(1)}:${rows.getString(2)}"
                    assertEquals(listOf("1:google-b", "2:lan-unverified", "3:google-a"), owners)
                }
                sql.executeQuery("SELECT accountSubject FROM listening_event_origins WHERE originEventId = '$origin'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("google-b", rows.getString(1))
                }
            }
        }
    }
}
