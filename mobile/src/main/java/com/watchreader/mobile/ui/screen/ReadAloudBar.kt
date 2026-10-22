package com.watchreader.mobile.ui.screen

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.watchreader.mobile.R
import com.watchreader.mobile.service.ReadAloudService
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.ReadAloudState
import com.watchreader.mobile.tts.SleepTimer
import com.watchreader.mobile.tts.SpeechRates
import com.watchreader.mobile.tts.TtsLanguages
import kotlinx.coroutines.delay

/**
 * What the reader shows under the page: the player while this book is read aloud, or the auto
 * page turn's pace while the controls are up and the pages turn by themselves. Nothing otherwise,
 * so the page keeps the whole screen.
 */
@Composable
fun ReaderBottomBar(listen: ReaderListen, auto: AutoTurnState, controlsVisible: Boolean, hasChapters: Boolean) {
    when {
        listen.here -> ReadAloudBar(listen, hasChapters)
        auto.enabled && controlsVisible -> AutoTurnBar(auto)
    }
}

/**
 * The small player under the page while the voice reads: back and forward a sentence, play and
 * pause, the speed, the sleep timer, the voice and language, and stop. A line above says what the
 * voice is doing, or how long the sleep timer has left.
 */
@Composable
fun ReadAloudBar(listen: ReaderListen, hasChapters: Boolean) {
    val context = LocalContext.current
    val prefs = remember { ReadAloudPrefs(context) }
    val fetching by ReadAloudPlayback.fetching.collectAsState()
    val timer by ReadAloudPlayback.sleepTimer.collectAsState()
    val atChapterEnd by ReadAloudPlayback.sleepAtChapterEnd.collectAsState()
    val sleepSeconds = sleepTimerSeconds(timer)
    var rate by remember { mutableFloatStateOf(prefs.speechRate) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var voices by rememberSaveable { mutableStateOf(false) }
    val state = listen.state
    val waitingFor = fetching

    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            Text(
                text = when {
                    sleepSeconds != null -> stringResource(R.string.listen_sleep_countdown, sleepSeconds / 60, sleepSeconds % 60)
                    atChapterEnd -> stringResource(R.string.listen_sleep_at_chapter_end)
                    state == ReadAloudState.PLAYING && waitingFor != null -> stringResource(R.string.listen_fetching, TtsLanguages.label(waitingFor))
                    state == ReadAloudState.LOADING -> stringResource(R.string.listen_loading)
                    state == ReadAloudState.PAUSED -> stringResource(R.string.listen_paused)
                    else -> stringResource(R.string.listen_reading)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { listen.skip(-1) }, enabled = state != ReadAloudState.LOADING) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.listen_action_previous))
                }
                if (state == ReadAloudState.LOADING) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FilledTonalIconButton(onClick = listen::playPause) {
                        val playing = state == ReadAloudState.PLAYING
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.listen_action_pause else R.string.listen_action_play),
                        )
                    }
                }
                IconButton(onClick = { listen.skip(1) }, enabled = state != ReadAloudState.LOADING) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.listen_action_next))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    val speedLabel = stringResource(R.string.listen_speed, SpeechRates.label(rate))
                    // TalkBack hears "Speed 1.25×" rather than the bare mark the button shows.
                    TextButton(onClick = { speedMenu = true }, modifier = Modifier.semantics { contentDescription = speedLabel }) {
                        Text(SpeechRates.label(rate))
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        for (option in SpeechRates.RATES) {
                            DropdownMenuItem(
                                text = { Text(SpeechRates.label(option)) },
                                trailingIcon = if (option == rate) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                                onClick = {
                                    speedMenu = false
                                    rate = option
                                    prefs.speechRate = option
                                    ReadAloudService.settingsChanged(context)
                                },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { sleepMenu = true }) {
                        Icon(Icons.Filled.Bedtime, contentDescription = stringResource(R.string.listen_sleep))
                    }
                    SleepTimerMenu(
                        expanded = sleepMenu,
                        // Like the watch, a timer is set only while the voice is playing; turning one off works always.
                        canSet = state == ReadAloudState.PLAYING,
                        selectedMinutes = timer?.minutes,
                        atChapterEnd = atChapterEnd,
                        hasChapters = hasChapters,
                        onSelect = { minutes -> sleepMenu = false; ReadAloudService.setSleepTimer(context, listen.bookId, minutes) },
                        onDismiss = { sleepMenu = false },
                    )
                }
                IconButton(onClick = { voices = true }) {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.listen_voice))
                }
                IconButton(onClick = listen::stop) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.listen_action_stop))
                }
            }
        }
    }
    if (voices) VoiceSheet(bookId = listen.bookId, onDismiss = { voices = false })
}

@Composable
private fun SleepTimerMenu(
    expanded: Boolean,
    canSet: Boolean,
    selectedMinutes: Int?,
    atChapterEnd: Boolean,
    hasChapters: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val off = selectedMinutes == null && !atChapterEnd
        DropdownMenuItem(
            text = { Text(stringResource(R.string.listen_sleep_off)) },
            trailingIcon = if (off) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
            onClick = { onSelect(0) },
        )
        for (minutes in SleepTimer.MINUTES) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.listen_sleep_minutes, minutes)) },
                trailingIcon = if (selectedMinutes == minutes) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                enabled = canSet,
                onClick = { onSelect(minutes) },
            )
        }
        if (hasChapters) DropdownMenuItem(
            text = { Text(stringResource(R.string.listen_sleep_chapter)) },
            trailingIcon = if (atChapterEnd) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
            enabled = canSet,
            onClick = { onSelect(ReadAloudService.SLEEP_CHAPTER_END) },
        )
    }
}

/**
 * The time the sleep timer has left, in whole seconds, refreshed on each second while the screen
 * is up. The deadline itself belongs to the service, which ends reading whether or not this runs.
 */
@Composable
private fun sleepTimerSeconds(timer: SleepTimer.State?): Long? {
    val lifecycleOwner = LocalLifecycleOwner.current
    val seconds by produceState(timer?.remainingSeconds(SystemClock.elapsedRealtime()), timer, lifecycleOwner) {
        value = timer?.remainingSeconds(SystemClock.elapsedRealtime())
        if (timer != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                value = timer.remainingSeconds(now)
                val remaining = timer.deadlineMillis - now
                if (remaining <= 0) break
                // Refresh on the next displayed-second boundary, including after waking the screen.
                delay(((remaining - 1) % 1_000) + 1)
            }
        }
    }
    return if (timer == null) null else seconds
}
