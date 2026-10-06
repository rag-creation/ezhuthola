package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingWordFilterTest {

    @Test fun realWordsAreNoted() {
        assertTrue(MissingWordFilter.worthNoting("machane", "hey machane"))
        assertTrue(MissingWordFilter.worthNoting("numio", "numio"))
        assertTrue(MissingWordFilter.worthNoting("ezhuthola", "try ezhuthola"))
    }

    @Test fun shortBitsAreSkipped() {
        assertFalse(MissingWordFilter.worthNoting("qr", "scan the qr"))
        assertFalse(MissingWordFilter.worthNoting("re", "re"))
    }

    @Test fun linkPartsAreSkipped() {
        assertFalse(MissingWordFilter.worthNoting("https", "open https"))
        assertFalse(MissingWordFilter.worthNoting("www", "www"))
        assertFalse(MissingWordFilter.worthNoting("getnumio", "see https://getnumio"))
        assertFalse(MissingWordFilter.worthNoting("getnumio", "www.getnumio"))
        assertFalse(MissingWordFilter.worthNoting("example", "mail you@example"))
    }
}
