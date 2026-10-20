package com.rr.numio.ezhuthola

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.media.AudioManager
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rr.numio.ezhuthola.engine.Clip
import com.rr.numio.ezhuthola.engine.FancyText
import com.rr.numio.ezhuthola.engine.Suggestions
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import android.os.SystemClock
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

// ---- Sizes: change these to resize the whole keyboard ----
private val BaseRowHeight = 88.dp      // touch height of one row (includes the gap) at 100%
/** Row height after the "Keyboard height" setting (75%–125%). */
private val RowHeight: Dp @Composable get() = BaseRowHeight * LocalKeyHeight.current
private val KeyGapH = 2.dp             // half of the gap between keys
private val KeyGapV = 5.dp             // half of the gap between rows
private const val KeyFontSize = 28
private const val LongPressMs = 350L   // hold a top-row key this long to type its number
private const val RepeatStartMs = 400L // hold backspace this long before it repeats
private const val RepeatEveryMs = 50L
private val SlideSlop = 14.dp          // a finger moving less than this is still a tap
private val CursorStep = 12.dp         // slide on space: one character per step
private val WordStep = 26.dp           // slide left from backspace: one word per step
private val AltPopupHeight = 54.dp     // the accents row that opens above a held key
private val EmojiRowHeight = 46.dp     // quick emoji row above the keys

/** Extra height panels add so they match the keyboard when the quick emoji row is on. */
private val LocalPanelExtra = staticCompositionLocalOf { 0.dp }

// ---- Colours: they come from the chosen theme (see KeyboardTheme.kt) ----
private val KeyboardBg: Color @Composable get() = LocalKeyboardTheme.current.background
private val KeyColor: Color @Composable get() = LocalKeyboardTheme.current.key
private val SpecialKeyColor: Color @Composable get() = LocalKeyboardTheme.current.specialKey
private val KeyText: Color @Composable get() = LocalKeyboardTheme.current.keyText
private val HintText: Color @Composable get() = LocalKeyboardTheme.current.hint
private val Accent: Color @Composable get() = LocalKeyboardTheme.current.accent
private val CardColor: Color @Composable get() = LocalKeyboardTheme.current.card

/** Key-press feedback: vibration and/or click sound, as set in the app. */
private fun View.keyTap(feedback: KeyFeedback) {
    if (feedback.vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    if (feedback.sound) {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }
}

private enum class Shift { OFF, ONCE, LOCKED }

private val numberHints = "1234567890".map { it.toString() }

private val letterRows = listOf(
    "qwertyuiop".map { it.toString() },
    "asdfghjkl".map { it.toString() },
    "zxcvbnm".map { it.toString() }
)

private val symbolRows = listOf(
    "1234567890".map { it.toString() },
    listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")", "/"),
    listOf("*", "\"", "'", ":", ";", "!", "?")
)

// Third page: decorations for names and bios (the ✿ key). Hold some for more.
private val decorRows = listOf(
    listOf("༺", "♡", "༻", "彡", "★", "✯", "✪", "❂", "☘", "⌘"),
    listOf("ღ", "✿", "❀", "❝", "❞", "⫷", "⫸", "◁", "▷", "⑅"),
    listOf("◉", "◎", "◐", "◑", "⊙", "⊗", "〇", "∇", "∆")
)
private val DecorAlternates: Map<String, List<String>> = mapOf(
    "༺" to listOf("༒", "༆", "༄"),
    "◉" to listOf("◍"),
    "◐" to listOf("◕", "◔", "◓", "◒"),
    "〇" to listOf("〶")
)

// Second symbols page (the =\< key)
private val moreSymbolRows = listOf(
    listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
    listOf("£", "$", "€", "¥", "^", "°", "=", "{", "}", "\\"),
    listOf("₹", "%", "©", "®", "™", "[", "]", "<", ">")
)

/**
 * Hold a letter for these. The first one is what you get if you just hold and let go:
 * the number on the top row, and on the other rows the symbol in the same spot on ?123.
 */
private val Alternates: Map<String, List<String>> = mapOf(
    "q" to listOf("1"),
    "w" to listOf("2", "ŵ"),
    "e" to listOf("3", "é", "è", "ê", "ë", "ē", "ę", "ė"),
    "r" to listOf("4", "ŕ", "ř"),
    "t" to listOf("5", "ť", "ţ", "þ"),
    "y" to listOf("6", "ý", "ÿ", "ŷ"),
    "u" to listOf("7", "ú", "ü", "ù", "û", "ū", "ů", "ű"),
    "i" to listOf("8", "í", "ì", "î", "ï", "ī", "į", "ı"),
    "o" to listOf("9", "ó", "ò", "ô", "ö", "õ", "ø", "œ", "ō", "ő"),
    "p" to listOf("0"),
    "a" to listOf("@", "á", "à", "â", "ä", "ã", "å", "æ", "ā", "ą"),
    "s" to listOf("#", "ß", "ś", "š", "ş"),
    "d" to listOf("₹", "ď", "đ", "ð"),
    "f" to listOf("_"),
    "g" to listOf("&", "ğ", "ġ"),
    "h" to listOf("-", "ĥ", "ħ"),
    "j" to listOf("+", "ĵ"),
    "k" to listOf("(", "[", "ķ"),
    "l" to listOf(")", "]", "/", "ł", "ľ", "ĺ"),
    "z" to listOf("*", "ž", "ź", "ż"),
    "x" to listOf("\""),
    "c" to listOf("'", "ç", "ć", "č", "ċ"),
    "v" to listOf(":"),
    "b" to listOf(";"),
    "n" to listOf("!", "ñ", "¡", "ń", "ň"),   // Spanish: ñ and ¡
    "m" to listOf("?", "¿")                  // Spanish: ¿
)

/**
 * Arabic keyboard (beta): the standard Arabic phone layout (ض on the Q key).
 * All 28 letters plus ء ة ى ؤ; hold ا ل ي و ت ء for the other forms (أ إ آ, لا, ئ, ة).
 */
private val arabicRows = listOf(
    listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
    listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
    listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د")
)

/** Arabic-Indic digits, offered next to the normal ones when holding the top row. */
private val arabicDigits = "١٢٣٤٥٦٧٨٩٠".map { it.toString() }

private val ArabicAlternates: Map<String, List<String>> = buildMap {
    // Top row: hold for the number (1–0), then the Arabic-Indic one (١–٠).
    arabicRows[0].take(10).forEachIndexed { i, k -> put(k, listOf(numberHints[i], arabicDigits[i])) }
    put("ا", listOf("أ", "إ", "آ", "ٱ"))
    put("ل", listOf("لا", "لأ", "لإ", "لآ"))
    put("ي", listOf("ئ", "ى"))
    put("ى", listOf("ئ", "ي"))
    put("و", listOf("ؤ"))
    put("ء", listOf("ئ", "ؤ", "أ", "إ"))
    put("ت", listOf("ة"))
}

/** Harakat (short vowels and marks): the key left of ذ types fatha, hold it for the rest. */
private val Harakat = listOf("َ", "ُ", "ِ", "ّ", "ْ", "ً", "ٌ", "ٍ", "ـ")

/** Hold the period key in Arabic: Arabic question mark, comma and semicolon first. */
private val ArabicPeriodAlternates = listOf("؟", "!", "،", "؛", ":", "\"", "'", "-", "…")

/** Hold the period key for more punctuation. */
private val PeriodAlternates = listOf("?", "!", "'", "\"", ":", ";", "-", "…", "¿", "¡")

/** Capital version of an accent when shift is on ("é" → "É"); symbols and ß stay as they are. */
private fun String.shifted(): String =
    if (length == 1 && this[0].isLetter()) uppercase().takeIf { it.length == 1 } ?: this else this

/**
 * What the space bar and backspace do when you slide a finger along them
 * (the keyboard service does the actual text work).
 */
class SlideActions(
    val startCursor: () -> Unit = {},
    val moveCursor: (Int) -> Unit = {},        // characters, negative = left
    val startWordDelete: () -> Unit = {},
    val selectWords: (Int) -> Unit = {},       // how many words are picked so far (0 = none)
    val finishWordDelete: (Int) -> Unit = {}   // finger lifted: delete that many words
)

