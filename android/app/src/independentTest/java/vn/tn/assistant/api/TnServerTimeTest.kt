package vn.tn.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TnServerTimeTest {
    @Test fun parsesRfc3339UtcOffsetsAndFractionalPrecision() {
        assertEquals(0L, serverTimeMillis("1970-01-01T00:00:00Z"))
        assertEquals(0L, serverTimeMillis("1970-01-01T01:00:00+01:00"))
        assertEquals(0L, serverTimeMillis("1969-12-31T19:00:00-05:00"))
        assertEquals(123L, serverTimeMillis("1970-01-01T00:00:00.123456789Z"))
        assertEquals(1L, serverTimeMillis("1970-01-01T00:00:00.001Z"))
    }

    @Test fun parsesGoRfc3339NanoCalendarAndFractionForms() {
        assertEquals(123L, serverTimeMillis("1970-01-01T00:00:00,123456789012Z"))
        assertEquals(-62_167_219_200_000L, serverTimeMillis("0000-01-01T00:00:00Z"))
        assertEquals(-12_219_724_800_000L, serverTimeMillis("1582-10-10T00:00:00Z"))
        assertEquals(0L, serverTimeMillis("2024-01-01T00:00:00+23:59")?.minus(serverTimeMillis("2023-12-31T00:01:00Z")!!))
    }

    @Test fun rejectsInvalidDatesTimesAndOffsets() {
        listOf(
            "2023-02-29T00:00:00Z", "2024-13-01T00:00:00Z", "2024-01-01T24:00:00Z",
            "2024-01-01T00:60:00Z", "2024-01-01T00:00:60Z",
            "2024-01-01T00:00:00-00:60", "2024-01-01T00:00:00+24:00",
            "2024-01-01 00:00:00Z",
        ).forEach { assertNull("Should reject $it", serverTimeMillis(it)) }
    }
}
