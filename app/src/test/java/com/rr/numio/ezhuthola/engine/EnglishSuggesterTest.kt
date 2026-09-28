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

    private fun best(typed: String) = english.suggest(typed).best

    @Test fun fixesTypos() {
        assertEquals("setting", best("setteng"))
        assertEquals("the", best("teh"))
        assertEquals("receive", best("recieve"))
        assertEquals("because", best("becuase"))
        assertEquals("definitely", best("definately"))
        assertEquals("finally", best("finaly"))
        assertEquals("friend", best("freind"))
        assertEquals("Setting", best("Setteng"))
    }

    @Test fun keepsRealWordsAndPrefixes() {
        assertEquals("setti", best("setti"))   // still typing: no correction
        assertEquals("form", best("form"))     // real word: left alone
        assertEquals("hel", best("hel"))
        assertTrue("setting" in words("setti"))
    }

    @Test fun leavesManglishAlone() {
        val guarded = EnglishSuggester.fromTsv(
            javaClass.getResource("/en_words.tsv")!!.readText().lineSequence(),
            isManglish = { it == "poda" }
        )
        assertEquals("poda", guarded.suggest("poda").best)
        assertEquals("acha", best("acha"))   // short words: only swapped letters get fixed
    }

    @Test fun correctionShownInStrip() {
        assertEquals("setting", words("setteng").first())
    }
}