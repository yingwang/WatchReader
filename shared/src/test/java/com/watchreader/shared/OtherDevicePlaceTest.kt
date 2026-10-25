package com.watchreader.shared

import com.watchreader.shared.reader.placeToOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtherDevicePlaceTest {
    @Test fun aPlaceOnThePageOpenIsNotOffered() {
        assertNull(placeToOffer(1_000, 1_000, 1_400, 10_000))
        assertNull(placeToOffer(1_399, 1_000, 1_400, 10_000))
    }

    @Test fun aPlaceElsewhereIsOfferedBackwardsAsWellAsForwards() {
        assertEquals(1_400, placeToOffer(1_400, 1_000, 1_400, 10_000))
        assertEquals(200, placeToOffer(200, 1_000, 1_400, 10_000))
        assertEquals(9_000, placeToOffer(9_000, 1_000, 1_400, 10_000))
    }

    @Test fun theEndOfTheBookIsOnTheLastPage() {
        assertNull(placeToOffer(10_000, 9_700, 10_000, 10_000))
        assertEquals(10_000, placeToOffer(10_000, 1_000, 1_400, 10_000))
        // A place past the end of this copy of the book is its end.
        assertNull(placeToOffer(12_000, 9_700, 10_000, 10_000))
    }
}
