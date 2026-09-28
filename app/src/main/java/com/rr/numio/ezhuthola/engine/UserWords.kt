package com.rr.numio.ezhuthola.engine

/**
 * Words this person has taught the keyboard, with how many times they chose each one.
 * Saved on the phone only ("word<TAB>count" per line). Nothing ever leaves the device.
 */
class UserWords(private val counts: MutableMap<String, Int> = HashMap()) {

    /** True when something changed since the last [toTsv]. */
    var dirty = false
        private set

    fun count(word: String): Int = counts[word] ?: 0

    operator fun contains(word: String) = word in counts

    val words: Set<String> get() = counts.keys

    fun learn(word: String) {
        if (word.isBlank() || word.length > MAX_LENGTH) return
        counts[word] = count(word) + 1
        if (counts.size > MAX_WORDS) forgetRarest()
        dirty = true
    }

    fun forget(word: String) {
        if (counts.remove(word) != null) dirty = true
    }

    fun toTsv(): String {
        dirty = false
        return counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    /** Keeps the file small: drops the least-used tenth when the list is full. */
    private fun forgetRarest() {
        counts.entries.sortedBy { it.value }.take(MAX_WORDS / 10).forEach { counts.remove(it.key) }
    }

    companion object {
        private const val MAX_WORDS = 5_000
        private const val MAX_LENGTH = 40

        fun fromTsv(text: String): UserWords {
            val map = HashMap<String, Int>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            }
            return UserWords(map)
        }
    }
}