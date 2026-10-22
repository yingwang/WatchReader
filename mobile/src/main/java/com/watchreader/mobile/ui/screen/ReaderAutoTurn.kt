package com.watchreader.mobile.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Auto page turn in the phone's reader: whether it is on, the seconds a page stays, and what
 * holds it. [hold] is for anything else on the screen that should stop the pages turning while it
 * lasts; text selection holds it with `hold("selection", true)` and lets go with `false`.
 */
@Stable
class AutoTurnState internal constructor(private val prefs: AutoTurnPrefs) {
    internal val timer = AutoTurnTimer(SystemClock::uptimeMillis)

    var enabled by mutableStateOf(prefs.enabled)
        private set
    var seconds by mutableIntStateOf(prefs.seconds)
        private set

    /** Bumped whenever the rules change, so the wait under way starts over under the new ones. */
    internal var version by mutableIntStateOf(0)
        private set

    init {
        timer.configure(enabled, seconds)
    }

    fun switchTo(on: Boolean) {
        enabled = on
        prefs.enabled = on
        changed()
    }

    /** The pace as a stop from slow (0) to fast; see [AutoTurnPrefs.speedOf]. */
    val speed: Int get() = AutoTurnPrefs.speedOf(seconds)

    fun setSpeed(speed: Int) {
        val chosen = AutoTurnPrefs.secondsAt(speed)
        if (chosen == seconds) return
        seconds = chosen
        prefs.seconds = chosen
        changed()
    }

    fun hold(reason: Any, on: Boolean) {
        timer.hold(reason, on)
        version++
    }

    /** The last page's time is up. The turning stops for this reading; the setting stays as it was. */
    internal fun ended() {
        enabled = false
        changed()
    }

    private fun changed() {
        timer.configure(enabled, seconds)
        version++
    }
}

@Composable
fun rememberAutoTurnState(): AutoTurnState {
    val context = LocalContext.current
    return remember { AutoTurnState(AutoTurnPrefs(context)) }
}

/**
 * Turns the page when its time is up. [paused] is the screen's own hold: the controls, the menu or
 * any sheet or dialog open. The wait runs only while the reader is in front, so it stops the moment
 * the app goes to the background and starts afresh on the way back; a book left open does not turn
 * in the dark and carry the saved place on with it.
 */
@Composable
fun AutoTurnEffect(auto: AutoTurnState, ready: ReaderUiState.Ready?, paused: Boolean, onTurn: () -> Unit, onEnd: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageStart = ready?.page?.start
    val last = ready != null && ready.page.end >= ready.totalChars
    LaunchedEffect(auto.version, pageStart, last, paused) {
        auto.timer.hold(SCREEN_HOLD, paused)
        if (pageStart == null || !auto.timer.running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            auto.timer.pageShown(last)
            while (true) {
                val wait = auto.timer.remaining() ?: break
                delay(wait)
                when (auto.timer.tick()) {
                    AutoTurnTimer.Tick.TURN -> { onTurn(); break }
                    AutoTurnTimer.Tick.END -> { auto.ended(); onEnd(); break }
                    AutoTurnTimer.Tick.WAIT -> Unit
                }
            }
        }
    }
}

private const val SCREEN_HOLD = "screen"

/**
 * Auto page turn's panel, under the page while the controls are up. The pages do not turn while it
 * shows, and it says so at the top in large type, with how to carry on, so a held page is not taken
 * for a broken one. Below, the pace as a slider from slow to fast with no seconds shown, as WeChat
 * Reading has it, and a button to stop turning altogether.
 */
@Composable
fun AutoTurnBar(auto: AutoTurnState) {
    val speedLabel = stringResource(R.string.auto_turn_speed)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PauseCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stringResource(R.string.auto_turn_paused), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.auto_turn_paused_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { auto.switchTo(false) }) { Text(stringResource(R.string.auto_turn_off)) }
            }
            Text(
                speedLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auto_turn_slow), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = auto.speed.toFloat(),
                    // No tick marks: the thumb still settles on the nearest of the stops.
                    onValueChange = { auto.setSpeed(it.roundToInt()) },
                    valueRange = 0f..(AutoTurnPrefs.SPEEDS - 1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = speedLabel },
                )
                Text(stringResource(R.string.auto_turn_fast), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
