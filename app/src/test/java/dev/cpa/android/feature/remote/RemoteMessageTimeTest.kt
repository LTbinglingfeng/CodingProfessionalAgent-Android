package dev.cpa.android.feature.remote

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class RemoteMessageTimeTest {
    @Test fun timestampUsesDeviceZoneAndLanguage() {
        val time = Instant.parse("2026-11-18T12:34:00Z").toEpochMilli().toDouble()
        assertEquals("11月18日 20:34", messageTime(time, ZoneId.of("Asia/Shanghai"), Locale.SIMPLIFIED_CHINESE))
        assertEquals("Nov 18, 12:34", messageTime(time, ZoneId.of("UTC"), Locale.US))
    }
    @Test fun absentAndCorruptTimesDoNotShowMisleadingDates() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE).forEach { assertNull(messageTime(it)) }
    }
}
