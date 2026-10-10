package dev.local.record.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Inclusive ISO dates are interpreted in each source's recorded time zone. */
data class HistoryQuery(val query: String = "", val fromDate: String = "", val throughDate: String = "", val project: String = "")

/** contentId identifies one immutable retained body; ownerId identifies its turn or recording. */
data class HistoryHit(val contentId: String, val ownerId: String, val origin: String, val text: String, val observedAt: Long, val zoneId: String, val project: String = "")

internal fun historyTerms(query: String): List<String> = Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+")
    .findAll(query.lowercase(Locale.ROOT)).flatMap { match ->
        if (match.value.first() in '\u4e00'..'\u9fff' && match.value.length > 1) match.value.windowed(2).asSequence() else sequenceOf(match.value)
    }.distinct().toList()

internal fun selectHistoryHits(sources: List<HistoryHit>, query: HistoryQuery): List<HistoryHit> {
    require(query.query.length <= 4_000 && query.project.length <= 80) { "检索条件过长" }
    fun date(value: String): LocalDate? {
        if (value.isBlank()) return null
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "日期须为 YYYY-MM-DD" }
        return LocalDate.parse(value)
    }
    val from = date(query.fromDate)
    val through = date(query.throughDate)
    require(from == null || through == null || !from.isAfter(through)) { "开始日期不能晚于结束日期" }
    val terms = historyTerms(query.query)
    return sources.asSequence().filter { source ->
        val day = Instant.ofEpochMilli(source.observedAt).atZone(ZoneId.of(source.zoneId)).toLocalDate()
        val body = source.text.lowercase(Locale.ROOT)
        (from == null || !day.isBefore(from)) && (through == null || !day.isAfter(through)) &&
            (query.project.isBlank() || source.project == query.project || source.text.contains(query.project)) &&
            (query.query.isBlank() || terms.any(body::contains))
    }.sortedWith(
        compareByDescending<HistoryHit> { query.query.isNotBlank() && it.text.contains(query.query.trim(), ignoreCase = true) }
            .thenByDescending { source -> terms.count { source.text.contains(it, ignoreCase = true) } }
            .thenByDescending { it.observedAt }.thenBy { it.contentId }
    ).take(10).map { source ->
        val body = source.text.lowercase(Locale.ROOT)
        val exact = query.query.trim().takeIf { it.isNotEmpty() }?.let { body.indexOf(it.lowercase(Locale.ROOT)) } ?: -1
        val first = if (exact >= 0) exact else terms.map(body::indexOf).filter { it >= 0 }.minOrNull() ?: 0
        val start = (first - 60).coerceAtLeast(0)
        source.copy(text = source.text.substring(start, (start + 300).coerceAtMost(source.text.length)))
    }.toList()
}
