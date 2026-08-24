package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the List<String> column converter, in particular the repair of
 * legacy/corrupted column values (artists.genres, enriched_metadata.tags/.genres).
 */
class ConvertersTest {

    // Normal delimited values

    @Test
    fun `delimited value round-trips`() {
        val stored = Converters.fromStringList(listOf("jazz", "cool jazz"))
        assertEquals("jazz|||cool jazz", stored)
        assertEquals(listOf("jazz", "cool jazz"), Converters.toStringList(stored))
    }

    @Test
    fun `null and empty values read as empty list`() {
        assertEquals(emptyList<String>(), Converters.toStringList(null))
        assertEquals(emptyList<String>(), Converters.toStringList(""))
    }

    @Test
    fun `empty list and null write as empty string`() {
        assertEquals("", Converters.fromStringList(emptyList()))
        assertEquals("", Converters.fromStringList(null))
    }

    // Whitespace / blank-segment repair

    @Test
    fun `whitespace noise around delimiter is repaired on read`() {
        assertEquals(
            listOf("jazz", "cool jazz"),
            Converters.toStringList(" jazz ||| cool jazz |||")
        )
    }

    @Test
    fun `blank segments are dropped on read`() {
        assertEquals(listOf("a", "b"), Converters.toStringList("a||||||b"))
    }

    @Test
    fun `elements are trimmed and blanks dropped on write`() {
        assertEquals("a|||b", Converters.fromStringList(listOf(" a ", "", "b")))
    }

    // Legacy JSON-array column values

    @Test
    fun `legacy json array column value is repaired into elements`() {
        // Old builds wrote the Moshi/JSON serialization of the list straight into
        // the TEXT column; the old converter read it back as ONE element containing
        // the whole JSON string.
        assertEquals(
            listOf("jazz", "cool jazz"),
            Converters.toStringList("[\"jazz\", \"cool jazz\"]")
        )
    }

    @Test
    fun `legacy json array with single element is repaired`() {
        assertEquals(listOf("pop"), Converters.toStringList("[\"pop\"]"))
    }

    @Test
    fun `empty legacy json array reads as empty list`() {
        assertEquals(emptyList<String>(), Converters.toStringList("[]"))
    }

    @Test
    fun `legacy json array escapes are folded`() {
        assertEquals(
            listOf("hip hop", "90's"),
            Converters.toStringList("[\"hip hop\", \"90's\"]")
        )
    }

    @Test
    fun `legacy json array null items are dropped`() {
        assertEquals(listOf("rock"), Converters.toStringList("[\"rock\", null]"))
    }

    // Non-JSON bracketed values must NOT be mangled

    @Test
    fun `bracketed genre name without quotes survives untouched`() {
        assertEquals(listOf("[abstract]"), Converters.toStringList("[abstract]"))
    }

    @Test
    fun `unterminated bracketed value survives untouched`() {
        assertEquals(listOf("[\"jazz"), Converters.toStringList("[\"jazz"))
    }

    // Full self-heal round trip

    @Test
    fun `corrupted column value heals to canonical delimited form on rewrite`() {
        // Read the corrupted value, write it back: the stored form is now clean.
        val repaired = Converters.toStringList("[\"jazz\", \"cool jazz\"]")
        assertEquals("jazz|||cool jazz", Converters.fromStringList(repaired))
    }
}
