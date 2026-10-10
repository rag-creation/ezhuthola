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
        assertEquals("ആണ്", best("aan"))      // chat: "aan" means "is"
        assert("ആൺ" in suggester.suggest("aan").words)  // "male" still one tap away
        assertEquals("അവൻ", best("avan"))     // other words ending in n keep their chillu
        assertEquals("ഇവൻ", best("ivan"))
        assertEquals("ഞാൻ", best("njan"))
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

    // Like the keyboard: the main list plus Ezhuthola's own extra words (മന്തി, കേരളം…).
    private val withEnglish = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(
            javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence() +
                javaClass.getResource("/ml_extra_words.tsv")!!.readText().lineSequence()
        ),
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

    @Test fun sentenceFromTesting() {
        // A real test sentence: ente phoninte battery low aanu… cinemakk… mandi… police station
        assertEquals("ആണ്", en("aanu"))
        assertEquals("പോവാൻ", en("povaan"))
        assertEquals("സിനിമയ്ക്ക്", en("cinemakk"))   // soft c, and യ്ക്ക് after a
        assertEquals("മന്തി", en("mandi"))          // the dish; മണ്ടി is still offered
        assert("മണ്ടി" in withEnglish.suggest("mandi").words)
        assertEquals("പോലീസ്", en("police"))
        assertEquals("സ്റ്റേഷൻ", en("station"))
        assertEquals("റെയിൽവേ", en("railway"))
    }

    @Test fun secondTestParagraph() {
        assertEquals("ഭയങ്കര", en("bhayangara"))
        assertEquals("ഇഷ്ടമായി", en("ishttamaayi"))
        assertEquals("ഇഷ്ടമായി", en("ishtamaayi"))
        assertEquals("അച്ഛന്", en("achanu"))
        assertEquals("മറ്റന്നാൾ", en("mattennal"))
        assertEquals("മറ്റന്നാൾ", en("mattannal"))
        for (t in listOf("njayarazhcha", "njayaraycha", "njaayaraazhcha")) assertEquals("ഞായറാഴ്ച", en(t), t)
        for (t in listOf("thinkalazhcha", "thingalazcha", "thinkalaazhcha")) assertEquals("തിങ്കളാഴ്ച", en(t), t)
        for (t in listOf("chovvazcha", "chovvazhchcha", "chovvaazhcha")) assertEquals("ചൊവ്വാഴ്ച", en(t), t)
        for (t in listOf("budhanaazhacha", "budhanazhcha", "budanazcha")) assertEquals("ബുധനാഴ്ച", en(t), t)
        for (t in listOf("vyazhazhcha", "vyaazhaazhcha")) assertEquals("വ്യാഴാഴ്ച", en(t), t)
        for (t in listOf("velliyazhcha", "velliazhcha", "velliyaazhcha")) assertEquals("വെള്ളിയാഴ്ച", en(t), t)
        for (t in listOf("sheniyazhcha", "shaniyazhcha", "saniyazhcha", "shaniyaycha")) assertEquals("ശനിയാഴ്ച", en(t), t)
        // Nothing nearby broke
        assertEquals("കുട്ടി", en("kutti"))
        assertEquals("അങ്ങനെ", en("angane"))
        assertEquals("അവന്", en("avanu"))
        assertEquals("അവൻ", en("avan"))
    }
}
