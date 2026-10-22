package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.highlight.HighlightColor
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import kotlin.math.roundToInt

/** A highlight's colour as the page draws it: see-through, a little stronger on a light page. */
internal fun HighlightColor.onPage(lightPage: Boolean): Color = Color(argb).copy(alpha = if (lightPage) 0.42f else 0.36f)

/** A highlight's colour at full strength, for the swatches and the list. */
internal val HighlightColor.solid: Color get() = Color(argb)

internal val HighlightColor.label: Int
    get() = when (this) {
        HighlightColor.YELLOW -> R.string.highlight_color_yellow
        HighlightColor.GREEN -> R.string.highlight_color_green
        HighlightColor.BLUE -> R.string.highlight_color_blue
        HighlightColor.PINK -> R.string.highlight_color_pink
    }

/**
 * Highlights and the selection, drawn under the page's text. It goes in the page's box before the
 * text, and lays the page out for [ReaderMarks] to find what lies under a finger. [measurer] and
 * [textStyle] are the ones the text is drawn with, so the marks sit exactly under the words.
 */
@Composable
internal fun ReaderMarksUnderlay(
    marks: ReaderMarks,
    page: Paginator.Page,
    geometry: PageGeometry,
    measurer: TextMeasurer,
    textStyle: TextStyle,
) {
    val density = LocalDensity.current
    val pageLayout = remember(page, geometry, measurer, textStyle, density) {
        PageTextLayout(page, geometry, with(density) { textStyle.fontSize.toPx() }) { text, width ->
            measurer.measure(
                text = AnnotatedString(text),
                style = textStyle,
                overflow = TextOverflow.Clip,
                maxLines = 1,
                constraints = Constraints(maxWidth = width),
            )
        }
    }
    SideEffect { marks.attach(pageLayout) }
    val state by marks.highlights.state.collectAsState()
    val onPage = remember(state.items, page) { state.items.filter { it.end > page.start && it.start < page.end } }
    val lightPage = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = if (lightPage) 0.30f else 0.40f)
    val selection = marks.selection
    if (onPage.isEmpty() && selection == null) return
    Canvas(Modifier.fillMaxSize()) {
        val corner = CornerRadius(3.dp.toPx())
        for (highlight in onPage) {
            val color = highlight.color.onPage(lightPage)
            for (r in pageLayout.rects(highlight.start, highlight.end)) drawRoundRect(color, r.topLeft, r.size, corner)
        }
        if (selection != null) {
            for (r in pageLayout.rects(selection.start, selection.end)) drawRoundRect(selectionColor, r.topLeft, r.size, corner)
        }
    }
}

/** What the selection toolbar's buttons do. */
internal class SelectionActions(
    val highlight: (HighlightColor?) -> Unit,
    val note: () -> Unit,
    val lookUp: () -> Unit,
    val translate: () -> Unit,
    val copy: () -> Unit,
    val share: () -> Unit,
)

/** The selection's two handles and its toolbar, over the page's text. */
@Composable
internal fun SelectionControls(marks: ReaderMarks, lastColor: HighlightColor, actions: SelectionActions) {
    val span = marks.selection ?: return
    val page = marks.layout ?: return
    val rects = page.rects(span.start, span.end)
    val first = rects.firstOrNull() ?: return
    val last = rects.last()
    SelectionHandle(marks, isStart = true, at = Offset(first.left, first.bottom))
    SelectionHandle(marks, isStart = false, at = Offset(last.right, last.bottom))
    val bounds = Rect(rects.minOf { it.left }, first.top, rects.maxOf { it.right }, last.bottom)
    val handleRoom = with(LocalDensity.current) { HANDLE_TOUCH.toPx() }
    ToolbarPlacement(bounds, handleRoom) { SelectionToolbar(lastColor, actions) }
}

private val HANDLE_TOUCH = 40.dp
private val HANDLE_RADIUS = 8.dp

