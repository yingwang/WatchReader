package com.watchreader.mobile.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import kotlinx.coroutines.delay

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

    /** One stop along the ladder: more seconds a page for a positive [by], fewer for a negative one. */
    fun step(by: Int) {
        seconds = AutoTurnPrefs.step(seconds, by)
        prefs.seconds = seconds
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
 * The pace of auto page turn, under the page while the controls are up (and the turning held):
 * fewer or more seconds a page, along the ladder, and a switch to turn it off.
 */
@Composable
fun AutoTurnBar(auto: AutoTurnState) {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.auto_turn), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { auto.step(-1) }, enabled = auto.seconds > AutoTurnPrefs.LADDER.first()) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.auto_turn_shorter))
                }
                Text(
                    stringResource(R.string.auto_turn_seconds, auto.seconds),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 80.dp),
                )
                IconButton(onClick = { auto.step(1) }, enabled = auto.seconds < AutoTurnPrefs.LADDER.last()) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.auto_turn_longer))
                }
                Switch(checked = auto.enabled, onCheckedChange = auto::switchTo, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
