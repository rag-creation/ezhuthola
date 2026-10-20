package com.rr.numio.ezhuthola.engine

/**
 * Word suggestions for the Arabic keyboard (beta).
 *
 * Arabic is typed with its own letters, so there is nothing to convert: the word stays
 * exactly as typed (no autocorrect) and the strip offers the most common words that start
 * with it. Many people type without hamza or harakat ("انا" for أنا, "مدرسه" for مدرسة),
 * so letters are compared in a loose form: أ إ آ ٱ = ا, ة = ه, ى = ي, ؤ = و, ئ = ي,
 * harakat ignored. The real spelling from the list is what gets suggested.
 *
 * [words] is in order of how common each word is (most common first).
 */
class ArabicSuggester(
    words: Collection<String>,
    private val userWords: UserWords = UserWords(),
    private val maxShown: Int = MAX_SHOWN,
) {
    private val list: Array<String> = words.toTypedArray()
    private val loose: Array<String> = Array(list.size) { normalize(list[it]) }

    /** For each loose first letter, the positions of words starting with it (still most common first). */
    private val byFirst: Map<Char, IntArray> = loose.indices
        .filter { loose[it].isNotEmpty() }
        .groupBy { loose[it][0] }
        .mapValues { (_, positions) -> positions.toIntArray() }

    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val key = normalize(typed)
        if (key.isEmpty()) return Suggestions(typed, typed, emptyList())

        val found = LinkedHashSet<String>()
        // Words this person picked before come first.
        userWords.words.asSequence()
            .filter { it != typed && normalize(it).startsWith(key) }
            .sortedByDescending { userWords.count(it) }
            .take(maxShown)
            .forEach { found += it }

        val positions = byFirst[key[0]] ?: IntArray(0)
        // The same word with its hamza or ta marbuta ("انا" → أنا) is the likeliest one.
        for (i in positions) {
            if (found.size >= maxShown) break
            if (loose[i] == key && list[i] != typed) found += list[i]
        }
        for (i in positions) {
            if (found.size >= maxShown) break
            if (list[i] != typed && loose[i].startsWith(key)) found += list[i]
        }
        return Suggestions(typed = typed, best = typed, words = found.take(maxShown))
    }

    companion object {
        private const val MAX_SHOWN = 5

        /** Loose form for matching: hamza seats, ta marbuta and alif maqsura folded, harakat dropped. */
        fun normalize(word: String): String {
            val out = StringBuilder(word.length)
            for (c in word) {
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> out.append('ا')
                    'ة' -> out.append('ه')
                    'ى' -> out.append('ي')
                    'ؤ' -> out.append('و')
                    'ئ' -> out.append('ي')
                    'ـ' -> {}                                  // tatweel ـ
                    in 'ً'..'ْ', 'ٰ' -> {}          // harakat
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /** Arabic letters, harakat and the لا pair: what goes into a word on the Arabic keyboard. */
        fun isArabicWordChar(c: Char): Boolean =
            c in 'ء'..'غ' || c in 'ف'..'ْ' || c == 'ٰ'
    }
}
