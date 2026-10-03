package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Forms the word list doesn't have (or has only rarely) are built from a real base + ending. */
class SuffixSplitterTest {

    private val suggester = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence())
    )

    private fun best(typed: String) = suggester.suggest(typed).best

    @Test fun anuswaramNounsTakeTtha() {
        assertEquals("പുസ്തകത്തിന്റെ", best("pusthakathinte")) // പുസ്തകം + ിന്റെ
        assertEquals("മലയാളത്തിൽ", best("malayalathil"))     // മലയാളം + ിൽ
    }

    @Test fun chilluBecomesFullLetterBeforeVowel() {
        assertEquals("അച്ഛനോട്", best("achanodu"))           // അച്ഛൻ + ോട്
    }

    @Test fun chilluStaysBeforeConsonant() {
        assertEquals("കൂട്ടുകാർക്ക്", best("koottukaarkku"))    // കൂട്ടുകാർ + ക്ക്
    }

    @Test fun vowelEndingTakesGlide() {
        assertEquals("ചേച്ചിയുടെ", best("chechiyude"))        // ചേച്ചി + യ + ുടെ
    }

    @Test fun wholeWordsInTheListStillWin() {
        assertEquals("വീട്ടിലേക്ക്", best("veettilekku"))
        assertEquals("അവന്റെ", best("avante"))
        assertEquals("അവളുടെ", best("avalude"))
        assertEquals("നാളെ", best("nale"))
        assertEquals("കാണണം", best("kaananam"))
    }

    @Test fun manglishChIsUsuallyDouble() {
        assertEquals("അച്ഛൻ", best("achan"))
        assertEquals("ചേച്ചി", best("chechi"))
        assertEquals("വെച്ച്", best("vechu"))
    }
}
