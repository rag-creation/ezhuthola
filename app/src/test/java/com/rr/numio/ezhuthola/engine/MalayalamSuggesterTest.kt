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

    @Test fun typedUNeverEndsInChillu() {
        assertEquals("ആണ്", best("aanu"))     // "is", not ആൺ (male)
        assertEquals("ആൺ", best("aan"))
        assertEquals("കണ്ടു", best("kandu"))   // no chillu twin: unchanged
    }

    @Test fun looseMatchingFindsRealWords() {
        assertEquals("വിശേഷങ്ങൾ", best("visheshangal")) // 3 changes: െ→േ, ശ→ഷ, ൽ→ൾ
        assertEquals("സുഖമാണോ", best("sukamano"))       // k→ഖ, a→ാ, ന→ണ, o→ോ
        assertEquals("സുഖമല്ലേ", best("sukhamalle"))
    }

    @Test fun unknownWordsStillWork() {
        // A name that isn't in the word list: the engine's own spelling is used.
        assertEquals("രാഗെശ്", best("raagesh"))
    }

    // ---- English words typed in Malayalam mode ------------------------------------

    private val withEnglish = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence()),
        english = EnglishInMalayalam.fromTsv(javaClass.getResource("/en_ml_words.tsv")!!.readText().lineSequence())
    )

    private fun en(typed: String) = withEnglish.suggest(typed).best

    @Test fun englishWordsAreWrittenTheMalayaliWay() {
        val expected = mapOf(
            "media" to "മീഡിയ", "one" to "വൺ", "two" to "ടു", "four" to "ഫോർ", "five" to "ഫൈവ്",
            "eight" to "എയ്റ്റ്", "nine" to "നൈൻ", "twelve" to "ട്വെൽവ്", "and" to "ആൻഡ്",
            "more" to "മോർ", "first" to "ഫസ്റ്റ്", "lady" to "ലേഡി", "this" to "ദിസ്", "is" to "ഈസ്",
            "important" to "ഇമ്പോർട്ടന്റ്", "we" to "വി", "need" to "നീഡ്", "to" to "ടു", "fix" to "ഫിക്സ്",
            "it" to "ഇറ്റ്", "phone" to "ഫോൺ", "school" to "സ്കൂൾ", "whatsapp" to "വാട്സാപ്പ്",
        )
        for ((typed, malayalam) in expected) assertEquals(malayalam, en(typed), typed)
        assertEquals("ഫോർ", en("Four"))   // auto-capital
    }

    @Test fun manglishWordsStayMalayalam() {
        // Also English words, but much more common as Manglish: the Malayalam word stays first.
        assertEquals("മോൻ", en("mon"))
        assertEquals("ഇവൻ", en("ivan"))
        assertEquals("മോളെ", en("mole"))
        assertEquals("കളി", en("kali"))
        assertEquals("നീ", en("nee"))
        // Ordinary Manglish is untouched.
        assertEquals("ഞാൻ", en("njan"))
        assertEquals("അമ്മ", en("amma"))
        assertEquals("എന്റെ", en("ente"))
    }

    @Test fun englishSpellingIsAlwaysOffered() {
        // Malayalam first, the English spelling right after it: one tap away.
        val mon = withEnglish.suggest("mon").words
        assertEquals("മോൻ", mon[0])
        assertEquals("മോൺ", mon[1])
        val ivan = withEnglish.suggest("ivan").words
        assertEquals("ഇവൻ", ivan[0])
        assertEquals("ഐവൻ", ivan[1])
    }
}
