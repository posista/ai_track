package de.posista.ailimits

import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
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
    SHARED("Übernommener Text oder Datei", "Mittel · von dir geprüft"),
    MANUAL("Manuelle Eingabe", "Abhängig von deiner Eingabe"),
    SCREENSHOT("Bildschirmfoto", "Mittel · von dir geprüft")
}

data class ResetCandidate(
    val provider: Provider,
    val window: LimitWindow,
    val resetAt: Instant,
    val reached: Boolean = false,
    val scope: String = "",
    val relativeFingerprint: String? = null
)

data class UndatedReset(
    val provider: Provider,
    val window: LimitWindow,
    val time: LocalTime,
    val zone: ZoneId,
    val reached: Boolean,
    val scope: String
)

internal fun scopeId(scope: String): String =
    Normalizer.normalize(scope.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

object ResetParser {
    const val MAX_INPUT = 4096
    const val MAX_FILE_INPUT = 65_536
    const val MAX_SCOPE_LENGTH = 64

    private val product = Regex("""\b(?:Claude Code|ChatGPT|Codex|Claude)\b""", RegexOption.IGNORE_CASE)
    private val limit = Regex(
        """\b(?:limits?|nutzungslimits?|wochenlimit|sitzungslimit|kontingent|usage cap|rate limit)\b""", RegexOption.IGNORE_CASE
    )
    private val reset = Regex(
        """\b(?:resets?\s+(?:at|on|in)\s+|try again\s+(?:at|on|in)\s+|resets?\s+(?=\d)|(?:erneut|wieder)\s+(?:ab|um|in|am)\s+|(?:reset|zurückgesetzt)\s+(?:ab|um|in|am)\s+|wird\s+(?:am\s+)?(?=\d{2}\.\d{2}\.\d{4}\b[^\n]*\bzurückgesetzt\b))""",
        RegexOption.IGNORE_CASE
    )
    private val apiContext = Regex(
        """\b(?:api[- ](?:usage|rate|key|token|limit)|(?:requests|tokens) per minute)\b""", RegexOption.IGNORE_CASE
    )
    private val reached = Regex(
        """\b(?:reached|hit|exceeded) (?:\w+ ){0,4}limits?\b|\blimits? (?:reached|exceeded)\b|\b(?:limit|nutzungslimit|kontingent) (?:ist )?(?:erreicht|aufgebraucht)\b""",
        RegexOption.IGNORE_CASE
    )
    private val weekly = Regex("""\b(?:weekly|wöchentlich\p{L}*|wochenlimit|7[ -]?day)\b""")
    private val short = Regex("""\b(?:session|kurzfristig\p{L}*|sitzungslimit|\d+[ -]?(?:hour|stunden))\b""")
    private val modelScope = Regex("""\b(?:fable|opus|sonnet|haiku)\b""")
    private val allModels = Regex("""\b(?:all models|alle modelle)\b""")
    private val dateTime = Regex(
        """^(?:(\d{4}-\d\d-\d\d)[T ]|(\d\d\.\d\d\.\d{4}),?\s+(?:um\s+)?)(\d{1,2}:\d\d(?::\d\d(?:\.\d{1,9})?)?)(?:\s*([ap]\.?m\.?)(?!\p{L}))?(?![\d:])""",
        RegexOption.IGNORE_CASE
    )
    private val clockTime = Regex("""^(\d{1,2})(?::(\d\d))?(?:\s*(am|pm)\b)?(?![\d:.])""", RegexOption.IGNORE_CASE)
    private val zoneToken = Regex(
        """^((?i:(?:UTC|GMT)[+-]\d{2}:?\d{2}|UTC|GMT|Z|PST|PDT|EST|EDT|CST|CDT|MST|MDT|CET|CEST|MEZ|MESZ|BST|IST)|[+-]\d{2}:?\d{2}|[A-Za-z][\w+.-]*(?:/[\w+.-]+)+|[A-Z][A-Z0-9]{0,4})(?![\w/:+-])"""
    )
    private val hours = Regex("""^(\d{1,3})\s*(?:hours?|hrs?|stunden?|std\.?|h)\b""", RegexOption.IGNORE_CASE)
    private val minutes = Regex("""^(\d{1,3})\s*(?:minutes?|mins?|minuten?|m)\b""", RegexOption.IGNORE_CASE)
    private val durationSeparator = Regex("""^\s*,?\s*(?:(?:and|und)\b\s*)?""", RegexOption.IGNORE_CASE)
    private val ambiguousDurationTail = Regex("""^[\s,;:/–—-]*(?:\d|and\b|or\b|und\b|oder\b)""", RegexOption.IGNORE_CASE)
    private val germanDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    private val manualDateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun readShared(text: String, fallback: Provider?, now: Instant, zone: ZoneId): List<ResetCandidate> {
        val input = text.take(MAX_FILE_INPUT)
        val lines = input.lineSequence().mapNotNull { line ->
            parseStructured(line) ?: parseNotice(line, fallback, zone, null)
        }.filter { isFuture(it.resetAt, now) }.toList()
        val candidates = lines.ifEmpty {
            listOfNotNull(parseNotice(input, fallback, zone, null)).filter { isFuture(it.resetAt, now) }
        }
        return candidates.groupBy { Triple(it.provider, it.window, scopeId(it.scope)) }.values.mapNotNull { options ->
            val first = options.first()
            first.takeIf { options.all { item -> item.resetAt == first.resetAt } }
        }
    }

    fun readNotification(text: String, provider: Provider, postedAt: Instant, now: Instant): ResetCandidate? {
        if (postedAt > now) return null
        return parseNotice(text.take(MAX_INPUT), provider, null, postedAt)
            ?.takeIf { isFuture(it.resetAt, now) && (it.provider == provider ||
                provider == Provider.CHATGPT && it.provider == Provider.CODEX ||
                provider == Provider.CLAUDE && it.provider == Provider.CLAUDE_CODE) }
    }

    fun readUndated(text: String, fallback: Provider?, defaultZone: ZoneId): UndatedReset? {
        val notice = notice(text.take(MAX_INPUT), fallback) ?: return null
        if (notice.relative) return null
        val time = clockTime.find(notice.timeText) ?: return null
        val (_, hourText, minuteText, period) = time.groupValues
        if (minuteText.isEmpty() && period.isEmpty()) return null
        val hour = hourText.toInt()
        val adjustedHour = when {
            period.isEmpty() -> hour
            hour !in 1..12 -> return null
            period.equals("pm", true) -> hour % 12 + 12
            else -> hour % 12
        }
        val tail = notice.timeText.substring(time.range.last + 1).trimStart()
        // A malformed or unfinished date must be corrected in the original input.
        if (tail.firstOrNull()?.let { it.isDigit() || it == ':' } == true) return null
        val zone = resolveZone(tail, defaultZone) ?: return null
        val localTime = runCatching { LocalTime.of(adjustedHour, minuteText.toIntOrNull() ?: 0) }.getOrNull()
            ?: return null
        return UndatedReset(notice.provider, notice.window, localTime, zone, notice.reached, notice.scope)
    }

    fun resolveLocal(value: LocalDateTime, zone: ZoneId): Instant? =
        zone.rules.getValidOffsets(value).singleOrNull()?.let(value::toInstant)

    fun parseManual(input: String, zone: ZoneId): Instant? {
        val value = input.trim()
        return parseOffset(value) ?: runCatching { LocalDateTime.parse(value, manualDateTime) }.getOrNull()
            ?.let { resolveLocal(it, zone) }
    }

    fun isFuture(resetAt: Instant, now: Instant) = resetAt > now && resetAt <= now.plus(Duration.ofDays(30))

    fun validScope(scope: String) = scope.length <= MAX_SCOPE_LENGTH &&
        scope.none { it == '|' || it.isISOControl() }

    fun formatLine(candidate: ResetCandidate): String {
        val window = when (candidate.window) {
            LimitWindow.SHORT -> "kurzfristig"
            LimitWindow.WEEKLY -> "wöchentlich"
            LimitWindow.UNKNOWN -> "unbekannt"
        }
        val state = if (candidate.reached) "erreicht" else "geplant"
        return "${candidate.provider.title} | $window | ${candidate.resetAt} | $state" +
            candidate.scope.trim().takeIf(String::isNotEmpty)?.let { " | $it" }.orEmpty()
    }

    private fun parseStructured(line: String): ResetCandidate? {
        val parts = line.split('|').map(String::trim)
        if (parts.size !in 3..5) return null
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
        val scope = parts.getOrNull(4).orEmpty()
        if (!validScope(scope)) return null
        return ResetCandidate(provider, window, parseOffset(parts[2]) ?: return null, hit, scope)
    }

    private data class Notice(
        val provider: Provider, val window: LimitWindow, val scope: String,
        val reached: Boolean, val timeText: String, val relative: Boolean
    )

    private fun notice(message: String, fallback: Provider?): Notice? {
        if (!limit.containsMatchIn(message) || apiContext.containsMatchIn(message)) return null
        val marker = reset.findAll(message).singleOrNull() ?: return null
        val declared = product.findAll(message).map { match ->
            Provider.entries.first { it.title.equals(match.value, true) }
        }.distinct().toList()
        val provider = when (declared.size) {
            0 -> fallback
            1 -> declared.first()
            else -> null
        } ?: return null
        val context = message.take(marker.range.first).lowercase(Locale.ROOT)
        val window = when {
            weekly.containsMatchIn(context) && short.containsMatchIn(context) -> return null
            weekly.containsMatchIn(context) -> LimitWindow.WEEKLY
            short.containsMatchIn(context) -> LimitWindow.SHORT
            else -> LimitWindow.UNKNOWN
        }
        val scope = if (provider == Provider.CLAUDE || provider == Provider.CLAUDE_CODE) {
            when {
                allModels.containsMatchIn(context) -> "Alle Modelle"
                else -> modelScope.findAll(context).map { it.value }.distinct().singleOrNull()
                    ?.replaceFirstChar { it.titlecase(Locale.ROOT) }.orEmpty()
            }
        } else ""
        val timeText = message.substring(marker.range.last + 1).trimStart()
        val relative = marker.value.trimEnd().endsWith(" in", ignoreCase = true)
        return Notice(provider, window, scope, reached.containsMatchIn(message), timeText, relative)
    }

    private fun parseNotice(message: String, fallback: Provider?, zone: ZoneId?, postedAt: Instant?): ResetCandidate? {
        val notice = notice(message, fallback) ?: return null
        val exact = parseDated(notice.timeText, zone)
        if (exact != null) {
            return ResetCandidate(notice.provider, notice.window, exact, notice.reached, notice.scope)
        }
        if (postedAt == null || !notice.relative) return null
        val delay = parseRelative(notice.timeText) ?: return null
        val normalized = message.lowercase(Locale.ROOT).trim().replace(Regex("""\s+"""), " ")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return ResetCandidate(notice.provider, notice.window, postedAt.plus(delay), notice.reached, notice.scope, fingerprint)
    }

    private fun parseDated(input: String, fallback: ZoneId?): Instant? {
        val match = dateTime.find(input) ?: return null
        val (_, isoDate, german, clock, period) = match.groupValues
        val zone = resolveZone(input.substring(match.range.last + 1), fallback) ?: return null
        return runCatching {
            val date = if (isoDate.isEmpty()) LocalDate.parse(german, germanDate) else LocalDate.parse(isoDate)
            val paddedClock = if (clock.indexOf(':') == 1) "0$clock" else clock
            val time = LocalTime.parse(paddedClock)
            val normalizedTime = if (period.isEmpty()) time else {
                if (time.hour !in 1..12) return null
                val hour = time.hour % 12 + if (period.startsWith('p', ignoreCase = true)) 12 else 0
                time.withHour(hour)
            }
            resolveLocal(date.atTime(normalizedTime), zone)
        }.getOrNull()
    }

    private fun resolveZone(suffix: String, fallback: ZoneId?): ZoneId? {
        val tail = suffix.trimStart()
        if (tail.startsWith('(')) {
            val end = tail.indexOf(')')
            if (end < 0) return null
            return parseZoneToken(tail.substring(1, end).trim())
        }
        val token = zoneToken.find(tail)?.value
        if (token != null) return parseZoneToken(token)
        if (tail.startsWith('+') || tail.startsWith('-') ||
            tail.startsWith("UTC+", true) || tail.startsWith("UTC-", true) ||
            tail.startsWith("GMT+", true) || tail.startsWith("GMT-", true)
        ) return null
        return fallback
    }

    private fun parseZoneToken(token: String): ZoneId? = runCatching {
        when {
            token.equals("Z", true) || token.equals("UTC", true) || token.equals("GMT", true) -> ZoneOffset.UTC
            token.startsWith("UTC", true) || token.startsWith("GMT", true) -> ZoneId.of(token.uppercase(Locale.ROOT))
            token.startsWith('+') || token.startsWith('-') -> ZoneOffset.of(token)
            '/' in token -> ZoneId.of(token)
            else -> null
        }
    }.getOrNull()

    private fun parseRelative(text: String): Duration? {
        val hourMatch = hours.find(text)
        val remainder = if (hourMatch == null) text else text.substring(hourMatch.range.last + 1)
        val separator = if (hourMatch == null) "" else durationSeparator.find(remainder)?.value.orEmpty()
        val minuteMatch = minutes.find(remainder.substring(separator.length))
        val tail = if (minuteMatch == null) remainder else remainder.substring(separator.length + minuteMatch.range.last + 1)
        val total = (hourMatch?.groupValues?.get(1)?.toLong() ?: 0) * 60 +
            (minuteMatch?.groupValues?.get(1)?.toLong() ?: 0)
        return if (total == 0L || ambiguousDurationTail.containsMatchIn(tail)) null else Duration.ofMinutes(total)
    }

    private fun parseOffset(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant() }.getOrNull()
}
