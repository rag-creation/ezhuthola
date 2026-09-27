package com.rr.numio.ezhuthola.engine

/**
 * English word completion: "tomo" → tomorrow, "hel" → hello, help, held.
 *
 * Words come from en_words.tsv ("word<TAB>count", most common first).
 * Data: FrequencyWords by Hermit Dave (CC BY-SA 4.0), based on OpenSubtitles.
 *
 * Space always types exactly what you typed (no autocorrect yet); tap a suggestion to use it.
 */
class EnglishSuggester(
    private val words: List<String>,   // lowercase, most common first
    private val maxShown: Int = 3,
) {
    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val prefix = typed.lowercase()

        // The list is sorted by how common each word is, so the first matches are the best ones.
        val matches = ArrayList<String>(maxShown)
        for (w in words) {
            // Apostrophes are ignored, so "dont" finds don't and "im" finds I'm.
            val plain = if ('\'' in w) w.replace("'", "") else w
            if (w != prefix && plain.startsWith(prefix)) {
                matches += matchCase(w, typed)
                if (matches.size == maxShown) break
            }
        }
        return Suggestions(typed = typed, best = typed, words = matches)
    }

    /** Keep the user's capitals: "Tomo" → "Tomorrow", "TOMO" → "TOMORROW". "i" words → "I". */
    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        word == "i" || word.startsWith("i'") -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    companion object {
        fun fromTsv(lines: Sequence<String>): EnglishSuggester =
            EnglishSuggester(lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(0, tab) else null
            }.toList())
    }
}