@Composable
fun KeyboardLayout(
    malayalam: Boolean,               // yellow ola key = Malayalam mode
    arabic: Boolean = false,          // Arabic keyboard (beta): Arabic letters
    onToggleLanguage: () -> Unit,
    languages: List<Language> = listOf(Language.ENGLISH, Language.MALAYALAM), // on the leaf key
    onChooseLanguage: (Language) -> Unit = {},   // picked from the menu when holding the leaf
    suggestions: Suggestions?,        // what the strip shows for the word being typed
    onPick: (String) -> Unit,         // user tapped a word in the strip
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    session: Int,                     // changes every time the keyboard opens for a field
    startWithNumbers: Boolean,        // number / phone fields open on the ?123 page
    clips: List<Clip>,                // clipboard panel contents
    onOpenClipboard: () -> Unit,
    onPasteClip: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteClip: (String) -> Unit,
    onClearClips: () -> Unit,
    onOpenSettings: () -> Unit,       // gear in the strip
    onSwitchKeyboard: () -> Unit,     // hold space: Android's keyboard picker
    searchEmoji: (String) -> EmojiResults,
    theme: KeyboardTheme,
    photo: Bitmap?,                   // background for the "Your photo" theme
    photoDim: Float,                  // 0 = photo as-is, 1 = black
    feedback: KeyFeedback,
    clipboardOn: Boolean = true,      // "Clipboard history" setting
    stickers: List<File> = emptyList(),
    stickerStatus: String? = null,    // "This app doesn't take stickers", shown in the sticker panel
    onOpenStickers: () -> Unit = {},
    onSendSticker: (File) -> Unit = {},
    onMakeSticker: () -> Unit = {},
    onDeleteSticker: (File) -> Unit = {},
    justCopied: String? = null,        // copied a moment ago: shown as a chip, one tap pastes
    onPasteJustCopied: () -> Unit = {},
    onVoice: () -> Unit = {},          // mic in the strip: hand over to a voice typing app
    voiceNote: String? = null,         // "No voice typing app on this phone yet"
    onVoiceNote: () -> Unit = {},
    keyHeight: Float = 1f,             // "Keyboard height" setting, 1 = normal
    slideActions: SlideActions = SlideActions(),
    fontStyle: String? = null,         // font style in use (FancyText id), null = normal letters
    onFontStyle: (String?) -> Unit = {},
    emojiRow: Boolean = false,         // "Quick emoji row" setting
    recentEmoji: List<String> = emptyList(),
    onEmoji: (String) -> Unit = onText, // an emoji was picked: type it and remember it
    onEmojiRow: (Boolean) -> Unit = {}, // turn the quick emoji row on/off from the keyboard
    fontStylesOn: Boolean = true        // "Font styles" setting: show the styles button
) {
    CompositionLocalProvider(
        LocalKeyboardTheme provides theme,
        LocalKeyFeedback provides feedback,
        LocalKeyHeight provides keyHeight,
        // Keys stay where they are on phones set to Arabic (or any right-to-left language).
        LocalLayoutDirection provides LayoutDirection.Ltr
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (theme.usesPhoto && photo != null) {
                // The photo fills the keyboard, darkened so the letters stay readable.
                val image = remember(photo) { photo.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = photoDim)))
            }
            KeyboardContent(
                malayalam, arabic, onToggleLanguage, languages, onChooseLanguage, suggestions, onPick, onText, onBackspace, onEnter,
                session, startWithNumbers, clips, onOpenClipboard, onPasteClip, onTogglePin,
                onDeleteClip, onClearClips, onOpenSettings, onSwitchKeyboard, searchEmoji,
                transparent = theme.usesPhoto && photo != null,
                clipboardOn = clipboardOn,
                stickers = stickers,
                stickerStatus = stickerStatus,
                onOpenStickers = onOpenStickers,
                onSendSticker = onSendSticker,
                onMakeSticker = onMakeSticker,
                onDeleteSticker = onDeleteSticker,
                justCopied = justCopied,
                onPasteJustCopied = onPasteJustCopied,
                onVoice = onVoice,
                voiceNote = voiceNote,
                onVoiceNote = onVoiceNote,
                slideActions = slideActions,
                fontStyle = fontStyle,
                onFontStyle = onFontStyle,
                emojiRow = emojiRow,
                recentEmoji = recentEmoji,
                onEmoji = onEmoji,
                onEmojiRow = onEmojiRow,
                fontStylesOn = fontStylesOn
            )
        }
    }
}

