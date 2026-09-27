package com.rr.numio.ezhuthola

import android.view.ContextThemeWrapper
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
import com.rr.numio.ezhuthola.engine.Suggestions
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

// ---- Numio dark palette ----
private val KeyboardBg = Color(0xFF141414)
private val KeyColor = Color(0xFF2A2A2A)
private val SpecialKeyColor = Color(0xFF1D1D1D)
private val KeyText = Color(0xFFF2F2F2)
private val HintText = Color(0xFF9E9E9E)
private val Accent = Color(0xFFF5C427)

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

@Composable
fun KeyboardLayout(
    malayalam: Boolean,               // yellow ola key = Malayalam mode
    onToggleLanguage: () -> Unit,
    suggestions: Suggestions?,        // what the strip shows for the word being typed
    onPick: (String) -> Unit,         // user tapped a word in the strip
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    var shift by remember { mutableStateOf(Shift.OFF) }
    var symbols by remember { mutableStateOf(false) }
    var emojiOpen by remember { mutableStateOf(false) }

    fun label(key: String) = if (!symbols && shift != Shift.OFF) key.uppercase() else key

    fun type(key: String) {
        onText(label(key))
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    val rows = if (symbols) symbolRows else letterRows

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyboardBg)
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        if (emojiOpen) {
            EmojiPanel(
                onEmoji = onText,
                onBackspace = onBackspace,
                onSpace = { onText(" ") },
                onClose = { emojiOpen = false }
            )
            return@Column
        }

        SuggestionStrip(suggestions, onPick)

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
            }
            rows[2].forEach { k -> Key(label(k), Modifier.weight(1f)) { type(k) } }
            Key(
                label = "",
                modifier = Modifier.weight(1.5f),
                color = SpecialKeyColor,
                repeat = true,
                icon = { BackspaceIcon(KeyText) }
            ) { onBackspace() }
        }

        // Row 4: ?123, comma, Ezhuthola key, space, period, enter
        Row(Modifier.fillMaxWidth()) {
            Key(
                label = if (symbols) "ABC" else "?123",
                modifier = Modifier.weight(1.5f),
                color = SpecialKeyColor,
                fontSize = 16
            ) { symbols = !symbols }
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
                fontSize = 16
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
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = modifier
            .height(RowHeight)
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
    val leaves = if (onYellow) {
        listOf(0x5E2A1C08L, 0x802A1C08L, 0xA62A1C08L, 0xCC2A1C08L).map { Color(it) }
    } else {
        listOf(0xFF9C6B26, 0xFFB07A2C, 0xFFC48A33, 0xFFD6993A).map { Color(it) }
    }
    val front = if (onYellow) Color(0xFF2A1C08) else Color(0xFFF0A53A)
    val hole = if (onYellow) Accent else SpecialKeyColor
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

/**
 * Full emoji panel: Google's open-source EmojiPickerView (categories, recents, skin tones),
 * drawn with the phone's own emoji font. Offline, no stickers/GIFs.
 */
@Composable
private fun EmojiPanel(
    onEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClose: () -> Unit
) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight * 3 + StripHeight), // same height as the letter keyboard + strip
        factory = { context ->
            // Dark theme wrapper so the picker matches the keyboard.
            EmojiPickerView(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)).apply {
                emojiGridColumns = 8
                setBackgroundColor(0xFF141414.toInt())
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
private fun SmileHint(color: Color) {
    Canvas(Modifier.size(17.dp)) {
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
    Box(
        modifier = Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
