package com.rr.numio.ezhuthola.engine

/**
 * "How you type it" for words in Your dictionary (Malayalam tab): ammachy → അമ്മച്ചി.
 *
 * Some words are typed in a way the Manglish rules can't read (y for i, chat spellings),
 * so loose matching never finds them. A shortcut ties the exact Manglish to the word.
 * Stored as "word<TAB>manglish" lines, one shortcut per word.
 */
object Shortcuts {
    private const val MAX_LENGTH = 40

    /** The Manglish as stored: trimmed, lowercase, English letters only. Null if it isn't that. */
    fun clean(input: String): String? {
        val typed = input.trim().lowercase()
        if (typed.isEmpty() || typed.length > MAX_LENGTH) return null
        if (!typed.all { it in 'a'..'z' }) return null
        return typed
    }

    fun line(word: String, manglish: String) = "$word\t$manglish"

    /** word → Manglish, from the stored lines. Broken lines are skipped. */
    fun parse(lines: Collection<String>): Map<String, String> = buildMap {
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size != 2) continue
            val typed = clean(parts[1]) ?: continue
            if (parts[0].isNotBlank()) put(parts[0], typed)
        }
    }

    /** Manglish → words typed that way, for the keyboard. */
    fun byTyped(shortcuts: Map<String, String>): Map<String, List<String>> =
        shortcuts.entries.groupBy({ it.value }, { it.key })
}
