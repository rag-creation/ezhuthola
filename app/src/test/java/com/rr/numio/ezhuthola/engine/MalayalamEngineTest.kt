package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MalayalamEngineTest {

    private val engine = MalayalamEngine(
        MalayalamRules.fromJson(
            javaClass.getResource("/ezhuthola_rules.json")!!.readText()
        )
    )

    private fun primary(word: String) = engine.transliterate(word).primary
    private fun candidates(word: String) = engine.transliterate(word).candidates

    // ---- Main result -------------------------------------------------------

    @Test fun doubledLetters() {
        assertEquals("അമ്മ", primary("amma"))
        assertEquals("നിന്നെ", primary("ninne"))
        assertEquals("കുട്ടി", primary("kutti"))
    }

    @Test fun plainWords() {
        assertEquals("രമ", primary("rama"))
        assertEquals("പനി", primary("pani"))
        assertEquals("എന്റെ", primary("ente"))
        assertEquals("ഇഷ്ടം", primary("ishtam"))
        assertEquals("വീടു", primary("veedu"))    // d inside a word → ട
        assertEquals("ദൈവം", primary("daivam"))   // d at the start stays ദ
    }

    @Test fun viramaBetweenConsonants() {
        assertEquals("പ്രെമ", primary("prema"))
        assertEquals("സ്നെഹം", primary("sneham"))
    }

    @Test fun wordEnd() {
        assertEquals("അവൻ", primary("avan"))      // chillu
        assertEquals("കമൽ", primary("kamal"))     // chillu
        assertEquals("കെട്ട്", primary("kett"))    // dead consonant → virama
        assertEquals("എന്ന്", primary("enn"))      // doubled → virama, not chillu
        assertEquals("കണ്ടു", primary("kandu"))    // typed u stays ു
        assertEquals("അവനു", primary("avanu"))    // typed u never becomes chillu
    }

    @Test fun midWordNj() {
        assertEquals("കുഞ്ഞു", primary("kunju"))
        assertEquals("കുഞ്ഞ്", primary("kunj"))
    }

    @Test fun autoCapitalFirstLetter() {
        assertTrue(primary("Njan").startsWith("ഞ"))   // not ണ
        assertEquals(primary("amma"), primary("Amma"))
    }

    // ---- Suggestion strip --------------------------------------------------

    @Test fun finalUAlternative() {
        assertTrue("കണ്ട്" in candidates("kandu"))
        assertTrue("അവന്" in candidates("avanu"))
    }

    @Test fun letterAlternatives() {
        assertTrue("കുട്ടികൾ" in candidates("kuttikal"))   // ൽ → ൾ
        assertTrue("അവൺ" in candidates("avan"))
        assertTrue("പറഞ്ഞു" in candidates("paranju"))     // ര → റ
        assertTrue("വീട്" in candidates("veedu"))         // ദ → ട, plus final u → ്
        assertTrue("എണ്ണ" in candidates("enna"))          // nn → ണ്ണ
    }

    @Test fun vowelAlternatives() {
        assertTrue("സ്നേഹം" in candidates("sneham"))
        assertTrue("പ്രേമ" in candidates("prema"))
    }

    @Test fun primaryAlwaysFirst() {
        val r = engine.transliterate("paranju")
        assertEquals(r.primary, r.candidates.first())
    }
}
