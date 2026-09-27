package com.watchreader.shared.stats

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where reading time came from. The phone keeps its own; the watch sends the other two. */
object ReadingSource {
    const val WATCH = "watch"
    const val LISTEN = "listen"
    const val PHONE = "phone"
}

/** Time spent on one book on one day from one source, and how many characters were read in it. */
data class ReadingDay(val day: String, val source: String, val millis: Long, val chars: Int)

/**
 * Everything the watch knows about the time spent on one book: when it was first opened and
 * finished there (0 when not yet), and the time per day. It is the whole history each time, so
 * the phone can replace what it had rather than add to it, and a report arriving twice is harmless.
 */
data class ReadingReport(
    val bookId: String,
    val firstOpenedEpochMs: Long,
    val finishedEpochMs: Long,
    val days: List<ReadingDay>,
) {
    fun toJson(): String = JSONObject()
        .put("bookId", bookId)
        .put("firstOpenedEpochMs", firstOpenedEpochMs)
        .put("finishedEpochMs", finishedEpochMs)
        .put("days", JSONArray().apply {
            for (d in days) put(JSONObject().put("day", d.day).put("source", d.source).put("millis", d.millis).put("chars", d.chars))
        })
        .toString()

    companion object {
        fun fromJson(json: String): ReadingReport {
            val o = JSONObject(json)
            val array = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until array.length()).map { i ->
                val d = array.getJSONObject(i)
                ReadingDay(d.getString("day"), d.getString("source"), d.getLong("millis"), d.optInt("chars", 0))
            }
            return ReadingReport(o.getString("bookId"), o.optLong("firstOpenedEpochMs", 0), o.optLong("finishedEpochMs", 0), days)
        }
    }
}

/** The local calendar day a moment falls on, as "2026-09-27". */
fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

/** The [count] days ending today, oldest first. */
fun lastDays(count: Int, today: LocalDate = LocalDate.now()): List<String> =
    (count - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }

/**
 * Characters a minute, from the reading (not listening) a book has seen; null until there is
 * enough of it to mean anything, since a few pages read slowly or skimmed say little.
 */
fun charsPerMinute(days: List<ReadingDay>): Double? {
    val read = days.filter { it.source != ReadingSource.LISTEN }
    val millis = read.sumOf { it.millis }
    val chars = read.sumOf { it.chars.toLong() }
    if (millis < MIN_SAMPLE_MS || chars < MIN_SAMPLE_CHARS) return null
    return chars * 60_000.0 / millis
}

private const val MIN_SAMPLE_MS = 5 * 60 * 1000L
private const val MIN_SAMPLE_CHARS = 2_000L

/** "under a minute", "34 min", "2 h 5 min". */
fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
