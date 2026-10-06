package com.rr.numio.ezhuthola.engine

/**
 * Decides whether an unknown English word belongs on the "Missing words" list in settings.
 *
 * Leaves out what isn't a real word you'd want to add:
 *  - very short bits like "qr" or "re"
 *  - pieces of links and emails: "https", "www", "getnumio" in "https://getnumio.org",
 *    "example" in "you@example.com"
 *
 * Pure Kotlin, so it can be unit-tested on a laptop.
 */
object MissingWordFilter {

    private const val MIN_LENGTH = 3

    /** Words that only ever show up as parts of links. */
    private val LINK_WORDS = setOf("http", "https", "www", "html", "htm", "php", "com", "org", "net")

    /** Characters that mean the word is inside a link or an email address. */
    private val LINK_MARKS = charArrayOf('.', '/', ':', '@', '\\', '=', '?', '&', '#', '_')

    /**
     * [word]: the finished word, lowercase. [before]: the text in front of the cursor,
     * ending with that word (a few dozen characters is enough).
     */
    fun worthNoting(word: String, before: String): Boolean {
        if (word.length < MIN_LENGTH) return false
        if (word in LINK_WORDS) return false
        // The run of text since the last space, without the word itself: "https://" for getnumio.
        val chunk = before.substringAfterLast(' ').substringAfterLast('\n')
        val prefix = if (chunk.endsWith(word, ignoreCase = true)) chunk.dropLast(word.length) else chunk
        return prefix.none { it in LINK_MARKS }
    }
}
