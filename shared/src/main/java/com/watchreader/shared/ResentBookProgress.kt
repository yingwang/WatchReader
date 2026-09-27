package com.watchreader.shared

/**
 * Where a book sent to the watch again picks up. The same text keeps its place exactly, the end
 * included, so a finished book stays finished. A text of another length keeps the same share of
 * the way through: an offset carried over as it stands points at some other passage, and starting
 * again from the first page throws the reading away.
 */
fun resentBookOffset(previousOffset: Int?, previousLength: Int?, newLength: Int): Int {
    if (previousOffset == null || previousLength == null || previousOffset !in 0..previousLength) return 0
    if (previousLength == newLength) return previousOffset
    if (previousLength == 0) return 0
    return (previousOffset.toLong() * newLength / previousLength).toInt().coerceIn(0, newLength)
}
