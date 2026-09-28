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
}