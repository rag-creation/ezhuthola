package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How Malayalis really type: "h" added or dropped (dh, kh, gh), letters doubled by feel,
 * rare conjuncts skipped, and the odd finger slip. These are real inputs from testing.
 */
class LooseManglishTest {

    private val rules = MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())
    private val engine = MalayalamEngine(rules)

    // Like the keyboard: the main list plus Ezhuthola's own extra words.
    private val suggester = MalayalamSuggester(
        engine,
        WordFrequencies.fromTsv(
            javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence() +
                javaClass.getResource("/ml_extra_words.tsv")!!.readText().lineSequence()
        ),
    )

    private fun primary(typed: String) = engine.transliterate(typed).primary
    private fun best(typed: String) = suggester.suggest(typed).best
    private fun strip(typed: String) = suggester.suggest(typed).words

    // ---- dh is usually ദ ---------------------------------------------------------

    @Test fun dhIsDaByDefault() {
        assertEquals("ദൈവം", primary("dhaivam"))
        assertEquals("ദൂരം", primary("dhooram"))
        assertEquals("ദൈവം", best("dhaivam"))
        assertEquals("ദൂരം", best("dhooram"))
    }

    @Test fun realDhaWordsStillWin() {
        assertEquals("ധൈര്യം", best("dhairyam"))
        assertEquals("ധ്യാനം", best("dhyanam"))
        assertEquals("മധുരം", best("madhuram"))
        assertEquals("അധ്യാപകൻ", best("adhyapakan"))
        assertTrue("ധനം" in strip("dhanam"))
    }

    @Test fun dhCanBeDoubledOrTha() {
        assertEquals("ബുദ്ധി", best("budhi"))
        assertEquals("ബുദ്ധി", best("buddhi"))
        assertEquals("യുദ്ധം", best("yudham"))
        assertEquals("കഥ", best("kadha"))
    }

    @Test fun ndhIsOneConjunct() {
        assertEquals("ന്ധ", primary("ndha"))
        assertEquals("ബന്ധം", best("bandham"))
        assertEquals("സന്ധ്യ", best("sandhya"))
        assertEquals("അന്ധൻ", best("andhan"))
    }

    @Test fun ndCanBeNda() {
        assertEquals("സുന്ദരി", best("sundari"))
        assertEquals("ചന്ദ്രൻ", best("chandran"))
        assertEquals("നന്ദി", best("nandi"))
        assertEquals("സന്ദേശം", best("sandesham"))
    }

    // ---- ർ inside a word -------------------------------------------------------------

    @Test fun deadRBeforeAConsonantIsChillu() {
        assertEquals("അർജുൻ", primary("arjun"))
        assertEquals("സർവം", primary("sarvam"))
        assertEquals("വിദ്യാർഥി", best("vidhyarthi"))
        assertEquals("ചേർത്ത്", best("cherth"))
    }

    @Test fun rBeforeYStaysJoined() {
        assertEquals("ധൈര്യം", best("dhairyam"))
        assertTrue(primary("dhairyam").contains("ര്യ"))
    }

    // ---- Skipped conjuncts, doubled letters, typos ---------------------------------

    @Test fun skippedVaStillFindsTheWord() {
        // Typed without the second ്വ, the way people do.
        assertTrue("ദ്വന്ദ്വയുദ്ധം" in strip("dhvandayudham"), strip("dhvandayudham").toString())
        assertEquals("ദ്വന്ദ്വയുദ്ധം", best("dvandvayudham"))
    }

    @Test fun doubledLetterStillFindsTheWord() {
        assertEquals("അപ്രത്യക്ഷം", best("apprathyaksham"))
        assertEquals("അപ്രത്യക്ഷം", best("aprathyaksham"))
    }

    @Test fun oneTypoIsOfferedNotForced() {
        // "s" where "a" belongs (they're neighbours on the keyboard), plus a doubled p.
        val words = strip("apprathysksham")
        assertEquals("അപ്രത്യക്ഷം", words[1], words.toString())   // one tap away…
        assertTrue(words[0] != "അപ്രത്യക്ഷം")                      // …but space keeps what was typed
    }

    @Test fun shortWordsAreNotFixed() {
        // Too short to guess a typo safely: no surprise words.
        assertEquals(listOf("ക്സ്യ്ഖ്"), strip("xyq").take(1))
    }

    @Test fun realWordsAreNotDisturbed() {
        assertEquals("ശാന്തം", best("santham"))
        assertEquals("സ്വന്തം", best("swantham"))
        assertEquals("കുട്ടികൾ", best("kuttikal"))
        assertEquals("വിശേഷങ്ങൾ", best("visheshangal"))
        assertEquals("ഞാൻ", best("njan"))
        assertEquals("പറഞ്ഞു", best("paranju"))
    }

    @Test fun oneTypoAwayCoversTheUsualSlips() {
        val v = MalayalamSuggester.oneTypoAway("abc")
        assertTrue("bc" in v)     // extra letter
        assertTrue("bac" in v)    // swapped
        assertTrue("sbc" in v)    // neighbour key (a → s)
        assertTrue("ahbc" in v)   // skipped h
        assertTrue("abc" !in v)
    }
}