/**
 * A handle hangs from one end of the selection, a stem down to a round grip. Dragging it moves
 * that end; the finger's own movement is followed, not the handle's, so the end does not jump
 * when the handle settles on a new line.
 */
@Composable
private fun SelectionHandle(marks: ReaderMarks, isStart: Boolean, at: Offset) {
    val color = MaterialTheme.colorScheme.primary
    val touch = with(LocalDensity.current) { HANDLE_TOUCH.toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.x - touch / 2).roundToInt(), at.y.roundToInt()) }
            .size(HANDLE_TOUCH)
            .pointerInput(isStart) {
                awaitEachGesture {
                    // Taken here, so the page underneath sees neither a tap nor a long press.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    marks.startDrag(isStart)
                    drag(down.id) { change ->
                        marks.dragBy(isStart, change.positionChange())
                        change.consume()
                    }
                    marks.endDrag()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = HANDLE_RADIUS.toPx()
            val x = size.width / 2
            drawLine(color, Offset(x, 0f), Offset(x, radius), strokeWidth = 2.dp.toPx())
            drawCircle(color, radius, Offset(x, radius * 2))
        }
    }
}

/**
 * Puts the toolbar above the selection, or below its handles when there is no room above, or at
 * the foot of the page when neither fits; centred on the selection and kept on the page.
 */
@Composable
private fun ToolbarPlacement(bounds: Rect, handleRoom: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val toolbar = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 8.dp.toPx()
        val above = bounds.top - gap - toolbar.height
        val below = bounds.bottom + handleRoom + gap
        val y = when {
            above >= 0f -> above
            below + toolbar.height <= constraints.maxHeight -> below
            else -> (constraints.maxHeight - toolbar.height).toFloat().coerceAtLeast(0f)
        }
        val x = (bounds.center.x - toolbar.width / 2f).coerceIn(0f, (constraints.maxWidth - toolbar.width).toFloat().coerceAtLeast(0f))
        layout(constraints.maxWidth, constraints.maxHeight) { toolbar.place(x.roundToInt(), y.roundToInt()) }
    }
}

/** Takes every touch that lands on it, so a tap between two buttons does not reach the page. */
private fun Modifier.keepTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
}

@Composable
private fun SelectionToolbar(lastColor: HighlightColor, actions: SelectionActions) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.keepTouches(),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The ringed swatch is the colour Highlight uses; a yellow icon would vanish on a light page.
            ColorRow(selected = lastColor, onPick = { actions.highlight(it) })
            Row {
                ToolbarButton(Icons.Filled.BorderColor, stringResource(R.string.highlight_action)) { actions.highlight(null) }
                ToolbarButton(Icons.Filled.EditNote, stringResource(R.string.highlight_note_action), onClick = actions.note)
                ToolbarButton(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.highlight_look_up), onClick = actions.lookUp)
                ToolbarButton(Icons.Filled.Translate, stringResource(R.string.highlight_translate), onClick = actions.translate)
                ToolbarButton(Icons.Filled.ContentCopy, stringResource(R.string.highlight_copy), onClick = actions.copy)
                ToolbarButton(Icons.Filled.Share, stringResource(R.string.highlight_share), onClick = actions.share)
            }
        }
    }
}

/**
 * The four colours as swatches; the one in use is ringed. Tapping one marks the words in it and
 * makes it the colour Highlight uses next time.
 */
@Composable
internal fun ColorRow(selected: HighlightColor?, onPick: (HighlightColor) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        val ring = MaterialTheme.colorScheme.onSurface
        for (color in HighlightColor.entries) {
            val isSelected = color == selected
            val description = stringResource(R.string.highlight_in_color, stringResource(color.label))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(color) })
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(28.dp).then(if (isSelected) Modifier.border(2.dp, ring, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(color.solid))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    // The label shows on a long press, for icons that do not explain themselves at a glance.
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}
