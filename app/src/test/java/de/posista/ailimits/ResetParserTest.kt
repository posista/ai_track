package de.posista.ailimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ResetParserTest {
    private val now = Instant.parse("2026-03-27T10:00:00Z")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun importsIndependentWindowsWithExplicitOffset() {
        val input = """
            Codex | kurzfristig | 2026-03-27T15:00:00+01:00 | erreicht
            Claude Code | weekly | 2026-04-01T17:15Z
        """.trimIndent()
        val result = ResetParser.readShared(input, null, now, berlin)
        assertEquals(listOf(
            ResetCandidate(Provider.CODEX, LimitWindow.SHORT, Instant.parse("2026-03-27T14:00:00Z"), true),
            ResetCandidate(Provider.CLAUDE_CODE, LimitWindow.WEEKLY, Instant.parse("2026-04-01T17:15:00Z"))
        ), result)
    }

    @Test fun usesPostingTimeForRelativeNotice() {
        val posted = now.minusSeconds(30 * 60)
        val result = ResetParser.readNotification(
            "Your 5-hour limit reached — resets in 2 hours and 15 minutes", Provider.CLAUDE, posted, now
        )
        assertEquals(ResetCandidate(Provider.CLAUDE, LimitWindow.SHORT, now.plusSeconds(105 * 60), true), result?.copy(relativeFingerprint = null))
    }

    @Test fun recognizesExplicitLimitWithinProviderFamily() {
        val result = ResetParser.readNotification(
            "Codex weekly usage limit resets at 2026-03-27T15:00:00+01:00", Provider.CHATGPT, now, now
        )
        assertEquals(Provider.CODEX, result?.provider)
        assertEquals(LimitWindow.WEEKLY, result?.window)
        assertFalse(result!!.reached)
        assertNull(ResetParser.readNotification(
            "Claude weekly usage limit resets at 2026-03-27T15:00:00+01:00", Provider.CHATGPT, now, now
        ))
    }

    @Test fun usesSelectedServiceAndLocalZoneInConfirmedImport() {
        assertEquals(ResetCandidate(Provider.CLAUDE, LimitWindow.UNKNOWN,
            Instant.parse("2026-03-27T14:00:00Z")),
            ResetParser.readShared("Usage limit resets at 2026-03-27 15:00", Provider.CLAUDE, now, berlin).single())
    }

    @Test fun acceptsFullGermanDateWithExplicitIanaZone() {
        val result = ResetParser.readNotification(
            "Wöchentliches Nutzungslimit erreicht. Wieder ab 29.03.2026, 03:30 (Europe/Berlin)",
            Provider.CLAUDE, now, now
        )
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), result?.resetAt)
        assertEquals(LimitWindow.WEEKLY, result?.window)
        assertNull(ResetParser.readNotification(
            "Limit resets at 2026-03-29 03:30 (PST)", Provider.CLAUDE, now, now
        ))
    }

    @Test fun rejectsPlainClockTimesRelativeSharesAndUnrelatedMessages() {
        assertTrue(ResetParser.readShared("Claude weekly limit resets in 2 hours", null, now, berlin).isEmpty())
        assertTrue(ResetParser.readShared("Claude limit resets at 4:30pm", null, now, berlin).isEmpty())
        assertNull(ResetParser.readNotification(
            "API rate limit resets at 2026-03-27T13:00Z", Provider.CHATGPT, now, now
        ))
        assertNull(ResetParser.readNotification("Task reminder at 2026-03-27T13:00Z", Provider.CHATGPT, now, now))
        assertNull(ResetParser.readNotification(
            "Weekly limit resets at 2026-03-27 15:00", Provider.CLAUDE, now, now
        ))
        assertNull(ResetParser.readNotification("Limit resets in 10 min", Provider.CLAUDE, now.minusSeconds(900), now))
    }

    @Test fun rejectsConflictingSameWindowDates() {
        val input = "Codex | weekly | 2026-03-28T12:00Z\nCodex | weekly | 2026-03-29T12:00Z"
        assertTrue(ResetParser.readShared(input, null, now, berlin).isEmpty())
    }

    @Test fun handlesDstAndRequiresOffsetsForAmbiguousOrInvalidLocalTimes() {
        assertNull(ResetParser.parseManual("2026-03-29 02:30", berlin))
        assertNull(ResetParser.parseManual("2026-10-25 02:30", berlin))
        assertNull(ResetParser.parseManual("2026-02-30 12:00", berlin))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"),
            ResetParser.parseManual("2026-10-25T02:30+01:00", berlin))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), ResetParser.parseManual("2026-03-29 03:30", berlin))
    }

    @Test fun parsesCompleteDurationAndRejectsAlternatives() {
        val input = "Weekly usage limit reached. Resets in 2 hours, 15 minutes"
        val first = ResetParser.readNotification(input, Provider.CLAUDE, now, now)!!
        assertEquals(now.plusSeconds(135 * 60), first.resetAt)
        val repost = ResetParser.readNotification(input, Provider.CLAUDE, now.plusSeconds(60), now.plusSeconds(60))!!
        assertEquals(first.relativeFingerprint, repost.relativeFingerprint)
        assertTrue(first.relativeFingerprint!!.length == 64)
        assertNull(ResetParser.readNotification(
            "Weekly usage limit resets in 2 hours or tomorrow", Provider.CLAUDE, now, now
        ))
        assertNull(ResetParser.readNotification(
            "Weekly usage limit resets in 2 hours, 15 seconds", Provider.CLAUDE, now, now
        ))
    }

    @Test fun givesExplicitZonePriorityOverDeviceZone() {
        val message = "Claude weekly usage limit resets at 2026-03-28 18:00 UTC"
        val reset = Instant.parse("2026-03-28T18:00:00Z")
        assertEquals(reset, ResetParser.readShared(message, null, now, berlin).single().resetAt)
        assertEquals(reset, ResetParser.readNotification(message, Provider.CLAUDE, now, now)?.resetAt)
        assertEquals(Instant.parse("2026-03-28T16:00:00Z"), ResetParser.readShared(
            message.replace("UTC", "+02:00"), null, now, berlin
        ).single().resetAt)
        assertTrue(ResetParser.readShared(message.replace("UTC", "PST"), null, now, berlin).isEmpty())
    }

    @Test fun understandsGermanMeldungAndProposesOnlyTheTimeForAnUndatedNotice() {
        val german = "Wöchentliches Nutzungslimit erreicht. Es wird am 28.03.2026 um 18:00 zurückgesetzt."
        assertEquals(Instant.parse("2026-03-28T17:00:00Z"),
            ResetParser.readShared(german, Provider.CLAUDE, now, berlin).single().resetAt)
        val text = "Usage limit reached · limit resets 3:45pm (Europe/London)"
        assertTrue(ResetParser.readShared(text, Provider.CLAUDE_CODE, now, berlin).isEmpty())
        val draft = ResetParser.readUndated(text, Provider.CLAUDE_CODE, berlin)!!
        assertEquals(java.time.LocalTime.of(15, 45), draft.time)
        assertEquals(ZoneId.of("Europe/London"), draft.zone)
        assertTrue(draft.reached)
    }

    @Test fun roundTripsNamedWindowsThroughResetFile() {
        val original = listOf(
            ResetCandidate(Provider.CLAUDE, LimitWindow.WEEKLY, now.plusSeconds(1800), scope = "Alle Modelle"),
            ResetCandidate(Provider.CLAUDE, LimitWindow.WEEKLY, now.plusSeconds(3600), true, "Fable")
        )
        val file = java.io.ByteArrayOutputStream()
        writeResetFile(file, original)
        val restored = ResetParser.readShared(readResetFile(file.toByteArray().inputStream()), null, now, berlin)
        assertEquals(original, restored)
    }

    @Test fun computesCountdownFromInstantsAcrossDst() {
        val beforeClockJump = ResetParser.parseManual("2026-03-29 01:45", berlin)!!
        val afterClockJump = ResetParser.parseManual("2026-03-29 03:45", berlin)!!
        assertEquals("1 Std.", remainingTime(afterClockJump, beforeClockJump))
        assertEquals("< 1 Min.", remainingTime(afterClockJump, afterClockJump.minusSeconds(30)))
        assertEquals("Fällig", remainingTime(afterClockJump, afterClockJump))
        assertEquals("1 T. 1 Std. 2 Min.", remainingTime(now.plusSeconds(90120), now))
    }
}
