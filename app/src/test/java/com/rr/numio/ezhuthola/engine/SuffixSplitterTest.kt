package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Forms the word list doesn't have (or has only rarely) are built from a real base + ending. */
class SuffixSplitterTest {

    private val suggester = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(
            javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence() +
                java.io.File("src/main/assets/ml_extra_words.tsv").readLines() // as the keyboard does
        )
    )

    private fun best(typed: String) = suggester.suggest(typed).best

    @Test fun anuswaramNounsTakeTtha() {
        assertEquals("പുസ്തകത്തിന്റെ", best("pusthakathinte")) // പുസ്തകം + ിന്റെ
        assertEquals("മലയാളത്തിൽ", best("malayalathil"))     // മലയാളം + ിൽ
    }

    @Test fun placesFromEzhutholasOwnList() {
        assertEquals("കേരളത്തിൽ", best("keralathil"))       // കേരളം + ിൽ
        assertEquals("കേരളം", best("keralam"))
        assertEquals("ദുബായിലേക്ക്", best("dubaayilekku"))  // ദുബായ് (in the main list)
        assertEquals("തൃശ്ശൂർ", best("thrissur"))
        assertEquals("പത്തനംതിട്ടയിൽ", best("pathanamthittayil"))
    }

    @Test fun theAppSpellsItsOwnName() {
        assertEquals("എഴുത്തോല", best("ezhuthola"))
        assertEquals("എഴുത്തോല", best("ezhuththola"))
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

    @Test fun englishWordsDontTurnIntoUnrelatedWords() {
        // "but" once matched ബൂത്ത് (booth) through the ത്ത rule
        assertEquals(false, suggester.suggest("but").words.contains("ബൂത്ത്"))
        assertEquals(false, suggester.suggest("that").words.contains("ത്തത്"))
    }

    @Test fun manglishChIsUsuallyDouble() {
        assertEquals("അച്ഛൻ", best("achan"))
        assertEquals("ചേച്ചി", best("chechi"))
        assertEquals("വെച്ച്", best("vechu"))
    }
}
