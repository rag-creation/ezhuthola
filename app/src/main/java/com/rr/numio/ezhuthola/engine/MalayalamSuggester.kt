package com.rr.numio.ezhuthola.engine

/**
 * How often each Malayalam word is used in real text.
 * Loaded from ml_words.tsv ("word<TAB>count", one per line, most common first).
 *
 * Data: FrequencyWords by Hermit Dave (CC BY-SA 4.0), based on OpenSubtitles.
 */
class WordFrequencies(private val counts: LinkedHashMap<String, Int>) {

    fun of(word: String): Int = counts[word] ?: 0

    val size: Int get() = counts.size

    /** Words in file order (most common first). */
    val words: Set<String> get() = counts.keys

    companion object {
        fun fromTsv(lines: Sequence<String>): WordFrequencies {
            val map = LinkedHashMap<String, Int>(100_000)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val count = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                map[line.substring(0, tab)] = count
            }
            return WordFrequencies(map)
        }
    }
}

/** What the suggestion strip shows for the word being typed. */
data class Suggestions(
    /** The Manglish letters typed, e.g. "paranju". Shown as a chip so English is one tap away. */
    val typed: String,
    /** Best Malayalam word: what space commits. */
    val best: String,
    /** Malayalam options, best first (includes [best]). */
    val words: List<String>,
)

/**
 * Engine + real-world word counts.
 *
 * 1. The engine lists the spellings the Manglish could mean.
 * 2. Loose matching finds real words that differ only in letters Manglish can't tell apart
 *    (ന/ണ, ല/ള, ശ/ഷ/സ, ട/ത, short/long vowels, k/kh…): "visheshangal" → വിശേഷങ്ങൾ.
 * 3. Everything is ranked by how common it is. Spellings nobody uses keep the engine's order.
 * 4. Words the list doesn't have are built from a real base word + ending ([SuffixSplitter]):
 *    "achanodu" → അച്ഛൻ + ോട് → അച്ഛനോട്.
 * 5. Words this person picked before ([userWords]) come first, most-picked first.
 */
class MalayalamSuggester(
    private val engine: MalayalamEngine,
    private val frequencies: WordFrequencies,
    private val maxShown: Int = 5,
    private val userWords: UserWords = UserWords(),
) {
    /** Loose key → real words with that key, most common first (max 4 each). */
    private val looseIndex: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (word in frequencies.words) {
            val list = getOrPut(looseKey(word)) { mutableListOf() }
            if (list.size < 4) list += word
        }
    }

    /** Base word + ending, for forms the word list doesn't have ("keralathil" → കേരളത്തിൽ). */
    private val splitter = SuffixSplitter(realWords = { manglish ->
        pool(manglish).map { it to frequencies.of(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
    })

    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val pool = pool(typed)

        // Forms built from a real base word count as a bit less common than the base.
        val built = splitter.split(typed)
        pool += built.keys
        fun score(word: String) = maxOf(frequencies.of(word), built[word] ?: 0)

        // Sorting is stable: equally-common words keep the engine's order.
        val ranked = pool.sortedWith(
            compareByDescending<String> { userWords.count(it) }.thenByDescending(::score)
        ).take(maxShown)
        return Suggestions(typed = typed, best = ranked.first(), words = ranked)
    }

    /** What the engine reads [typed] as, plus real words that differ only in loose letters. */
    private fun pool(typed: String): LinkedHashSet<String> {
        val engineWords = engine.transliterate(typed).candidates
        val pool = LinkedHashSet<String>(engineWords)
        engineWords.asSequence().map(::looseKey).distinct().forEach { key ->
            looseIndex[key]?.let(pool::addAll)
        }
        return pool
    }

    /**
     * How often the best Malayalam reading of [typed] is used ("poda" → പോടാ: common).
     * English mode uses this to leave Manglish alone instead of "correcting" it.
     */
    fun commonness(typed: String): Int =
        if (typed.isEmpty()) 0 else frequencies.of(suggest(typed).best)

    companion object {
        /** Letters Manglish can't tell apart are folded together. */
        private val fold: Map<Char, String> = buildMap {
            // vowel length: ാ ീ ൂ േ ോ → short (ാ disappears like the inherent a)
            put('ാ', ""); put('ീ', "ി"); put('ൂ', "ു"); put('േ', "െ"); put('ോ', "ൊ"); put('ൌ', "ൊ"); put('ൗ', "ൊ")
            put('ആ', "അ"); put('ഈ', "ഇ"); put('ഊ', "ഉ"); put('ഏ', "എ"); put('ഓ', "ഒ")
            // letters Manglish typers really mix up (g/k, b/p stay separate: people type those right)
            put('ഖ', "ക"); put('ഘ', "ഗ"); put('ഛ', "ച"); put('ഝ', "ജ")   // kh/k, gh/g, chh/ch, jh/j
            put('ഠ', "ട"); put('ഢ', "ഡ"); put('ഥ', "ത"); put('ധ', "ദ")   // aspirates
            put('ഫ', "പ"); put('ഭ', "ബ")
            put('ട', "ത")                                                 // t: ട or ത
            put('ഡ', "ദ")                                                 // d: ദ or ഡ
            put('ണ', "ന"); put('ള', "ല"); put('റ', "ര"); put('ഴ', "ല")
            put('ശ', "സ"); put('ഷ', "സ")
            // chillus → letter + virama
            put('ൻ', "ന്"); put('ൺ', "ന്"); put('ർ', "ര്"); put('ൽ', "ല്"); put('ൾ', "ല്"); put('ം', "മ്")
        }

        /**
         * Manglish "ch" is usually written double: ചേച്ചി (chechi), അച്ഛൻ (achan).
         * ൃ is typed "ru" or "ri": hrudayam → ഹൃദയം, thrissur → തൃശ്ശൂർ.
         */
        private val foldPairs = listOf(
            "ച്ഛ" to "ച", "ച്ച" to "ച",
            "\u0D4D\u0D30\u0D41" to "\u0D43", "\u0D4D\u0D30\u0D3F" to "\u0D43", // ്രു, ്രി → ൃ
        )

        /**
         * Manglish "th" inside a word is often ത്ത: പത്തനംതിട്ട (pathanamthitta).
         * Not at the very start or end, or "but" would match ബൂത്ത് (booth).
         */
        private val doubleTha = Regex("(?<=.)ത്ത(?!്$)")

        fun looseKey(word: String): String {
            var w = word
            for ((from, to) in foldPairs) if (from in w) w = w.replace(from, to)
            if ("ത്ത" in w) w = w.replace(doubleTha, "ത")
            return buildString(w.length) {
                for (c in w) append(fold[c] ?: c)
            }
        }
    }
}