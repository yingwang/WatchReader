package com.watchreader.wear.tts

object SentenceParser {
    // Sentence-ending punctuation: Chinese and English
    private val sentenceEndPattern = Regex("""(?<=[。！？.!?])\s*|(?<=\n)""")

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return sentenceEndPattern.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun splitWithRanges(text: String): List<Pair<String, IntRange>> {
        val sentences = mutableListOf<Pair<String, IntRange>>()
        if (text.isBlank()) return sentences

        var pos = 0
        for (sentence in split(text)) {
            val start = text.indexOf(sentence, pos)
            if (start >= 0) {
                sentences.add(sentence to (start until start + sentence.length))
                pos = start + sentence.length
            }
        }
        return sentences
    }
}
