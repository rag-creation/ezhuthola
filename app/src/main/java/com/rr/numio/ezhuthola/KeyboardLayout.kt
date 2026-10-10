package com.rr.numio.ezhuthola

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

// ---- Sizes: change these to resize the whole keyboard ----
private val RowHeight = 88.dp          // touch height of one row (includes the gap)
private val KeyGapH = 2.dp             // half of the gap between keys
private val KeyGapV = 5.dp             // half of the gap between rows
private const val KeyFontSize = 28
private const val LongPressMs = 350L   // hold a top-row key this long to type its number
private const val RepeatStartMs = 400L // hold backspace this long before it repeats
private const val RepeatEveryMs = 50L

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

// Second symbols page (the =\< key)
private val moreSymbolRows = listOf(
    listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
    listOf("£", "$", "€", "¥", "^", "°", "=", "{", "}", "\\"),
    listOf("₹", "%", "©", "®", "™", "[", "]", "<", ">")
)

@Composable
fun KeyboardLayout(
    malayalam: Boolean,               // yellow ola key = Malayalam mode
    onToggleLanguage: () -> Unit,
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
    onDeleteSticker: (File) -> Unit = {}
) {
    CompositionLocalProvider(LocalKeyboardTheme provides theme, LocalKeyFeedback provides feedback) {
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
                malayalam, onToggleLanguage, suggestions, onPick, onText, onBackspace, onEnter,
                session, startWithNumbers, clips, onOpenClipboard, onPasteClip, onTogglePin,
                onDeleteClip, onClearClips, onOpenSettings, onSwitchKeyboard, searchEmoji,
                transparent = theme.usesPhoto && photo != null,
                clipboardOn = clipboardOn,
                stickers = stickers,
                stickerStatus = stickerStatus,
                onOpenStickers = onOpenStickers,
                onSendSticker = onSendSticker,
                onMakeSticker = onMakeSticker,
                onDeleteSticker = onDeleteSticker
            )
        }
    }
}

@Composable
private fun KeyboardContent(
    malayalam: Boolean,
    onToggleLanguage: () -> Unit,
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
    onDeleteSticker: (File) -> Unit = {}
) {
    // Keyed on `session`, so every new text field starts fresh instead of
    // keeping the page (?123, emoji, shift) that was open last time.
    var shift by remember(session) { mutableStateOf(Shift.OFF) }
    var symbols by remember(session) { mutableStateOf(startWithNumbers) }
    var moreSymbols by remember(session) { mutableStateOf(false) }   // second symbols page
    var emojiOpen by remember(session) { mutableStateOf(false) }
    var clipboardOpen by remember(session) { mutableStateOf(false) }
    var stickersOpen by remember(session) { mutableStateOf(false) }

    fun label(key: String) = if (!symbols && shift != Shift.OFF) key.uppercase() else key

    fun type(key: String) {
        onText(label(key))
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    val rows = when {
        !symbols -> letterRows
        moreSymbols -> moreSymbolRows
        else -> symbolRows
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (transparent) Color.Transparent else KeyboardBg)
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        if (emojiOpen) {
            EmojiPanel(
                onEmoji = onText,
                onBackspace = onBackspace,
                onSpace = { onText(" ") },
                onClose = { emojiOpen = false },
                searchEmoji = searchEmoji
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

        if (suggestions == null || suggestions.typed.isEmpty()) {
            // Not typing a word: show the clipboard button.
            IdleStrip(
                onOpenSettings = onOpenSettings,
                onOpenClipboard = { onOpenClipboard(); clipboardOpen = true },
                onOpenStickers = { onOpenStickers(); stickersOpen = true }
            )
        } else {
            SuggestionStrip(suggestions, onPick)
        }

        // Row 1: letters, hold for numbers
        Row(Modifier.fillMaxWidth()) {
            rows[0].forEachIndexed { i, k ->
                val hint = if (symbols) null else numberHints[i]
                Key(
                    label = label(k),
                    modifier = Modifier.weight(1f),
                    hint = hint,
                    onLongPress = hint?.let { number -> { onText(number) } }
                ) { type(k) }
            }
        }

        // Row 2: indented by half a key, like a real QWERTY
        Row(Modifier.fillMaxWidth()) {
            if (!symbols) Spacer(Modifier.weight(0.5f))
            rows[1].forEach { k -> Key(label(k), Modifier.weight(1f)) { type(k) } }
            if (!symbols) Spacer(Modifier.weight(0.5f))
        }

        // Row 3: shift + letters + backspace
        Row(Modifier.fillMaxWidth()) {
            if (!symbols) {
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
                    label = if (moreSymbols) "?123" else "=\\<",
                    modifier = Modifier.weight(1.5f),
                    color = SpecialKeyColor,
                    fontSize = 16
                ) { moreSymbols = !moreSymbols }
            }
            rows[2].forEach { k -> Key(label(k), Modifier.weight(1f)) { type(k) } }
            Key(
                label = "",
                modifier = Modifier.weight(1.5f),
                color = LocalKeyboardTheme.current.deleteKey ?: SpecialKeyColor,
                repeat = true,
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
            ) { symbols = !symbols; moreSymbols = false }
            Key(
                label = ",",
                modifier = Modifier.weight(1f),
                color = SpecialKeyColor,
                hintIcon = { SmileHint(HintText) },
                onLongPress = { emojiOpen = true }   // hold comma → emoji panel
            ) { onText(",") }

            // The Ezhuthola key: switches Malayalam <-> English.
            // Malayalam mode = solid yellow key. English mode = dark key, yellow leaf.
            Key(
                label = "",
                modifier = Modifier.weight(1.2f),
                color = if (malayalam) Accent else SpecialKeyColor,
                icon = { OlaIcon(onYellow = malayalam) }
            ) { onToggleLanguage() }

            Key(
                label = if (malayalam) "മലയാളം" else "English",
                modifier = Modifier.weight(4f),
                textColor = HintText,
                fontSize = 16,
                onLongPress = onSwitchKeyboard     // hold space → choose another keyboard
            ) { onText(" ") }
            Key(".", Modifier.weight(1f), color = SpecialKeyColor) { onText(".") }
            Key(
                label = "",
                modifier = Modifier.weight(1.5f),
                color = SpecialKeyColor,
                icon = { EnterIcon(KeyText) }
            ) { onEnter() }
        }
    }
}

/**
 * One key. The whole cell is touchable, including the gap around the drawn key,
 * so there are no "dead zones" between keys. That's a big part of what makes typing feel easy.
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
    icon: (@Composable () -> Unit)? = null,
    hintIcon: (@Composable () -> Unit)? = null,
    onPress: () -> Unit
) {
    val view = LocalView.current
    val feedback = LocalKeyFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = modifier
            .height(RowHeight)
            .pointerInput(repeat, feedback) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    view.keyTap(feedback)
                    try {
                        if (repeat) {
                            // Backspace: delete now, then keep deleting while held.
                            press()
                            var done = withTimeoutOrNull(RepeatStartMs) { waitForUpOrCancellation(); true }
                            while (done == null) {
                                press()
                                done = withTimeoutOrNull(RepeatEveryMs) { waitForUpOrCancellation(); true }
                            }
                        } else {
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
                    } finally {
                        pressed = false
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
            .height(RowHeight * 3), // search bar + grid = same height as the strip + letter keys
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

/** Outline smiley: the comma key's hint, and the "back to emoji" key in search. */
@Composable
private fun SmileIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
        drawCircle(color, radius = 10 * u, center = Offset(12 * u, 12 * u), style = stroke)
        drawPath(Path().apply {
            moveTo(7.5f * u, 14 * u)
            quadraticTo(12 * u, 18.5f * u, 16.5f * u, 14 * u)
        }, color, style = stroke)
        drawCircle(color, radius = 1.4f * u, center = Offset(9 * u, 9.5f * u))
        drawCircle(color, radius = 1.4f * u, center = Offset(15 * u, 9.5f * u))
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

/** Strip when no word is being typed: settings on the left, stickers and clipboard on the right. */
@Composable
private fun IdleStrip(onOpenSettings: () -> Unit, onOpenClipboard: () -> Unit, onOpenStickers: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StripButton(onOpenSettings) { SettingsIcon(HintText, Modifier.size(24.dp)) }
        Spacer(Modifier.weight(1f))
        StripButton(onOpenStickers) { StickerIcon(HintText, Modifier.size(24.dp)) }
        StripButton(onOpenClipboard) { ClipboardIcon(HintText, Modifier.size(24.dp)) }
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

/** Two slider lines: "settings". */
@Composable
private fun SettingsIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val w = 1.8f * u
        drawLine(color, Offset(4 * u, 7 * u), Offset(14 * u, 7 * u), w, StrokeCap.Round)
        drawLine(color, Offset(18 * u, 7 * u), Offset(20 * u, 7 * u), w, StrokeCap.Round)
        drawLine(color, Offset(4 * u, 17 * u), Offset(8 * u, 17 * u), w, StrokeCap.Round)
        drawLine(color, Offset(12 * u, 17 * u), Offset(20 * u, 17 * u), w, StrokeCap.Round)
        drawCircle(color, 2 * u, Offset(16 * u, 7 * u), style = Stroke(w))
        drawCircle(color, 2 * u, Offset(10 * u, 17 * u), style = Stroke(w))
    }
}

