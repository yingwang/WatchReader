package com.watchreader.mobile.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Auto page turn in the phone's reader, for one reading: whether the pages are turning, paused or
 * off, the pace, and whether its sheet is open. Only the pace is kept for next time; a book always
 * opens with the pages still. [hold] is for anything on the screen that should stop the pages
 * while it lasts; the sheet holds them itself while it is open.
 */
@Stable
class AutoTurnState internal constructor(
    private val prefs: AutoTurnPrefs,
    now: () -> Long = SystemClock::uptimeMillis,
) {
    private val timer = AutoTurnTimer(now)

    var seconds by mutableIntStateOf(prefs.seconds)
        private set

    var phase by mutableStateOf(AutoTurnTimer.Phase.OFF)
        private set

    /** When the page on screen turns, on the timer's clock; null while nothing counts down. */
    var deadline by mutableStateOf<Long?>(null)
        private set

    /** Whether auto page turn's sheet is open. */
    var sheetOpen by mutableStateOf(false)
        private set

    init {
        timer.setSeconds(seconds)
    }

    /** Started for this reading, whether the pages are turning now or paused. */
    val on: Boolean get() = phase != AutoTurnTimer.Phase.OFF

    val paused: Boolean get() = phase == AutoTurnTimer.Phase.PAUSED

    /** The pace as a stop from slow (0) to fast; see [AutoTurnPrefs.speedOf]. */
    val speed: Int get() = AutoTurnPrefs.speedOf(seconds)

    fun setSpeed(speed: Int) {
        val chosen = AutoTurnPrefs.secondsAt(speed)
        if (chosen == seconds) return
        seconds = chosen
        prefs.seconds = chosen
        timer.setSeconds(chosen)
        sync()
    }

    /** Sets the pages turning; they start the moment nothing holds them. */
    fun start() {
        timer.start()
        sync()
    }

    fun stop() {
        timer.stop()
        sync()
    }

    fun openSheet() {
        sheetOpen = true
        hold(SHEET_HOLD, true)
    }

    fun closeSheet() {
        sheetOpen = false
        hold(SHEET_HOLD, false)
    }

    fun hold(reason: Any, on: Boolean) {
        timer.hold(reason, on)
        sync()
    }

    internal fun pageShown(last: Boolean) {
        timer.pageShown(last)
        sync()
    }

    internal fun remaining(): Long? = timer.remaining()

    internal fun progress(): Float? = timer.progress()

    internal fun tick(): AutoTurnTimer.Tick = timer.tick().also { sync() }

    private fun sync() {
        phase = timer.phase
        deadline = timer.deadline
    }

    companion object {
        private const val SHEET_HOLD = "sheet"

        /**
         * Keeps the turning and the open sheet through a rotation, which builds the screen again
         * in the middle of a reading. A book opened afresh starts from a new state, with both off.
         */
        internal fun saver(prefs: AutoTurnPrefs, now: () -> Long = SystemClock::uptimeMillis) = Saver<AutoTurnState, BooleanArray>(
            save = { booleanArrayOf(it.on, it.sheetOpen) },
            restore = { saved ->
                AutoTurnState(prefs, now).apply {
                    if (saved.getOrElse(0) { false }) start()
                    if (saved.getOrElse(1) { false }) openSheet()
                }
            },
        )
    }
}

@Composable
fun rememberAutoTurnState(): AutoTurnState {
    val context = LocalContext.current
    val prefs = remember { AutoTurnPrefs(context) }
    val saver = remember(prefs) { AutoTurnState.saver(prefs) }
    return rememberSaveable(saver = saver) { AutoTurnState(prefs) }
}

/**
 * Turns the page when its time is up. [paused] is the screen's own hold: the controls, the menu or
 * any sheet or dialog open, text selected. Nothing turns before a page is up, and nothing turns
 * while the reader is not in front: a book left open does not turn in the dark and carry the saved
 * place on with it, and on the way back the page starts its time afresh.
 */
@Composable
fun AutoTurnEffect(auto: AutoTurnState, ready: ReaderUiState.Ready?, paused: Boolean, onTurn: () -> Unit, onEnd: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageStart = ready?.page?.start
    val last = ready != null && ready.page.end >= ready.totalChars
    val held = paused || pageStart == null
    LaunchedEffect(auto, held) { auto.hold(SCREEN_HOLD, held) }
    LaunchedEffect(auto, lifecycle) {
        lifecycle.currentStateFlow.collect { auto.hold(BACKGROUND_HOLD, !it.isAtLeast(Lifecycle.State.RESUMED)) }
    }
    LaunchedEffect(auto, pageStart, last) { if (pageStart != null) auto.pageShown(last) }
    val turn by rememberUpdatedState(onTurn)
    val end by rememberUpdatedState(onEnd)
    // A new deadline comes with every page, every pause let go of and every change of pace.
    LaunchedEffect(auto, auto.deadline) {
        while (true) {
            val wait = auto.remaining() ?: break
            delay(wait)
            when (auto.tick()) {
                AutoTurnTimer.Tick.TURN -> { turn(); break }
                AutoTurnTimer.Tick.END -> { end(); break }
                AutoTurnTimer.Tick.WAIT -> Unit
            }
        }
    }
}

private const val SCREEN_HOLD = "screen"
private const val BACKGROUND_HOLD = "background"

/**
 * Says on the page that auto page turn is on: a small pill by the percentage under the page, with
 * a ring that fills as the page's time runs out, or a pause sign and "paused" while anything holds
 * the pages. A tap on it opens the sheet, to change the pace or stop. Nothing shows while it is off.
 */
