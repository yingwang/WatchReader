package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** The smallest a control may be to the finger, as Android's accessibility guidance has it. */
internal val MIN_TOUCH_TARGET = 48.dp

/**
 * Draws [content] at its own size but takes presses over a box at least [MIN_TOUCH_TARGET] each
 * way, centred on it, for a control that is smaller than a fingertip and sits in a line that must
 * not be pushed apart to make room. [target] goes on that box: the click, its semantics and an
 * indication-free interaction source, whose presses [content] shows itself. The box reaches over
 * whatever lies around the control and takes the touches that land there.
 */
@Composable
internal fun OverhangingTouchTarget(target: Modifier, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(
        content = {
            Box { content() }
            Box(target)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val drawn = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val min = MIN_TOUCH_TARGET.roundToPx()
        val width = maxOf(drawn.width, min)
        val height = maxOf(drawn.height, min)
        // Measured after the drawing and placed over it, so it is the one a finger reaches.
        val box = measurables[1].measure(Constraints.fixed(width, height))
        layout(drawn.width, drawn.height) {
            drawn.place(0, 0)
            box.place((drawn.width - width) / 2, (drawn.height - height) / 2)
        }
    }
}