/**
 * Clipboard panel: opens in place of the keys, like the emoji panel.
 * Tap a clip to paste it. Hold a clip to pin or delete it. Pinned clips stay until removed;
 * the rest disappear after an hour. Passwords and OTPs are never saved.
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
            .height(RowHeight * 3 + StripHeight) // same height as the letter keyboard + strip
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

/** Clipboard board with a clip at the top and two lines of "text". */
@Composable
private fun ClipboardIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.9f * u, join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawRoundRect(
            color, Offset(6 * u, 4 * u), Size(12 * u, 17 * u), CornerRadius(2 * u, 2 * u), style = stroke
        )
        drawRoundRect(
            color, Offset(9 * u, 2.5f * u), Size(6 * u, 3 * u), CornerRadius(1 * u, 1 * u), style = stroke
        )
        drawLine(color, Offset(9 * u, 11 * u), Offset(15 * u, 11 * u), 1.9f * u, StrokeCap.Round)
        drawLine(color, Offset(9 * u, 15 * u), Offset(13 * u, 15 * u), 1.9f * u, StrokeCap.Round)
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
            .height(RowHeight * 3 + StripHeight) // same height as the letter keyboard + strip
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

/** Square sticker with its corner peeling up. */
@Composable
private fun StickerIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.9f * u, join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(15 * u, 20 * u)
            lineTo(7 * u, 20 * u)
            quadraticTo(4 * u, 20 * u, 4 * u, 17 * u)
            lineTo(4 * u, 7 * u)
            quadraticTo(4 * u, 4 * u, 7 * u, 4 * u)
            lineTo(17 * u, 4 * u)
            quadraticTo(20 * u, 4 * u, 20 * u, 7 * u)
            lineTo(20 * u, 15 * u)
            close()
        }, color, style = stroke)
        drawPath(Path().apply {
            moveTo(20 * u, 15 * u)
            lineTo(16 * u, 15 * u)
            quadraticTo(15 * u, 15 * u, 15 * u, 16 * u)
            lineTo(15 * u, 20 * u)
        }, color, style = stroke)
    }
}
