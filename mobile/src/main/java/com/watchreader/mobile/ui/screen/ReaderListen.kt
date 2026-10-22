package com.watchreader.mobile.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.core.content.ContextCompat
import com.watchreader.mobile.R
import com.watchreader.mobile.service.ReadAloudService
import com.watchreader.mobile.tts.ReadAloudNotice
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.ReadAloudState
import com.watchreader.mobile.tts.TtsLanguages
import com.watchreader.mobile.ui.viewmodel.ReaderViewModel
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import kotlinx.coroutines.flow.combine

/**
 * The reader's side of reading aloud: whether the voice is reading this book, the sentence it is
 * on, and the few things the reader asks of it. The service does the reading; this only starts
 * it, controls it and follows it.
 */
@Stable
class ReaderListen internal constructor(
    val bookId: String,
    private val context: Context,
    private val askForNotifications: () -> Unit,
    private val playback: State<ReadAloudState>,
    private val playingBook: State<String?>,
    private val sentence: State<IntRange?>,
) {
    val state: ReadAloudState get() = playback.value

    /** The voice is reading this book, playing, paused or opening it. */
    val here: Boolean get() = playingBook.value == bookId && playback.value != ReadAloudState.IDLE

    val playing: Boolean get() = here && playback.value == ReadAloudState.PLAYING

    /** The sentence the voice is on in this book, for the page to tint; null when there is none. */
    val spoken: IntRange?
        get() = sentence.value.takeIf { here && (state == ReadAloudState.PLAYING || state == ReadAloudState.PAUSED) }

    /**
     * Reads the book aloud from [offset], the page's place in the text. The first time, a phone on
     * Android 13 or later is asked for leave to show the notification; listening goes ahead
     * whatever the answer, without the notification when it is no.
     */
    fun start(offset: Int) {
        val prefs = ReadAloudPrefs(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.notificationsAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.notificationsAsked = true
            askForNotifications()
        }
        ReadAloudService.play(context, bookId, offset)
    }

    fun stop() {
        if (here) ReadAloudService.stop(context)
    }

    fun playPause() {
        when {
            playing -> ReadAloudService.pause(context)
            here && state == ReadAloudState.PAUSED -> ReadAloudService.resume(context)
        }
    }

    fun skip(by: Int) {
        if (here) ReadAloudService.skip(context, by)
    }
}

/**
 * Connects the reader to the voice: the page follows the sentence being read and turns with it,
 * a page turned or a jump made by hand while listening moves the voice there, and what the voice
 * has to say (a missing voice above all) comes up on the reader's snackbar.
 */
@Composable
fun rememberReaderListen(bookId: String, vm: ReaderViewModel, snackbar: SnackbarHostState): ReaderListen {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val playback = ReadAloudPlayback.state.collectAsState()
    val playingBook = ReadAloudPlayback.bookId.collectAsState()
    val sentence = ReadAloudPlayback.sentence.collectAsState()
    val listen = remember(bookId) {
        ReaderListen(bookId, context.applicationContext, { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, playback, playingBook, sentence)
    }

    LaunchedEffect(bookId) {
        combine(ReadAloudPlayback.bookId, ReadAloudPlayback.sentence) { id, range -> if (id == bookId) range else null }
            .collect { range -> if (range != null) vm.followSpoken(range.first) }
    }
    LaunchedEffect(listen.here, listen.state) { vm.setReadAloud(listen.here, listen.playing) }
    LaunchedEffect(bookId) {
        vm.moved.collect { offset -> if (listen.here) ReadAloudService.seek(context, bookId, offset) }
    }
    LaunchedEffect(bookId) {
        ReadAloudPlayback.notices.collect { notice ->
            val message = when (notice) {
                ReadAloudNotice.NoEngine -> context.getString(R.string.listen_no_engine)
                ReadAloudNotice.NoAudioFocus -> context.getString(R.string.listen_no_focus)
                is ReadAloudNotice.NoVoice -> context.getString(R.string.listen_no_voice, TtsLanguages.label(notice.locale))
                is ReadAloudNotice.VoiceOffline -> context.getString(R.string.listen_voice_offline, TtsLanguages.label(notice.locale))
                is ReadAloudNotice.VoiceFailed -> context.getString(R.string.listen_voice_failed, TtsLanguages.label(notice.locale))
            }
            // A missing voice is offered from the system's own voice download page, as far as the
            // engine has one; the other notices have nothing to offer but patience.
            val offerVoices = notice is ReadAloudNotice.NoVoice || notice is ReadAloudNotice.VoiceFailed
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = if (offerVoices) context.getString(R.string.listen_voice_get) else null,
                withDismissAction = offerVoices,
                duration = if (offerVoices) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) openVoiceDownloads(context)
        }
    }
    return listen
}

/** The engine's page for getting voice data; nothing happens on a phone whose engine has none. */
internal fun openVoiceDownloads(context: Context) {
    runCatching {
        context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The two entries the reader's menu gains: Listen, which starts or stops the voice from this
 * page, and auto page turn, which opens its sheet. The two never run together: whichever is
 * started stops the other.
 */
@Composable
fun ReaderPlaybackMenuItems(
    listen: ReaderListen,
    auto: AutoTurnState,
    /** The page's place in the text; null until a page is up. */
    pageStart: Int?,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(if (listen.here) R.string.listen_menu_stop else R.string.listen_menu_start)) },
        leadingIcon = { Icon(Icons.Filled.Headphones, contentDescription = null) },
        enabled = listen.here || pageStart != null,
        onClick = {
            onDismiss()
            if (listen.here) {
                listen.stop()
            } else if (pageStart != null) {
                auto.stop()
                listen.start(pageStart)
            }
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.auto_turn)) },
        leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null) },
        enabled = auto.on || pageStart != null,
        onClick = {
            onDismiss()
            auto.openSheet()
        },
    )
}

/**
 * Tints the sentence being read on the page, behind the text. It lays out only the lines the
 * sentence is on, the way the page lays out every line, so the tint sits exactly under the words.
 * [spoken] is read while drawing, so a new sentence redraws the tint alone and not the page.
 */
@Composable
fun SpokenSentenceTint(
    page: Paginator.Page,
    geometry: PageGeometry,
    measurer: TextMeasurer,
    style: TextStyle,
    spoken: () -> IntRange?,
    color: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        val sentence = spoken() ?: return@Canvas
        if (sentence.last < page.start || sentence.first >= page.end) return@Canvas
        for (line in page.lines) {
            if (line.end <= line.start) continue
            val from = maxOf(sentence.first, line.start)
            val to = minOf(sentence.last + 1, line.end)
            if (to <= from) continue
            val slot = geometry.slots.getOrNull(line.slot) ?: continue
            val layout = measurer.measure(
                text = AnnotatedString(page.text(line)),
                style = style,
                overflow = TextOverflow.Clip,
                maxLines = 1,
                constraints = Constraints(maxWidth = slot.width),
            )
            val path = layout.getPathForRange(from - line.start, to - line.start)
            translate(slot.left, slot.top) { drawPath(path, color) }
        }
    }
}
