package com.rr.numio.ezhuthola.engine

import org.json.JSONObject

/**
 * Ezhuthola's Manglish → Malayalam engine.
 *
 * Pure Kotlin (only org.json for loading), no Android code, so it can be unit-tested on a laptop.
 * The keyboard sends it the Manglish word typed so far; it returns the best Malayalam
 * spelling plus alternatives for the suggestion strip.
 *
 * The whole word is re-read on every keystroke, so it is always safe to apply
 * word-end rules (chillu, anuswaram, virama): if the user keeps typing, the next
 * call simply re-reads the longer word.
 */

// ---------------------------------------------------------------------------
// Rules (loaded from ezhuthola_rules.json)
// ---------------------------------------------------------------------------

class MalayalamRules(
    val independentVowels: Map<String, String>,
    val vowelSigns: Map<String, String>,
    val consonants: Map<String, String>,
    val clusters: Map<String, String>,
    /** Letters that change inside a word: "nj" is ഞ at the start (njan) but ഞ്ഞ inside (paranju). */
    val consonantsMidWord: Map<String, String>,
    val chilluLetters: Map<String, String>,
    val alternatives: Map<String, List<String>>,
    val vowelAlternatives: Map<String, List<String>>,
    val virama: String,
    val anuswaram: String,
) {
    // Longest keys first, so "chh" is tried before "ch" before "c".
    internal val clusterKeys = clusters.keys.sortedByDescending { it.length }
    internal val consonantKeys = consonants.keys.sortedByDescending { it.length }
    internal val vowelSignKeys = vowelSigns.keys.sortedByDescending { it.length }
    internal val independentVowelKeys = independentVowels.keys.sortedByDescending { it.length }

    /** ന → ൻ, ണ → ൺ, ല → ൽ, ള → ൾ, ര/റ → ർ. Keyed by the letter, so swapped letters get the right chillu. */
    internal val chilluByLetter: Map<String, String> = buildMap {
        chilluLetters.forEach { (key, chillu) -> consonants[key]?.let { putIfAbsent(it, chillu) } }
    }

    internal val anuswaramLetter: String? = consonants["m"]

    companion object {
        fun fromJson(text: String): MalayalamRules {
            val json = JSONObject(text)
            fun obj(name: String) = json.optJSONObject(name) ?: JSONObject()
            fun JSONObject.strings(): Map<String, String> =
                keys().asSequence().associateWith { getString(it) }
            fun JSONObject.lists(): Map<String, List<String>> =
                keys().asSequence().associateWith { k ->
                    val arr = getJSONArray(k)
                    (0 until arr.length()).map { arr.getString(it) }
                }
            return MalayalamRules(
                independentVowels = obj("independent_vowels").strings(),
                vowelSigns = obj("vowel_signs").strings(),
                consonants = obj("consonants").strings(),
                clusters = obj("clusters").strings(),
                consonantsMidWord = obj("consonants_mid_word").strings(),
                chilluLetters = obj("chillu_letters").strings(),
                alternatives = obj("alternatives").lists(),
                vowelAlternatives = obj("vowel_alternatives").lists(),
                virama = json.getString("virama"),
                anuswaram = json.getString("anuswaram"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

data class Transliteration(
    /** Best guess: what space commits if the user picks nothing. */
    val primary: String,
    /** Primary first, then alternatives. Duplicates removed. Feed this to the suggestion strip. */
    val candidates: List<String>,
)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

class MalayalamEngine(private val rules: MalayalamRules, private val maxCandidates: Int = 300) {

    private sealed interface Seg {
        /** A consonant (or cluster like ന്റ) with an optional vowel after it. */
        data class Consonant(
            val key: String,        // Manglish that produced it: "n", "nt", "zh"
            val base: String,       // Malayalam letter(s): "ന", "ന്ന", "ന്റ"
            val doubled: Boolean,   // typed twice ("nn") → base already holds the conjunct
            val vowelKey: String?,  // null = no vowel typed after it (a dead consonant)
            val vowelSign: String?, // "" for the inherent "a"
        ) : Seg

        /** A vowel on its own: word start, or right after another vowel. */
        data class Vowel(val key: String, val text: String) : Seg

        /** Anything the rules don't know (digits, stray symbols): copied as-is. */
        data class Other(val text: String) : Seg
    }

    fun transliterate(input: String): Transliteration {
        if (input.isEmpty()) return Transliteration("", emptyList())
        val segments = parse(normalizeAutoCapital(input))

        val primary = render(segments, uAsVirama = false)
        val out = LinkedHashSet<String>()
        out += primary

        // A final "u" is ambiguous: kandu → കണ്ടു, but veedu often means വീട്. Offer both.
        val endsInU = (segments.lastOrNull() as? Seg.Consonant)?.vowelKey == "u"
        if (endsInU) out += render(segments, uAsVirama = true)

        // Alternatives: one change (ന↔ണ, ല↔ള, െ↔േ, a↔aa...), then two changes in
        // different places (nale → നാളെ needs both a→ാ and ല→ള). The word list ranks them later.
        val swaps = possibleSwaps(segments)
        fun add(variant: List<Seg>) {
            out += render(variant, uAsVirama = false)
            if (endsInU) out += render(variant, uAsVirama = true)
        }
        for ((i, seg) in swaps) add(segments.replaced(i, seg))
        for (a in swaps.indices) for (b in a + 1 until swaps.size) {
            val (i, segI) = swaps[a]
            val (j, segJ) = swaps[b]
            if (i != j) add(segments.replaced(i, segI).replaced(j, segJ))
            if (out.size >= maxCandidates) break
        }

        return Transliteration(primary, out.take(maxCandidates))
    }

    // ---- Autocap ----------------------------------------------------------

    /** Phones auto-capitalize the first letter. "Njan" means "njan" (ഞ), not "N"+"jan" (ണ). */
    private fun normalizeAutoCapital(s: String): String {
        if (!s[0].isUpperCase()) return s
        val looksAutoCapitalized = s.length == 1 || s[1].isLowerCase()
        return if (looksAutoCapitalized) s[0].lowercaseChar() + s.substring(1) else s
    }

    // ---- Parsing ------------------------------------------------------------

    private fun parse(input: String): List<Seg> {
        var s = input
        val out = mutableListOf<Seg>()
        var i = 0
        while (i < s.length) {
            // 1. Clusters first: nt → ന്റ, nd → ണ്ട, ksh → ക്ഷ
            val cluster = rules.clusterKeys.firstOrNull { s.startsWith(it, i) }
            if (cluster != null) {
                val (vk, vs, used) = vowelSignAt(s, i + cluster.length)
                out += Seg.Consonant(cluster, rules.clusters.getValue(cluster), false, vk, vs)
                i += cluster.length + used
                continue
            }

            // 2. Consonants, with doubling for a repeated single letter: kk → ക്ക
            val cons = rules.consonantKeys.firstOrNull { s.startsWith(it, i) }
            if (cons != null) {
                val letter = (if (i > 0) rules.consonantsMidWord[cons] else null)
                    ?: rules.consonants.getValue(cons)
                val doubled = cons.length == 1 && i + 1 < s.length && s[i + 1] == cons[0]
                val base = if (doubled) letter + rules.virama + letter else letter
                val advance = if (doubled) 2 else cons.length
                val (vk, vs, used) = vowelSignAt(s, i + advance)
                out += Seg.Consonant(cons, base, doubled, vk, vs)
                i += advance + used
                continue
            }

            // 3. Independent vowels
            val vowel = rules.independentVowelKeys.firstOrNull { s.startsWith(it, i) }
            if (vowel != null) {
                out += Seg.Vowel(vowel, rules.independentVowels.getValue(vowel))
                i += vowel.length
                continue
            }

            // 4. A capital the rules don't use (Q, B, K... with caps on): read it as lowercase.
            if (s[i].isUpperCase()) {
                s = s.substring(0, i) + s[i].lowercaseChar() + s.substring(i + 1)
                continue
            }

            // 5. Unknown: pass through
            out += Seg.Other(s[i].toString())
            i++
        }
        return out
    }

    private fun vowelSignAt(s: String, from: Int): Triple<String?, String?, Int> {
        val key = rules.vowelSignKeys.firstOrNull { s.startsWith(it, from) }
            ?: return Triple(null, null, 0)
        return Triple(key, rules.vowelSigns.getValue(key), key.length)
    }

    // ---- Rendering ----------------------------------------------------------

    private fun render(segments: List<Seg>, uAsVirama: Boolean): String = buildString {
        segments.forEachIndexed { index, seg ->
            when (seg) {
                is Seg.Vowel -> append(seg.text)
                is Seg.Other -> append(seg.text)
                is Seg.Consonant -> {
                    val isLast = index == segments.lastIndex
                    when {
                        // A vowel was typed after it.
                        seg.vowelKey != null ->
                            if (isLast && uAsVirama && seg.vowelKey == "u") {
                                append(seg.base).append(rules.virama)        // veedu → വീട്
                            } else {
                                append(seg.base).append(seg.vowelSign ?: "")  // kandu → കണ്ടു
                            }

                        // Dead consonant in the middle: joins the next consonant.
                        !isLast -> {
                            append(seg.base)
                            if (segments[index + 1] is Seg.Consonant) append(rules.virama) // prema → പ്രേമ
                        }

                        // Dead consonant at the end of the word.
                        !seg.doubled && seg.base == rules.anuswaramLetter ->
                            append(rules.anuswaram)                          // sneham → ...ം

                        !seg.doubled && rules.chilluByLetter[seg.base] != null ->
                            append(rules.chilluByLetter.getValue(seg.base))  // avan → അവൻ

                        else -> append(seg.base).append(rules.virama)        // kett → കെട്ട്, enn → എന്ന്
                    }
                }
            }
        }
    }

    // ---- Alternatives ---------------------------------------------------------

    /** Every single change we could make: (position, replacement segment). */
    private fun possibleSwaps(segments: List<Seg>): List<Pair<Int, Seg>> {
        val variants = mutableListOf<Pair<Int, Seg>>()
        segments.forEachIndexed { index, seg ->
            when (seg) {
                is Seg.Consonant -> {
                    // Letter swaps. Doubled letters use their own list: "nn" → ന്ന / ണ്ണ.
                    val listKey = if (seg.doubled) seg.key + seg.key else seg.key
                    rules.alternatives[listKey].orEmpty()
                        .filter { it != seg.base }
                        .forEach { alt -> variants += index to seg.copy(base = alt) }

                    // Vowel swaps: e → E (െ → േ), o → O.
                    seg.vowelKey?.let { vk ->
                        rules.vowelAlternatives[vk].orEmpty().forEach { altKey ->
                            rules.vowelSigns[altKey]?.let { sign ->
                                variants += index to seg.copy(vowelKey = altKey, vowelSign = sign)
                            }
                        }
                    }
                }
                is Seg.Vowel -> rules.vowelAlternatives[seg.key].orEmpty().forEach { altKey ->
                    rules.independentVowels[altKey]?.let { text ->
                        variants += index to Seg.Vowel(altKey, text)
                    }
                }
                is Seg.Other -> Unit
            }
        }
        return variants
    }

    private fun <T> List<T>.replaced(index: Int, item: T): List<T> =
        toMutableList().also { it[index] = item }
}
