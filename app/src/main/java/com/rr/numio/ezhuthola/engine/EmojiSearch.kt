package com.rr.numio.ezhuthola.engine

import java.util.TreeMap

/**
 * Emoji search in English, Manglish and Malayalam: "love", "chiri", "kollam", "ചിരി".
 *
 * Pure Kotlin, no Android code, so it can be unit-tested on a laptop.
 *
 * Data:
 *  - emoji_keywords.tsv: Unicode CLDR annotations (English + Malayalam), in the order of
 *    Unicode's emoji-test.txt. Unicode License v3. Built by tools/build_emoji_data.py.
 *  - emoji_manglish.tsv: Ezhuthola's own Manglish words ("pwoli", "umma"…), GPL-3.0.
 *
 * Manglish works by asking the Malayalam engine what the letters could mean ("chiri" → ചിരി)
 * and matching that against the Malayalam keywords, with the same loose matching the
 * suggestion strip uses (ല/ള, ന/ണ, short/long vowels…).
 */
class EmojiSearch(
    entries: List<EmojiEntry>,
    manglish: Map<String, List<String>> = emptyMap(),
) {
    private val emojis: List<String> = entries.map { it.emoji }

    /** English word → (emoji position, weight). */
    private val english = TreeMap<String, MutableList<Hit>>()

    /** Malayalam word, loosened (see [MalayalamSuggester.looseKey]) → (emoji position, weight). */
    private val malayalam = TreeMap<String, MutableList<Hit>>()

    /** Ezhuthola's own Manglish words → (emoji position, weight). */
    private val manglishIndex = TreeMap<String, MutableList<Hit>>()

    private data class Hit(val emoji: Int, val weight: Int)

    /** Emojis people use most get a small push, so "love" shows ❤️ before 💌. */
    private val popular = HashSet<Int>()

    /** Emojis that match a word only by accident get pushed back ("love" → 🏩 love hotel). */
    private val rare = HashSet<Int>()

    init {
        val position = HashMap<String, Int>()
        entries.forEachIndexed { i, e ->
            position[e.emoji] = i
            position.putIfAbsent(e.emoji.replace(VS16, ""), i)

            // The emoji's own name counts more than its other keywords.
            words(e.enName).forEach { english.add(it.lowercase(), i, NAME) }
            e.enWords.flatMap(::words).forEach { english.add(it.lowercase(), i, KEYWORD) }

            words(e.mlName).forEach { malayalam.add(loose(it), i, NAME) }
            e.mlWords.flatMap(::words).forEach { malayalam.add(loose(it), i, KEYWORD) }
        }
        POPULAR.forEach { e -> (position[e] ?: position[e.replace(VS16, "")])?.let(popular::add) }
        RARE.forEach { e -> (position[e] ?: position[e.replace(VS16, "")])?.let(rare::add) }
        manglish.forEach { (word, list) ->
            list.forEachIndexed { rank, emoji ->
                // The first emoji listed for a word is the best one for it.
                val i = position[emoji] ?: position[emoji.replace(VS16, "")] ?: return@forEachIndexed
                manglishIndex.add(word.lowercase(), i, OWN - rank.coerceAtMost(3))
            }
        }
    }

    val size: Int get() = emojis.size

    /** A whole English emoji word ("love", "angry"): no Manglish reading needed. */
    fun isEnglishWord(term: String): Boolean = english.containsKey(term.trim().lowercase())

    /**
     * The Malayalam to show under the search box: the first reading that really matches
     * Unicode's Malayalam emoji words ("chiri" → ചിരി). English words get none, so
     * "love" never shows a made-up reading like ലോവെ.
     */
    fun bestReading(term: String, readings: List<String>): String? {
        if (isEnglishWord(term)) return null
        return readings.take(MAX_READINGS).firstOrNull { word ->
            val key = searchKey(word)
            key.length >= 2 &&
                (malayalam.containsKey(key) || malayalam.subMap(key, false, key + '\uFFFF', false).isNotEmpty())
        }
    }

    /**
     * Emojis for [query], best first.
     *
     * Every word of the query has to match ("red heart" → ❤️ only).
     * [readings] gives the Malayalam words a Manglish word could be ("chiri" → [ചിരി, ചിരിച്ചു]);
     * leave it empty for English-only search.
     */
    fun search(
        query: String,
        readings: (String) -> List<String> = { emptyList() },
        limit: Int = 48,
    ): List<String> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val found = searchAll(terms, readings, limit)
        // "kili poyi" finds nothing word by word, but "kilipoyi" is in Ezhuthola's own list.
        if (found.isEmpty() && terms.size > 1) return searchAll(listOf(terms.joinToString("")), readings, limit)
        return found
    }

    private fun searchAll(terms: List<String>, readings: (String) -> List<String>, limit: Int): List<String> {
        var total: HashMap<Int, Int>? = null
        for (term in terms) {
            val scores = scoreTerm(term, readings)
            total = if (total == null) {
                scores
            } else {
                // Keep only emojis that matched every word so far.
                HashMap<Int, Int>().also { both ->
                    for ((i, s) in total) scores[i]?.let { both[i] = s + it }
                }
            }
            if (total.isEmpty()) return emptyList()
        }
        for (i in popular) total!!.computeIfPresent(i) { _, s -> s + POPULAR_BONUS }
        for (i in rare) total!!.computeIfPresent(i) { _, s -> s - RARE_PENALTY }
        return total!!.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { emojis[it.key] }
    }

    /** Best score each emoji gets for one word of the query. */
    private fun scoreTerm(term: String, readings: (String) -> List<String>): HashMap<Int, Int> {
        val scores = HashMap<Int, Int>()
        fun hit(i: Int, score: Int) {
            if (score > (scores[i] ?: 0)) scores[i] = score
        }

        val isMalayalamScript = term.any { it in '\u0D00'..'\u0D7F' }
        if (!isMalayalamScript) {
            match(english, term, minPrefix = 1, ::hit)
            match(manglishIndex, term, minPrefix = 3, ::hit)
        }

        // A whole English emoji word ("love") isn't Manglish, so don't guess Malayalam for it.
        val mlWords = when {
            isMalayalamScript -> listOf(term)
            english.containsKey(term) -> emptyList()
            else -> readings(term)
        }
        for (word in mlWords.take(MAX_READINGS)) {
            val key = searchKey(word)
            if (key.length < 2) continue
            // Manglish is a guess, so it counts a little less than an English word.
            match(malayalam, key, minPrefix = 2) { i, s -> hit(i, s - 2) }
        }
        return scores
    }

    /**
     * Exact word = weight × 10. A word that starts with the term = weight × 6,
     * a little less the more letters are still missing ("lov" → love before lovely).
     */
    private fun match(
        index: TreeMap<String, MutableList<Hit>>,
        term: String,
        minPrefix: Int,
        hit: (Int, Int) -> Unit,
    ) {
        index[term]?.forEach { hit(it.emoji, it.weight * 10) }
        if (term.length < minPrefix) return
        for ((word, hits) in index.subMap(term, false, term + '\uFFFF', false)) {
            val missing = (word.length - term.length).coerceAtMost(5)
            hits.forEach { hit(it.emoji, it.weight * 6 - missing) }
        }
    }

    private fun TreeMap<String, MutableList<Hit>>.add(word: String, emoji: Int, weight: Int) {
        if (word.isEmpty()) return
        val list = getOrPut(word) { mutableListOf() }
        val old = list.indexOfFirst { it.emoji == emoji }
        when {
            old < 0 -> list += Hit(emoji, weight)
            list[old].weight < weight -> list[old] = Hit(emoji, weight)
        }
    }

    companion object {
        private const val NAME = 3
        private const val KEYWORD = 2
        private const val OWN = 4          // Ezhuthola's Manglish list: chosen by hand, so it wins
        private const val MAX_READINGS = 6
        private const val VS16 = "\uFE0F"  // "show as emoji" mark
        private const val VIRAMA = '\u0D4D'
        private const val ANUSWARAM = '\u0D02'      // ം

        private const val POPULAR_BONUS = 6
        private const val RARE_PENALTY = 12
        private val RARE = listOf("🏩")
        private val POPULAR = listOf(
            "❤️", "😂", "🥰", "😍", "😭", "🙏", "👍", "😊", "🔥", "😘", "🥺", "✨",
            "😁", "🤣", "💕", "😢", "😅", "👌", "🎉", "💯", "😡", "🤔", "😎", "🙂",
        )

        /**
         * Loose key to look a Malayalam reading up with. While typing "chir" the engine says ചിർ,
         * so the end mark is dropped to still find ചിരി. A word ending in ം (പുച്ഛം, സ്നേഹം) is
         * already whole: keep its end, or പുച്ഛം would also match പൂച്ചമുഖം (cat face).
         */
        private fun searchKey(word: String): String =
            if (word.endsWith(ANUSWARAM)) loose(word) else loose(word).trimEnd(VIRAMA)

        /** Loose spelling, see [MalayalamSuggester.looseKey] ("hrudayam" ഹ്രുദയം = ഹൃദയം). */
        private fun loose(word: String) = MalayalamSuggester.looseKey(word)

        /** "face with tears of joy" → face, with, tears, of, joy. Colons and commas are dropped. */
        private fun words(text: String): List<String> =
            text.split(' ', ':', ',', '“', '”', '"', '(', ')').filter { it.isNotEmpty() }

        /** emoji_keywords.tsv: emoji, English name, English keywords, Malayalam name, Malayalam keywords. */
        fun parseKeywords(lines: Sequence<String>): List<EmojiEntry> = lines.mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 5 || c[0].isEmpty()) return@mapNotNull null
            EmojiEntry(
                emoji = c[0],
                enName = c[1],
                enWords = c[2].split('|').filter { it.isNotEmpty() },
                mlName = c[3],
                mlWords = c[4].split('|').filter { it.isNotEmpty() },
            )
        }.toList()

        /** emoji_manglish.tsv: "word<TAB>emoji emoji…". Lines starting with # are comments. */
        fun parseManglish(lines: Sequence<String>): Map<String, List<String>> = buildMap {
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val list = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                line.substring(0, tab).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { put(it, (get(it) ?: emptyList()) + list) }
            }
        }
    }
}

/** One emoji with its CLDR names and keywords. */
data class EmojiEntry(
    val emoji: String,
    val enName: String,
    val enWords: List<String>,
    val mlName: String,
    val mlWords: List<String>,
)
