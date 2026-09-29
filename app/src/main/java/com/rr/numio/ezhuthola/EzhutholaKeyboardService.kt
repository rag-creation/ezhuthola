package com.rr.numio.ezhuthola

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
import com.rr.numio.ezhuthola.engine.EnglishSuggester
import com.rr.numio.ezhuthola.engine.MalayalamEngine
import com.rr.numio.ezhuthola.engine.MalayalamRules
import com.rr.numio.ezhuthola.engine.MalayalamSuggester
import com.rr.numio.ezhuthola.engine.Suggestions
import com.rr.numio.ezhuthola.engine.UserWords
import com.rr.numio.ezhuthola.engine.WordFrequencies
import java.io.File

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

    /** Loaded in the background at start-up; null for the first moment. */
    @Volatile private var suggester: MalayalamSuggester? = null
    @Volatile private var english: EnglishSuggester? = null

    /** Words this person taught the keyboard. Saved in the app's private files. */
    @Volatile private var mlUser: UserWords? = null
    @Volatile private var enUser: UserWords? = null

    // ---- Clipboard ----
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onCopied() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var history = ClipboardHistory()
    private var clips by mutableStateOf<List<Clip>>(emptyList())

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

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        malayalam = prefs.getBoolean(KeyboardSettings.MALAYALAM, false) // open in the mode you left it
        clipboard.addPrimaryClipChangedListener(clipListener)

        Thread {
            val pinned = File(filesDir, CLIPS_FILE)
            if (pinned.exists()) {
                val loaded = ClipboardHistory.fromText(pinned.readText())
                mainHandler.post { history = loaded }
            }
            val ml = readUserWords(ML_USER_FILE)
            val en = readUserWords(EN_USER_FILE)
            mlUser = ml
            enUser = en
            val rules = MalayalamRules.fromJson(
                assets.open("ezhuthola_rules.json").bufferedReader().use { it.readText() }
            )
            val words = assets.open("ml_words.tsv").bufferedReader().useLines {
                WordFrequencies.fromTsv(it)
            }
            suggester = MalayalamSuggester(MalayalamEngine(rules), words, userWords = ml)
            english = assets.open("en_words.tsv").bufferedReader().useLines {
                // Manglish like "poda" or "adipoli" is never "corrected" into English.
                EnglishSuggester.fromTsv(it, isManglish = { w ->
                    (suggester?.commonness(w) ?: 0) >= 50
                }, userWords = en)
            }
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
                    onOpenClipboard = { clips = history.all(now()) },
                    onPasteClip = ::pasteClip,
                    onTogglePin = { history.togglePin(it); clips = history.all(now()); saveClips() },
                    onDeleteClip = { history.delete(it); clips = history.all(now()); saveClips() },
                    onClearClips = { history.clearUnpinned(); clips = history.all(now()) },
                    onOpenSettings = ::openSettings,
                    onSwitchKeyboard = {
                        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    },
                    theme = theme,
                    photo = photo,
                    photoDim = photoDim,
                    feedback = feedback
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        plainField = info?.let(::isPlainField) ?: false
        startWithNumbers = info?.let(::isNumberField) ?: false
        passwordField = info?.let(::isPasswordField) ?: false
        // `restarting` = same field, the app just refreshed it: keep the current page.
        if (!restarting) session++
        applySettings()
        undo = null
        resetWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        commitWord()
        saveUserWords()
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboard.removePrimaryClipChangedListener(clipListener)
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
            currentInputConnection?.finishComposingText()
            resetWord()
        }
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
        when {
            // A letter: add it to the word being typed (Manglish or English).
            !plainField && text.length == 1 && text[0].isAsciiLetter() -> {
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
        currentInputConnection?.setComposingText(if (malayalam) s.best else typed, 1)
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
        ic?.finishComposingText()
        resetWord()
    }

    /** Backspace right after a correction puts back what was typed: "setting " → setteng. */
    private fun undoCorrection(): Boolean {
        val (fixed, typed) = undo ?: return false
        undo = null
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString() != "$fixed ") return false
        ic.deleteSurroundingText(fixed.length + 1, 0)
        ic.commitText(typed, 1)
        enUser?.learn(typed.lowercase()) // "machane" won't be "corrected" again
        return true
    }

    // ---- Settings -------------------------------------------------------------

    /** Picks up anything changed in the app screen: theme, photo, vibration, sound, clipboard. */
    private fun applySettings() {
        theme = Themes.byId(prefs.getString(KeyboardSettings.THEME, Themes.Numio.id))
        photoDim = prefs.getFloat(KeyboardSettings.PHOTO_DIM, 0.45f)
        feedback = KeyFeedback(
            vibrate = prefs.getBoolean(KeyboardSettings.VIBRATE, true),
            sound = prefs.getBoolean(KeyboardSettings.KEY_SOUND, false)
        )
        if (!prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true)) {
            history.clearUnpinned()
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
        // and the ∨ / gesture bar turns dark on the Light theme so it stays visible.
        keyboardRoot?.setBackgroundColor(theme.background.toArgb())
        window?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars =
                theme.id == Themes.Light.id
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

    // ---- Clipboard ------------------------------------------------------------

    /** Something was copied. Save it, unless it's a password, an OTP, or history is off. */
    private fun onCopied() {
        if (!prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true) || passwordField) return
        val clip = try { clipboard.primaryClip } catch (e: SecurityException) { null } ?: return
        // Password managers and OTP autofill mark their copies as sensitive (Android 13+).
        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        history.add(text, now())
        clips = history.all(now())
    }

    private fun pasteClip(text: String) {
        commitWord()
        undo = null
        commit(text)
    }

    /** Only pinned clips are written to storage. */
    private fun saveClips() {
        if (!history.dirty) return
        val text = history.toText()
        Thread { File(filesDir, CLIPS_FILE).writeText(text) }.start()
    }

    private fun now() = System.currentTimeMillis()

    // ---- Learning your words ------------------------------------------------

    /**
     * A chip was tapped: remember that choice.
     * Your own letters (the typed chip, or anything in English mode) → English words to keep.
     * A Malayalam chip → that spelling comes first next time.
     */
    private fun learn(chosen: String) {
        val typed = suggestions?.typed ?: return
        if (malayalam && chosen != typed) mlUser?.learn(chosen) else enUser?.learn(chosen.lowercase())
    }

    private fun readUserWords(name: String): UserWords {
        val file = File(filesDir, name)
        return if (file.exists()) UserWords.fromTsv(file.readText()) else UserWords()
    }

    /** Writes learned words to the phone's storage (in the background, only if changed). */
    private fun saveUserWords() {
        for ((words, name) in listOf(mlUser to ML_USER_FILE, enUser to EN_USER_FILE)) {
            if (words == null || !words.dirty) continue
            val text = words.toTsv()
            Thread { File(filesDir, name).writeText(text) }.start()
        }
    }

    private fun resetWord() {
        word.clear()
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
        const val ML_USER_FILE = "user_words_ml.tsv"
        const val EN_USER_FILE = "user_words_en.tsv"
        const val CLIPS_FILE = "pinned_clips.txt"
    }
}