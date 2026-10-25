package com.watchreader.shared.reader

/**
 * A place read on the other device, as a reader on the page [pageStart, pageEnd) of a book
 * [length] long would be offered it: null when it is on that page already (the end of the book
 * counting as the last page), and the place, kept within the book, otherwise.
 */
fun placeToOffer(place: Int, pageStart: Int, pageEnd: Int, length: Int): Int? {
    val target = place.coerceIn(0, length)
    val onThisPage = target in pageStart until pageEnd || (target >= length && pageEnd >= length)
    return if (onThisPage) null else target
}
