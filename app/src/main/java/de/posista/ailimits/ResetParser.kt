package de.posista.ailimits

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Provider(val title: String) {
    CHATGPT("ChatGPT"), CODEX("Codex"), CLAUDE("Claude"), CLAUDE_CODE("Claude Code");

    companion object {
        fun fromPackage(packageName: String): Provider? = when (packageName) {
            "com.openai.chatgpt" -> CHATGPT
            "com.anthropic.claude" -> CLAUDE
            else -> null
        }
    }
}

enum class LimitWindow(val title: String) {
    SHORT("Kurzfristig"), WEEKLY("Wöchentlich"), UNKNOWN("Fenster unbekannt")
}

enum class Source(val title: String, val confidence: String) {
    NOTIFICATION("Android-Mitteilung des Anbieters", "Mittel · automatisch erkannt"),
    SHARED("Übernommener Text", "Mittel · von dir geprüft"),
    MANUAL("Manuelle Eingabe", "Abhängig von deiner Eingabe")
}

data class ResetCandidate(
    val provider: Provider,
    val window: LimitWindow,
    val resetAt: Instant,
    val reached: Boolean = false
)

object ResetParser {
    const val MAX_INPUT = 4096

    private val product = Regex("""\b(?:Claude Code|ChatGPT|Codex|Claude)\b""", RegexOption.IGNORE_CASE)
    private val limit = Regex(
        """\b(?:limits?|nutzungslimits?|wochenlimit|sitzungslimit|kontingent|usage cap|rate limit)\b""", RegexOption.IGNORE_CASE
    )
    private val reset = Regex(
        """\b(?:resets?\s+(?:at|on|in)|try again\s+(?:at|on|in)|resets?\s*(?=\d)|(?:erneut|wieder)\s+(?:ab|um|in|am)|(?:reset|zurückgesetzt)\s+(?:ab|um|in|am))\b""",
        RegexOption.IGNORE_CASE
    )
    private val apiContext = Regex("""\b(?:api[- ](?:usage|rate|key|token|limit)|(?:requests|tokens) per minute)\b""", RegexOption.IGNORE_CASE)
    private val reached = Regex(
        """\b(?:reached|hit|exceeded) (?:\w+ ){0,4}limits?\b|\blimits? (?:reached|exceeded)\b|\b(?:limit|nutzungslimit|kontingent) (?:ist )?(?:erreicht|aufgebraucht)\b""",
        RegexOption.IGNORE_CASE
    )
    private val weekly = Regex("""\b(?:weekly|wöchentlich\p{L}*|wochenlimit|7[ -]?day)\b""")
    private val short = Regex("""\b(?:session|kurzfristig\p{L}*|sitzungslimit|(?:\d+)[ -]?(?:hour|stunden))\b""")
    private val offsetTimestamp = Regex(
        """\b\d{4}-\d\d-\d\d[T ]\d\d:\d\d(?::\d\d(?:\.\d{1,9})?)?(?:Z|[+-]\d\d:\d\d)(?![\w:+-])"""
    )
    private val localTimestamp = Regex(
        """^(?:(\d{4}-\d\d-\d\d)[T ]|(\d\d\.\d\d\.\d{4}),?\s+)(\d\d:\d\d)(?![\w:+-]|\s*[+-]\d)(?:\s*\(([^)]+)\))?"""
    )
    private val relative = Regex(
        """(?i)^\s*(?:(\d{1,3})\s*(?:hours?|hrs?|h|stunden?|std\.?)(?![\p{L}\d]))?\s*(?:and\s+|und\s+)?(?:(\d{1,3})\s*(?:minutes?|mins?|m|minuten?)(?![\p{L}\d]))?"""
    )

    fun readShared(text: String, fallback: Provider?, now: Instant, zone: ZoneId): List<ResetCandidate> {
        val input = text.take(MAX_INPUT)
        val lines = input.lineSequence().mapNotNull { line ->
            parseStructured(line) ?: parseNotice(line, fallback, now, zone, null)
        }.filter { isFuture(it.resetAt, now) }.toList()
        val candidates = lines.ifEmpty {
            listOfNotNull(parseNotice(input, fallback, now, zone, null))
                .filter { isFuture(it.resetAt, now) }
        }
        return candidates.groupBy { it.provider to it.window }.values.mapNotNull { options ->
            val first = options.first()
            first.takeIf { options.all { item -> item.resetAt == first.resetAt } }
        }
    }