@Composable
private fun KeyboardContent(
    malayalam: Boolean,
    arabic: Boolean,
    onToggleLanguage: () -> Unit,
    languages: List<Language>,
    onChooseLanguage: (Language) -> Unit,
    suggestions: Suggestions?,
    onPick: (String) -> Unit,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    session: Int,
    startWithNumbers: Boolean,
    clips: List<Clip>,
    onOpenClipboard: () -> Unit,
    onPasteClip: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteClip: (String) -> Unit,
    onClearClips: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    searchEmoji: (String) -> EmojiResults,
    transparent: Boolean,
    clipboardOn: Boolean = true,
    stickers: List<File> = emptyList(),
    stickerStatus: String? = null,
    onOpenStickers: () -> Unit = {},
    onSendSticker: (File) -> Unit = {},
    onMakeSticker: () -> Unit = {},
    onDeleteSticker: (File) -> Unit = {},
    justCopied: String? = null,        // copied a moment ago: shown as a chip, one tap pastes
    onPasteJustCopied: () -> Unit = {},
    onVoice: () -> Unit = {},          // mic in the strip: hand over to a voice typing app
    voiceNote: String? = null,         // "No voice typing app on this phone yet"
    onVoiceNote: () -> Unit = {},
    slideActions: SlideActions = SlideActions(),
    fontStyle: String? = null,
    onFontStyle: (String?) -> Unit = {},
    emojiRow: Boolean = false,
    recentEmoji: List<String> = emptyList(),
    onEmoji: (String) -> Unit = onText,
    onEmojiRow: (Boolean) -> Unit = {},
    fontStylesOn: Boolean = true
) {
    // Keyed on `session`, so every new text field starts fresh instead of
    // keeping the page (?123, emoji, shift) that was open last time.
    var shift by remember(session) { mutableStateOf(Shift.OFF) }
    var symbols by remember(session) { mutableStateOf(startWithNumbers) }
    var moreSymbols by remember(session) { mutableStateOf(false) }   // second symbols page
    var decor by remember(session) { mutableStateOf(false) }         // third page: ✿ decorations
    var emojiOpen by remember(session) { mutableStateOf(false) }
    var clipboardOpen by remember(session) { mutableStateOf(false) }
    var stickersOpen by remember(session) { mutableStateOf(false) }
    var stylesOpen by remember(session) { mutableStateOf(false) }   // font styles strip

    fun label(key: String) = if (!symbols && shift != Shift.OFF) key.uppercase() else key

    fun type(key: String) {
        onText(label(key))
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    val rows = when {
        !symbols && arabic -> arabicRows
        !symbols -> letterRows
        decor -> decorRows
        moreSymbols -> moreSymbolRows
        else -> symbolRows
    }

    // Hold a letter: accents and symbols (letters page only).
    fun alternates(key: String): List<String>? =
        if (decor) DecorAlternates[key] else if (symbols) null
        else if (arabic) ArabicAlternates[key]
        else Alternates[key]?.map { if (shift != Shift.OFF) it.shifted() else it }
    fun typeAlternate(text: String) {
        onText(text)
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    // Slide on space: move the cursor. Slide left from backspace: pick whole words, lift to delete.
    val cursorSteps = remember { intArrayOf(0) }
    val spaceSlide = remember(slideActions) {
        KeySlide(
            step = CursorStep,
            onStart = { cursorSteps[0] = 0; slideActions.startCursor() },
            onMove = { total -> slideActions.moveCursor(total - cursorSteps[0]); cursorSteps[0] = total },
            onEnd = { }
        )
    }
    val deleteSlide = remember(slideActions) {
        KeySlide(
            step = WordStep,
            onStart = slideActions.startWordDelete,
            onMove = { total -> slideActions.selectWords(maxOf(0, -total)) },
            onEnd = { total -> slideActions.finishWordDelete(maxOf(0, -total)) }
        )
    }
    val altPopup = remember { AltPopupState() }

    CompositionLocalProvider(
        LocalAltPopup provides altPopup,
        LocalPanelExtra provides if (emojiRow) EmojiRowHeight else 0.dp
    ) {
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                altPopup.origin = it.positionInRoot()
                altPopup.width = it.size.width.toFloat()
            }
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (transparent) Color.Transparent else KeyboardBg)
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        if (emojiOpen) {
            EmojiPanel(
                onEmoji = onEmoji,
                onBackspace = onBackspace,
                onSpace = { onText(" ") },
                onClose = { emojiOpen = false },
                searchEmoji = searchEmoji,
                emojiRow = emojiRow,
                onEmojiRow = onEmojiRow
            )
            return@Column
        }
        if (clipboardOpen) {
            ClipboardPanel(
                clips = clips,
                clipboardOn = clipboardOn,
                onPaste = { onPasteClip(it); clipboardOpen = false },
                onTogglePin = onTogglePin,
                onDelete = onDeleteClip,
                onClearAll = onClearClips,
                onBackspace = onBackspace,
                onSpace = { onText(" ") },
                onClose = { clipboardOpen = false }
            )
            return@Column
        }
        if (stickersOpen) {
            StickerPanel(
                stickers = stickers,
                status = stickerStatus,
                onSend = onSendSticker,
                onMake = onMakeSticker,
                onDelete = onDeleteSticker,
                onBackspace = onBackspace,
                onSpace = { onText(" ") },
                onClose = { stickersOpen = false }
            )
            return@Column
        }

        if (stylesOpen || fontStyle != null) {
            // Font styles: pick one, then type. ✕ goes back to normal letters.
            FontStyleStrip(
                selected = fontStyle,
                malayalam = malayalam,
                onPick = onFontStyle,
                onSwitchToEnglish = onToggleLanguage,
                onClose = { onFontStyle(null); stylesOpen = false }
            )
        } else if (suggestions == null || suggestions.typed.isEmpty()) {
            // Not typing a word: show the clipboard button.
            IdleStrip(
                onOpenSettings = onOpenSettings,
                onOpenStyles = if (fontStylesOn) ({ stylesOpen = true }) else null,
                onOpenClipboard = { onOpenClipboard(); clipboardOpen = true },
                onOpenStickers = { onOpenStickers(); stickersOpen = true },
                onOpenEmoji = { emojiOpen = true },
                justCopied = if (clipboardOn) justCopied else null,
                onPasteJustCopied = onPasteJustCopied,
                onVoice = onVoice,
                voiceNote = voiceNote,
                onVoiceNote = onVoiceNote
            )
        } else {
            SuggestionStrip(suggestions, onPick)
        }

        if (emojiRow) QuickEmojiRow(recentEmoji, onEmoji, onHide = { onEmojiRow(false) })

        // Row 1: letters, hold for numbers
        Row(Modifier.fillMaxWidth()) {
            rows[0].forEachIndexed { i, k ->
                val hint = if (symbols) null else numberHints.getOrNull(i)
                Key(
                    label = label(k),
                    modifier = Modifier.weight(1f),
                    hint = hint,
                    alternates = alternates(k),
                    onAlternate = ::typeAlternate
                ) { type(k) }
            }
        }

        // Row 2: indented by half a key, like a real QWERTY
        Row(Modifier.fillMaxWidth()) {
            val indent = !symbols && !arabic // Arabic's middle row is full width
            if (indent) Spacer(Modifier.weight(0.5f))
            rows[1].forEach { k ->
                Key(label(k), Modifier.weight(1f), alternates = alternates(k), onAlternate = ::typeAlternate) { type(k) }
            }
            if (indent) Spacer(Modifier.weight(0.5f))
        }

        // Row 3: shift + letters + backspace
        Row(Modifier.fillMaxWidth()) {
            if (!symbols && arabic) {
                // Arabic has no capitals: this key types fatha, hold it for the other harakat.
                Key(
                    label = "ـَ",
                    modifier = Modifier.weight(1.2f),
                    color = SpecialKeyColor,
                    fontSize = 18,
                    alternates = Harakat,
                    onAlternate = onText
                ) { onText(Harakat[0]) }
            } else if (!symbols) {
                Key(
                    label = "",
                    modifier = Modifier.weight(1.5f),
                    color = if (shift == Shift.OFF) SpecialKeyColor else Accent.copy(alpha = 0.25f),
                    icon = { ShiftIcon(shift, if (shift == Shift.OFF) KeyText else Accent) }
                ) {
                    shift = when (shift) {
                        Shift.OFF -> Shift.ONCE
                        Shift.ONCE -> Shift.LOCKED
                        Shift.LOCKED -> Shift.OFF
                    }
                }
            } else {
                // Symbols: switch between the two symbol pages
                Key(
                    // ?123 page → =\< page → ✿ decorations → back to ?123
                    label = when { decor -> "?123"; moreSymbols -> "✿"; else -> "=\\<" },
                    modifier = Modifier.weight(1.5f),
                    color = SpecialKeyColor,
                    fontSize = 16
                ) {
                    when {
                        decor -> { decor = false; moreSymbols = false }
                        moreSymbols -> { moreSymbols = false; decor = true }
                        else -> moreSymbols = true
                    }
                }
            }
            rows[2].forEach { k ->
                Key(label(k), Modifier.weight(1f), alternates = alternates(k), onAlternate = ::typeAlternate) { type(k) }
            }
            Key(
                label = "",
                modifier = Modifier.weight(if (!symbols && arabic) 1.2f else 1.5f),
                color = LocalKeyboardTheme.current.deleteKey ?: SpecialKeyColor,
                repeat = true,
                slide = deleteSlide,
                icon = { BackspaceIcon(LocalKeyboardTheme.current.deleteInk ?: KeyText) }
            ) { onBackspace() }
        }

        // Row 4: ?123, comma, Ezhuthola key, space, period, enter
        Row(Modifier.fillMaxWidth()) {
            Key(
                label = if (symbols) "ABC" else "?123",
                modifier = Modifier.weight(1.5f),
                color = SpecialKeyColor,
                fontSize = 16
            ) { symbols = !symbols; moreSymbols = false; decor = false }
            val comma = if (arabic) "،" else ","      // Arabic comma in Arabic mode
            Key(
                label = comma,
                modifier = Modifier.weight(1f),
                color = SpecialKeyColor,
                hint = "@",
                onLongPress = { onText("@") }        // hold comma → @ (emoji has its own strip button)
            ) { onText(comma) }

            // The Ezhuthola key: switches Malayalam <-> English.
            // Malayalam mode = solid yellow key. English mode = dark key, yellow leaf.
            Key(
                label = "",
                modifier = Modifier.weight(1.2f),
                // Arabic: a light accent tint, so it can't be mistaken for Malayalam's solid yellow.
                color = when {
                    malayalam -> Accent
                    arabic -> Accent.copy(alpha = 0.25f)
                    else -> SpecialKeyColor
                },
                icon = { OlaIcon(onYellow = malayalam) },
                // Hold the leaf: EN · മ · ع, slide to one and let go to go straight there.
                alternates = if (languages.size > 2) languages.map { it.short } else null,
                onAlternate = { short -> languages.firstOrNull { it.short == short }?.let(onChooseLanguage) }
            ) { onToggleLanguage() }

            Key(
                label = when {
                    malayalam -> "മലയാളം"
                    arabic -> "العربية"
                    else -> "English"
                },
                modifier = Modifier.weight(4f),
                textColor = HintText,
                fontSize = 16,
                onLongPress = onSwitchKeyboard,    // hold space → choose another keyboard
                slide = spaceSlide                 // slide on space → move the cursor
            ) { onText(" ") }
            Key(
                ".",
                Modifier.weight(1f),
                color = SpecialKeyColor,
                alternates = if (arabic) ArabicPeriodAlternates else PeriodAlternates,
                onAlternate = onText
            ) { onText(".") }
            Key(
                label = "",
                modifier = Modifier.weight(1.5f),
                color = SpecialKeyColor,
                icon = { EnterIcon(KeyText) }
            ) { onEnter() }
        }
    }
    altPopup.shown?.let { AltPopupView(it) }
    }
    }
}

/**
 * One key. The whole cell is touchable, including the gap around the drawn key,
 * so there are no "dead zones" between keys. That's a big part of what makes typing feel easy.
 *
 * [alternates]: hold the key and a row of accents/symbols opens above it; slide to one and let go.
 * [slide]: sliding a finger along the key does something (space moves the cursor,
 * backspace picks words to delete) instead of a tap.
 */