@Composable
fun AutoTurnIndicator(auto: AutoTurnState, modifier: Modifier = Modifier) {
    if (!auto.on) return
    val paused = auto.paused
    val name = stringResource(R.string.auto_turn)
    val state = stringResource(if (paused) R.string.auto_turn_state_paused else R.string.auto_turn_state_turning)
    val ink = MaterialTheme.colorScheme.onBackground
    val press = remember { MutableInteractionSource() }
    // The pill is a line of small print tall; the finger gets a 48 dp target around it that
    // reaches over the page's margin, so the line under the page keeps its height.
    OverhangingTouchTarget(
        target = Modifier
            .clickable(
                interactionSource = press,
                indication = null,
                onClickLabel = stringResource(R.string.auto_turn_open),
                role = Role.Button,
                onClick = auto::openSheet,
            )
            .semantics {
                contentDescription = name
                stateDescription = state
            },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.08f))
                .indication(press, LocalIndication.current)
                .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (paused) {
                Icon(Icons.Filled.Pause, contentDescription = null, tint = ink.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            } else {
                TurnProgress(auto, MaterialTheme.colorScheme.primary, ink.copy(alpha = 0.2f), Modifier.size(16.dp))
            }
            Text(
                stringResource(if (paused) R.string.auto_turn_paused else R.string.auto_turn),
                color = ink.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                // Spoken through the pill's own name and state.
                modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics { },
            )
        }
    }
}

/**
 * A ring that fills once over each page's time. It moves in small steps, a dozen a second at the
 * most and far fewer at a slow pace, and is read only while drawing, in a layer of its own, so it
 * neither recomposes nor redraws the page. Between a turn and the next page it stays full.
 */
@Composable
private fun TurnProgress(auto: AutoTurnState, color: Color, track: Color, modifier: Modifier) {
    var filled by remember { mutableFloatStateOf(auto.progress() ?: 0f) }
    val deadline = auto.deadline
    val step = (auto.seconds * 1000L / RING_STEPS).coerceIn(80L, 500L)
    LaunchedEffect(deadline, step) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            filled = auto.progress() ?: break
            if (filled >= 1f) break
            delay(step)
        }
    }
    Canvas(modifier.graphicsLayer()) {
        val width = 2.dp.toPx()
        val corner = Offset(width / 2, width / 2)
        val ring = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = corner, size = ring, style = Stroke(width))
        drawArc(color, -90f, 360f * filled, useCenter = false, topLeft = corner, size = ring, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** Steps the ring takes in a page's time; enough that each moves it about a pixel. */
private const val RING_STEPS = 100L

/**
 * Auto page turn's sheet, opened from the reader's menu or from the pill under the page. While the
 * pages are still it offers the pace and Start, which closes the sheet, sends the controls away and
 * sets the pages turning at once, with no further tap. While they turn the pace applies as it is
 * moved, Done goes back to the turning pages, and Stop ends it. The pages wait while it is open.
 * Starting stops reading aloud: the two never run together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTurnSheet(auto: AutoTurnState, listen: ReaderListen, hideControls: () -> Unit) {
    if (!auto.sheetOpen) return
    ModalBottomSheet(onDismissRequest = auto::closeSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.auto_turn),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (auto.on) R.string.auto_turn_about_on else R.string.auto_turn_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            AutoTurnSpeed(auto, Modifier.padding(top = 20.dp))
            if (auto.on) {
                Button(
                    onClick = { auto.closeSheet(); hideControls() },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.auto_turn_done)) }
                OutlinedButton(
                    onClick = { auto.stop(); auto.closeSheet() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.auto_turn_stop)) }
            } else {
                Button(
                    onClick = {
                        listen.stop()
                        auto.start()
                        auto.closeSheet()
                        hideControls()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.auto_turn_start), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * Auto page turn's panel, under the page while the controls are up and the pages are paused. It
 * says so at the top in large type, so a held page is not taken for a broken one, and offers the
 * two ways on in words: Resume, which sends the controls away and turns again, and Stop. The pace
 * is there too, as in the sheet.
 */
@Composable
fun AutoTurnBar(auto: AutoTurnState, onResume: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PauseCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    stringResource(R.string.auto_turn_paused),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                )
            }
            AutoTurnSpeed(auto, Modifier.padding(top = 12.dp))
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.auto_turn_resume), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = auto::stop,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.auto_turn_stop)) }
        }
    }
}

/**
 * The pace as a slider from slow to fast with no tick marks and no seconds shown, as WeChat Reading
 * has it; it applies as it moves. TalkBack hears its place among the stops instead of a percentage.
 */
@Composable
private fun AutoTurnSpeed(auto: AutoTurnState, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.auto_turn_speed_description)
    val position = stringResource(R.string.auto_turn_speed_state, auto.speed + 1, AutoTurnPrefs.SPEEDS)
    Column(modifier) {
        Text(
            stringResource(R.string.auto_turn_speed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auto_turn_slow), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = auto.speed.toFloat(),
                // No tick marks: the thumb still settles on the nearest of the stops.
                onValueChange = { auto.setSpeed(it.roundToInt()) },
                valueRange = 0f..(AutoTurnPrefs.SPEEDS - 1).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics {
                    contentDescription = description
                    stateDescription = position
                },
            )
            Text(stringResource(R.string.auto_turn_fast), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
