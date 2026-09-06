package com.watchreader.wear.reader

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import com.watchreader.wear.R
import java.io.File

/**
 * The faces the reader offers.
 *
 * Two come from the system and cover both scripts: the sans family is Roboto for Latin and the
 * Hei-style Noto Sans CJK for Chinese, and the serif family is Noto Serif with the Song-style Noto
 * Serif CJK behind it. The Chinese faces are also offered on their own, so a reader can hold a book
 * in Song while its English quotations stay in the same face rather than switching mid-sentence.
 * The last one, a Kai calligraphic face, travels with the app because no watch ships it.
 */
object Typefaces {
    data class Face(val key: String, val label: String, val family: () -> FontFamily)

    private const val CJK_SANS = "/system/fonts/NotoSansCJK-Regular.ttc"
    private const val CJK_SERIF = "/system/fonts/NotoSerifCJK-Regular.ttc"

    /** Both Noto CJK collections order their faces Japanese, Korean, Simplified, Traditional. */
    private const val SIMPLIFIED_CHINESE_TTC_INDEX = 2

    /**
     * Built once: every screen then hands Compose the same family objects, so its typeface cache
     * hits instead of parsing a twenty-megabyte collection again on each recomposition.
     */
    private val faces: List<Face> by lazy {
        buildList {
            add(Face("sans", "Sans") { FontFamily.SansSerif })
            add(Face("serif", "Serif") { FontFamily.Serif })
            systemFace("hei", "Hei", CJK_SANS)?.let { add(it) }
            systemFace("song", "Song", CJK_SERIF)?.let { add(it) }
            val kai = FontFamily(Font(R.font.lxgw_wenkai))
            add(Face("kai", "Kai") { kai })
        }
    }

    /** Only the faces this watch can actually render, in the order they cycle. */
    fun available(): List<Face> = faces

    fun familyFor(key: String): FontFamily =
        available().firstOrNull { it.key == key }?.family?.invoke() ?: FontFamily.SansSerif

    fun labelFor(key: String): String =
        available().firstOrNull { it.key == key }?.label ?: available().first().label

    private fun systemFace(key: String, label: String, path: String): Face? {
        val file = File(path)
        if (!file.isFile) return null
        // A collection that will not load is worse than an option that was never offered. Loading
        // it as a plain Font takes face 0, which is the Japanese one and draws 直, 骨, 角 and their
        // like in Japanese forms; the Simplified face has to be asked for by index.
        val family = runCatching {
            android.graphics.Typeface.Builder(file).setTtcIndex(SIMPLIFIED_CHINESE_TTC_INDEX).build()
                ?.let { FontFamily(Typeface(it)) }
        }.getOrNull() ?: return null
        return Face(key, label) { family }
    }
}
