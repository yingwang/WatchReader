package com.watchreader.wear.tts

import java.util.Locale

object LanguageDetector {
    fun detect(text: String): Locale {
        val cjkCount = text.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val total = text.count { it.isLetterOrDigit() }
        return if (total > 0 && cjkCount.toFloat() / total > 0.3f) {
            Locale.SIMPLIFIED_CHINESE
        } else {
            Locale.US
        }
    }
}
