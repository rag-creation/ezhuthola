package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs every line of ml_test_cases.tsv through the keyboard's Malayalam suggester:
 * the word list plus Ezhuthola's own extra words, like on the phone.
 */
class ManglishTestSetTest {

    private val suggester = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(javaClass.getResource("/ezhuthola_rules.json")!!.readText())),
        WordFrequencies.fromTsv(
            javaClass.getResource("/ml_words.tsv")!!.readText().lineSequence() +
                javaClass.getResource("/ml_extra_words.tsv")!!.readText().lineSequence()
        ),
        english = EnglishInMalayalam.fromTsv(javaClass.getResource("/en_ml_words.tsv")!!.readText().lineSequence()),
    )

    @Test fun everyWordInTheTestSet() {
        val failures = javaClass.getResource("/ml_test_cases.tsv")!!.readText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                val (typed, want) = parts
                val words = suggester.suggest(typed).words
                val ok = if (parts.getOrNull(2) == "strip") want in words else words.firstOrNull() == want
                if (ok) null else "$typed → want $want, got $words"
            }.toList()
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
