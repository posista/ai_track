package de.posista.ailimits

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageScanTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun importsTwoClaudeWeeklyQuotasWithExplicitOffsets() {
        val screenshot = """
            Usage
            Weekly limits
            All models
            33% used
            Resets 2026-11-22 10:00 UTC
            Fable
            20% used
            Resets 2026-11-23 7:00 PM +01:00
            Extra usage
            Resets 2026-12-01 7:00 PM +01:00
        """.trimIndent()
        val result = UsageScan.read(screenshot, Provider.CLAUDE, now, berlin)
        assertEquals(listOf("Alle Modelle", "Fable"), result.map { it.scope })
        assertEquals(listOf(Instant.parse("2026-11-22T10:00:00Z"), Instant.parse("2026-11-23T18:00:00Z")),
            result.map { it.resetAt })
        assertTrue(result.all { it.window == LimitWindow.WEEKLY })
    }

    @Test fun relativeTimeAndWeekdayRequireAFreshScreenshot() {
        val screenshot = """
            Current session
            Resets in 2 hours, 15 minutes
            Weekly limits
            Resets Fri, 4:30 PM
        """.trimIndent()
        assertTrue(UsageScan.read(screenshot, Provider.CHATGPT, now, berlin).isEmpty())
        val result = UsageScan.read(screenshot, Provider.CHATGPT, now, berlin, now)
        assertEquals(listOf(LimitWindow.SHORT, LimitWindow.WEEKLY), result.map { it.window })
        assertEquals(listOf(Instant.parse("2026-11-19T14:15:00Z"), Instant.parse("2026-11-20T15:30:00Z")),
            result.map { it.resetAt })
    }

    @Test fun doesNotGuessCalendarDateOrAcceptTextWithoutALimitHeading() {
        assertTrue(UsageScan.read("Resets in 2 hours", Provider.CLAUDE, now, berlin, now).isEmpty())
        assertTrue(UsageScan.read("Current session\nResets 4:30 PM", Provider.CLAUDE, now, berlin, now).isEmpty())
        assertTrue(UsageScan.read("Current session\nResets Thu, 13:00", Provider.CLAUDE, now, berlin, now).isNotEmpty())
        assertEquals(Instant.parse("2026-11-26T12:00:00Z"),
            UsageScan.read("Current session\nResets Thu, 13:00", Provider.CLAUDE, now, berlin, now).single().resetAt)
    }

    @Test fun automaticNotificationRequiresAnExplicitZoneOrARelativeTime() {
        assertTrue(UsageScan.read("Weekly limits\nResets 2026-11-22 10:00", Provider.CLAUDE,
            now, zone = null, capturedAt = now).isEmpty())
        val relative = UsageScan.read("Aktuelle Sitzung\nWird in 2 Stunden zurückgesetzt", Provider.CLAUDE,
            now, zone = null, capturedAt = now).single()
        assertEquals(now.plusSeconds(7200), relative.resetAt)
        assertTrue(relative.relativeFingerprint != null)
    }
}
