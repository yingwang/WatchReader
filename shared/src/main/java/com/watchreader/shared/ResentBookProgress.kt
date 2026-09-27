package com.watchreader.shared

/** EOF is a valid saved position, not a reason to restart a completed book. */
fun resentBookOffset(previousOffset: Int?, previousLength: Int?, newLength: Int): Int =
    previousOffset?.takeIf { previousLength == newLength && it in 0..newLength } ?: 0
