package com.rr.numio.ezhuthola

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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

/**
 * The keyboard itself. Android starts this service whenever Ezhuthola is the active keyboard.
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

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        val keyboardView = ComposeView(this).apply {
            setBackgroundColor(0xFF141414.toInt())
            setContent {
                KeyboardLayout(
                    onText = ::typeText,
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // ---- Sending text to the app the user is typing in ----

    private fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        // A real key event handles selected text and emoji correctly.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
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
}