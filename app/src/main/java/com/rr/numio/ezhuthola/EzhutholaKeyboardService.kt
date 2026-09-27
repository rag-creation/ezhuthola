package com.rr.numio.ezhuthola

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rr.numio.ezhuthola.engine.EnglishSuggester
import com.rr.numio.ezhuthola.engine.MalayalamEngine
import com.rr.numio.ezhuthola.engine.MalayalamRules
import com.rr.numio.ezhuthola.engine.MalayalamSuggester
import com.rr.numio.ezhuthola.engine.Suggestions
import com.rr.numio.ezhuthola.engine.WordFrequencies

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

    /** Manglish letters of the word being typed, e.g. "paran". Empty = not composing. */
    private val word = StringBuilder()

    /** Loaded in the background at start-up; null for the first moment. */
    @Volatile private var suggester: MalayalamSuggester? = null
    @Volatile private var english: EnglishSuggester? = null

    /** Password, email and web-address fields always get plain English. */
    private var plainField = false

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        Thread {
            val rules = MalayalamRules.fromJson(
                assets.open("ezhuthola_rules.json").bufferedReader().use { it.readText() }
            )
            val words = assets.open("ml_words.tsv").bufferedReader().useLines {
                WordFrequencies.fromTsv(it)
            }
            suggester = MalayalamSuggester(MalayalamEngine(rules), words)
            english = assets.open("en_words.tsv").bufferedReader().useLines {
                EnglishSuggester.fromTsv(it)
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
                    onEnter = ::enter
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

        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        plainField = info?.let(::isPlainField) ?: false
        resetWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        commitWord()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        malayalam = !malayalam
        if (word.isNotEmpty() && !plainField) refreshWord()
    }

    private fun onKeyText(text: String) {
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
        ic.setComposingText(chosen, 1)
        ic.finishComposingText()
        resetWord()
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
        currentInputConnection?.setComposingText(s.best, 1)
    }

    /** Make the underlined word permanent (space, punctuation, enter, switching mode). */
    private fun commitWord() {
        if (word.isEmpty()) return
        currentInputConnection?.finishComposingText()
        resetWord()
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
}