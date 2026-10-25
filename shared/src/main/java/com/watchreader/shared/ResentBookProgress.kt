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

/**
 * A place read on the other device, put into this device's copy of the book, [length] characters
 * long. The two copies can differ (the apps a version apart, the bundled guide rewritten on one
 * side), and an offset past the end showed the book as more than read, then was taken for no place
 * at all when the book was next sent. A [length] not known yet (0) keeps the offset, never below 0.
 */
fun offsetWithin(offset: Int, length: Int): Int = if (length > 0) offset.coerceIn(0, length) else offset.coerceAtLeast(0)
