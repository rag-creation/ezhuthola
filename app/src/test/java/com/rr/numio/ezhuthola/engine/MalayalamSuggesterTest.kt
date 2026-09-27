package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MalayalamSuggesterTest {

    private val suggester = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence())
    )

    private fun best(typed: String) = suggester.suggest(typed).best

    @Test fun commonWordsComeFirst() {
        assertEquals("ഞാൻ", best("njan"))
        assertEquals("പറഞ്ഞു", best("paranju"))
        assertEquals("കുട്ടികൾ", best("kuttikal"))
        assertEquals("സ്നേഹം", best("sneham"))
        assertEquals("വീട്", best("veedu"))
        assertEquals("കണ്ടു", best("kandu"))
        assertEquals("അമ്മ", best("amma"))
        assertEquals("എന്റെ", best("ente"))
        assertEquals("ഇഷ്ടം", best("ishtam"))
        assertEquals("നിങ്ങൾ", best("ningal"))
        assertEquals("നാളെ", best("nale"))       // needs two changes: a→ാ and ല→ള
    }

    @Test fun unknownWordsStillWork() {
        // A name that isn't in the word list: the engine's own spelling is used.
        assertEquals("രാഗെശ്", best("raagesh"))
    }
}
