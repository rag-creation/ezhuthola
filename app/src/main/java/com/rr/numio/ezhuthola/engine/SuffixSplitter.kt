package com.rr.numio.ezhuthola.engine

/**
 * Suffix splitting: spells word endings onto real base words.
 *
 * The word list can't hold every form of every word. "keralathil" (കേരളത്തിൽ) may be missing,
 * and then the engine guesses letter by letter: കെരലതിൽ. But കേരളം is in the list.
 * So the typed word is split into base + ending ("keral|athil"), the base is looked up as
 * a real word (കേരളം), and the ending is joined on the way Malayalam does it:
 *
 *   ം  + case ending       കേരളം + ിൽ      → കേരളത്തിൽ      (keralathil)
 *   chillu + vowel ending  അച്ഛൻ + ോട്      → അച്ഛനോട്       (achanodu)
 *   ൻ + "te"               അവൻ  + ന്റെ      → അവന്റെ         (avante)
 *   chillu + consonant     കൂട്ടുകാർ + ക്ക്   → കൂട്ടുകാർക്ക്     (koottukaarkku)
 *   vowel + y/v + ending   ചേച്ചി + യ + ുടെ  → ചേച്ചിയുടെ      (chechiyude)
 *
 * Pure Kotlin, no Android code.
 */
class SuffixSplitter(
    /** Real Malayalam words a Manglish base could be, with how common each is, best first. */
    private val realWords: (manglish: String) -> List<Pair<String, Int>>,
) {
    /**
     * Words built from [typed] = base + known ending, each with a score.
     * The score is lower than the base word's own count, so a real word that is in the
     * list as a whole still comes first.
     */
    fun split(typed: String): Map<String, Int> {
        val lower = typed.lowercase()
        if (lower.length < 4) return emptyMap()
        val out = HashMap<String, Int>()
        fun add(word: String, baseCount: Int) {
            val score = baseCount / SCORE_DIVISOR + 1
            if (score > (out[word] ?: 0)) out[word] = score
        }

        for ((ending, malayalam) in ENDINGS) {
            if (!lower.endsWith(ending)) continue
            val base = typed.dropLast(ending.length)
            if (base.length < 2) continue
            val startsWithVowelSign = malayalam[0].isVowelSign()

            if (startsWithVowelSign) {
                // ം nouns: "keralathil" = keral + ath + il, base keralam → കേരള + ത്ത + ിൽ
                for (ath in ATH) {
                    if (!base.lowercase().endsWith(ath)) continue
                    val stem = base.dropLast(ath.length) + "am"
                    best(stem) { it.last() == ANUSWARAM }?.let { (word, n) ->
                        add(word.dropLast(1) + "ത്ത" + malayalam, n)
                    }
                }
                // chillu: അച്ഛൻ + ോട് → അച്ഛനോട്
                best(base) { it.last() in CHILLU_BASE }?.let { (word, n) ->
                    add(word.dropLast(1) + CHILLU_BASE.getValue(word.last()) + malayalam, n)
                }
                // vowel + glide: chechi + y + ude → ചേച്ചി + യ + ുടെ
                val glide = GLIDES[base.last().lowercaseChar()]
                if (glide != null && base.length > 2) {
                    best(base.dropLast(1)) { it.endsInVowel() }?.let { (word, n) ->
                        add(word + glide + malayalam, n)
                    }
                }
            } else {
                // consonant endings stick on as they are: കൂട്ടുകാർ + ക്ക്, കുട്ടി + കളുടെ
                best(base) { it.last() in CHILLU_BASE || it.endsInVowel() }?.let { (word, n) ->
                    add(word + malayalam, n)
                }
            }
        }

        // "avante" = avan + te → അവന്റെ (ൻ + te is ന്റെ, not ൻതെ)
        if (lower.endsWith("te") && lower.length > 4) {
            best(typed.dropLast(2)) { it.last() == 'ൻ' }?.let { (word, n) ->
                add(word.dropLast(1) + "ന്റെ", n)
            }
        }
        return out
    }

    /** Most common real word for [manglish] that passes [accept], if it's common enough to trust. */
    private fun best(manglish: String, accept: (String) -> Boolean): Pair<String, Int>? =
        realWords(manglish).firstOrNull { (word, n) -> n >= MIN_BASE_COUNT && word.length >= 2 && accept(word) }

    private fun Char.isVowelSign() = this in '\u0D3E'..'\u0D4C' || this == '\u0D57'

    /** Ends in a vowel (sign, inherent a, or a vowel letter): can take a y/v glide. */
    private fun String.endsInVowel(): Boolean {
        val c = last()
        return c.isVowelSign() || c in '\u0D15'..'\u0D39' || c in '\u0D05'..'\u0D14'
    }

    companion object {
        /** The base must be used at least this often, so rare junk isn't built on. */
        private const val MIN_BASE_COUNT = 3

        /** Built words score base count ÷ this. */
        private const val SCORE_DIVISOR = 6

        private const val ANUSWARAM = '\u0D02' // ം

        /** Chillu → the full letter it becomes before a vowel. */
        private val CHILLU_BASE = mapOf('ൻ' to "ന", 'ൺ' to "ണ", 'ർ' to "ര", 'ൽ' to "ല", 'ൾ' to "ള")

        /** "y" after i/e/a, "v" after u: kuttiyude, guruvinte. */
        private val GLIDES = mapOf('y' to "യ", 'v' to "വ")

        /** How people type the ത്ത of ം nouns: keralathil, keralaththil, keralatthil. */
        private val ATH = listOf("athth", "atth", "ath")

        /**
         * Manglish ending → Malayalam ending. Endings starting with a vowel sign attach to the
         * base's last letter; the others stick on after it. Longer endings first.
         */
        val ENDINGS: List<Pair<String, String>> = listOf(
            // place: in / to / from / through
            "ilninnu" to "ിൽനിന്ന്", "ilninn" to "ിൽനിന്ന്",
            "ilekku" to "ിലേക്ക്", "ilekk" to "ിലേക്ക്", "ilek" to "ിലേക്ക്",
            "iloode" to "ിലൂടെ", "ilude" to "ിലൂടെ",
            "ilulla" to "ിലുള്ള", "ilaanu" to "ിലാണ്", "ilanu" to "ിലാണ്",
            "ilum" to "ിലും", "ile" to "ിലെ", "ilo" to "ിലോ", "il" to "ിൽ",
            // of / to / for
            "inaayi" to "ിനായി", "inayi" to "ിനായി",
            "inte" to "ിന്റെ", "inum" to "ിനും", "inu" to "ിന്", "ine" to "ിനെ",
            "ude" to "ുടെ",
            // with
            "odum" to "ോടും", "odu" to "ോട്", "od" to "ോട്", "ode" to "ോടെ",
            // is / and / became
            "aano" to "ാണോ", "ano" to "ാണോ", "aanu" to "ാണ്", "anu" to "ാണ്",
            "aayi" to "ായി", "ayi" to "ായി", "um" to "ും",
            // object: അവനെ, കേരളത്തെ
            "e" to "െ",
            // plural
            "kalude" to "കളുടെ", "kalkku" to "കൾക്ക്", "kalil" to "കളിൽ",
            "kalum" to "കളും", "kale" to "കളെ", "kal" to "കൾ",
            "maarude" to "മാരുടെ", "maar" to "മാർ",
            // to (dative)
            "kkaayi" to "ക്കായി", "kkayi" to "ക്കായി", "kkum" to "ക്കും", "kku" to "ക്ക്", "kk" to "ക്ക്",
        ).sortedByDescending { it.first.length }
    }
}
