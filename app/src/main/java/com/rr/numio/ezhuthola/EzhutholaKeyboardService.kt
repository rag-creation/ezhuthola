package com.rr.numio.ezhuthola

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethod
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rr.numio.ezhuthola.engine.Clip
import com.rr.numio.ezhuthola.engine.ClipboardHistory
import com.rr.numio.ezhuthola.engine.EmojiSearch
import com.rr.numio.ezhuthola.engine.EnglishInMalayalam
import com.rr.numio.ezhuthola.engine.EnglishSuggester
import com.rr.numio.ezhuthola.engine.MalayalamEngine
import com.rr.numio.ezhuthola.engine.MalayalamRules
import com.rr.numio.ezhuthola.engine.MalayalamSuggester
import com.rr.numio.ezhuthola.engine.MissingWordFilter
import com.rr.numio.ezhuthola.engine.Suggestions
import com.rr.numio.ezhuthola.engine.UserWords
import com.rr.numio.ezhuthola.engine.WordFrequencies
import java.io.File
import java.util.concurrent.Executors

/**
 * The keyboard itself. Android starts this service whenever Ezhuthola is the active keyboard.
 *
 * Letters are collected into the word being typed. In Malayalam mode ("paranju") the best
 * Malayalam spelling is shown underlined and the strip shows options; in English mode the word
 * stays as typed and the strip offers completions ("tomo" → tomorrow).
 * Space, punctuation or enter commits the word; tapping a chip commits that one.
 *
 * A service has no lifecycle of its own, but Compose needs one, so this class provides it
 * (LifecycleOwner + SavedStateRegistryOwner) and attaches it to the keyboard window.
 */
class EzhutholaKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // ---- Keyboard state (Compose redraws the keyboard when these change) ----

    private var malayalam by mutableStateOf(false)
    private var suggestions by mutableStateOf<Suggestions?>(null)

    /** Goes up by one every time the keyboard opens for a field, so the layout starts fresh. */
    private var session by mutableIntStateOf(0)
    private var startWithNumbers by mutableStateOf(false)

    /** Manglish letters of the word being typed, e.g. "paran". Empty = not composing. */
    private val word = StringBuilder()

    /** Last English correction, so backspace can undo it: (setting, setteng). */
    private var undo: Pair<String, String>? = null

    /** What is underlined right now (the typed word, or its Malayalam spelling). */
    private var shown = ""

    /**
     * An English word the cursor was put into after it was typed ("yu|o"): its letters before
     * and after the cursor, so a word tapped in the strip can replace it.
     */
    private var tapped: Pair<Int, Int>? = null
    private val showWordAtCursor = Runnable { suggestForWordAtCursor() }

    /** Right after a word is picked from the strip, don't offer suggestions for it again. */
    private var quietUntil = 0L

    /** Loaded in the background at start-up; null for the first moment. */
    @Volatile private var suggester: MalayalamSuggester? = null
    @Volatile private var english: EnglishSuggester? = null
    @Volatile private var emojiSearch: EmojiSearch? = null

    /** Words this person taught the keyboard. Saved in the app's private files. */
    @Volatile private var mlUser: UserWords? = null
    @Volatile private var enUser: UserWords? = null

    /** English words you type (known ones move up) and ones the keyboard didn't know. */
    @Volatile private var enUsed: UserWords? = null
    @Volatile private var enMissing: UserWords? = null
    private var ignoredMissing: Set<String> = emptySet()

    /** Incognito fields (private browser tabs and such) ask keyboards not to learn anything. */
    private var noLearning = false

    /** Word files are written one at a time, in order, off the main thread. */
    private val fileWriter = Executors.newSingleThreadExecutor()

    // ---- Clipboard ----
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onCopied() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var history = ClipboardHistory()
    /** The last clipboard text we looked at, so a clip you deleted isn't added again. */
    private var lastClipText: String? = null
    private var clipboardOn by mutableStateOf(true)
    private var clips by mutableStateOf<List<Clip>>(emptyList())

    // Stickers (files/stickers, as .webp files)
    private val stickerStore by lazy { StickerStore(this) }
    private var stickers by mutableStateOf<List<File>>(emptyList())
    private var stickerStatus by mutableStateOf<String?>(null)

    /** Password fields: never save what's copied while typing there. */
    private var passwordField = false

    /** Small settings, like the last language mode. Changed in the app screen. */
    private val prefs by lazy { KeyboardSettings.prefs(this) }

    // ---- Look & feel (read from settings each time the keyboard opens) ----
    private var theme by mutableStateOf(Themes.Numio)
    private var photo by mutableStateOf<Bitmap?>(null)
    private var photoStamp = 0L      // when the loaded photo file was saved
    private var photoDim by mutableStateOf(0.45f)
    private var feedback by mutableStateOf(KeyFeedback())
    private var keyboardRoot: View? = null

    /** Password, email and web-address fields always get plain English. */
    private var plainField = false

    /**
     * A theme picked in the app screen shows at once, even while the keyboard is open
     * (the "Try it" box). Kept in a field: Android only holds listeners weakly.
     */
    private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KeyboardSettings.THEME || key == KeyboardSettings.PHOTO_DIM) {
            mainHandler.post { applySettings() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        malayalam = prefs.getBoolean(KeyboardSettings.MALAYALAM, false) // open in the mode you left it
        clipboard.addPrimaryClipChangedListener(clipListener)
        prefs.registerOnSharedPreferenceChangeListener(lookListener)

        Thread {
            // Pinned clips, plus recent ones saved before Android last closed the keyboard.
            val pinned = File(filesDir, CLIPS_FILE)
            val recent = File(filesDir, RECENT_CLIPS_FILE)
            val loaded = ClipboardHistory.fromText(if (pinned.exists()) pinned.readText() else "")
            if (recent.exists()) loaded.mergeFrom(ClipboardHistory.recentFromText(recent.readText()), now())
            mainHandler.post {
                history.mergeFrom(loaded, now())
                clips = history.all(now())
            }
            val ml = readUserWords(ML_USER_FILE)
            val en = readUserWords(LearnedWords.TAUGHT_FILE)
            val used = readUserWords(LearnedWords.USED_FILE)
            val missing = LearnedWords.read(this, LearnedWords.MISSING_FILE, LearnedWords.MISSING_MAX)
            mlUser = ml
            enUser = en
            enUsed = used
            enMissing = missing
            mainHandler.post(::applyWordRequests) // anything added or dismissed in settings
            val rules = MalayalamRules.fromJson(
                assets.open("ezhuthola_rules.json").bufferedReader().use { it.readText() }
            )
            // Ezhuthola's own extra words (places like കേരളം) are added after the main list.
            val extra = assets.open("ml_extra_words.tsv").bufferedReader().use { it.readLines() }
            val words = assets.open("ml_words.tsv").bufferedReader().useLines {
                WordFrequencies.fromTsv(it + extra.asSequence())
            }
            // English words typed in Malayalam mode, written the Malayali way (four → ഫോർ).
            val englishInMl = assets.open("en_ml_words.tsv").bufferedReader().useLines {
                EnglishInMalayalam.fromTsv(it)
            }
            suggester = MalayalamSuggester(MalayalamEngine(rules), words, userWords = ml, english = englishInMl)
            // Ezhuthola's own chat words (bro, tbh, ngl) are merged into the subtitle list.
            // Manglish chat words (machane, kazhicho, vave) join them: never corrected, suggested.
            val enExtra = assets.open("en_extra_words.tsv").bufferedReader().use { it.readLines() } +
                assets.open("en_manglish_words.tsv").bufferedReader().use { it.readLines() }
            // Real but rare words: left as typed and kept off Missing words, never suggested.
            // Quiet words (swear words like myre) are treated the same: typed fine, never offered.
            val enKnown = assets.open("en_known_words.txt").bufferedReader().use { it.readLines() } +
                assets.open("en_quiet_words.txt").bufferedReader().use { it.readLines() }
            english = assets.open("en_words.tsv").bufferedReader().useLines {
                // Manglish like "poda" or "adipoli" is never "corrected" into English.
                EnglishSuggester.fromTsv(
                    it, isManglish = ::isManglish, userWords = en,
                    extra = enExtra.asSequence(), used = used, known = enKnown.asSequence()
                )
            }
            emojiSearch = loadEmojiSearch()
        }.start()
    }

    override fun onCreateInputView(): View {
        val keyboardView = ComposeView(this).apply {
            setBackgroundColor(0xFF141414.toInt())
            setContent {
                KeyboardLayout(
                    malayalam = malayalam,
                    onToggleLanguage = ::toggleLanguage,
                    suggestions = suggestions,
                    onPick = ::pick,
                    onText = ::onKeyText,
                    onBackspace = ::backspace,
                    onEnter = ::enter,
                    session = session,
                    startWithNumbers = startWithNumbers,
                    clips = clips,
                    onOpenClipboard = { captureClipboard(); clips = history.all(now()) },
                    onPasteClip = ::pasteClip,
                    onTogglePin = { history.togglePin(it); clips = history.all(now()); saveClips() },
                    onDeleteClip = { history.delete(it); clips = history.all(now()); saveClips() },
                    onClearClips = { history.clearUnpinned(); clips = history.all(now()); saveClips() },
                    onOpenSettings = ::openSettings,
                    onSwitchKeyboard = {
                        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    },
                    searchEmoji = ::searchEmoji,
                    theme = theme,
                    photo = photo,
                    photoDim = photoDim,
                    feedback = feedback,
                    clipboardOn = clipboardOn,
                    stickers = stickers,
                    stickerStatus = stickerStatus,
                    onOpenStickers = ::openStickers,
                    onSendSticker = ::sendSticker,
                    onMakeSticker = ::openStickerMaker,
                    onDeleteSticker = { stickerStore.delete(it); stickers = stickerStore.list() }
                )
            }
        }

        window?.window?.decorView?.let { root ->
            // Compose looks for a lifecycle on the window's root view, so attach it there.
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)

            // Android 15+ draws the keyboard's navigation bar (gesture handle + hide ∨ button)
            // ON TOP of our keys. The keyboard window draws that bar itself, so Android does
            // not report its size to us as a normal inset. We reserve that space ourselves.
            val navBarHeight = imeNavigationBarHeight()
            keyboardView.setPadding(0, 0, 0, navBarHeight)

            // If Android does report a bigger inset (e.g. 3-button navigation), use that instead.
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                keyboardView.setPadding(
                    navBar.left, 0, navBar.right,
                    maxOf(navBar.bottom, navBarHeight)
                )
                ViewCompat.onApplyWindowInsets(v, insets) // keep the window's normal behaviour
            }
            ViewCompat.requestApplyInsets(root)
        }

        keyboardRoot = keyboardView
        applySettings()
        return keyboardView
    }

    /**
     * Some apps ask for the keyboard on their own when a screen opens, even when there is
     * nothing to type into (no text box, so the field has no input type). Stay hidden then.
     * A tap on a real text box is an explicit request and still opens the keyboard, and so
     * does a terminal app (which also has no input type) when you tap into it.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val askedByUser = (flags and (InputMethod.SHOW_EXPLICIT or InputMethod.SHOW_FORCED)) != 0
        val info = currentInputEditorInfo
        if (!askedByUser && (info == null || info.inputType == InputType.TYPE_NULL)) return false
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        plainField = info?.let(::isPlainField) ?: false
        startWithNumbers = info?.let(::isNumberField) ?: false
        passwordField = info?.let(::isPasswordField) ?: false
        noLearning = info != null &&
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        // `restarting` = same field, the app just refreshed it: keep the current page.
        if (!restarting) session++
        applySettings()
        captureClipboard()
        undo = null
        mainHandler.removeCallbacks(showWordAtCursor)
        tapped = null
        resetWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        mainHandler.removeCallbacks(showWordAtCursor)
        forgetTapped()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        commitWord()
        saveUserWords()
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboard.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterOnSharedPreferenceChangeListener(lookListener)
        saveUserWords()
        fileWriter.shutdown() // writes already queued still finish
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    /** If the user taps somewhere else in the text, stop composing and keep what's there. */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val cursorLeftTheWord = newSelStart != newSelEnd || candidatesStart < 0 || newSelEnd != candidatesEnd
        if (word.isNotEmpty() && cursorLeftTheWord) {
            val ic = currentInputConnection
            // Some apps drop the underline while you type, and "suggestion" would split into
            // "sugges" + "tion". If the cursor is still right after the word, pick it back up.
            if (newSelStart == newSelEnd && candidatesStart < 0 && shown.isNotEmpty() &&
                ic != null && endsWithWord(ic.getTextBeforeCursor(shown.length + 1, 0), shown)
            ) {
                ic.setComposingRegion(newSelStart - shown.length, newSelStart)
                return
            }
            ic?.finishComposingText()
            resetWord()
        }
        // The cursor was moved into a word that's already typed: show suggestions for it.
        // Waits a moment, so the steps of our own typing (word, then space) don't flash the strip.
        mainHandler.removeCallbacks(showWordAtCursor)
        if (word.isEmpty() && newSelStart == newSelEnd) mainHandler.postDelayed(showWordAtCursor, 150)
        else forgetTapped()
    }

    private fun endsWithWord(before: CharSequence?, w: String): Boolean {
        val b = before?.toString() ?: return false
        return b.endsWith(w) && (b.length == w.length || !b[0].isLetter())
    }

    /** English only: the cursor sits in or right after a word like "yuo", so suggest "you". */
    private fun suggestForWordAtCursor() {
        if (word.isNotEmpty() || malayalam || plainField || passwordField) return
        if (SystemClock.uptimeMillis() < quietUntil) return
        val en = english ?: return
        val ic = currentInputConnection ?: return
        val left = ic.getTextBeforeCursor(MAX_WORD, 0)?.toString().orEmpty().takeLastWhile(::isWordChar)
        val right = ic.getTextAfterCursor(MAX_WORD, 0)?.toString().orEmpty().takeWhile(::isWordChar)
        val whole = left + right
        if (left.isEmpty() || whole.length < 2 || !whole.any { it.isAsciiLetter() }) {
            forgetTapped()
            return
        }
        tapped = left.length to right.length
        suggestions = en.suggest(whole)
    }

    private fun forgetTapped() {
        if (tapped != null) suggestions = null
        tapped = null
    }

    private fun isWordChar(c: Char) = c.isAsciiLetter() || c == '\''

    /**
     * Typing a letter right after a word ("sugges|"): carry on with that word, so it is
     * checked and suggested as one. Not in the middle of a word, and not in Malayalam.
     */
    private fun resumeWordBeforeCursor() {
        if (malayalam) return
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (after.isNotEmpty() && isWordChar(after[0])) return
        val left = ic.getTextBeforeCursor(MAX_WORD, 0)?.toString().orEmpty().takeLastWhile(::isWordChar)
        if (left.isEmpty() || left.length >= MAX_WORD || !left.any { it.isAsciiLetter() }) return
        ic.deleteSurroundingText(left.length, 0)
        word.append(left)
    }

    // ---- Keys --------------------------------------------------------------

    /**
     * Switching mid-word converts the word being typed instead of finishing it:
     * type "nal", tap the leaf, and it becomes Malayalam; keep typing "e" → നാളെ.
     */
    private fun toggleLanguage() {
        undo = null
        malayalam = !malayalam
        prefs.edit().putBoolean(KeyboardSettings.MALAYALAM, malayalam).apply()
        if (word.isNotEmpty() && !plainField) refreshWord()
    }

    private fun onKeyText(text: String) {
        undo = null
        forgetTapped()
        when {
            // A letter: add it to the word being typed (Manglish or English).
            !plainField && text.length == 1 && text[0].isAsciiLetter() -> {
                if (word.isEmpty()) resumeWordBeforeCursor()
                word.append(text)
                refreshWord()
            }
            // Space: commit the best word, then the space.
            text == " " -> {
                commitWord()
                commit(" ")
            }
            // Anything else (punctuation, numbers, emoji): finish the word first.
            else -> {
                commitWord()
                commit(text)
            }
        }
    }

    private fun backspace() {
        forgetTapped()
        if (word.isNotEmpty()) {
            word.deleteCharAt(word.lastIndex)
            if (word.isEmpty()) {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
                resetWord()
            } else {
                refreshWord()
            }
            return
        }
        // A real key event handles selected text and emoji correctly.
        if (undoCorrection()) return
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        forgetTapped()
        commitWord()
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            // Search, Send, Go, Next, Done... whatever the app asked for.
            ic.performEditorAction(action)
        } else {
            // Plain new line (e.g. WhatsApp message box).
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** A word from the strip was tapped: type it plus a space. */
    private fun pick(chosen: String) {
        val ic = currentInputConnection ?: return
        quietUntil = SystemClock.uptimeMillis() + 500
        tapped?.let { (before, after) ->
            // A word typed earlier ("yuo"): swap it in place, adding a space only at the end.
            tapped = null
            learn(chosen)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(before, after)
            ic.commitText(chosen, 1)
            if (ic.getTextAfterCursor(1, 0).isNullOrEmpty()) ic.commitText(" ", 1)
            ic.endBatchEdit()
            suggestions = null
            undo = null
            return
        }
        learn(chosen)
        ic.setComposingText(chosen, 1)
        ic.finishComposingText()
        resetWord()
        undo = null
        commit(" ")
    }

    // ---- Composing ---------------------------------------------------------

    /**
     * Update the underlined word and the strip.
     * Malayalam: shows the best Malayalam spelling. English: shows exactly what was typed,
     * with word completions in the strip.
     */
    private fun refreshWord() {
        val typed = word.toString()
        val loading = Suggestions(typed = typed, best = typed, words = emptyList())
        val s = if (malayalam) {
            suggester?.suggest(typed) ?: loading
        } else {
            english?.suggest(typed) ?: loading
        }
        suggestions = s
        // Malayalam shows the best spelling while typing; English shows exactly what was typed
        // (a correction is only swapped in when the word is finished).
        shown = if (malayalam) s.best else typed
        currentInputConnection?.setComposingText(shown, 1)
    }

    /** Make the underlined word permanent (space, punctuation, enter, switching mode). */
    private fun commitWord() {
        if (word.isEmpty()) return
        val ic = currentInputConnection
        val s = suggestions
        // English: swap in the correction ("setteng" → setting) and remember it for undo.
        if (!malayalam && s != null && s.best != s.typed) {
            ic?.setComposingText(s.best, 1)
            undo = s.best to s.typed
        }
        if (!malayalam) noteEnglishWord(s?.best ?: word.toString())
        ic?.finishComposingText()
        resetWord()
    }

    /**
     * Learn from an English word that was just typed. A known word counts as used, so it moves
     * up in completions. An unknown one goes on the "Missing words" list in settings, unless
     * it's Manglish (Malayalam mode handles that) or you dismissed it before.
     * Nothing is learned in incognito fields, and password fields never get here.
     */
    private fun noteEnglishWord(typed: String) {
        if (noLearning || plainField) return
        val en = english ?: return
        val lower = typed.lowercase()
        if (lower.length < 2 || lower.length > 30 || !lower.all { it in 'a'..'z' || it == '\'' }) return
        if (en.knows(lower)) {
            enUsed?.learn(lower)
        } else if (!isManglish(lower) && lower !in ignoredMissing) {
            // Skip bits like "qr" and pieces of links ("https", "getnumio" in https://getnumio.org).
            val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: lower
            if (MissingWordFilter.worthNoting(lower, before)) enMissing?.learn(lower)
        }
    }

    /** Common Manglish like "poda" or "adipoli" (it's in the Malayalam word list). */
    private fun isManglish(word: String) = (suggester?.commonness(word) ?: 0) >= 50

    /** Backspace right after a correction puts back what was typed: "setting " → setteng. */
    private fun undoCorrection(): Boolean {
        val (fixed, typed) = undo ?: return false
        undo = null
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString() != "$fixed ") return false
        ic.deleteSurroundingText(fixed.length + 1, 0)
        ic.commitText(typed, 1)
        enUser?.learn(typed.lowercase()) // "machane" won't be "corrected" again
        enMissing?.forget(typed.lowercase())
        return true
    }

    // ---- Settings -------------------------------------------------------------

    /** Picks up anything changed in the app screen: theme, photo, vibration, sound, clipboard. */
    private fun applySettings() {
        applyWordRequests()
        theme = Themes.byId(prefs.getString(KeyboardSettings.THEME, Themes.Numio.id))
        photoDim = prefs.getFloat(KeyboardSettings.PHOTO_DIM, 0.45f)
        feedback = KeyFeedback(
            vibrate = prefs.getBoolean(KeyboardSettings.VIBRATE, true),
            sound = prefs.getBoolean(KeyboardSettings.KEY_SOUND, false)
        )
        clipboardOn = prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true)
        if (!clipboardOn) {
            if (history.all(now()).any { !it.pinned }) { history.clearUnpinned(); saveClips() }
            clips = history.all(now())
        }
        // Load the photo only when the photo theme is on, and again only if it changed.
        val file = KeyboardSettings.photoFile(this)
        if (theme.usesPhoto && file.exists()) {
            if (photo == null || file.lastModified() != photoStamp) {
                photoStamp = file.lastModified()
                Thread {
                    val bitmap = KeyboardSettings.loadPhoto(this)
                    mainHandler.post { photo = bitmap }
                }.start()
            }
        } else {
            photo = null
        }
        // The strip under the keys (behind Android's navigation bar) matches the theme,
        // and the ∨ / gesture bar turns dark on light themes so it stays visible.
        keyboardRoot?.setBackgroundColor(theme.background.toArgb())
        window?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars =
                theme.isLight
        }
    }

    /** Gear in the strip: open the Ezhuthola app screen. */
    private fun openSettings() {
        commitWord()
        requestHideSelf(0)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- Stickers -------------------------------------------------------------

    private fun openStickers() {
        stickerStatus = null
        stickerStore.addSamplesOnce()
        stickers = stickerStore.list()
    }

    /** "+ Make" in the sticker panel: open the maker (typing a caption there uses Ezhuthola too). */
    private fun openStickerMaker() {
        commitWord()
        requestHideSelf(0)
        startActivity(
            Intent(this, StickerMakerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Tap a sticker: WhatsApp gets a real sticker, other apps an image, if they take one. */
    private fun sendSticker(file: File) {
        commitWord()
        val result = StickerSender.send(this, currentInputConnection, currentInputEditorInfo, file)
        stickerStatus = when (result) {
            StickerResult.STICKER -> null
            StickerResult.IMAGE -> "Sent as a picture (this app has no stickers)"
            StickerResult.NOT_SUPPORTED -> "This app doesn't accept stickers here"
        }
    }

    // ---- Clipboard ------------------------------------------------------------

    /** Something was copied. Save it, unless it's a password, an OTP, or history is off. */
    private fun onCopied() {
        val text = readClipboard() ?: return
        lastClipText = text
        history.add(text, now())
        clips = history.all(now())
        saveClips()
    }

    /**
     * Android doesn't always tell a keyboard that something was copied (and Samsung may close
     * the keyboard in the background, losing what it heard). So whenever the keyboard opens,
     * or the clipboard panel does, look at the clipboard ourselves and add anything new.
     */
    private fun captureClipboard() {
        val text = readClipboard() ?: return
        if (text == lastClipText) return // already have it, or it was deleted on purpose
        lastClipText = text
        // Already in the list (saved before Android closed the keyboard)? Leave it where it is.
        if (history.all(now()).any { it.text == text }) return
        history.add(text, now())
        clips = history.all(now())
        saveClips()
    }

    /** The copied text, or null for nothing / passwords / OTPs / history switched off. */
    private fun readClipboard(): String? {
        if (!prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true) || passwordField) return null
        val clip = try { clipboard.primaryClip } catch (e: Exception) { null } ?: return null
        // Password managers and OTP autofill mark their copies as sensitive (Android 13+).
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return null
        if (clip.itemCount == 0) return null
        val text = try {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        } catch (e: Exception) { "" }
        return text.ifEmpty { null }
    }

    private fun pasteClip(text: String) {
        commitWord()
        undo = null
        commit(text)
    }

    /**
     * Pinned clips, and recent ones, are written to the app's private storage. Recent clips are
     * saved too because Android (Samsung especially) closes keyboards in the background, which
     * used to wipe the list. They still expire after an hour, like before.
     */
    private fun saveClips() {
        val pinnedText = if (history.dirty) history.toText() else null
        val recentText = history.recentToText(now())
        fileWriter.execute {
            pinnedText?.let { File(filesDir, CLIPS_FILE).writeText(it) }
            File(filesDir, RECENT_CLIPS_FILE).writeText(recentText)
        }
    }

    private fun now() = System.currentTimeMillis()

    // ---- Emoji search -----------------------------------------------------------

    /**
     * Unicode CLDR keywords (English + Malayalam) and Ezhuthola's own Manglish words.
     * Emojis this phone's font can't draw are left out, so search never shows empty boxes.
     */
    private fun loadEmojiSearch(): EmojiSearch {
        val paint = Paint()
        val entries = assets.open("emoji_keywords.tsv").bufferedReader().useLines {
            EmojiSearch.parseKeywords(it)
        }.filter { paint.hasGlyph(it.emoji) }
        val own = assets.open("emoji_manglish.tsv").bufferedReader().useLines {
            EmojiSearch.parseManglish(it)
        }
        return EmojiSearch(entries, own)
    }

    /**
     * Search from the emoji panel: "love", "chiri", "kollam".
     * Manglish words are read by the Malayalam engine ("chiri" → ചിരി) and matched against
     * Unicode's Malayalam keywords. The reading of the last word is shown under the search box.
     */
    private fun searchEmoji(query: String): EmojiResults {
        val search = emojiSearch ?: return EmojiResults(emptyList())
        val ml = suggester
        val readings = HashMap<String, List<String>>()
        fun read(word: String) = readings.getOrPut(word) { ml?.suggest(word)?.words ?: emptyList() }

        val emojis = search.search(query, ::read)
        val last = query.trim().substringAfterLast(' ')
        // Only show a reading that really matched a Malayalam emoji word ("chiri" → ചിരി),
        // never a guess for an English word ("love" → ലോവെ).
        val reading = if (last.length >= 2) search.bestReading(last, read(last)) else null
        return EmojiResults(emojis, reading)
    }

    // ---- Learning your words ------------------------------------------------

    /**
     * A chip was tapped: remember that choice.
     * Your own letters (the typed chip, or anything in English mode) → English words to keep.
     * A Malayalam chip → that spelling comes first next time.
     */
    private fun learn(chosen: String) {
        val typed = suggestions?.typed ?: return
        if (malayalam && chosen != typed) {
            mlUser?.learn(chosen)
        } else {
            enUser?.learn(chosen.lowercase())
            enMissing?.forget(chosen.lowercase())
        }
    }

    private fun readUserWords(name: String): UserWords {
        val file = File(filesDir, name)
        return if (file.exists()) UserWords.fromTsv(file.readText()) else UserWords()
    }

    /**
     * The app screen's "Missing words" list leaves requests (add, dismiss, clear) instead of
     * changing the files itself; carry them out here. Runs on the main thread.
     */
    private fun applyWordRequests() {
        val taught = enUser ?: return
        val missing = enMissing ?: return
        ignoredMissing = LearnedWords.applyRequests(this, taught, missing)
        writeChangedWords() // so the list in the app screen is up to date
    }

    /** Writes learned words to the phone's storage (in the background, only if changed). */
    private fun saveUserWords() {
        applyWordRequests() // e.g. "Add" tapped while the keyboard was open in the Try it box
        writeChangedWords()
    }

    private fun writeChangedWords() {
        for ((words, name) in listOf(
            mlUser to ML_USER_FILE,
            enUser to LearnedWords.TAUGHT_FILE,
            enUsed to LearnedWords.USED_FILE,
            enMissing to LearnedWords.MISSING_FILE,
        )) {
            if (words == null || !words.dirty) continue
            val text = words.toTsv()
            fileWriter.execute { File(filesDir, name).writeText(text) }
        }
    }

    private fun resetWord() {
        word.clear()
        shown = ""
        suggestions = null
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun isPlainField(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                // Not TYPE_TEXT_VARIATION_URI: browser address bars use it, and people
                // search in Malayalam there. The leaf key switches to English for URLs.
            )
            else -> false
        }
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Number, phone, date and time fields open on the ?123 page. */
    private fun isNumberField(info: EditorInfo): Boolean =
        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }

    /** Height of the navigation bar Android draws over keyboards (Android 15 and newer). */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun imeNavigationBarHeight(): Int {
        if (Build.VERSION.SDK_INT < 35) return 0 // older Android lays the keyboard out above it
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        return if (id != 0) {
            resources.getDimensionPixelSize(id)
        } else {
            (48 * resources.displayMetrics.density).toInt() // safe fallback: 48dp
        }
    }

    private companion object {
        /** Longest word picked back up or suggested for after the cursor moves. */
        const val MAX_WORD = 30
        const val ML_USER_FILE = "user_words_ml.tsv"
        const val CLIPS_FILE = "pinned_clips.txt"
        const val RECENT_CLIPS_FILE = "recent_clips.txt"
    }
}