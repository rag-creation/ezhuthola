package com.rr.numio.ezhuthola.engine

/**
 * Words with how many times this person used each one: words they taught the keyboard,
 * words they type, or words the keyboard didn't know (see the "Missing words" settings).
 * Saved on the phone only ("word<TAB>count" per line). Nothing ever leaves the device.
 */
class UserWords(
    private val counts: MutableMap<String, Int> = HashMap(),
    private val maxWords: Int = MAX_WORDS,
) {

    /** True when something changed since the last [toTsv]. */
    var dirty = false
        private set

    /** Goes up on every change, so lookups built from these words know when to rebuild. */
    var version = 0
        private set

    fun count(word: String): Int = counts[word] ?: 0

    operator fun contains(word: String) = word in counts

    val words: Set<String> get() = counts.keys

    fun isEmpty() = counts.isEmpty()

    /** Most used first; ties in A–Z order. */
    fun ranked(): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    fun learn(word: String) {
        if (word.isBlank() || word.length > MAX_LENGTH) return
        counts[word] = count(word) + 1
        if (counts.size > maxWords) forgetRarest()
        dirty = true
        version++
    }

    fun forget(word: String) {
        if (counts.remove(word) != null) { dirty = true; version++ }
    }

    fun clear() {
        if (counts.isNotEmpty()) { dirty = true; version++ }
        counts.clear()
    }

    fun toTsv(): String {
        dirty = false
        return counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    /** Keeps the file small: drops the least-used tenth when the list is full. */
    private fun forgetRarest() {
        counts.entries.sortedBy { it.value }.take(maxWords / 10 + 1).map { it.key }.forEach { counts.remove(it) }
    }

    companion object {
        private const val MAX_WORDS = 5_000
        private const val MAX_LENGTH = 40

        fun fromTsv(text: String, maxWords: Int = MAX_WORDS): UserWords = UserWords(parse(text), maxWords)

        /**
         * A word typed into "Your dictionary", the way it's stored: trimmed and lowercase.
         * Null when it isn't one word (empty, has spaces, too long, or odd characters).
         * Letters, digits, ' and - are fine, so "don't", "e-mail" and "4g" all work.
         */
        fun clean(input: String): String? {
            val word = input.trim().lowercase()
            if (word.isEmpty() || word.length > MAX_LENGTH) return null
            if (!word.any(Char::isLetter)) return null
            if (!word.all { it.isLetterOrDigit() || it == '\'' || it == '’' || it == '-' }) return null
            return word
        }

        /**
         * A Malayalam word typed into "Your dictionary" (Malayalam tab), trimmed. Null unless it's
         * one word in Malayalam letters (ZWJ/ZWNJ allowed, for old-style chillus and ligatures).
         */
        fun cleanMalayalam(input: String): String? {
            val word = input.trim()
            if (word.isEmpty() || word.length > MAX_LENGTH) return null
            if (!word.all { it in '\u0D00'..'\u0D7F' || it == '\u200C' || it == '\u200D' }) return null
            if (!word.any { it in '\u0D05'..'\u0D39' || it in '\u0D7A'..'\u0D7F' }) return null // a letter, not just signs
            return word
        }

        private fun parse(text: String): HashMap<String, Int> {
            val map = HashMap<String, Int>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            }
            return map
        }
    }
}