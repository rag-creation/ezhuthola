package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnglishSuggesterTest {

    private val english = EnglishSuggester.fromTsv(
        javaClass.getResource("/en_words.tsv")!!.readText().lineSequence()
    )

    private fun words(typed: String) = english.suggest(typed).words

    @Test fun completesCommonWords() {
        assertEquals("tomorrow", words("tomo").first())
        assertTrue("hello" in words("hel"))
        assertTrue("because" in words("beca"))
    }

    @Test fun spaceKeepsWhatYouTyped() {
        assertEquals("helo", english.suggest("helo").best)
    }

    @Test fun keepsCapitals() {
        assertEquals("Tomorrow", words("Tomo").first())
        assertEquals("TOMORROW", words("TOMO").first())
        assertEquals("I'm", words("im").first())
    }

    @Test fun contractions() {
        assertTrue("don't" in words("don"))
        assertEquals("don't", words("dont").first())
        assertEquals("can't", words("cant").first())
    }

    @Test fun noSwearWordsSuggested() {
        assertTrue(words("fu").none { it.startsWith("fuck") })
    }
}
