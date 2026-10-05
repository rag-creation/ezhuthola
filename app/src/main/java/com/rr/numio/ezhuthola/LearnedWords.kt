package com.rr.numio.ezhuthola

import android.content.Context
import com.rr.numio.ezhuthola.engine.UserWords
import java.io.File

/**
 * The English words the keyboard learns as you type, shared by the keyboard and the app screen.
 * All of it stays in Ezhuthola's private storage on the phone.
 *
 * - taught: words you told the keyboard to keep (never corrected, suggested first)
 * - used: known words you type, so the ones you use move up ("br" → bro)
 * - missing: words you typed that the keyboard didn't know, for you to add or dismiss in settings
 *
 * Only the keyboard writes these files. The app screen leaves requests in the preferences
 * (add this word, dismiss that one, clear the list) and the keyboard carries them out the next
 * time it opens or closes. That way the two never overwrite each other's changes, even when
 * the keyboard is open in the app's own "Try it" box.
 */
object LearnedWords {
    const val TAUGHT_FILE = "user_words_en.tsv"
    const val USED_FILE = "used_words_en.tsv"
    const val MISSING_FILE = "missing_words_en.tsv"

    /** Words waiting to be added (tapped "Add" in settings). */
    const val TO_ADD = "missing_to_add"

    /** Words you dismissed from the missing list, so they aren't listed again. */
    const val IGNORED = "missing_ignored"

    /** True when you tapped "Clear list" and the keyboard hasn't cleared it yet. */
    const val CLEAR = "missing_clear"

    /** The missing list keeps at most this many words (the least typed go first). */
    const val MISSING_MAX = 300

    fun read(context: Context, name: String, maxWords: Int = 5_000): UserWords {
        val f = File(context.filesDir, name)
        return if (f.exists()) UserWords.fromTsv(f.readText(), maxWords) else UserWords(maxWords = maxWords)
    }

    /** The missing list as the app screen should show it: most typed first, requests applied. */
    fun missing(context: Context): List<Pair<String, Int>> {
        val prefs = KeyboardSettings.prefs(context)
        if (prefs.getBoolean(CLEAR, false)) return emptyList()
        val hidden = toAdd(context) + ignored(context)
        return read(context, MISSING_FILE, MISSING_MAX).ranked().filter { it.first !in hidden }
    }

    /** Keep this word: it's never corrected and gets suggested. */
    fun add(context: Context, word: String) = addTo(context, TO_ADD, word)

    /** Not a word you want: it leaves the list and won't be listed again. */
    fun dismiss(context: Context, word: String) = addTo(context, IGNORED, word)

    fun clearMissing(context: Context) {
        KeyboardSettings.prefs(context).edit().putBoolean(CLEAR, true).apply()
    }

    fun toAdd(context: Context): Set<String> = stringSet(context, TO_ADD)

    fun ignored(context: Context): Set<String> = stringSet(context, IGNORED)

    /**
     * Keyboard side: carry out what the app screen asked for, then forget the requests.
     * Returns the dismissed words so the keyboard won't list them again.
     */
    fun applyRequests(context: Context, taught: UserWords, missing: UserWords): Set<String> {
        val prefs = KeyboardSettings.prefs(context)
        val adds = toAdd(context)
        val ignored = ignored(context)
        if (prefs.getBoolean(CLEAR, false)) missing.clear()
        for (word in adds) {
            taught.learn(word)
            missing.forget(word)
        }
        ignored.forEach(missing::forget)
        if (adds.isNotEmpty() || prefs.getBoolean(CLEAR, false)) {
            prefs.edit().remove(TO_ADD).remove(CLEAR).apply()
        }
        return ignored
    }

    private fun addTo(context: Context, key: String, word: String) {
        val prefs = KeyboardSettings.prefs(context)
        prefs.edit().putStringSet(key, stringSet(context, key) + word).apply()
    }

    // A copy: Android says the set it returns must not be changed.
    private fun stringSet(context: Context, key: String): Set<String> =
        KeyboardSettings.prefs(context).getStringSet(key, emptySet()).orEmpty().toSet()
}