    fun readNotification(
        text: String, provider: Provider, postedAt: Instant, now: Instant
    ): ResetCandidate? =
        parseNotice(text.take(MAX_INPUT), provider, now, null, postedAt)
            ?.takeIf { isFuture(it.resetAt, now) && (it.provider == provider ||
                provider == Provider.CHATGPT && it.provider == Provider.CODEX ||
                provider == Provider.CLAUDE && it.provider == Provider.CLAUDE_CODE) }

    fun parseManual(input: String, zone: ZoneId): Instant? {
        val value = input.trim()
        return parseOffset(value) ?: runCatching {
            val dateTime = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))
            val offset = zone.rules.getValidOffsets(dateTime).single()
            dateTime.toInstant(offset)
        }.getOrNull()
    }

    fun isFuture(resetAt: Instant, now: Instant) = resetAt > now && resetAt <= now.plus(Duration.ofDays(30))

    private fun parseStructured(line: String): ResetCandidate? {
        val parts = line.split('|').map(String::trim)
        if (parts.size !in 3..4) return null
        val provider = Provider.entries.singleOrNull { it.title.equals(parts[0], true) } ?: return null
        val window = when (parts[1].lowercase(Locale.ROOT)) {
            "kurzfristig", "short", "session" -> LimitWindow.SHORT
            "wöchentlich", "weekly" -> LimitWindow.WEEKLY
            "unbekannt", "unknown" -> LimitWindow.UNKNOWN
            else -> return null
        }
        val hit = when (parts.getOrNull(3)?.lowercase(Locale.ROOT)) {
            null, "geplant", "scheduled" -> false
            "erreicht", "reached" -> true
            else -> return null
        }
        return ResetCandidate(provider, window, parseOffset(parts[2]) ?: return null, hit)
    }

    private fun parseNotice(
        message: String, fallback: Provider?, now: Instant, zone: ZoneId?, postedAt: Instant?
    ): ResetCandidate? {
        if (!limit.containsMatchIn(message) || apiContext.containsMatchIn(message)) return null
        val marker = reset.findAll(message).singleOrNull() ?: return null
        val declared = product.findAll(message).map { match ->
            Provider.entries.first { it.title.equals(match.value, true) }
        }.distinct().toList()
        val provider = when {
            declared.isEmpty() -> fallback
            declared.size == 1 -> declared.first()
            else -> null
        } ?: return null
        val context = message.take(marker.range.first).lowercase(Locale.ROOT)
        val window = when {
            weekly.containsMatchIn(context) && short.containsMatchIn(context) -> return null
            weekly.containsMatchIn(context) -> LimitWindow.WEEKLY
            short.containsMatchIn(context) -> LimitWindow.SHORT
            else -> LimitWindow.UNKNOWN
        }
        val timeText = message.substring(marker.range.last + 1).trimStart()
        val exact = offsetTimestamp.find(timeText)?.takeIf { it.range.first == 0 }
            ?.let { parseOffset(it.value) }
        val local = localTimestamp.find(timeText)?.let { timestamp ->
            val (_, isoDay, germanDay, clock, zoneId) = timestamp.groupValues
            val date = isoDay.ifEmpty { germanDay.split('.').asReversed().joinToString("-") }
            val explicitZone = zoneId.takeIf { '/' in it || it == "UTC" }
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            val resolvedZone = if (zoneId.isEmpty()) zone else explicitZone
            resolvedZone?.let { parseManual("$date $clock", it) }
        }
        val until = exact ?: local ?: if (postedAt != null && postedAt <= now &&
            marker.value.endsWith(" in", ignoreCase = true)
        ) parseRelative(timeText, postedAt) else null
        return ResetCandidate(provider, window, until ?: return null, reached.containsMatchIn(message))
    }

    private fun parseRelative(text: String, postedAt: Instant): Instant? {
        val match = relative.find(text) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLongOrNull() ?: 0
        val tail = text.substring(match.range.last + 1).trimStart()
        val total = hours * 60 + minutes
        if (total == 0L || tail.firstOrNull()?.isDigit() == true) return null
        return postedAt.plusSeconds(total * 60)
    }

    private fun parseOffset(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant() }.getOrNull()
}
