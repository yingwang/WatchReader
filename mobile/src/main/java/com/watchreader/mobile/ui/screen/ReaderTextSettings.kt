package com.watchreader.mobile.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.shared.R as SharedR
import java.io.File

/** Space between lines, as a multiple of the text size. NORMAL is the spacing the reader always had. */
enum class LineSpacing(val key: String, val factor: Float, @StringRes val label: Int) {
    TIGHT("tight", 1.4f, R.string.reader_spacing_tight),
    NORMAL("normal", 1.65f, R.string.reader_spacing_normal),
    LOOSE("loose", 1.9f, R.string.reader_spacing_loose);

    companion object {
        fun fromKey(key: String?): LineSpacing = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/** The blank edge around the page. NORMAL is the margin the reader always had. */
enum class PageMargin(val key: String, val dp: Int, @StringRes val label: Int) {
    NARROW("narrow", 12, R.string.reader_margin_narrow),
    NORMAL("normal", 24, R.string.reader_margin_normal),
    WIDE("wide", 40, R.string.reader_margin_wide);

    companion object {
        fun fromKey(key: String?): PageMargin = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/** A typeface the reader offers; [family] is built once and shared by every page. */
class ReadingFace(val key: String, @StringRes val label: Int, val family: FontFamily)

/**
 * The faces the phone's reader offers: the same five as the watch (see its Typefaces), so a book
 * can look alike on both, and Literata, Google's reading face for Play Books, for Latin text. Sans
 * and Serif are the system families, which cover both scripts (Chinese in Serif comes out in Noto
 * Serif CJK on a Pixel). Hei and Song are the Chinese faces on their own, so English quotations
 * in a Chinese book stay in the same face. Kai travels with the app, from the shared module.
 * Characters a face lacks fall back to the system font.
 */
object ReaderTypefaces {
    private const val CJK_SANS = "/system/fonts/NotoSansCJK-Regular.ttc"
    private const val CJK_SERIF = "/system/fonts/NotoSerifCJK-Regular.ttc"

    /** Both Noto CJK collections order their faces Japanese, Korean, Simplified, Traditional. */
    private const val SIMPLIFIED_CHINESE_TTC_INDEX = 2

    val faces: List<ReadingFace> by lazy {
        buildList {
            add(ReadingFace("sans", R.string.reader_font_sans, FontFamily.SansSerif))
            add(ReadingFace("serif", R.string.reader_font_serif, FontFamily.Serif))
            systemFace("hei", R.string.reader_font_hei, CJK_SANS)?.let { add(it) }
            systemFace("song", R.string.reader_font_song, CJK_SERIF)?.let { add(it) }
            add(ReadingFace("kai", R.string.reader_font_kai, FontFamily(Font(SharedR.font.lxgw_wenkai))))
            add(ReadingFace("literata", R.string.reader_font_literata, FontFamily(Font(R.font.literata_regular))))
        }
    }

    /** The face saved under [key], or Sans for a key this phone cannot show. */
    fun byKey(key: String?): ReadingFace = faces.firstOrNull { it.key == key } ?: faces.first()

    private fun systemFace(key: String, @StringRes label: Int, path: String): ReadingFace? {
        val file = File(path)
        if (!file.isFile) return null
        // Face 0 of the collection is the Japanese one; the Simplified face is asked for by index.
        val family = runCatching {
            android.graphics.Typeface.Builder(file).setTtcIndex(SIMPLIFIED_CHINESE_TTC_INDEX).build()
                ?.let { FontFamily(Typeface(it)) }
        }.getOrNull() ?: return null
        return ReadingFace(key, label, family)
    }
}

/** The size, spacing and margin a page is laid out with. */
data class TextSettings(val fontSize: Int, val spacing: LineSpacing, val margin: PageMargin)

/**
 * Three ready-made pages. Comfortable is the reader's old default, so a reader who never opens
 * the settings sees no change. A preset is shown as chosen only while all three values match it.
 */
enum class ReadingPreset(val settings: TextSettings, @StringRes val label: Int) {
    COMPACT(TextSettings(16, LineSpacing.TIGHT, PageMargin.NARROW), R.string.reader_preset_compact),
    COMFORTABLE(TextSettings(18, LineSpacing.NORMAL, PageMargin.NORMAL), R.string.reader_preset_comfortable),
    LARGE(TextSettings(24, LineSpacing.LOOSE, PageMargin.NORMAL), R.string.reader_preset_large);

    companion object {
        fun matching(settings: TextSettings): ReadingPreset? = entries.firstOrNull { it.settings == settings }
    }
}

/**
 * A row of chips, the chosen one filled; for the presets none is filled once a value is changed
 * by hand. The row wraps rather than scrolls, so no choice is ever cut off at the dialog's edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: (T) -> Int,
    family: (T) -> FontFamily? = { null },
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                // A font's own name is set in that font, which says more than the name does.
                label = { Text(stringResource(label(option)), fontFamily = family(option)) },
            )
        }
    }
}
