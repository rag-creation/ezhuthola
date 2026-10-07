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

    @Test fun manglishGetsNoEnglishLookAlikes() {
        val guarded = EnglishSuggester.fromTsv(
            javaClass.getResource("/en_words.tsv")!!.readText().lineSequence(),
            isManglish = { it == "visesham" }
        )
        val s = guarded.suggest("visesham")
        assertEquals("visesham", s.best)
        assertTrue(s.words.none { it == "bisexual" })
    }

    @Test fun manglishWordsStayAndAreSuggested() {
        val withManglish = EnglishSuggester.fromTsv(
            javaClass.getResource("/en_words.tsv")!!.readText().lineSequence(),
            extra = sequenceOf("machane\t15000", "kazhicho\t15000")
        )
        assertEquals("machane", withManglish.suggest("machane").best)
        assertTrue("kazhicho" in withManglish.suggest("kazhi").words)
    }

    @Test fun quietWordsAreKeptButNeverOffered() {
        val quiet = EnglishSuggester.fromTsv(
            javaClass.getResource("/en_words.tsv")!!.readText().lineSequence(),
            known = sequenceOf("myre")
        )
        assertEquals("myre", quiet.suggest("myre").best)
        assertTrue(quiet.suggest("my").words.none { it == "myre" })
        assertTrue(quiet.suggest("myr").words.none { it == "myre" })
    }

    @Test fun correctionShownInStrip() {
        assertEquals("setting", words("setteng").first())
    }

    // ---- Chat words and learning from what you type --------------------------

    private val mainLines = javaClass.getResource("/en_words.tsv")!!.readText()
    private val extraLines = javaClass.getResource("/en_extra_words.tsv")!!.readText()

    private fun chatEnglish(used: UserWords = UserWords()) = EnglishSuggester.fromTsv(
        mainLines.lineSequence(), extra = extraLines.lineSequence(), used = used
    )

    @Test fun chatWordsAreSuggested() {
        val chat = chatEnglish()
        assertTrue("bro" in chat.suggest("br").words)
        assertTrue("tbh" in chat.suggest("tb").words)
        assertTrue("ngl" in chat.suggest("ng").words)
        assertTrue("idk" in chat.suggest("id").words)
        assertTrue("swag" in chat.suggest("swa").words)
        assertEquals("ohh", chat.suggest("oh").words.first())   // not "ohio"
    }

    @Test fun extraListKeepsTheMainListsOrder() {
        val chat = chatEnglish()
        assertEquals("tomorrow", chat.suggest("tomo").words.first())
        assertEquals("setting", chat.suggest("setteng").best)
        assertEquals("the", chat.suggest("teh").best)
        assertEquals("don't", chat.suggest("dont").words.first())
    }

    @Test fun chatWordsAreNotCorrected() {
        val chat = chatEnglish()
        for (w in listOf("tbh", "ngl", "idk", "lmao", "bruh", "swag", "pls")) {
            assertEquals(w, chat.suggest(w).best)
        }
    }

    @Test fun wordsYouUseMoveUp() {
        val used = UserWords()
        val chat = chatEnglish(used)
        assertEquals("drink", chat.suggest("dr").words.first())
        used.learn("drama")
        used.learn("drama")
        assertEquals("drama", chat.suggest("dr").words.first())
        assertEquals("swag", chatEnglish(UserWords().apply { learn("swag") }).suggest("sw").words.first())
    }

    @Test fun usedWordsOutsideTheFirstMatchesStillShow() {
        val used = UserWords()
        repeat(3) { used.learn("brochure") }
        assertEquals("brochure", chatEnglish(used).suggest("br").words.first())
    }

    @Test fun knowsListedAndTaughtWords() {
        val taught = UserWords()
        val en = EnglishSuggester.fromTsv(mainLines.lineSequence(), userWords = taught)
        assertTrue(en.knows("Brother"))
        assertTrue(!en.knows("ngl"))
        assertTrue(chatEnglish().knows("ngl"))
        taught.learn("arun")
        assertTrue(en.knows("arun"))
    }

    private val withKnown by lazy {
        EnglishSuggester.fromTsv(
            javaClass.getResource("/en_words.tsv")!!.readText().lineSequence(),
            known = javaClass.getResource("/en_known_words.txt")!!.readText().lineSequence(),
        )
    }

    @Test fun rareRealWordsAreLeftAlone() {
        assertTrue(!english.knows("filch"))
        assertTrue(withKnown.knows("filch"))
        assertTrue(withKnown.knows("Thoraxes"))
        assertTrue(withKnown.knows("brother's"))
        assertEquals("primly", withKnown.suggest("primly").best)
        assertEquals("filch", withKnown.suggest("filch").best)
    }

    @Test fun rareRealWordsAreNotSuggested() {
        assertTrue("primly" !in withKnown.suggest("prim").words)
        assertTrue("filch" !in withKnown.suggest("filc").words)
    }

    @Test fun typosAreStillFixedWithTheSpellingList() {
        assertEquals("the", withKnown.suggest("teh").best)
        assertEquals("setting", withKnown.suggest("setteng").best)
        assertEquals("receive", withKnown.suggest("recieve").best)
        assertEquals("definitely", withKnown.suggest("definately").best)
        assertEquals("finally", withKnown.suggest("finaly").best)
        assertEquals("friend", withKnown.suggest("freind").best)
    }

    @Test fun namesKeepTheirSpelling() {
        val en = chatEnglish()
        assertEquals("BBC", en.suggest("bbc").best)
        assertEquals("GitHub", en.suggest("github").best)
        assertEquals("F-Droid", en.suggest("fdroid").best)
        assertEquals("Numio", en.suggest("Numio").best)
        assertEquals("YOUTUBE", en.suggest("YOUTUBE").best)
        assertTrue("F-Droid" in en.suggest("fdr").words)
        assertTrue("GitLab" in en.suggest("gitl").words)
        assertTrue(en.knows("fdroid"))
        assertEquals("fever", en.suggest("fever").best)
    }
}
