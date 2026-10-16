package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Verifies the device-zone reads behind the timezone-aware bucketing.
 *
 * The offset is resolved from the zone rules at the *instant* of the play, so a play recorded last
 * winter keeps its winter offset after the clocks change -- which is exactly what the per-row
 * column stores and what the migration backfills.
 */
class DeviceTimeZoneTest {
    @Test
    fun `offsetMinutesAt follows the rules of the instant, DST included`() {
        val london = ZoneId.of("Europe/London")
        val january = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()
        val july = Instant.parse("2024-07-15T12:00:00Z").toEpochMilli()

        assertEquals(0, DeviceTimeZone.offsetMinutesAt(january, london))
        assertEquals(60, DeviceTimeZone.offsetMinutesAt(july, london))
    }

    @Test
    fun `offsetMinutesAt keeps half-hour and quarter-hour zones exact`() {
        val kolkata = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()
        val kathmandu = Instant.parse("2024-07-15T12:00:00Z").toEpochMilli()

        assertEquals(330, DeviceTimeZone.offsetMinutesAt(kolkata, ZoneId.of("Asia/Kolkata")))
        assertEquals(345, DeviceTimeZone.offsetMinutesAt(kathmandu, ZoneId.of("Asia/Kathmandu")))
    }

    @Test
    fun `todayIn matches the date in the recorded zone`() {
        listOf(0, -300, -480, 330, 345, 420, 780).forEach { offset ->
            assertEquals(
                "offset $offset",
                LocalDate.now(ZoneOffset.ofTotalSeconds(offset * 60)),
                DeviceTimeZone.todayIn(offset),
            )
        }
    }

    @Test
    fun `todayIn falls back to the device zone when the offset is missing or impossible`() {
        assertEquals(LocalDate.now(), DeviceTimeZone.todayIn(null))
        // Beyond any real UTC offset, and beyond ZoneOffset's own +/-18h range: never throw inside
        // streak maths just because a row carries nonsense.
        assertEquals(LocalDate.now(), DeviceTimeZone.todayIn(100_000))
        assertEquals(LocalDate.now(), DeviceTimeZone.todayIn(-100_000))
    }

    @Test
    fun `zoneOffsetOf rejects impossible offsets`() {
        assertNull(DeviceTimeZone.zoneOffsetOf(null))
        assertNull(DeviceTimeZone.zoneOffsetOf(100_000))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), DeviceTimeZone.zoneOffsetOf(330))
    }
}