@Composable
private fun Key(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = KeyColor,
    textColor: Color = KeyText,
    fontSize: Int = KeyFontSize,
    hint: String? = null,
    repeat: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    alternates: List<String>? = null,
    onAlternate: (String) -> Unit = {},
    slide: KeySlide? = null,
    icon: (@Composable () -> Unit)? = null,
    hintIcon: (@Composable () -> Unit)? = null,
    onPress: () -> Unit
) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    val popup = LocalAltPopup.current
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    val alts by rememberUpdatedState(alternates)
    val pickAlternate by rememberUpdatedState(onAlternate)
    val slideNow by rememberUpdatedState(slide)
    val bounds = remember { arrayOf(Rect.Zero) }   // where this key is, for the accents row

    fun tick() {
        if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Box(
        modifier = modifier
            .height(RowHeight)
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .pointerInput(repeat, feedback) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    view.keyTap(feedback)
                    try {
                        val s = slideNow
                        val options = alts
                        when {
                            s != null -> {
                                // Space and backspace: a tap, a hold, or a slide along the key.
                                val holdMs = when {
                                    repeat -> RepeatStartMs
                                    longPress != null -> LongPressMs
                                    else -> null
                                }
                                when (trackSlide(down, holdMs, SlideSlop.toPx(), s.step.toPx(), s, ::tick)) {
                                    SlideEnd.TAP -> press()
                                    SlideEnd.HELD -> if (repeat) {
                                        // Backspace held still: keep deleting while held.
                                        press()
                                        var done = withTimeoutOrNull(RepeatEveryMs) { waitForUpOrCancellation(); true }
                                        while (done == null) {
                                            press()
                                            done = withTimeoutOrNull(RepeatEveryMs) { waitForUpOrCancellation(); true }
                                        }
                                    } else {
                                        longPress?.let {
                                            if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            it()
                                        }
                                        waitForUpOrCancellation()
                                    }
                                    SlideEnd.SLID, SlideEnd.CANCELLED -> Unit
                                }
                            }
                            repeat -> {
                                // Backspace: delete now, then keep deleting while held.
                                press()
                                var done = withTimeoutOrNull(RepeatStartMs) { waitForUpOrCancellation(); true }
                                while (done == null) {
                                    press()
                                    done = withTimeoutOrNull(RepeatEveryMs) { waitForUpOrCancellation(); true }
                                }
                            }
                            options != null && options.isNotEmpty() -> {
                                // true = released, false = slid off, null = still holding
                                val released = withTimeoutOrNull(LongPressMs) {
                                    waitForUpOrCancellation() != null
                                }
                                when (released) {
                                    true -> press()
                                    false -> Unit
                                    null -> {
                                        if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        chooseAlternate(down, options, bounds[0], popup, ::tick)?.let(pickAlternate)
                                    }
                                }
                            }
                            else -> {
                                val onLong = longPress
                                if (onLong == null) {
                                    if (waitForUpOrCancellation() != null) press()
                                } else {
                                    // true = released, false = slid off, null = still holding
                                    val released = withTimeoutOrNull(LongPressMs) {
                                        waitForUpOrCancellation() != null
                                    }
                                    when (released) {
                                        true -> press()
                                        false -> Unit
                                        null -> {
                                            if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            onLong()
                                            waitForUpOrCancellation()
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        pressed = false
                        popup.shown = null
                    }
                }
            }
            .padding(horizontal = KeyGapH, vertical = KeyGapV),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(if (pressed) lerp(color, Color.White, 0.18f) else color),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                icon()
            } else {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            if (hintIcon != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 7.dp)
                ) { hintIcon() }
            }
            if (hint != null) {
                Text(
                    text = hint,
                    color = HintText,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sliding on space / backspace
// ---------------------------------------------------------------------------

/** Sliding a finger along a key. [onMove] gets the steps from the start (negative = left). */
private class KeySlide(
    val step: Dp,
    val onStart: () -> Unit,
    val onMove: (total: Int) -> Unit,
    val onEnd: (total: Int) -> Unit
)

private enum class SlideEnd { TAP, HELD, SLID, CANCELLED }

/**
 * Follows one finger on a key. Until it moves more than [slopPx] it's a tap (lifted) or a hold
 * (still down after [holdMs]). Once it moves, it's a slide: every [stepPx] left or right is one step.
 */
private suspend fun AwaitPointerEventScope.trackSlide(
    down: PointerInputChange,
    holdMs: Long?,
    slopPx: Float,
    stepPx: Float,
    slide: KeySlide,
    tick: () -> Unit
): SlideEnd {
    val deadline = SystemClock.uptimeMillis() + (holdMs ?: 0L)
    var sliding = false
    var anchor = down.position.x
    var total = 0
    while (true) {
        val event = if (!sliding && holdMs != null) {
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) return SlideEnd.HELD
            withTimeoutOrNull(left) { awaitPointerEvent() } ?: return SlideEnd.HELD
        } else {
            awaitPointerEvent()
        }
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) {
            if (sliding) slide.onEnd(0)
            return SlideEnd.CANCELLED
        }
        if (!change.pressed) {
            if (!sliding) return SlideEnd.TAP
            slide.onEnd(total)
            return SlideEnd.SLID
        }
        val x = change.position.x
        if (!sliding && abs(x - anchor) > slopPx) {
            sliding = true
            // The first step counts as soon as the finger starts sliding.
            anchor = x - sign(x - anchor) * stepPx
            slide.onStart()
        }
        if (sliding) {
            change.consume()
            val now = ((x - anchor) / stepPx).toInt()
            if (now != total) {
                total = now
                tick()
                slide.onMove(total)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Accents row (hold a letter)
// ---------------------------------------------------------------------------

/** Where the accents row is drawn. It's drawn over the keyboard itself, not in a separate window. */
private class AltPopupState {
    var shown by mutableStateOf<AltPopup?>(null)
    var origin = Offset.Zero    // top-left of the keyboard area
    var width = 0f
}

/** [reversed]: near the right edge the row opens to the left, so the first choice stays above the key. */
private data class AltPopup(
    val options: List<String>,
    val left: Float,
    val bottom: Float,
    val cellWidth: Float,
    val selected: Int,
    val reversed: Boolean
)

private val LocalAltPopup = staticCompositionLocalOf { AltPopupState() }

/**
 * Opens the accents row above the key and follows the finger along it.
 * Returns what was chosen when the finger lifts (the first choice if it didn't move).
 */
private suspend fun AwaitPointerEventScope.chooseAlternate(
    down: PointerInputChange,
    options: List<String>,
    key: Rect,
    popup: AltPopupState,
    tick: () -> Unit
): String? {
    val cell = key.width.coerceAtLeast(1f)
    val width = cell * options.size
    val keyLeft = key.left - popup.origin.x
    val reversed = keyLeft + width > popup.width
    val left = if (reversed) (keyLeft + cell - width).coerceAtLeast(0f) else keyLeft
    val bottom = key.top - popup.origin.y + KeyGapV.toPx()

    fun optionAt(xInKey: Float): Int {
        val slot = ((keyLeft + xInKey - left) / cell).toInt().coerceIn(0, options.size - 1)
        return if (reversed) options.size - 1 - slot else slot
    }

    popup.shown = AltPopup(options, left, bottom, cell, 0, reversed)
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return null
            change.consume()
            val shown = popup.shown ?: return null
            if (!change.pressed) return shown.options[shown.selected]
            val i = optionAt(change.position.x)
            if (i != shown.selected) {
                popup.shown = shown.copy(selected = i)
                tick()
            }
        }
    } finally {
        popup.shown = null
    }
}

@Composable
private fun AltPopupView(p: AltPopup) {
    val density = LocalDensity.current
    val theme = LocalKeyboardTheme.current
    val heightPx = with(density) { AltPopupHeight.toPx() }
    val cellDp = with(density) { p.cellWidth.toDp() }
    // Solid background even on the photo theme, so the row is easy to read.
    val background = if (theme.usesPhoto) Color(0xFF1E1E1E) else theme.background
    val order = if (p.reversed) p.options.indices.reversed() else p.options.indices
    Row(
        modifier = Modifier
            .offset { IntOffset(p.left.roundToInt(), (p.bottom - heightPx).roundToInt().coerceAtLeast(0)) }
            .height(AltPopupHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
    ) {
        order.forEach { i ->
            val selected = i == p.selected
            Box(
                modifier = Modifier
                    .width(cellDp)
                    .height(AltPopupHeight)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Accent else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = p.options[i],
                    color = if (selected) theme.inkOnAccent else KeyText,
                    fontSize = 22.sp
                )
            }
        }
    }
}

/**
 * Mini Ezhuthola logo, drawn in code with the same shapes as the app icon:
 * grey cover board, fanned palm leaves, front leaf with the string hole, red string.
 *
 * English mode (dark key): real logo colours.
 * Malayalam mode (yellow key): leaves in dark ink so they stand out on yellow.
 */
@Composable
internal fun OlaIcon(
    onYellow: Boolean,
    modifier: Modifier = Modifier.size(width = 36.dp, height = 31.dp)
) {
    val cover = Color(0xFF54595F)
    // On the accent-coloured key the leaves are drawn in the theme's "ink" colour.
    val ink = LocalKeyboardTheme.current.inkOnAccent
    val leaves = if (onYellow) {
        listOf(0.37f, 0.5f, 0.65f, 0.8f).map { ink.copy(alpha = it) }
    } else {
        listOf(0xFF9C6B26, 0xFFB07A2C, 0xFFC48A33, 0xFFD6993A).map { Color(it) }
    }
    val front = if (onYellow) ink else Color(0xFFF0A53A)
    val hole = if (onYellow) Accent else LocalKeyboardTheme.current.specialKey
    val string = if (onYellow) Color(0xFFB0303D) else Color(0xFFC83E4D)

    Canvas(modifier) {
        // The logo's shapes live in a 206 × 200 box (same coordinates as the icon artwork).
        val minX = 90f; val minY = 58f; val boxW = 206f; val boxH = 200f
        val s = min(size.width / boxW, size.height / boxH)
        val dx = (size.width - boxW * s) / 2 - minX * s
        val dy = (size.height - boxH * s) / 2 - minY * s
        val pivot = Offset(118f, 210f)

        withTransform({
            translate(dx, dy)
            scale(s, s, pivot = Offset.Zero)
        }) {
            // Cover board (back)
            rotate(-68f, pivot) {
                drawRoundRect(cover, Offset(112f, 197f), Size(160f, 26f), CornerRadius(4f, 4f))
                drawCircle(hole, 5f, Offset(246f, 210f))
            }
            // Fanned leaves, darkest at the back
            listOf(-52f, -38f, -24f, -10f).forEachIndexed { i, angle ->
                rotate(angle, pivot) {
                    drawRoundRect(leaves[i], Offset(108f, 199f), Size(166f, 22f), CornerRadius(11f, 11f))
                }
            }
            // Front leaf
            drawRoundRect(front, Offset(92f, 194f), Size(198f, 44f), CornerRadius(16f, 16f))
            // String hole + red string loop
            drawCircle(hole, 6f, Offset(112f, 216f))
            drawPath(
                Path().apply {
                    moveTo(112f, 216f)
                    cubicTo(98f, 236f, 94f, 250f, 106f, 252f)
                    cubicTo(118f, 253f, 118f, 238f, 112f, 221f)
                },
                string,
                style = Stroke(width = 6f, cap = StrokeCap.Round)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Emoji panel
// ---------------------------------------------------------------------------

/** What emoji search found, and how the Manglish was read ("chiri" → ചിരി), if it was. */
data class EmojiResults(val emojis: List<String>, val reading: String? = null)

/**
 * Full emoji panel: Google's open-source EmojiPickerView (categories, recents, skin tones),
 * drawn with the phone's own emoji font. Offline, no stickers/GIFs.
 *
 * A search bar sits on top. Tapping it swaps the emoji grid for letter keys: what you type
 * there goes into the search, not into the app. Search understands English ("love"),
 * Manglish ("chiri") and Malayalam keywords, from Unicode CLDR.
 */
@Composable
private fun EmojiPanel(
    onEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClose: () -> Unit,
    searchEmoji: (String) -> EmojiResults,
    emojiRow: Boolean = false,
    onEmojiRow: (Boolean) -> Unit = {},
) {
    var searching by remember { mutableStateOf(false) }
    if (searching) {
        EmojiSearchPanel(onEmoji, searchEmoji, onBack = { searching = false })
        return
    }

    val theme = LocalKeyboardTheme.current
    val light = theme.isLight
    val emojiTheme = if (light) android.R.style.Theme_DeviceDefault_Light else android.R.style.Theme_DeviceDefault
    val emojiBg = if (theme.usesPhoto) android.graphics.Color.TRANSPARENT else theme.background.toArgb()

    SearchBar(query = "", reading = null) { searching = true }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight * 3 + LocalPanelExtra.current), // search bar + grid = same height as the strip + letter keys
        factory = { context ->
            // Dark theme wrapper so the picker matches the keyboard.
            EmojiPickerView(ContextThemeWrapper(context, emojiTheme)).apply {
                emojiGridColumns = 8
                setBackgroundColor(emojiBg)
                setOnEmojiPickedListener { item -> onEmoji(item.emoji) }
            }
        }
    )
    Row(Modifier.fillMaxWidth()) {
        Key(
            label = "ABC",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            textColor = Accent,
            fontSize = 16
        ) { onClose() }
        Key("space", Modifier.weight(3.5f), textColor = HintText, fontSize = 16) { onSpace() }
        // Recent row switch: shows/hides the quick emoji row above the letter keys.
        Key(
            label = if (emojiRow) "Recent ✓" else "Recent",
            modifier = Modifier.weight(1.5f),
            color = if (emojiRow) Accent.copy(alpha = 0.22f) else SpecialKeyColor,
            textColor = if (emojiRow) Accent else KeyText,
            fontSize = 13
        ) { onEmojiRow(!emojiRow) }
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            repeat = true,
            icon = { BackspaceIcon(KeyText) }
        ) { onBackspace() }
    }
}

/**
 * Searching: results on top, letter keys below. Same height as the normal keyboard.
 * Tapping a result types it into the app and keeps the search open, so you can add more.
 */
@Composable
private fun EmojiSearchPanel(
    onEmoji: (String) -> Unit,
    searchEmoji: (String) -> EmojiResults,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Spacer(Modifier.height(LocalPanelExtra.current)) // same height as the keyboard with the emoji row
    val results = remember(query) { if (query.isBlank()) EmojiResults(emptyList()) else searchEmoji(query) }

    // Strip: [search box] [results →]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.widthIn(max = 150.dp)) {
            SearchBar(query = query, reading = results.reading, fill = false) { }
        }
        Spacer(Modifier.width(6.dp))
        when {
            query.isBlank() -> Text(
                text = "love · chiri · kollam",
                color = HintText,
                fontSize = 14.sp,
                maxLines = 1
            )
            results.emojis.isEmpty() -> Text(
                text = "No emoji found",
                color = HintText,
                fontSize = 14.sp,
                maxLines = 1
            )
            else -> LazyRow(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(results.emojis) { emoji ->
                    EmojiResult(emoji) { onEmoji(emoji) }
                }
            }
        }
    }

    fun type(letter: String) {
        if (query.length < 30) query += letter
    }

    Row(Modifier.fillMaxWidth()) {
        letterRows[0].forEach { k -> Key(k, Modifier.weight(1f)) { type(k) } }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(0.5f))
        letterRows[1].forEach { k -> Key(k, Modifier.weight(1f)) { type(k) } }
        Spacer(Modifier.weight(0.5f))
    }
    Row(Modifier.fillMaxWidth()) {
        // Clear: empty the search box in one tap.
        Key(
            label = "Clear",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            fontSize = 14
        ) { query = "" }
        letterRows[2].forEach { k -> Key(k, Modifier.weight(1f)) { type(k) } }
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            repeat = true,
            icon = { BackspaceIcon(KeyText) }
        ) { query = query.dropLast(1) }
    }
    Row(Modifier.fillMaxWidth()) {
        // Back to the emoji grid
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            icon = { SmileIcon(Accent, Modifier.size(24.dp)) }
        ) { onBack() }
        Key("space", Modifier.weight(5f), textColor = HintText, fontSize = 16) {
            if (query.isNotEmpty() && !query.endsWith(" ")) query += " "
        }
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            icon = { SearchIcon(KeyText, Modifier.size(24.dp)) }
        ) {
            // Search key: type the best result, like pressing enter in a search box.
            results.emojis.firstOrNull()?.let(onEmoji)
        }
    }
}

