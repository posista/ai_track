package de.posista.ailimits

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal object UsageScan {
    private val shortHeading = Regex(
        """^(?:current session|aktuelle sitzung|(?:5[ -]?(?:hours?|stunden)|five[ -]hour)[ -](?:usage[ -])?limits?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val weeklyHeading = Regex(
        """^(?:weekly(?: usage)? limits?|wöchentliche(?:s|n)? (?:nutzungs)?limits?|wochenlimits?|7[ -]day(?: usage)? limits?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val otherSection = Regex(
        """^(?:extra usage|additional usage|usage credits|app limits|billing|zusätzliche nutzung|mehrnutzung|nutzungsguthaben|app-limits|abrechnung)\b""",
        RegexOption.IGNORE_CASE
    )
    private val scopeHeading = Regex(
        """^(all models|alle modelle|fable|opus|sonnet|haiku)(?:\s+(?:only|models?|nur|modell|modelle))?\s*[:·]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val resetLine = Regex(
        """^(?:resets?\b|(?:nächster )?reset\b|zurücksetzung\b|(?:wird|zurückgesetzt)(?=\s+(?:in|am|um|ab)\b))\s*[:·-]?\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val positioning = Regex("""^(?:on|at|am|um|ab)\s+""", RegexOption.IGNORE_CASE)
    private val weekday = Regex(
        """^(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|mo|di|mi|do|fr|sa|so)\.?[,\s]+(?:at\s+|um\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val days = listOf(
        setOf("mon", "monday", "mo", "montag"),
        setOf("tue", "tuesday", "di", "dienstag"),
        setOf("wed", "wednesday", "mi", "mittwoch"),
        setOf("thu", "thursday", "do", "donnerstag"),
        setOf("fri", "friday", "fr", "freitag"),
        setOf("sat", "saturday", "sa", "samstag"),
        setOf("sun", "sunday", "so", "sonntag")
    )

    fun read(
        text: String, provider: Provider, now: Instant, zone: ZoneId?, capturedAt: Instant? = null
    ): List<ResetCandidate> {
        if (provider != Provider.CHATGPT && provider != Provider.CLAUDE) return emptyList()
        val input = text.take(ResetParser.MAX_INPUT)
        val candidates = zone?.let {
            ResetParser.readShared(input, provider, now, it).filter { candidate -> candidate.provider == provider }
        }.orEmpty().toMutableList()
        var window: LimitWindow? = null
        var scope = ""
        for (original in input.lineSequence()) {
            val line = original.trim().replace('\u00A0', ' ').replace('\u2011', '-').replace('\u2013', '-')
            when {
                otherSection.containsMatchIn(line) -> {
                    window = null
                    scope = ""
                }
                shortHeading.containsMatchIn(line) -> {
                    window = LimitWindow.SHORT
                    scope = ""
                }
                weeklyHeading.containsMatchIn(line) -> {
                    window = LimitWindow.WEEKLY
                    scope = ""
                }
                window == LimitWindow.WEEKLY && provider == Provider.CLAUDE -> {
                    scopeHeading.matchEntire(line)?.let { match ->
                        val name = match.groupValues[1].lowercase(Locale.ROOT)
                        scope = if (name in setOf("all models", "alle modelle")) "Alle Modelle"
                        else name.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    }
                }
            }
            val activeWindow = window ?: continue
            val marker = resetLine.matchEntire(line) ?: continue
            val value = positioning.replaceFirst(marker.groupValues[1], "").removeSuffix(" zurückgesetzt").trim()
            val reset = readTime(value, provider, now, zone, capturedAt) ?: continue
            candidates.add(reset.copy(window = activeWindow, scope = scope))
        }
        return candidates.groupBy { it.provider to (it.window to scopeId(it.scope)) }.values.mapNotNull { options ->
            options.firstOrNull()?.takeIf { first -> options.all { it.resetAt == first.resetAt } }
        }
    }

    private fun readTime(
        value: String, provider: Provider, now: Instant, zone: ZoneId?, capturedAt: Instant?
    ): ResetCandidate? {
        val prefix = "${provider.title} usage limit resets"
        if (zone != null) {
            ResetParser.readShared("$prefix at $value", provider, now, zone).singleOrNull()?.let { return it }
        } else if (capturedAt != null) {
            ResetParser.readNotification("$prefix at $value", provider, capturedAt, now)?.let { return it }
        }
        if (capturedAt == null || capturedAt > now) return null
        if (value.startsWith("in ", ignoreCase = true)) {
            return ResetParser.readNotification("$prefix $value", provider, capturedAt, now)
        }

        if (zone == null) return null
        val weekdayMatch = weekday.matchEntire(value) ?: return null
        val day = DayOfWeek.of(days.indexOfFirst { weekdayMatch.groupValues[1].lowercase(Locale.ROOT) in it } + 1)
        val draft = ResetParser.readUndated("$prefix at ${weekdayMatch.groupValues[2]}", provider, zone) ?: return null
        val localCapture = capturedAt.atZone(draft.zone)
        var date = localCapture.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
        if (!date.atTime(draft.time).isAfter(localCapture.toLocalDateTime())) date = date.plusWeeks(1)
        val resetAt = ResetParser.resolveLocal(date.atTime(draft.time), draft.zone) ?: return null
        return ResetCandidate(provider, LimitWindow.UNKNOWN, resetAt).takeIf { ResetParser.isFuture(resetAt, now) }
    }
}
