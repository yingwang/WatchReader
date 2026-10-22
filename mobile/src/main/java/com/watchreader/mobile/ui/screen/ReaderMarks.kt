package com.watchreader.mobile.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.watchreader.mobile.highlight.SelectionDrag
import com.watchreader.mobile.highlight.TextSpan
import com.watchreader.mobile.highlight.WordSelection
import com.watchreader.mobile.ui.viewmodel.ReaderHighlightsViewModel

/** A highlight tapped on the page, and where, while its menu is open. */
data class OpenedHighlight(val id: String, val at: Offset)

/**
 * The note being written. [highlightId] is the highlight it belongs to, or null for a note on the
 * words selected now, which become a highlight when it is saved.
 */
data class NoteEditor(val highlightId: String?, val span: TextSpan?, val quote: String, val initial: String)

/**
 * What the reader has selected or opened on the page, and how a press, a tap or a handle changes
 * it. The page's own tap handling asks here first: while words are selected a tap only lets them
 * go, and a tap on a highlight opens it instead of turning the page. A selection never leaves the
 * page it was made on; when the page changes, or is laid out again in another size, it goes.
 */
@Stable
class ReaderMarks(val highlights: ReaderHighlightsViewModel) {
    /** The selected words, in book offsets; null when nothing is selected. */
    var selection by mutableStateOf<TextSpan?>(null)
        private set

    var opened by mutableStateOf<OpenedHighlight?>(null)

    var noteEditor by mutableStateOf<NoteEditor?>(null)

    /** The page as last drawn, for finding what lies under a finger. */
    internal var layout by mutableStateOf<PageTextLayout?>(null)
        private set

    /** Where the dragged handle's end of the selection is, following the finger rather than the handle. */
    private var dragPoint: Offset? = null

    val active: Boolean get() = selection != null

    /** The selected words as they read on the page. */
    val selectedText: String?
        get() {
            val span = selection ?: return null
            val page = layout?.page ?: return null
            val from = (span.start - page.start).coerceIn(0, page.chars.length)
            val to = (span.end - page.start).coerceIn(from, page.chars.length)
            return page.chars.substring(from, to)
        }

    internal fun attach(next: PageTextLayout) {
        if (layout === next) return
        if (layout != null) {
            selection = null
            opened = null
        }
        layout = next
    }

    /** Selects the word under [position]; false over blank page, which the screen handles as before. */
    fun onLongPress(position: Offset): Boolean {
        val page = layout ?: return false
        val at = page.charAt(position) ?: return false
        val word = WordSelection.wordAt(page.page.chars, at - page.page.start) ?: return false
        selection = TextSpan(page.page.start + word.start, page.page.start + word.end)
        opened = null
        return true
    }

    /** True when the tap was for the selection or a highlight, and the page should not turn. */
    fun onTap(position: Offset): Boolean {
        val page = layout
        val span = selection
        if (span != null) {
            // A tap on the selected words keeps them; anywhere else lets them go.
            val onSelection = page != null && page.rects(span.start, span.end).any { it.contains(position) }
            if (!onSelection) clear()
            return true
        }
        if (page == null) return false
        val at = page.charAt(position) ?: return false
        // Where highlights overlap, the one drawn last (the later in the book) is the one on top.
        val hit = highlights.state.value.items.lastOrNull { at in it } ?: return false
        opened = OpenedHighlight(hit.id, position)
        return true
    }

    fun clear() {
        selection = null
        dragPoint = null
    }

    fun startDrag(isStart: Boolean) {
        val page = layout ?: return
        val span = selection ?: return
        val rects = page.rects(span.start, span.end)
        val edge = (if (isStart) rects.firstOrNull() else rects.lastOrNull()) ?: return
        dragPoint = Offset(if (isStart) edge.left else edge.right, edge.center.y)
    }

    fun dragBy(isStart: Boolean, delta: Offset) {
        val page = layout ?: return
        val span = selection ?: return
        val point = (dragPoint ?: return) + delta
        dragPoint = point
        val to = page.boundaryAt(point) ?: return
        val text = page.page.chars
        val start = page.page.start
        selection = if (isStart) SelectionDrag.moveStart(text, start, span, to) else SelectionDrag.moveEnd(text, start, span, to)
    }

    /** A selection dragged to the start of a line keeps the space before it out. */
    fun endDrag() {
        dragPoint = null
        val page = layout ?: return
        val span = selection ?: return
        val local = TextSpan(span.start - page.page.start, span.end - page.page.start)
        WordSelection.trim(page.page.chars, local)?.let { selection = TextSpan(it.start + page.page.start, it.end + page.page.start) }
    }
}

@Composable
fun rememberReaderMarks(highlights: ReaderHighlightsViewModel): ReaderMarks = remember(highlights) { ReaderMarks(highlights) }