/**
 * Rounded search box. Empty: "Search emoji". Typing: the query, with the Malayalam
 * the Manglish was read as underneath ("chiri" / ചിരി) so you can see it was understood.
 */
@Composable
private fun SearchBar(query: String, reading: String?, fill: Boolean = true, onClick: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .then(if (fill) Modifier.fillMaxWidth().padding(horizontal = 6.dp) else Modifier)
            .height(StripHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CardColor)
                .border(1.dp, if (query.isEmpty() && fill) KeyColor else Accent, RoundedCornerShape(20.dp))
                .clickable {
                    view.keyTap(feedback)
                    onClick()
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchIcon(if (query.isEmpty()) HintText else Accent, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            if (query.isEmpty()) {
                Text("Search emoji", color = HintText, fontSize = 15.sp, maxLines = 1)
            } else {
                Column {
                    Text(
                        text = query + "|",
                        color = KeyText,
                        fontSize = 15.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (reading != null && reading != query) {
                        Text(
                            text = reading,
                            color = Accent,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiResult(emoji: String, onClick: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                view.keyTap(feedback)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 28.sp, maxLines = 1)
    }
}

/** Magnifying glass. */
@Composable
private fun SearchIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawCircle(color, 6.5f * u, Offset(10.5f * u, 10.5f * u), style = Stroke(2f * u))
        drawLine(color, Offset(15.5f * u, 15.5f * u), Offset(20.5f * u, 20.5f * u), 2.2f * u, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------
// Key icons (drawn in code on a 24×24 grid, same shapes as the mockup)
// ---------------------------------------------------------------------------

@Composable
private fun ShiftIcon(state: Shift, color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.width / 24f
        val arrow = Path().apply {
            moveTo(12 * u, 3.5f * u)
            lineTo(19.5f * u, 11.5f * u)
            lineTo(15 * u, 11.5f * u)
            lineTo(15 * u, 17.5f * u)
            lineTo(9 * u, 17.5f * u)
            lineTo(9 * u, 11.5f * u)
            lineTo(4.5f * u, 11.5f * u)
            close()
        }
        if (state == Shift.OFF) {
            drawPath(arrow, color, style = Stroke(width = 1.8f * u, join = StrokeJoin.Round))
        } else {
            drawPath(arrow, color) // filled = next letter capital
        }
        if (state == Shift.LOCKED) {
            // underline = caps lock
            drawLine(color, Offset(8 * u, 21 * u), Offset(16 * u, 21 * u), 2f * u, StrokeCap.Round)
        }
    }
}

@Composable
private fun BackspaceIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, join = StrokeJoin.Round, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(9 * u, 5 * u)
            lineTo(20 * u, 5 * u)
            lineTo(20 * u, 19 * u)
            lineTo(9 * u, 19 * u)
            lineTo(3 * u, 12 * u)
            close()
        }
        drawPath(body, color, style = stroke)
        drawLine(color, Offset(12 * u, 9.5f * u), Offset(17 * u, 14.5f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(17 * u, 9.5f * u), Offset(12 * u, 14.5f * u), 1.8f * u, StrokeCap.Round)
    }
}

@Composable
private fun EnterIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u, join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(19 * u, 5 * u)
            lineTo(19 * u, 12 * u)
            lineTo(6 * u, 12 * u)
        }, color, style = stroke)
        drawPath(Path().apply {
            moveTo(10 * u, 8 * u)
            lineTo(6 * u, 12 * u)
            lineTo(10 * u, 16 * u)
        }, color, style = stroke)
    }
}

/** Small outline smiley shown on the comma key: "hold for emoji". Same grey as the number hints. */
@Composable
private fun SmileHint(color: Color) = SmileIcon(color, Modifier.size(17.dp))

