package com.rr.numio.ezhuthola.engine

/**
 * Small text measurements for the space-bar cursor and the backspace word swipe.
 * Plain functions on strings, so they can be unit tested without a phone.
 */
object TextSteps {

    /**
     * How many characters the last [count] words of [before] take up, counting back from the end.
     * Spaces right before the cursor go with the word in front of them, so swiping over
     * "hello world " takes "world " first, then "hello ". Words are split on spaces only,
     * which works the same for English, Manglish and Malayalam.
     */
    fun lengthOfWordsBefore(before: CharSequence, count: Int): Int {
        var i = before.length
        repeat(count) {
            if (i == 0) return before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
        }
        return before.length - i
    }

    /** How many whole words there are to take from [before] (so the swipe stops at the start). */
    fun wordsBefore(before: CharSequence): Int {
        var n = 0
        var inWord = false
        for (c in before) {
            if (c.isWhitespace()) inWord = false
            else if (!inWord) { inWord = true; n++ }
        }
        return n
    }
}
