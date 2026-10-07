package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.shared.stats.ReadingDay
import com.watchreader.shared.stats.ReadingSource
import com.watchreader.shared.stats.charsPerMinute
import com.watchreader.shared.stats.formatDuration
import com.watchreader.shared.stats.lastDays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The reading time of the last week across all books: today, a bar a day, where it was spent,
 * and which books it went to. The watch shows none of this; it only keeps the time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(listVm: BookListViewModel, onBack: () -> Unit, onDetails: (String) -> Unit) {
    val rows by listVm.readingTime.collectAsState()
    val books by listVm.books.collectAsState()
    val week = remember { lastDays(7) }
    val inWeek = rows.filter { it.day in week }
    val perDay = week.map { day -> inWeek.filter { it.day == day }.sumOf { it.millis } }

    Scaffold(
        topBar = { StatsTopBar(stringResource(R.string.stats_title), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(durationOrNothing(perDay.last()), style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.stats_last_week), style = MaterialTheme.typography.titleMedium)
            WeekBars(week, perDay)
            Text(
                stringResource(R.string.stats_week_total, formatDuration(perDay.sum())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceBreakdown(inWeek)

            val perBook = inWeek.groupBy { it.bookId }.mapValues { (_, r) -> r.sumOf { it.millis } }
                .filterValues { it > 0 }.toList().sortedByDescending { it.second }
            val titles = books.orEmpty().associate { it.id to it.title }
            if (perBook.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.stats_books), style = MaterialTheme.typography.titleMedium)
                for ((id, millis) in perBook) {
                    val title = titles[id] ?: continue
                    StatRow(title, formatDuration(millis), Modifier.clickable { onDetails(id) })
                }
            }
            Text(
                stringResource(R.string.stats_counted_from_now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            )
        }
    }
}

/** One book: when it came and was opened and finished, how far it has got, and the time it took. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(bookId: String, listVm: BookListViewModel, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val books by listVm.books.collectAsState()
    val book = books.orEmpty().firstOrNull { it.id == bookId }
    val rowsFlow = remember(bookId) { listVm.readingTimeFor(bookId) }
    val rows by rowsFlow.collectAsState(initial = emptyList())
    val milestoneFlow = remember(bookId) { listVm.milestoneFor(bookId) }
    val milestone by milestoneFlow.collectAsState(initial = null)

    Scaffold(
        topBar = { StatsTopBar(book?.title ?: stringResource(R.string.details_title), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (book == null) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The author under the title, as a byline: several names run longer than a row's value has room for.
            book.author?.let { author ->
                Text(
                    author,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val progress = book.readProgress.coerceIn(0f, 1f)
            StatRow(stringResource(R.string.details_added), date(book.addedEpochMs))
            StatRow(stringResource(R.string.details_first_opened), milestone?.firstOpenedEpochMs?.takeIf { it > 0 }?.let(::date) ?: stringResource(R.string.details_not_yet))
            milestone?.finishedEpochMs?.takeIf { it > 0 }?.let { StatRow(stringResource(R.string.details_finished), date(it)) }
            StatRow(stringResource(R.string.details_progress), "${(progress * 100).toInt()}%")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            StatRow(stringResource(R.string.details_reading_time), formatDuration(rows.sumOf { it.millis }))
            SourceBreakdown(rows)
            // The pace is the reader's own on this book, from the pages read rather than listened to.
            val pace = charsPerMinute(rows.map { ReadingDay(it.day, it.source, it.millis, it.chars) })
            val left = book.totalChars - book.readOffsetChars
            if (pace != null && progress < 1f && left > 0) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                StatRow(
                    stringResource(R.string.details_time_left),
                    stringResource(R.string.details_time_left_value, formatDuration((left / pace * 60_000).toLong())),
                )
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.stats_counted_from_now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            FilledTonalButton(onClick = { onOpenBook(book.id) }, modifier = Modifier.padding(top = 20.dp, bottom = 32.dp)) {
                Text(stringResource(R.string.details_open))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** Where the time went: each source that has any, the others left out. */
@Composable
private fun SourceBreakdown(rows: List<ReadingTime>) {
    val sources = listOf(
        ReadingSource.WATCH to R.string.stats_on_watch,
        ReadingSource.PHONE to R.string.stats_on_phone,
        ReadingSource.LISTEN to R.string.stats_listening,
    )
    for ((source, label) in sources) {
        val millis = rows.filter { it.source == source }.sumOf { it.millis }
        if (millis > 0) StatRow(stringResource(label), formatDuration(millis), indent = true)
    }
}

@Composable
private fun StatRow(label: String, value: String, modifier: Modifier = Modifier, indent: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = if (indent) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** A bar a day, today on the right and in full colour; the tallest day sets the scale. */
@Composable
private fun WeekBars(days: List<String>, millis: List<Long>) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val labels = days.map { LocalDate.parse(it).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    val description = days.indices.joinToString(", ") { "${labels[it]} ${formatDuration(millis[it])}" }
    val most = (millis.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
            val slot = size.width / days.size
            val width = slot * 0.56f
            for (i in days.indices) {
                val left = i * slot + (slot - width) / 2
                drawRoundRect(track, Offset(left, 0f), Size(width, size.height), CornerRadius(6f, 6f))
                val h = size.height * millis[i] / most
                if (h > 0) {
                    drawRoundRect(
                        if (i == days.lastIndex) bar else bar.copy(alpha = 0.55f),
                        Offset(left, size.height - h),
                        Size(width, h),
                        CornerRadius(6f, 6f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (label in labels) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun durationOrNothing(millis: Long): String =
    if (millis <= 0) stringResource(R.string.stats_nothing_yet) else formatDuration(millis)

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun date(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormat)

private val headerDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

/**
 * The library's first line: today's date and today's reading time, for a reader who has any on
 * record at all. The date stands in for a "Today" label, so the line also says what day it is.
 */
@Composable
fun TodayLine(rows: List<ReadingTime>, onClick: () -> Unit) {
    val date = remember { LocalDate.now() }
    val today = date.toString()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(date.format(headerDateFormat), style = MaterialTheme.typography.titleMedium)
        Text(
            durationOrNothing(rows.filter { it.day == today }.sumOf { it.millis }) + "  ›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
