package com.rr.numio.ezhuthola.engine

/**
 * English suggestions: word completion ("tomo" → tomorrow) and typo correction
 * ("setteng" → setting, "teh" → the).
 *
 * Words come from en_words.tsv ("word<TAB>count", most common first).
 * Data: FrequencyWords by Hermit Dave (CC BY-SA 4.0), based on OpenSubtitles.
 *
 * [Suggestions.best] is what space types: what you typed if it is a real word,
 * otherwise a correction when there is a close one. The typed chip keeps your own word
 * one tap away, and backspace right after a correction undoes it.
 */
class EnglishSuggester(
    private val words: List<String>,   // lowercase, most common first
    private val maxShown: Int = 3,
    /** True for Manglish like "poda" or "adipoli": never "correct" those into English. */
    private val isManglish: (String) -> Boolean = { false },
    /** Words this person taught the keyboard: never corrected, suggested first. */
    private val userWords: UserWords = UserWords(),
) {
    private val rankOf = HashMap<String, Int>(words.size * 2).apply {
        words.forEachIndexed { i, w -> putIfAbsent(w, i) }
    }

    /** Words without apostrophes, so "dont" finds don't and "im" finds I'm. */
    private val plain: Array<String> = Array(words.size) { words[it].replace("'", "") }

    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val lower = typed.lowercase()
        val rank = rankOf[lower]
        val commonWord = (rank != null && rank < COMMON) || lower in userWords

        // 1. Typo corrections. Common words are never touched ("form" stays form).
        val corrections = if (commonWord || lower.length < 3) emptyList() else corrections(lower)
        val auto = corrections.firstOrNull()?.takeIf { c ->
            val closeEnough = if (lower.length >= 6) c.cost <= 2
            else c.cost <= 1 && sameLetters(lower, c.word) // short: swaps only
            // A rare word the list does know ("realy") is kept unless the fix is far more likely.
            val beatsTyped = rank == null || c.score < score(0, rank)
            closeEnough && beatsTyped && !isManglish(lower)
        }

        // 2. Completions: the list is sorted by how common each word is, so first matches are best.
        val completions = ArrayList<String>(maxShown)
        userWords.words.asSequence()
            .filter { it != lower && it.startsWith(lower) }
            .sortedByDescending { userWords.count(it) }
            .take(maxShown)
            .forEach { completions += it }
        for (i in words.indices) {
            if (completions.size == maxShown) break
            if (plain[i].startsWith(lower) && words[i] != lower && words[i] !in completions) {
                completions += words[i]
                if (completions.size == maxShown) break
            }
        }

        // Strip order: the auto-correction first, then completions, then other corrections.
        val shown = LinkedHashSet<String>()
        auto?.let { shown += it.word }
        shown += completions
        corrections.forEach { shown += it.word }
        val chips = shown.filter { it != lower }.take(maxShown).map { matchCase(it, typed) }

        val best = auto?.let { matchCase(it.word, typed) } ?: typed
        return Suggestions(typed = typed, best = best, words = chips)
    }

    // ---- Typo correction ---------------------------------------------------

    /**
     * [cost] is in half-steps: a neighbouring key or two swapped letters = 1,
     * any other wrong / missing / extra letter = 2. [score]: lower is better; it mixes
     * how close the word is with how common it is, so "finaly" → finally, not Finlay.
     */
    private class Candidate(val word: String, val cost: Int, val score: Double)

    private fun score(cost: Int, rank: Int) = cost + kotlin.math.log10(rank + 2.0)

    private fun showLimit(length: Int) = if (length >= 5) 4 else 2      // shown in the strip

    private fun sameLetters(a: String, b: String) =
        a.length == b.length && a.toList().sorted() == b.toList().sorted()

    private fun corrections(typed: String): List<Candidate> {
        val limit = showLimit(typed.length)
        val maxLenDiff = limit / 2
        val found = ArrayList<Candidate>()
        val first = typed[0]
        for (rank in words.indices) {
            val target = plain[rank]   // a missing apostrophe costs nothing
            if (target.isEmpty() || kotlin.math.abs(target.length - typed.length) > maxLenDiff) continue
            // People rarely get the first letter wrong: it must match, be a neighbouring key,
            // or be swapped with the second ("hte" → the). This makes the search ~10× faster.
            val t0 = target[0]
            if (t0 != first && !isNeighbour(t0, first) &&
                !(target.length > 1 && t0 == typed[1] && target[1] == first)) continue
            val cost = distance(typed, target, limit)
            if (cost <= limit && words[rank] != typed) found += Candidate(words[rank], cost, score(cost, rank))
        }
        return found.sortedBy { it.score }.take(maxShown)
    }

    /** Edit distance with keyboard-aware costs; gives up early once over [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it * 2 }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i * 2
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0
                    isNeighbour(ca, cb) -> 1
                    else -> 2
                }
                var v = minOf(prev[j - 1] + sub, prev[j] + 2, cur[j - 1] + 2)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    v = minOf(v, prev2[j - 2] + 1) // swapped letters: "teh" → the
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    // ---- Helpers -----------------------------------------------------------

    /** Keep the user's capitals: "Tomo" → "Tomorrow", "TOMO" → "TOMORROW". "i" words → "I". */
    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        word == "i" || word.startsWith("i'") -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    companion object {
        /** Words this common are always left as typed. */
        private const val COMMON = 20_000

        fun fromTsv(
            lines: Sequence<String>,
            isManglish: (String) -> Boolean = { false },
            userWords: UserWords = UserWords(),
        ): EnglishSuggester =
            EnglishSuggester(lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(0, tab) else null
            }.toList(), isManglish = isManglish, userWords = userWords)

        /** Where each letter sits on the QWERTY keys (rows are shifted like the real keyboard). */
        private val keyPos: Map<Char, Pair<Double, Int>> = buildMap {
            listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5)
                .forEachIndexed { row, (letters, shift) ->
                    letters.forEachIndexed { i, c -> put(c, (i + shift) to row) }
                }
        }

        /** neighbours[a][b] = keys a and b touch, for 'a'..'z'. Worked out once. */
        private val neighbours: Array<BooleanArray> = Array(26) { a ->
            BooleanArray(26) { b ->
                val pa = keyPos.getValue('a' + a)
                val pb = keyPos.getValue('a' + b)
                a != b && kotlin.math.abs(pa.second - pb.second) <= 1 &&
                        kotlin.math.abs(pa.first - pb.first) <= 1.0
            }
        }

        private fun isNeighbour(a: Char, b: Char): Boolean =
            a in 'a'..'z' && b in 'a'..'z' && neighbours[a - 'a'][b - 'a']
    }
}