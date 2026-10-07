package com.rr.numio.ezhuthola.engine

/**
 * English suggestions: word completion ("tomo" → tomorrow) and typo correction
 * ("setteng" → setting, "teh" → the).
 *
 * Words come from en_words.tsv ("word<TAB>count", most common first).
 * Data: FrequencyWords by Hermit Dave (CC BY-SA 4.0), based on OpenSubtitles.
 * Ezhuthola's own en_extra_words.tsv adds chat words the subtitles miss or bury (bro, tbh, ngl).
 * en_known_words.txt (from ESDB/SCOWL by Kevin Atkinson) is a quiet spelling list: correct but
 * rare words ("photosynthesis") are left alone and never go on Missing words, but are not suggested.
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
    /** Known words this person types, with how often: they move up in completions ("br" → bro). */
    private val used: UserWords = UserWords(),
    /** Real but rare words: never suggested, only kept from being "corrected" or listed as missing. */
    private val known: Set<String> = emptySet(),
    /** How names are written, by their plain lowercase form: "bbc" → BBC, "fdroid" → F-Droid. */
    private val display: Map<String, String> = emptyMap(),
) {
    private val rankOf = HashMap<String, Int>(words.size * 2).apply {
        words.forEachIndexed { i, w -> putIfAbsent(w, i) }
    }

    /** Words without apostrophes, so "dont" finds don't and "im" finds I'm. */
    private val plain: Array<String> = Array(words.size) { words[it].replace("'", "") }

    /** True when the word is in the list or was taught. Anything else may be a missing word. */
    fun knows(word: String): Boolean {
        val lower = word.lowercase()
        return lower in rankOf || lower in userWords || isKnown(lower)
    }

    /** In the spelling list, or a possessive of a word that is ("photosynthesis's"). */
    private fun isKnown(lower: String): Boolean =
        lower in known || (lower.endsWith("'s") && lower.dropLast(2).let { it in known || it in rankOf })

    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val lower = typed.lowercase()
        // A real word from the spelling list counts as the rarest listed word: it stays as
        // typed unless a much more common word is one small slip away ("fro" → for).
        val rank = rankOf[lower] ?: if (isKnown(lower)) words.size else null
        val commonWord = (rank != null && rank < COMMON) || lower in userWords || used.count(lower) >= 2

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
        if (completions.size < maxShown) {
            // The most common words that fit, plus the ones this person uses, then the ones
            // they use often move up: type "bro" a couple of times and "br" offers it first.
            val pool = LinkedHashSet<String>()
            for (i in words.indices) {
                if (plain[i].startsWith(lower) && words[i] != lower) {
                    pool += words[i]
                    if (pool.size == POOL) break
                }
            }
            used.words.forEach { w ->
                if (w != lower && w in rankOf && w.replace("'", "").startsWith(lower)) pool += w
            }
            pool.filter { it !in completions }
                .sortedBy { completionScore(it) }
                .take(maxShown - completions.size)
                .forEach { completions += it }
        }

        // Strip order: the auto-correction first, then completions, then other corrections.
        val shown = LinkedHashSet<String>()
        auto?.let { shown += it.word }
        shown += completions
        corrections.forEach { shown += it.word }
        val chips = shown.filter { it != lower }.take(maxShown).map { matchCase(it, typed) }

        // A name typed in small letters gets its own spelling: "bbc" → BBC, "github" → GitHub.
        val best = auto?.let { matchCase(it.word, typed) }
            ?: display[lower]?.takeIf { lower !in userWords }?.let { matchCase(lower, typed) } // undone once: kept
            ?: typed
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

    /** Lower is better: how common the word is, minus a bonus for each time this person used it. */
    private fun completionScore(word: String): Double {
        val rank = rankOf[word] ?: words.size
        val uses = used.count(word)
        return kotlin.math.log10(rank + 2.0) - USE_BONUS * kotlin.math.log2(1.0 + uses)
    }

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
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> (display[word] ?: word).uppercase()
        word in display -> display.getValue(word)
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        word == "i" || word.startsWith("i'") -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    companion object {
        /** Words this common are always left as typed. */
        private const val COMMON = 20_000

        /** How many common words that fit are considered before ranking completions. */
        private const val POOL = 12

        /** Each doubling of how often you've used a word is worth this much rank (as log10). */
        private const val USE_BONUS = 1.5

        /**
         * [lines] is the main list, most common first. [extra] lines (Ezhuthola's own words)
         * are merged in by count: a word already in the list keeps whichever count is higher.
         * Blank lines and lines starting with # are skipped.
         */
        fun fromTsv(
            lines: Sequence<String>,
            isManglish: (String) -> Boolean = { false },
            userWords: UserWords = UserWords(),
            extra: Sequence<String> = emptySequence(),
            used: UserWords = UserWords(),
            known: Sequence<String> = emptySequence(),
        ): EnglishSuggester {
            val counts = LinkedHashMap<String, Long>()
            for (line in lines) {
                val (word, count) = parse(line) ?: continue
                counts.putIfAbsent(word, count)
            }
            var merged = false
            val display = HashMap<String, String>()
            for (line in extra) {
                // Names keep how they're written: "F-Droid" is stored as fdroid, shown as F-Droid.
                val written = line.substringBefore('\t').trim()
                val (word, count) = parse(line.replace("-", "")) ?: continue
                if (written.isNotEmpty() && written != word) display[word] = written
                if (count > (counts[word] ?: 0L)) {
                    counts[word] = count
                    merged = true
                }
            }
            // The main list is already in order; sortedBy is stable, so equal counts keep their place.
            val ordered = if (merged) counts.entries.sortedByDescending { it.value }.map { it.key }
            else counts.keys.toList()
            val knownWords = HashSet<String>()
            for (line in known) {
                val w = line.trim()
                if (w.isNotEmpty() && !w.startsWith("#")) knownWords += w.lowercase()
            }
            return EnglishSuggester(
                ordered, isManglish = isManglish, userWords = userWords, used = used, known = knownWords,
                display = display,
            )
        }

        private fun parse(line: String): Pair<String, Long>? {
            if (line.isBlank() || line.startsWith("#")) return null
            val tab = line.indexOf('\t')
            if (tab <= 0) return null
            val word = line.substring(0, tab).trim().lowercase()
            val count = line.substring(tab + 1).trim().toLongOrNull() ?: 0L
            return if (word.isEmpty()) null else word to count
        }

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