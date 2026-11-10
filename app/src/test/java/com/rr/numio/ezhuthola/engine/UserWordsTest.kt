package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserWordsTest {

    private val rules = MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())
    private val mlFreq = WordFrequencies.fromTsv(javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence())
    private val enLines = javaClass.getResource("/en_words.tsv")!!.readText()

    @Test fun savesAndLoads() {
        val words = UserWords()
        words.learn("machane"); words.learn("machane"); words.learn("നാളെ")
        assertTrue(words.dirty)
        val again = UserWords.fromTsv(words.toTsv())
        assertFalse(words.dirty)
        assertEquals(2, again.count("machane"))
        assertEquals(1, again.count("നാളെ"))
    }

    @Test fun pickedMalayalamWordComesFirst() {
        val user = UserWords()
        val ml = MalayalamSuggester(MalayalamEngine(rules), mlFreq, userWords = user)
        val before = ml.suggest("paranju")
        val other = before.words.first { it != before.best }
        user.learn(other)
        assertEquals(other, ml.suggest("paranju").best)
    }

    @Test fun taughtEnglishWordIsNotCorrected() {
        val user = UserWords()
        val en = EnglishSuggester.fromTsv(enLines.lineSequence(), userWords = user)
        assertEquals("machine", en.suggest("machane").best)
        user.learn("machane")
        assertEquals("machane", en.suggest("machane").best)
        assertEquals("machane", en.suggest("mach").words.first())
    }

    @Test fun rankedMostUsedFirst() {
        val words = UserWords()
        words.learn("b"); words.learn("a"); words.learn("c"); words.learn("c")
        assertEquals(listOf("c" to 2, "a" to 1, "b" to 1), words.ranked())
    }

    @Test fun keepsToItsLimit() {
        val words = UserWords(maxWords = 10)
        repeat(3) { words.learn("often") }
        for (i in 1..20) words.learn("w$i")
        assertTrue(words.words.size <= 10)
        assertTrue("often" in words)
    }

    @Test fun clearEmptiesAndMarksDirty() {
        val words = UserWords.fromTsv("swag\t3")
        assertFalse(words.dirty)
        words.clear()
        assertTrue(words.isEmpty())
        assertTrue(words.dirty)
    }

    @Test fun dictionaryWordsAreCleanedUp() {
        assertEquals("machane", UserWords.clean("  Machane "))
        assertEquals("don't", UserWords.clean("don't"))
        assertEquals("e-mail", UserWords.clean("e-mail"))
        assertEquals("4g", UserWords.clean("4G"))
        assertEquals(null, UserWords.clean(""))
        assertEquals(null, UserWords.clean("two words"))
        assertEquals(null, UserWords.clean("123"))
        assertEquals(null, UserWords.clean("hi!"))
        assertEquals(null, UserWords.clean("a".repeat(41)))
    }

    @Test fun deletedDictionaryWordIsGone() {
        val words = UserWords()
        words.learn("machane")
        words.forget("machane")
        assertFalse("machane" in words)
        assertTrue(words.dirty)
    }

    @Test fun malayalamDictionaryWordsAreCleanedUp() {
        assertEquals("അമ്മച്ചി", UserWords.cleanMalayalam(" അമ്മച്ചി "))
        assertEquals(null, UserWords.cleanMalayalam("ammachi"))
        assertEquals(null, UserWords.cleanMalayalam("അമ്മ ച്ചി"))
        assertEquals(null, UserWords.cleanMalayalam("്"))
        assertEquals(null, UserWords.cleanMalayalam(""))
    }

    @Test fun ownMalayalamWordComesFirstEvenIfTheListLacksIt() {
        val user = UserWords()
        val ml = MalayalamSuggester(MalayalamEngine(rules), mlFreq, userWords = user)
        user.learn("കുഞ്ഞാട്ടൻ")
        assertEquals("കുഞ്ഞാട്ടൻ", ml.suggest("kunjattan").best)
        // Loose spellings find it too (long/short vowels, ട/ത).
        assertTrue("കുഞ്ഞാട്ടൻ" in ml.suggest("kunjaattan").words)
        user.forget("കുഞ്ഞാട്ടൻ")
        assertTrue(user.version > 0)
    }

    @Test fun howYouTypeItBringsTheWordFirst() {
        val ml = MalayalamSuggester(MalayalamEngine(rules), mlFreq, userWords = UserWords())
        ml.shortcuts = mapOf("ammachy" to listOf("അമ്മച്ചി"))
        // Exact: first, so space types it.
        assertEquals("അമ്മച്ചി", ml.suggest("ammachy").best)
        // Still typing: offered second.
        assertEquals("അമ്മച്ചി", ml.suggest("ammac").words[1])
        // Too short to guess: left alone.
        assertTrue("അമ്മച്ചി" !in ml.suggest("am").words)
    }
}
