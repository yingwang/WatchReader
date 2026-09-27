package com.watchreader.wear.tts

/** Accessed on the service's main dispatcher. Invalidate before stopping the engine. */
internal class UtteranceSession {
    var generation: Long = 0
        private set

    fun invalidate(): Long = ++generation
    fun id(index: Int): String = "$generation:$index"
    fun index(id: String): Int? {
        val parts = id.split(':')
        if (parts.size != 2 || parts[0].toLongOrNull() != generation) return null
        return parts[1].toIntOrNull()?.takeIf { it >= 0 }
    }
}
