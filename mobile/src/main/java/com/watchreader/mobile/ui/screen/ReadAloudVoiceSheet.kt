package com.watchreader.mobile.ui.screen

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.watchreader.mobile.R
import com.watchreader.mobile.service.ReadAloudService
import com.watchreader.mobile.tts.LanguageDetector
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.TtsLanguages
import java.util.Locale

/** What the engine said about one language the book is read in. */
private class LanguageVoices(val locale: Locale, val state: TtsLanguages.State, val voices: List<Pair<String, Locale>>)

/**
 * The languages a book is read aloud in, each spoken in the voice chosen for it, the order they
 * are listed in for the voice choices: the book's own languages, with the reader's choice for its
 * Latin-script text in place of the guess when one was made.
 */
internal fun languagesShown(detected: Set<Locale>, latinChoice: Locale?): List<Locale> {
    val latin = LanguageDetector.LATIN_LANGUAGES
    val base = if (latinChoice == null) detected
    else detected.filterNot { found -> latin.any { LanguageDetector.sameLanguage(it, found) } }.toSet() + latinChoice
    val order = LanguageDetector.LANGUAGES
    return base.sortedBy { found -> order.indexOfFirst { LanguageDetector.sameLanguage(it, found) }.let { if (it < 0) Int.MAX_VALUE else it } }
}

/** Whether a book written in [detected] is in Latin letters throughout, so its language may be chosen by hand. */
internal fun isLatinBook(detected: Set<Locale>): Boolean =
    detected.isNotEmpty() && detected.all { found -> LanguageDetector.LATIN_LANGUAGES.any { LanguageDetector.sameLanguage(it, found) } }

/**
 * Voice and language for the book being read: for each language it is read in, whether the phone
 * has the voice, a way to the engine's voice downloads when it does not, and the voices to choose
 * between when there are several. A book in Latin letters can also be set to be read in another
 * language, for the few the detector cannot tell apart.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceSheet(bookId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ReadAloudPrefs(context) }
    val detected by ReadAloudPlayback.languages.collectAsState()
    var latinChoice by remember { mutableStateOf(prefs.latinLanguage(bookId)) }
    val shown = remember(detected, latinChoice) { languagesShown(detected, latinChoice) }
    var answer by remember { mutableStateOf<List<LanguageVoices>?>(null) }
    var noEngine by remember { mutableStateOf(false) }
    var chosen by remember { mutableIntStateOf(0) }

    // The engine is asked again on coming back from its download page, which may have added a voice.
    val lifecycleOwner = LocalLifecycleOwner.current
    var asked by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) asked++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(asked, shown) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                noEngine = true
                return@TextToSpeech
            }
            val listed = TtsLanguages.voices(tts)
            answer = shown.map { locale ->
                LanguageVoices(locale, TtsLanguages.stateOf(tts, locale, listed), TtsLanguages.choices(tts, locale).map { it.name to it.locale })
            }
        }
        onDispose { TtsLanguages.release(engine) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.listen_voice), style = MaterialTheme.typography.titleMedium)
            if (latinChoice != null || isLatinBook(detected)) {
                LanguageChoice(
                    detected = detected,
                    choice = latinChoice,
                    onChoose = { locale ->
                        latinChoice = locale
                        prefs.setLatinLanguage(bookId, locale)
                        ReadAloudService.settingsChanged(context)
                    },
                )
            }
            val languages = answer
            when {
                noEngine -> Text(stringResource(R.string.listen_no_engine), modifier = Modifier.padding(top = 16.dp))
                languages == null || shown.isEmpty() -> Text(
                    stringResource(R.string.listen_voice_asking),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> for (language in languages) {
                    // Read again whenever a chip is picked, so the tick moves at once.
                    val selected = chosen.let { prefs.voice(language.locale) }
                    Text(
                        TtsLanguages.label(language.locale),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                when (language.state) {
                                    TtsLanguages.State.INSTALLED -> R.string.listen_voice_installed
                                    TtsLanguages.State.DOWNLOAD -> R.string.listen_voice_download
                                    TtsLanguages.State.NONE -> R.string.listen_voice_missing
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (language.state != TtsLanguages.State.INSTALLED) {
                            OutlinedButton(onClick = { openVoiceDownloads(context) }) { Text(stringResource(R.string.listen_voice_get)) }
                        }
                    }
                    if (language.voices.size > 1 || (language.voices.isNotEmpty() && selected != null)) {
                        val labels = TtsLanguages.voiceLabels(language.voices.map { it.second })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            val pick = { name: String? ->
                                prefs.setVoice(language.locale, name)
                                chosen++
                                ReadAloudService.settingsChanged(context)
                            }
                            FilterChip(
                                selected = selected == null || language.voices.none { it.first == selected },
                                onClick = { pick(null) },
                                label = { Text(stringResource(R.string.listen_voice_default)) },
                            )
                            language.voices.forEachIndexed { i, (name, _) ->
                                FilterChip(selected = selected == name, onClick = { pick(name) }, label = { Text(labels[i]) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Read the book in": the detector's guess, or a Latin-script language the reader picks instead. */
@Composable
private fun LanguageChoice(detected: Set<Locale>, choice: Locale?, onChoose: (Locale?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val guess = detected.firstOrNull { found -> LanguageDetector.LATIN_LANGUAGES.any { LanguageDetector.sameLanguage(it, found) } }
    val automatic = stringResource(R.string.listen_language_auto, guess?.let(TtsLanguages::label) ?: "")
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.listen_language), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                Text(choice?.let(TtsLanguages::label) ?: automatic)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(automatic) },
                    trailingIcon = if (choice == null) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    onClick = { open = false; onChoose(null) },
                )
                for (locale in LanguageDetector.LATIN_LANGUAGES.sortedBy(TtsLanguages::label)) {
                    val picked = choice != null && LanguageDetector.sameLanguage(choice, locale)
                    DropdownMenuItem(
                        text = { Text(TtsLanguages.label(locale)) },
                        trailingIcon = if (picked) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                        onClick = { open = false; onChoose(locale) },
                    )
                }
            }
        }
    }
}
