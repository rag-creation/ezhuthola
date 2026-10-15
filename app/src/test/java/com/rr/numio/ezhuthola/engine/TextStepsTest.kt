package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class TextStepsTest {

    @Test fun oneWordTakesTheSpaceBeforeTheCursorToo() {
        assertEquals("world ".length, TextSteps.lengthOfWordsBefore("hello world ", 1))
        assertEquals("world".length, TextSteps.lengthOfWordsBefore("hello world", 1))
    }

    @Test fun moreWordsGoFurtherBack() {
        assertEquals("hello world ".length, TextSteps.lengthOfWordsBefore("hello world ", 2))
        assertEquals(" two three".length, TextSteps.lengthOfWordsBefore("one two three", 2))
    }

    @Test fun neverGoesPastTheStart() {
        assertEquals(5, TextSteps.lengthOfWordsBefore("hello", 4))
        assertEquals(0, TextSteps.lengthOfWordsBefore("", 2))
        assertEquals(0, TextSteps.lengthOfWordsBefore("abc", 0))
    }

    @Test fun malayalamWordsSplitOnSpaces() {
        assertEquals("സുഖമാണോ".length, TextSteps.lengthOfWordsBefore("നമസ്കാരം സുഖമാണോ", 1))
    }

    @Test fun countsWords() {
        assertEquals(0, TextSteps.wordsBefore("   "))
        assertEquals(3, TextSteps.wordsBefore(" one  two three "))
    }
}