/** Emoji: an angular face with square eyes and a yellow smile (monochrome unless [accent] is given). */
@Composable
private fun SmileIcon(color: Color, modifier: Modifier, accent: Color = color) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(8.14f * u, 2.80f * u); lineTo(15.86f * u, 2.80f * u); lineTo(21.20f * u, 8.14f * u); lineTo(21.20f * u, 15.86f * u); lineTo(15.86f * u, 21.20f * u); lineTo(8.14f * u, 21.20f * u); lineTo(2.80f * u, 15.86f * u); lineTo(2.80f * u, 8.14f * u); close() }, color, style = Stroke(1.60f * u, join = StrokeJoin.Miter, cap = StrokeCap.Butt))
        drawPath(Path().apply { moveTo(8.60f * u, 8.80f * u); lineTo(10.40f * u, 8.80f * u); lineTo(10.40f * u, 10.80f * u); lineTo(8.60f * u, 10.80f * u); close() }, color)
        drawPath(Path().apply { moveTo(13.60f * u, 8.80f * u); lineTo(15.40f * u, 8.80f * u); lineTo(15.40f * u, 10.80f * u); lineTo(13.60f * u, 10.80f * u); close() }, color)
        drawPath(Path().apply { moveTo(7.40f * u, 13.20f * u); lineTo(16.60f * u, 13.20f * u); lineTo(14.20f * u, 16.60f * u); lineTo(9.80f * u, 16.60f * u); close() }, accent)
    }
}

// ---------------------------------------------------------------------------
// Suggestion strip
// ---------------------------------------------------------------------------

private val StripHeight = 52.dp

/**
 * Malayalam: [typed Manglish]  [best Malayalam]  [other options…]
 * English:   [typed word]  [completions…]
 * The yellow chip is what space will type. In Malayalam mode the Manglish chip types
 * the English letters instead, so English words work without switching mode.
 */
@Composable
private fun SuggestionStrip(suggestions: Suggestions?, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (suggestions == null || suggestions.typed.isEmpty()) return@Row

        // Yellow chip = what space will type (best Malayalam word, or the English as typed).
        val typedIsBest = suggestions.typed == suggestions.best
        Chip(
            text = suggestions.typed,
            textColor = if (typedIsBest) Accent else HintText,
            background = if (typedIsBest) Accent.copy(alpha = 0.18f) else Color.Transparent,
            fontSize = if (typedIsBest) 20 else 16,
            bold = typedIsBest
        ) { onPick(suggestions.typed) }
        suggestions.words.forEach { word ->
            val isBest = word == suggestions.best
            Chip(
                text = word,
                textColor = if (isBest) Accent else KeyText,
                background = if (isBest) Accent.copy(alpha = 0.18f) else Color.Transparent,
                bold = isBest
            ) { onPick(word) }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    textColor: Color,
    background: Color = Color.Transparent,
    fontSize: Int = 20,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable {
                view.keyTap(feedback)
                onClick()
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

/** Strip when no word is being typed: settings and emoji on the left, mic, stickers and clipboard on the right. */
@Composable
private fun IdleStrip(
    onOpenSettings: () -> Unit,
    onOpenClipboard: () -> Unit,
    onOpenStickers: () -> Unit,
    onOpenEmoji: () -> Unit,
    onOpenStyles: (() -> Unit)? = null,
    justCopied: String? = null,
    onPasteJustCopied: () -> Unit = {},
    onVoice: () -> Unit = {},
    voiceNote: String? = null,
    onVoiceNote: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StripButton(onOpenSettings) { SettingsIcon(HintText, Modifier.size(24.dp)) }
        StripButton(onOpenEmoji) { SmileIcon(HintText, Modifier.size(24.dp), accent = Accent) }
        onOpenStyles?.let { StripButton(it) { StylesIcon() } }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            when {
                voiceNote != null -> VoiceNoteChip(voiceNote, onVoiceNote)
                justCopied != null -> JustCopiedChip(justCopied, onPasteJustCopied)
            }
        }
        StripButton(onVoice) { MicIcon(HintText, Modifier.size(24.dp)) }
        StripButton(onOpenStickers) { StickerIcon(HintText, Modifier.size(24.dp)) }
        StripButton(onOpenClipboard) { ClipboardIcon(HintText, Modifier.size(24.dp)) }
    }
}

/** What you just copied, as a chip in the strip: tap to paste it. Like Gboard and FUTO. */
@Composable
private fun JustCopiedChip(text: String, onPaste: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Row(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(CardColor)
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(19.dp))
            .clickable {
                view.keyTap(feedback)
                onPaste()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClipboardIcon(HintText, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.replace('\n', ' '),
            color = KeyText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A short note from the mic, in the middle of the strip. */
@Composable
private fun VoiceNoteChip(text: String, onTap: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(CardColor)
            .clickable {
                view.keyTap(feedback)
                onTap()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = KeyText,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Mic: angular like the clipboard, with a yellow level inside. */
@Composable
private fun MicIcon(color: Color, modifier: Modifier, accent: Color = Accent) {
    Canvas(modifier) {
        val u = size.width / 24f
        val line = Stroke(width = 1.42f * u, join = StrokeJoin.Miter, cap = StrokeCap.Butt)
        drawPath(Path().apply {
            moveTo(10.60f * u, 2.60f * u); lineTo(13.40f * u, 2.60f * u); lineTo(15.40f * u, 4.60f * u); lineTo(15.40f * u, 12.20f * u)
            lineTo(13.40f * u, 14.20f * u); lineTo(10.60f * u, 14.20f * u); lineTo(8.60f * u, 12.20f * u); lineTo(8.60f * u, 4.60f * u); close()
        }, color, style = line)
        drawPath(Path().apply {
            moveTo(5.40f * u, 10.20f * u); lineTo(5.40f * u, 13.20f * u); lineTo(9.20f * u, 17.60f * u)
            lineTo(14.80f * u, 17.60f * u); lineTo(18.60f * u, 13.20f * u); lineTo(18.60f * u, 10.20f * u)
        }, color, style = line)
        drawLine(color, Offset(12.00f * u, 17.60f * u), Offset(12.00f * u, 21.20f * u), 1.42f * u)
        drawLine(color, Offset(8.60f * u, 21.20f * u), Offset(15.40f * u, 21.20f * u), 1.42f * u)
        drawPath(Path().apply {
            moveTo(10.30f * u, 9.70f * u); lineTo(13.70f * u, 8.50f * u); lineTo(13.70f * u, 11.60f * u)
            lineTo(12.70f * u, 12.60f * u); lineTo(11.30f * u, 12.60f * u); lineTo(10.30f * u, 11.60f * u); close()
        }, accent)
    }
}

@Composable
private fun StripButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                view.keyTap(feedback)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** Settings: a gear with the Numio N. inside, from RR's design. */
@Composable
private fun SettingsIcon(color: Color, modifier: Modifier, accent: Color = Accent) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(10.00f * u, 1.00f * u); lineTo(14.00f * u, 1.00f * u); lineTo(15.10f * u, 4.00f * u); lineTo(8.90f * u, 4.00f * u); close() }, color)
        drawPath(Path().apply { moveTo(18.36f * u, 2.81f * u); lineTo(21.19f * u, 5.64f * u); lineTo(19.85f * u, 8.54f * u); lineTo(15.46f * u, 4.15f * u); close() }, color)
        drawPath(Path().apply { moveTo(23.00f * u, 10.00f * u); lineTo(23.00f * u, 14.00f * u); lineTo(20.00f * u, 15.10f * u); lineTo(20.00f * u, 8.90f * u); close() }, color)
        drawPath(Path().apply { moveTo(21.19f * u, 18.36f * u); lineTo(18.36f * u, 21.19f * u); lineTo(15.46f * u, 19.85f * u); lineTo(19.85f * u, 15.46f * u); close() }, color)
        drawPath(Path().apply { moveTo(14.00f * u, 23.00f * u); lineTo(10.00f * u, 23.00f * u); lineTo(8.90f * u, 20.00f * u); lineTo(15.10f * u, 20.00f * u); close() }, color)
        drawPath(Path().apply { moveTo(5.64f * u, 21.19f * u); lineTo(2.81f * u, 18.36f * u); lineTo(4.15f * u, 15.46f * u); lineTo(8.54f * u, 19.85f * u); close() }, color)
        drawPath(Path().apply { moveTo(1.00f * u, 14.00f * u); lineTo(1.00f * u, 10.00f * u); lineTo(4.00f * u, 8.90f * u); lineTo(4.00f * u, 15.10f * u); close() }, color)
        drawPath(Path().apply { moveTo(2.81f * u, 5.64f * u); lineTo(5.64f * u, 2.81f * u); lineTo(8.54f * u, 4.15f * u); lineTo(4.15f * u, 8.54f * u); close() }, color)
        drawCircle(color, 8.35f * u, Offset(12.00f * u, 12.00f * u), style = Stroke(2.60f * u))
        drawCircle(accent, 6.50f * u, Offset(12.00f * u, 12.00f * u), style = Stroke(0.60f * u))
        drawPath(Path().apply { moveTo(8.45f * u, 8.64f * u); lineTo(11.42f * u, 10.98f * u); lineTo(10.07f * u, 12.03f * u); lineTo(10.07f * u, 15.46f * u); lineTo(8.45f * u, 15.46f * u); close() }, accent)
        drawPath(Path().apply { moveTo(12.66f * u, 8.84f * u); lineTo(14.37f * u, 8.84f * u); lineTo(14.37f * u, 15.66f * u); lineTo(11.20f * u, 13.10f * u); lineTo(12.66f * u, 11.81f * u); close() }, accent)
        drawCircle(color, 0.82f * u, Offset(15.88f * u, 13.46f * u))
    }
}

/**
 * Clipboard panel: opens in place of the keys, like the emoji panel.
 * Tap a clip to paste it. Hold a clip to pin or delete it. Pinned clips stay until removed;
 * only the last 20 others are kept (a new copy drops the oldest). Passwords and OTPs are never saved.
 */
@Composable
private fun ClipboardPanel(
    clips: List<Clip>,
    clipboardOn: Boolean,
    onPaste: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClose: () -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val selectedClip = clips.firstOrNull { it.text == selected }

    Column(
        Modifier
            .fillMaxWidth()
            .height(RowHeight * 3 + StripHeight + LocalPanelExtra.current) // same height as the letter keyboard + strip
    ) {
        // Header: title on the left, actions on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(StripHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClipboardIcon(Accent, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Clipboard", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (selectedClip != null) {
                HeaderButton(if (selectedClip.pinned) "Unpin" else "Pin") {
                    onTogglePin(selectedClip.text); selected = null
                }
                HeaderButton("Delete") { onDelete(selectedClip.text); selected = null }
                HeaderButton("Cancel") { selected = null }
            } else if (clips.any { !it.pinned }) {
                HeaderButton("Clear all") { onClearAll() }
            }
        }

        if (clips.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    // With history off nothing is saved, so say so instead of looking broken.
                    text = if (clipboardOn) "Text you copy shows up here.\nPasswords and OTPs are never saved."
                           else "Clipboard history is off.\nTurn it on in settings (the button at top left).",
                    color = HintText,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(clips, key = { it.text }) { clip ->
                    ClipCard(
                        clip = clip,
                        isSelected = clip.text == selected,
                        onTap = { if (selected != null) selected = null else onPaste(clip.text) },
                        onHold = { selected = clip.text }
                    )
                }
            }
        }
        Text(
            text = "Stays on your phone · hold a clip to pin or delete",
            color = HintText,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = TextAlign.Center
        )
    }
    Row(Modifier.fillMaxWidth()) {
        Key(
            label = "ABC",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            textColor = Accent,
            fontSize = 16
        ) { onClose() }
        Key("space", Modifier.weight(5f), textColor = HintText, fontSize = 16) { onSpace() }
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            repeat = true,
            icon = { BackspaceIcon(KeyText) }
        ) { onBackspace() }
    }
}

