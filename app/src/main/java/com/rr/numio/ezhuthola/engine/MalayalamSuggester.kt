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

    /** All counts added up: lets a count be compared with another word list's ("per million"). */
    val total: Long by lazy { counts.values.sumOf { it.toLong() } }

    fun perMillion(word: String): Float = if (total == 0L) 0f else of(word) * 1_000_000f / total

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
 * 6. English words typed in Malayalam mode are written the Malayali way ([english]):
 *    "four" → ഫോർ, not പോർ. The English spelling comes first when the English word is clearly
 *    more common than the Malayalam reading; otherwise it's offered second ("mon" stays മോൻ).
 */
class MalayalamSuggester(
    private val engine: MalayalamEngine,
    private val frequencies: WordFrequencies,
    private val maxShown: Int = 5,
    private val userWords: UserWords = UserWords(),
    private val english: EnglishInMalayalam? = null,
) {
    /** Loose key → real words with that key, most common first (max 4 each). */
    private val looseIndex: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (word in frequencies.words) {
            val list = getOrPut(looseKey(word)) { mutableListOf() }
            if (list.size < 4) list += word
        }
    }

    /** Looser still ([laxKey]): only used when nothing real turned up. Built on first use. */
    private val laxIndex: Map<String, List<String>> by lazy {
        buildMap<String, MutableList<String>> {
            for (word in frequencies.words) {
                val list = getOrPut(laxKey(word)) { mutableListOf() }
                if (list.size < 4) list += word
            }
        }
    }

    /** Base word + ending, for forms the word list doesn't have ("keralathil" → കേരളത്തിൽ). */
    private val splitter = SuffixSplitter(realWords = { manglish ->
        // The base may be an English word: cinema + kk → സിനിമ + യ്ക്ക്, phone + inte → ഫോൺ + ിന്റെ.
        val bases = pool(manglish).also { set -> english?.of(manglish)?.let { set += it.malayalam } }
        bases.map { it to frequencies.of(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
    })

    fun suggest(typed: String): Suggestions = suggest(typed, withEnglish = true)

    /** Builds the typo-match index now, off the main thread, so the first typo doesn't stutter. */
    fun warmUp() {
        laxIndex.size
    }

    private fun suggest(typed: String, withEnglish: Boolean): Suggestions {
        if (typed.isEmpty()) return Suggestions("", "", emptyList())
        val pool = pool(typed)

        // Forms built from a real base word count as a bit less common than the base.
        val built = splitter.split(typed)
        pool += built.keys

        // A typed final "u" never ends in a chillu: aanu is ആണ്, not ആൺ (male). Subtitle text
        // often writes ആണ് with the old chillu form, so the list counts it as ആൺ: lend that count
        // to the ് spelling, and drop the chillu word.
        val endsInU = typed.endsWith("u", ignoreCase = true)
        if (endsInU) {
            // അച്ഛൻ → അച്ഛന് (achanu): the ് form may not be in the list itself.
            val chillus = pool.filter { it.isNotEmpty() && it.last() in CHILLU_OF.values }
            pool.removeAll(chillus.toSet())
            for (word in chillus) pool += word.dropLast(1) + LETTER_OF.getValue(word.last()) + VIRAMA
        }
        // Words the letters spell exactly beat loose look-alikes unless those are 5× as common:
        // kutti is കുട്ടി (586), not കൂടി (1652). Loose matching rescues misspellings; it
        // shouldn't override a word typed right.
        val exact = engine.transliterate(typed).candidates.toHashSet()
        val exactIsReal = exact.any { frequencies.of(it) > 0 }
        fun score(word: String): Int {
            val own0 = maxOf(frequencies.of(word), built[word] ?: 0)
            val own = if (exactIsReal && word !in exact && word !in built) own0 / LOOSE_PENALTY else own0
            if (!endsInU || !word.endsWith(VIRAMA) || word.length < 2) return own
            val chillu = CHILLU_OF[word[word.length - 2]] ?: return own
            return maxOf(own, frequencies.of(word.dropLast(2) + chillu))
        }

        // Sorting is stable: equally-common words keep the engine's order.
        var ranked = pool.sortedWith(
            compareByDescending<String> { userWords.count(it) }.thenByDescending(::score)
        ).take(maxShown)

        // Looser spellings (skipped ്വ, doubled letters) go right after the real words found so
        // far, ahead of made-up spellings: dhvandayudham → ദ്വന്ദ്വയുദ്ധം too, not only ദ്വന്ദയുദ്ധം.
        val realCount = ranked.count { score(it) > 0 || userWords.count(it) > 0 }
        val spelled = laxMatches(typed).filter { it !in ranked.take(realCount) }
        if (spelled.isNotEmpty()) {
            ranked = (ranked.take(realCount) + spelled + ranked.drop(realCount)).distinct().take(maxShown)
        } else if (realCount == 0) {
            // Still nothing real: one-typo fixes. Offered after what was typed, never in its place.
            val fixed = typoFixes(typed)
            if (fixed.isNotEmpty()) {
                ranked = (ranked.take(1) + fixed + ranked.drop(1)).distinct().take(maxShown)
            }
        }

        // Chat shortcuts: "aan" is almost always ആണ് (is), rarely ആൺ (male).
        chatShortcut(typed.lowercase())?.let { word ->
            if (ranked.none { userWords.count(it) > userWords.count(word) }) {
                ranked = (listOf(word) + ranked.filter { it != word }).take(maxShown)
            }
        }

        // An English word ("four", "media"): its Malayali spelling, first or second.
        val en = if (withEnglish) english?.of(typed) else null
        if (en != null && typed.length >= 2) {
            val spelled = en.malayalam
            val others = ranked.filter { it != spelled }
            val best = others.firstOrNull()
            val picked = best != null && userWords.count(best) > userWords.count(spelled)
            val englishFirst = !picked && (userWords.count(spelled) > 0 || best == null ||
                prefersEnglish(en.perMillion, frequencies.perMillion(best)))
            ranked = (if (englishFirst) listOf(spelled) + others else listOf(best!!, spelled) + others.drop(1))
                .take(maxShown)
        }
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

    /*
     * Malayalis add or drop "h" loosely, double letters by feel, skip rare conjuncts, and
     * fingers slip. These two catch what loose matching misses.
     */

    /** Real words matching [laxKey]: dvandayudham → ദ്വന്ദ്വയുദ്ധം, apprathyaksham → അപ്രത്യക്ഷം. */
    private fun laxMatches(typed: String): List<String> {
        if (!rescuable(typed)) return emptyList()
        return byCount(laxIndex[laxKey(engine.primary(typed.lowercase()))].orEmpty())
    }

    /** Real words one typo away: apprathysksham → അപ്രത്യക്ഷം ("s" typed for "a"). */
    private fun typoFixes(typed: String): List<String> {
        if (!rescuable(typed)) return emptyList()
        val found = LinkedHashSet<String>()
        for (variant in oneTypoAway(typed.lowercase())) {
            laxIndex[laxKey(engine.primary(variant))]?.let(found::addAll)
        }
        return byCount(found)
    }

    private fun rescuable(typed: String) =
        typed.length >= RESCUE_MIN_LENGTH && typed.all { it in 'a'..'z' || it in 'A'..'Z' }

    private fun byCount(words: Collection<String>) =
        words.distinct().sortedByDescending { frequencies.of(it) }.take(RESCUE_MAX)

    /**
     * The English reading wins when the Malayalam reading isn't a real word, or when the English
     * word is used clearly more (four 240/M vs പോർ 81/M) and isn't rare (ammo 5/M stays അമ്മോ).
     */
    private fun prefersEnglish(englishPerMillion: Float, malayalamPerMillion: Float): Boolean =
        malayalamPerMillion == 0f || (englishPerMillion >= 20f && englishPerMillion > 2 * malayalamPerMillion)

    /**
     * How often the best Malayalam reading of [typed] is used ("poda" → പോടാ: common).
     * English mode uses this to leave Manglish alone instead of "correcting" it.
     */
    fun commonness(typed: String): Int =
        if (typed.isEmpty()) 0 else frequencies.of(suggest(typed, withEnglish = false).best)

    companion object {
        private const val VIRAMA = "\u0D4D"

        /** How much more common a loose look-alike must be to beat the word the letters spell. */
        private const val LOOSE_PENALTY = 5

        /** Manglish chat spellings that mean a different word than the letters say. */
        private val CHAT_SHORTCUTS = mapOf(
            "aan" to "ആണ്",
            "mandi" to "മന്തി",   // the dish; മണ്ടി (fool) stays in the strip
        )

        /**
         * Days of the week: people spell the -ആഴ്ച ending many ways (aazhcha, azcha, aycha,
         * azhacha, azhchcha…), and the letters alone often land on nonsense.
         */
        private val DAYS = listOf(
            "nj[aeo]{1,2}y[ai]r" to "ഞായറാഴ്ച",
            "thin[kg]al|thingkal|tinkal" to "തിങ്കളാഴ്ച",
            "cho[wv]v?a|chowa" to "ചൊവ്വാഴ്ച",
            "budh?an" to "ബുധനാഴ്ച",
            "v[iy]{1,2}a{1,2}(zh|z|y|l)" to "വ്യാഴാഴ്ച",
            "vell?iy?" to "വെള്ളിയാഴ്ച",
            "s(h)?[ae]niy?" to "ശനിയാഴ്ച",
        ).map { (stem, word) ->
            Regex("(?:$stem)a{0,2}(?:zh|z|y|sh|s)?a?(?:chch|cch|ch)a") to word
        }

        /** "Day after tomorrow": mattannal, mattennal, matannaal → മറ്റന്നാൾ */
        private val MATTANNAL = Regex("matt?[ae]nn?a{1,2}[l]")

        fun chatShortcut(typed: String): String? {
            CHAT_SHORTCUTS[typed]?.let { return it }
            if (MATTANNAL.matches(typed)) return "മറ്റന്നാൾ"
            if (typed.endsWith("cha")) for ((regex, word) in DAYS) if (regex.matches(typed)) return word
            return null
        }

        /** ന → ൻ, ണ → ൺ, ര → ർ, ല → ൽ, ള → ൾ */
        private val CHILLU_OF = mapOf('ന' to 'ൻ', 'ണ' to 'ൺ', 'ര' to 'ർ', 'ല' to 'ൽ', 'ള' to 'ൾ')
        private val LETTER_OF = CHILLU_OF.entries.associate { (letter, chillu) -> chillu to letter }

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
            "ങ്ക" to "ങ", "ങ്ങ" to "ങ",     // bhayangara → ഭയങ്കര
            "ഷ്ട്ട" to "ഷ്ട",                 // ishttam → ഇഷ്ടം
            "ന്ദ" to "ണ്ട",                   // nd: chandanam → ചന്ദനം, not only ണ്ട
            "ദ്ധ" to "ദ", "ധ്ധ" to "ദ",       // yudham → യുദ്ധം (dh is often ദ്ധ)

            "\u0D4D\u0D30\u0D41" to "\u0D43", "\u0D4D\u0D30\u0D3F" to "\u0D43", // ്രു, ്രി → ൃ
        )

        /**
         * Manglish "th" inside a word is often ത്ത: പത്തനംതിട്ട (pathanamthitta).
         * Not at the very start or end, or "but" would match ബൂത്ത് (booth).
         */
        private val doubleTha = Regex("(?<=.)ത്ത(?!്$)")

        /** Manglish "tt" inside a word is often റ്റ: mattoru → മറ്റൊരു. Not at the end ("but" ≠ ബൂത്ത്). */
        private val rtta = Regex("റ്റ(?!്$)")

        /** Words shorter than this are too easy to "fix" into something else. */
        private const val RESCUE_MIN_LENGTH = 5
        private const val RESCUE_MAX = 3

        /** Keys next to each other on QWERTY: the usual slips (s for a, n for m…). */
        private val NEIGHBOURS: Map<Char, String> = mapOf(
            'q' to "wa", 'w' to "qes", 'e' to "wrd", 'r' to "etf", 't' to "ryg", 'y' to "tuh",
            'u' to "yij", 'i' to "uok", 'o' to "ipl", 'p' to "o", 'a' to "qsz", 's' to "awdz",
            'd' to "sfe", 'f' to "dgr", 'g' to "fht", 'h' to "gjy", 'j' to "hku", 'k' to "jli",
            'l' to "ko", 'z' to "asx", 'x' to "zc", 'c' to "xv", 'v' to "cb", 'b' to "vn",
            'n' to "bm", 'm' to "n",
        )

        /** Letters people most often leave out or add: vowels, h (dh/th/kh), v, y, r. */
        private const val OFTEN_SKIPPED = "aeiouhvyr"

        /** Every spelling one slip away: a letter missing, extra, swapped with its neighbour, or mistyped. */
        internal fun oneTypoAway(word: String): Set<String> = buildSet {
            for (i in word.indices) {
                add(word.removeRange(i, i + 1))                                       // extra letter
                if (i + 1 < word.length) add(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
                for (c in NEIGHBOURS[word[i]].orEmpty()) add(word.substring(0, i) + c + word.substring(i + 1))
            }
            for (i in 0..word.length) for (c in OFTEN_SKIPPED) add(word.substring(0, i) + c + word.substring(i))
            remove(word)
        }

        /**
         * [looseKey], plus what Manglish typers skip: ്വ (dvandvam typed dvandam) and
         * doubled letters (apprathyaksham for അപ്രത്യക്ഷം).
         */
        internal fun laxKey(word: String): String =
            looseKey(word).replace("\u0D4D\u0D35", "").replace(doubled, "$1")

        private val doubled = Regex("(\\p{L})\u0D4D\\1")

        fun looseKey(word: String): String {
            var w = word
            for ((from, to) in foldPairs) if (from in w) w = w.replace(from, to)
            if ("റ്റ" in w) w = w.replace(rtta, "ട്ട")
            if ("ത്ത" in w) w = w.replace(doubleTha, "ത")
            return buildString(w.length) {
                for (c in w) append(fold[c] ?: c)
            }
        }
    }
}