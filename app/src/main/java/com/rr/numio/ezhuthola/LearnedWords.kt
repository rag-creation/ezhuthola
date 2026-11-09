package com.rr.numio.ezhuthola

import android.content.Context
import com.rr.numio.ezhuthola.engine.UserWords
import java.io.File

/**
 * The English words the keyboard learns as you type, shared by the keyboard and the app screen.
 * All of it stays in Ezhuthola's private storage on the phone.
 *
 * - Malayalam: words picked from Malayalam suggestions or added to the dictionary's Malayalam tab
 * - taught: words you told the keyboard to keep (never corrected, suggested first). This is
 *   "Your dictionary" in settings, where you can add, change and delete them.
 * - used: known words you type, so the ones you use move up ("br" → bro)
 * - missing: words you typed that the keyboard didn't know, for you to add or dismiss in settings
 *
 * Only the keyboard writes these files. The app screen leaves requests in the preferences
 * (add this word, remove that one, dismiss, clear the list) and the keyboard carries them out the next
 * time it opens or closes. That way the two never overwrite each other's changes, even when
 * the keyboard is open in the app's own "Try it" box.
 */
object LearnedWords {
    const val TAUGHT_FILE = "user_words_en.tsv"
    const val USED_FILE = "used_words_en.tsv"
    const val MISSING_FILE = "missing_words_en.tsv"

    /** Words waiting to be added (tapped "Add" in settings). */
    const val TO_ADD = "missing_to_add"

    /** Words waiting to be taken out of your dictionary (deleted or changed in settings). */
    const val TO_REMOVE = "dictionary_to_remove"

    /** Malayalam words: picked from suggestions or added in "Your dictionary" (Malayalam tab). */
    const val ML_FILE = "user_words_ml.tsv"
    const val ML_TO_ADD = "dictionary_ml_to_add"
    const val ML_TO_REMOVE = "dictionary_ml_to_remove"

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
    fun add(context: Context, word: String, malayalam: Boolean = false) {
        val (addKey, removeKey) = keys(malayalam)
        KeyboardSettings.prefs(context).edit()
            .putStringSet(addKey, stringSet(context, addKey) + word)
            .putStringSet(removeKey, stringSet(context, removeKey) - word)
            .apply()
    }

    /** Take this word out of your dictionary: it can be corrected again like any other word. */
    fun remove(context: Context, word: String, malayalam: Boolean = false) {
        val (addKey, removeKey) = keys(malayalam)
        KeyboardSettings.prefs(context).edit()
            .putStringSet(addKey, stringSet(context, addKey) - word)
            .putStringSet(removeKey, stringSet(context, removeKey) + word)
            .apply()
    }

    /** Fix a word in your dictionary (e.g. a typo when you added it). */
    fun change(context: Context, old: String, new: String, malayalam: Boolean = false) {
        if (old == new) return
        remove(context, old, malayalam)
        add(context, new, malayalam)
    }

    /** "Your dictionary" as the app screen should show it: A–Z, requests applied. */
    fun dictionary(context: Context, malayalam: Boolean = false): List<String> {
        val (addKey, removeKey) = keys(malayalam)
        val removed = stringSet(context, removeKey)
        val file = if (malayalam) ML_FILE else TAUGHT_FILE
        return (read(context, file).words + stringSet(context, addKey)).filter { it !in removed }.sorted()
    }

    private fun keys(malayalam: Boolean) =
        if (malayalam) ML_TO_ADD to ML_TO_REMOVE else TO_ADD to TO_REMOVE

    /** Not a word you want: it leaves the list and won't be listed again. */
    fun dismiss(context: Context, word: String) = addTo(context, IGNORED, word)

    fun clearMissing(context: Context) {
        KeyboardSettings.prefs(context).edit().putBoolean(CLEAR, true).apply()
    }

    fun toAdd(context: Context): Set<String> = stringSet(context, TO_ADD)

    fun ignored(context: Context): Set<String> = stringSet(context, IGNORED)

    fun toRemove(context: Context): Set<String> = stringSet(context, TO_REMOVE)

    /**
     * Keyboard side: carry out what the app screen asked for, then forget the requests.
     * Returns the dismissed words so the keyboard won't list them again.
     */
    fun applyRequests(context: Context, taught: UserWords, missing: UserWords, malayalam: UserWords? = null): Set<String> {
        val prefs = KeyboardSettings.prefs(context)
        val adds = toAdd(context)
        val removes = toRemove(context)
        val ignored = ignored(context)
        if (prefs.getBoolean(CLEAR, false)) missing.clear()
        for (word in adds) {
            taught.learn(word)
            missing.forget(word)
        }
        removes.forEach(taught::forget)
        val mlAdds = stringSet(context, ML_TO_ADD)
        val mlRemoves = stringSet(context, ML_TO_REMOVE)
        if (malayalam != null && (mlAdds.isNotEmpty() || mlRemoves.isNotEmpty())) {
            mlAdds.forEach(malayalam::learn)
            mlRemoves.forEach(malayalam::forget)
            prefs.edit().remove(ML_TO_ADD).remove(ML_TO_REMOVE).apply()
        }
        ignored.forEach(missing::forget)
        if (adds.isNotEmpty() || removes.isNotEmpty() || prefs.getBoolean(CLEAR, false)) {
            prefs.edit().remove(TO_ADD).remove(TO_REMOVE).remove(CLEAR).apply()
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