@Composable
private fun ClipCard(clip: Clip, isSelected: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Accent.copy(alpha = 0.18f) else CardColor)
            .border(1.dp, if (isSelected) Accent else KeyColor, RoundedCornerShape(12.dp))
            .pointerInput(clip.text) {
                detectTapGestures(
                    onTap = {
                        view.keyTap(feedback)
                        tap()
                    },
                    onLongPress = {
                        if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hold()
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = clip.text,
            color = KeyText,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = if (clip.pinned) 16.dp else 0.dp)
        )
        if (clip.pinned) {
            PinIcon(Accent, Modifier.align(Alignment.TopEnd).size(14.dp))
        }
    }
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                view.keyTap(feedback)
                onClick()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = KeyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Clipboard: chamfered board and clip, the Numio N in yellow, three lines of text,
 * and a yellow folded corner. RR's own design, traced onto a 24×24 grid.
 */
@Composable
private fun ClipboardIcon(color: Color, modifier: Modifier, accent: Color = Accent) {
    Canvas(modifier) {
        val u = size.width / 24f
        val line = Stroke(width = 1.42f * u, join = StrokeJoin.Miter, cap = StrokeCap.Butt)
        drawPath(Path().apply {
            moveTo(4.74f * u, 8.76f * u); lineTo(4.74f * u, 5.68f * u); lineTo(5.68f * u, 4.85f * u); lineTo(7.68f * u, 4.85f * u)
        }, color, style = line)
        drawPath(Path().apply {
            moveTo(9.79f * u, 2.79f * u); lineTo(14.65f * u, 2.79f * u); lineTo(15.29f * u, 3.41f * u); lineTo(15.29f * u, 4.94f * u); lineTo(14.65f * u, 5.53f * u); lineTo(9.79f * u, 5.53f * u); lineTo(9.12f * u, 4.94f * u); lineTo(9.12f * u, 3.41f * u); close()
        }, color, style = line)
        drawPath(Path().apply {
            moveTo(16.47f * u, 4.85f * u); lineTo(18.47f * u, 4.85f * u); lineTo(19.41f * u, 5.79f * u); lineTo(19.41f * u, 17.44f * u); lineTo(16.03f * u, 21.18f * u); lineTo(5.82f * u, 21.18f * u); lineTo(4.74f * u, 20.18f * u); lineTo(4.74f * u, 16.56f * u)
        }, color, style = line)
        drawLine(color, Offset(12.21f * u, 9.12f * u), Offset(16.85f * u, 9.12f * u), 1.06f * u, StrokeCap.Square)
        drawLine(color, Offset(12.21f * u, 11.47f * u), Offset(16.85f * u, 11.47f * u), 1.06f * u, StrokeCap.Square)
        drawLine(color, Offset(12.21f * u, 13.82f * u), Offset(15.91f * u, 13.82f * u), 1.06f * u, StrokeCap.Square)
        drawPath(Path().apply {
            moveTo(3.76f * u, 8.91f * u); lineTo(6.94f * u, 11.26f * u); lineTo(5.38f * u, 12.65f * u); lineTo(5.38f * u, 15.91f * u); lineTo(3.76f * u, 15.91f * u); close()
        }, accent)
        drawPath(Path().apply {
            moveTo(8.03f * u, 9.35f * u); lineTo(10.00f * u, 9.35f * u); lineTo(10.00f * u, 16.06f * u); lineTo(6.56f * u, 13.47f * u); lineTo(8.03f * u, 12.35f * u); close()
        }, accent)
        drawPath(Path().apply {
            moveTo(14.26f * u, 16.94f * u); lineTo(18.03f * u, 16.41f * u); lineTo(14.26f * u, 20.15f * u); close()
        }, accent)
    }
}

/** Filled push-pin: marks pinned clips. */
@Composable
private fun PinIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawPath(Path().apply {
            moveTo(9 * u, 3 * u)
            lineTo(15 * u, 3 * u)
            lineTo(14 * u, 9 * u)
            lineTo(18 * u, 13 * u)
            lineTo(6 * u, 13 * u)
            lineTo(10 * u, 9 * u)
            close()
        }, color)
        drawLine(color, Offset(12 * u, 13 * u), Offset(12 * u, 21 * u), 2f * u, StrokeCap.Round)
    }
}
// ---------------------------------------------------------------------------
// Stickers
// ---------------------------------------------------------------------------

/**
 * Sticker panel: opens in place of the keys, like the clipboard. Tap a sticker to send it,
 * hold one to delete it. The first tile opens the sticker maker.
 * WhatsApp gets a real sticker; other apps that take pictures get an image.
 */
@Composable
private fun StickerPanel(
    stickers: List<File>,
    status: String?,
    onSend: (File) -> Unit,
    onMake: () -> Unit,
    onDelete: (File) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClose: () -> Unit
) {
    var selected by remember { mutableStateOf<File?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(RowHeight * 3 + StripHeight + LocalPanelExtra.current) // same height as the letter keyboard + strip
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(StripHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StickerIcon(Accent, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Stickers", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val chosen = selected
            if (chosen != null) {
                HeaderButton("Delete") { onDelete(chosen); selected = null }
                HeaderButton("Cancel") { selected = null }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "make") { MakeStickerCell(onMake) }
            items(stickers, key = { it.path + it.lastModified() }) { file ->
                StickerCell(
                    file = file,
                    isSelected = file == selected,
                    onTap = { if (selected != null) selected = null else onSend(file) },
                    onHold = { selected = file }
                )
            }
        }
        Text(
            text = status ?: if (selected != null) "Delete this sticker?" else "Made on your phone · hold to delete",
            color = if (status != null) Accent else HintText,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = TextAlign.Center
        )
    }
    Row(Modifier.fillMaxWidth()) {
        Key(
            label = "ABC",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            textColor = Accent,
            fontSize = 16
        ) { onClose() }
        Key("space", Modifier.weight(5f), textColor = HintText, fontSize = 16) { onSpace() }
        Key(
            label = "",
            modifier = Modifier.weight(1.5f),
            color = SpecialKeyColor,
            repeat = true,
            icon = { BackspaceIcon(KeyText) }
        ) { onBackspace() }
    }
}

/** First tile: "+ Make" opens the sticker maker in the Ezhuthola app. */
@Composable
private fun MakeStickerCell(onMake: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable {
                view.keyTap(feedback)
                onMake()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("+", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Make", color = Accent, fontSize = 12.sp)
    }
}

@Composable
private fun StickerCell(file: File, isSelected: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    // A small copy is enough for the grid (512 px → 128 px).
    val image = remember(file.path, file.lastModified()) {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Accent.copy(alpha = 0.18f) else CardColor)
            .border(1.dp, if (isSelected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .pointerInput(file) {
                detectTapGestures(
                    onTap = {
                        view.keyTap(feedback)
                        tap()
                    },
                    onLongPress = {
                        if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hold()
                    }
                )
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) Image(image, contentDescription = "Sticker", contentScale = ContentScale.Fit)
    }
}

/** Sticker: a chamfered square with its corner peeling up in yellow. */
@Composable
private fun StickerIcon(color: Color, modifier: Modifier, accent: Color = Accent) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(15.20f * u, 20.40f * u); lineTo(5.40f * u, 20.40f * u); lineTo(3.60f * u, 18.60f * u); lineTo(3.60f * u, 5.40f * u); lineTo(5.40f * u, 3.60f * u); lineTo(18.60f * u, 3.60f * u); lineTo(20.40f * u, 5.40f * u); lineTo(20.40f * u, 15.20f * u) }, color, style = Stroke(1.60f * u, join = StrokeJoin.Miter, cap = StrokeCap.Butt))
        drawPath(Path().apply { moveTo(20.40f * u, 15.20f * u); lineTo(15.20f * u, 20.40f * u); lineTo(15.20f * u, 15.20f * u); close() }, accent)
        drawPath(Path().apply { moveTo(8.20f * u, 8.00f * u); lineTo(10.00f * u, 8.00f * u); lineTo(10.00f * u, 9.80f * u); lineTo(8.20f * u, 9.80f * u); close() }, color)
        drawPath(Path().apply { moveTo(14.00f * u, 8.00f * u); lineTo(15.80f * u, 8.00f * u); lineTo(15.80f * u, 9.80f * u); lineTo(14.00f * u, 9.80f * u); close() }, color)
        drawPath(Path().apply { moveTo(8.20f * u, 12.60f * u); lineTo(10.40f * u, 14.40f * u); lineTo(13.60f * u, 14.40f * u) }, color, style = Stroke(1.40f * u, join = StrokeJoin.Miter, cap = StrokeCap.Butt))
    }
}


// ---------------------------------------------------------------------------
// Font styles and the quick emoji row
// ---------------------------------------------------------------------------

/**
 * Strip button for font styles: a handwritten N with a looped left stroke, a yellow sparkle
 * and a yellow swoosh underneath (RR's design). Thin hairlines, thick down-strokes.
 */
@Composable
private fun StylesIcon(color: Color = HintText, accent: Color = Accent) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        // Hairline: the hook, the loop and the thin left stroke up to the top
        drawPath(Path().apply {
            moveTo(8.20f * u, 11.20f * u)
            cubicTo(5.60f * u, 11.40f * u, 1.70f * u, 13.60f * u, 1.50f * u, 16.10f * u)
            cubicTo(1.40f * u, 18.10f * u, 3.30f * u, 18.90f * u, 5.00f * u, 18.20f * u)
            cubicTo(7.80f * u, 17.10f * u, 9.80f * u, 12.90f * u, 11.20f * u, 9.80f * u)
            cubicTo(12.30f * u, 7.40f * u, 13.30f * u, 5.60f * u, 14.30f * u, 5.20f * u)
        }, color, style = Stroke(width = 0.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Thicker underside of the loop
        drawPath(Path().apply {
            moveTo(4.40f * u, 12.40f * u)
            cubicTo(2.00f * u, 13.60f * u, 0.70f * u, 15.60f * u, 1.00f * u, 17.00f * u)
            cubicTo(1.30f * u, 18.60f * u, 3.20f * u, 19.20f * u, 5.00f * u, 18.30f * u)
            cubicTo(3.60f * u, 18.60f * u, 2.20f * u, 17.90f * u, 2.20f * u, 16.40f * u)
            cubicTo(2.20f * u, 14.90f * u, 3.10f * u, 13.50f * u, 4.40f * u, 12.40f * u)
            close()
        }, color)
        // Main down-stroke: thick, tapering at the top
        drawPath(Path().apply {
            moveTo(14.00f * u, 5.00f * u)
            cubicTo(12.20f * u, 6.60f * u, 11.40f * u, 8.60f * u, 11.60f * u, 11.60f * u)
            cubicTo(11.80f * u, 13.80f * u, 12.00f * u, 15.60f * u, 12.00f * u, 17.10f * u)
            cubicTo(12.60f * u, 17.00f * u, 13.20f * u, 16.80f * u, 13.60f * u, 16.40f * u)
            cubicTo(13.30f * u, 14.40f * u, 12.90f * u, 11.60f * u, 12.90f * u, 9.40f * u)
            cubicTo(12.90f * u, 7.40f * u, 13.30f * u, 6.00f * u, 14.00f * u, 5.00f * u)
            close()
        }, color)
        // Right stroke: up from the bottom, thinning to a small flick
        drawPath(Path().apply {
            moveTo(12.60f * u, 16.90f * u)
            cubicTo(15.60f * u, 14.60f * u, 17.40f * u, 8.60f * u, 19.40f * u, 5.80f * u)
            cubicTo(20.40f * u, 4.40f * u, 21.60f * u, 3.50f * u, 22.80f * u, 3.40f * u)
            cubicTo(23.10f * u, 3.40f * u, 23.00f * u, 3.80f * u, 22.70f * u, 3.80f * u)
            cubicTo(21.60f * u, 3.90f * u, 20.60f * u, 4.90f * u, 19.80f * u, 6.40f * u)
            cubicTo(18.20f * u, 9.40f * u, 16.60f * u, 14.40f * u, 13.60f * u, 16.60f * u)
            close()
        }, color)
        // Four-point sparkle
        drawPath(Path().apply {
            moveTo(20.90f * u, 7.00f * u)
            quadraticTo(21.20f * u, 9.30f * u, 23.30f * u, 9.60f * u)
            quadraticTo(21.20f * u, 9.90f * u, 20.90f * u, 12.20f * u)
            quadraticTo(20.60f * u, 9.90f * u, 18.50f * u, 9.60f * u)
            quadraticTo(20.60f * u, 9.30f * u, 20.90f * u, 7.00f * u)
            close()
        }, accent)
        // Swoosh underneath
        drawPath(Path().apply {
            moveTo(4.20f * u, 21.70f * u)
            quadraticTo(12.00f * u, 17.60f * u, 21.40f * u, 16.40f * u)
            quadraticTo(22.30f * u, 16.50f * u, 21.50f * u, 16.90f * u)
            quadraticTo(12.60f * u, 17.90f * u, 4.70f * u, 22.10f * u)
            quadraticTo(3.90f * u, 22.20f * u, 4.20f * u, 21.70f * u)
            close()
        }, accent)
    }
}

/**
 * [✕] [Normal] [𝐁𝐨𝐥𝐝] [𝐼𝑡𝑎𝑙𝑖𝑐] [𝓢𝓬𝓻𝓲𝓹𝓽]…  Tap a style, then type: English letters come out in it.
 * Styles only work on English letters, so in Malayalam mode the strip offers to switch.
 */
@Composable
private fun FontStyleStrip(
    selected: String?,
    malayalam: Boolean,
    onPick: (String?) -> Unit,
    onSwitchToEnglish: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StripButton(onClose) { CloseIcon(HintText, Modifier.size(20.dp)) }
        if (malayalam) {
            Chip(
                text = "Styles work in English · tap to switch",
                textColor = Accent,
                background = Accent.copy(alpha = 0.12f),
                fontSize = 14
            ) { onSwitchToEnglish() }
            return@Row
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                val on = selected == null
                Chip(
                    text = "Normal",
                    textColor = if (on) Accent else KeyText,
                    background = if (on) Accent.copy(alpha = 0.18f) else Color.Transparent,
                    fontSize = 16,
                    bold = on
                ) { onPick(null) }
            }
            items(FancyText.all, key = { it.id }) { style ->
                val on = style.id == selected
                Chip(
                    text = style.preview,
                    textColor = if (on) Accent else KeyText,
                    background = if (on) Accent.copy(alpha = 0.18f) else Color.Transparent,
                    fontSize = 17
                ) { onPick(if (on) null else style.id) }
            }
        }
    }
}

/** Your most used emojis, one tap to type. Scrolls sideways. */
@Composable
private fun QuickEmojiRow(emojis: List<String>, onEmoji: (String) -> Unit, onHide: () -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(EmojiRowHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(emojis, key = { it }) { emoji ->
            EmojiResult(emoji) { onEmoji(emoji) }
        }
        // At the end of the row: hide it (turn it back on with "Recent" in the emoji panel).
        item(key = "hide") {
            StripButton(onHide) { CloseIcon(HintText, Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun CloseIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        drawLine(color, Offset(5 * u, 5 * u), Offset(19 * u, 19 * u), 2.2f * u, StrokeCap.Round)
        drawLine(color, Offset(19 * u, 5 * u), Offset(5 * u, 19 * u), 2.2f * u, StrokeCap.Round)
    }
}
