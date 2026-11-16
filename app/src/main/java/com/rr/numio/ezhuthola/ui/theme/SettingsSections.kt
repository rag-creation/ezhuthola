package com.rr.numio.ezhuthola

import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Same Numio palette as the rest of the app screen
private val Card = Color(0xFF1A1A1A)
private val CardBorder = Color(0xFF262626)
private val TextMain = Color(0xFFF2F2F2)
private val TextDim = Color(0xFFA8A8A8)
private val Accent = Color(0xFFF5C427)
private val AccentSoft = Color(0x2EF5C427)
private val Ink = Color(0xFF141414)

/**
 * The main screen's settings below "Try it": TYPING, MORE (Words, Stickers, Clipboard),
 * KEYBOARD LOOK, and a card with Tips and About.
 * Changes are saved straight away; the keyboard picks them up the next time it opens.
 * [refreshKey] changes when you come back to the app, so the word counts are read again.
 */
@Composable
fun KeyboardSettingsSections(refreshKey: Int = 0, onOpenWords: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KeyboardSettings.prefs(context) }
    val scope = rememberCoroutineScope()

    var vibrate by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.VIBRATE, true)) }
    var sound by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.KEY_SOUND, false)) }
    var clipboard by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true)) }
    var emojiRow by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.EMOJI_ROW, false)) }
    var numberRow by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.NUMBER_ROW, false)) }
    var autoCaps by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.AUTO_CAPS, true)) }
    var fontStyles by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.FONT_STYLES, true)) }
    var keyHeight by remember {
        mutableFloatStateOf(
            prefs.getFloat(KeyboardSettings.KEY_HEIGHT, 1f)
                .coerceIn(KeyboardSettings.MIN_KEY_HEIGHT, KeyboardSettings.MAX_KEY_HEIGHT)
        )
    }
    var themeId by remember { mutableStateOf(prefs.getString(KeyboardSettings.THEME, Themes.Numio.id)!!) }
    var dim by remember { mutableFloatStateOf(prefs.getFloat(KeyboardSettings.PHOTO_DIM, 0.45f)) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var photoError by remember { mutableStateOf(false) }
    var tipsOpen by remember { mutableStateOf(false) }

    // The saved photo, loaded in the background (null if none).
    val photo by produceState<ImageBitmap?>(null, photoVersion) {
        value = withContext(Dispatchers.IO) { KeyboardSettings.loadPhoto(context)?.asImageBitmap() }
    }
    // Your words and the missing list, for the "Words" row.
    val counts by produceState(0 to 0, refreshKey) {
        value = withContext(Dispatchers.IO) { wordCounts(context) }
    }

    fun chooseTheme(id: String) {
        themeId = id
        prefs.edit().putString(KeyboardSettings.THEME, id).apply()
    }

    // Android's photo picker: Ezhuthola only gets the one photo you pick. No permission needed.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { KeyboardSettings.savePhoto(context, uri) }
                photoError = !ok
                if (ok) {
                    photoVersion++
                    chooseTheme(Themes.Photo.id)
                }
            }
        }
    }
    fun openPicker() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // ---- TYPING ----
    Label("TYPING")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        ToggleRow("Vibrate on keypress", "Uses your phone's touch vibration", vibrate) {
            vibrate = it; prefs.edit().putBoolean(KeyboardSettings.VIBRATE, it).apply()
        }
        Divider()
        ToggleRow("Key sound", "Click on every key", sound) {
            sound = it; prefs.edit().putBoolean(KeyboardSettings.KEY_SOUND, it).apply()
        }
        Divider()
        ToggleRow("Auto capitals", "Start of a sentence, English only", autoCaps) {
            autoCaps = it; prefs.edit().putBoolean(KeyboardSettings.AUTO_CAPS, it).apply()
        }
        Divider()
        ToggleRow("Number row", "1 to 0 above the letters", numberRow) {
            numberRow = it; prefs.edit().putBoolean(KeyboardSettings.NUMBER_ROW, it).apply()
        }
        Divider()
        ToggleRow("Quick emoji row", "Your recent emojis above the keys", emojiRow) {
            emojiRow = it; prefs.edit().putBoolean(KeyboardSettings.EMOJI_ROW, it).apply()
        }
        Divider()
        ToggleRow("Font styles", "𝐁𝐨𝐥𝐝, 𝓢𝓬𝓻𝓲𝓹𝓽 and more in the strip", fontStyles) {
            fontStyles = it; prefs.edit().putBoolean(KeyboardSettings.FONT_STYLES, it).apply()
        }
        Divider()
        // Keyboard height: 50%–125%. The keyboard follows the slider straight away
        // (try it in the box at the top of this screen).
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keyboard height", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(keyHeight * 100).roundToInt()}%", color = TextDim, fontSize = 15.sp)
            if (keyHeight != 1f) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "Reset",
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            keyHeight = 1f
                            prefs.edit().putFloat(KeyboardSettings.KEY_HEIGHT, 1f).apply()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Slider(
            value = keyHeight,
            onValueChange = { keyHeight = (it * 20).roundToInt() / 20f }, // steps of 5%
            onValueChangeFinished = { prefs.edit().putFloat(KeyboardSettings.KEY_HEIGHT, keyHeight).apply() },
            valueRange = KeyboardSettings.MIN_KEY_HEIGHT..KeyboardSettings.MAX_KEY_HEIGHT,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0xFF333333)
            ),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }

    // ---- MORE ----
    Spacer(Modifier.height(10.dp))
    Label("MORE")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        val (yours, missing) = counts
        NavRow(
            icon = RowIcon.Words,
            title = "Words",
            subtitle = if (yours == 0) "Your own words, Malayalam and English" else "$yours of your words · Malayalam and English",
            badge = if (missing > 0) "$missing missing" else null,
            onClick = onOpenWords
        )
        Divider()
        NavRow(
            icon = RowIcon.Sticker,
            title = "Stickers",
            subtitle = "Make one from a photo or Malayalam text",
            onClick = { context.startActivity(android.content.Intent(context, StickerMakerActivity::class.java)) }
        )
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable {
                    clipboard = !clipboard
                    prefs.edit().putBoolean(KeyboardSettings.CLIPBOARD_HISTORY, clipboard).apply()
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawnIcon(RowIcon.Clipboard)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Clipboard history", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Passwords and OTPs never saved", color = TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            NumioSwitch(clipboard) {
                clipboard = it; prefs.edit().putBoolean(KeyboardSettings.CLIPBOARD_HISTORY, it).apply()
            }
        }
    }

    // ---- KEYBOARD LOOK ----
    Spacer(Modifier.height(10.dp))
    Label("KEYBOARD LOOK")
    Themes.all.filterNot { it.usesPhoto }.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { theme ->
                ThemeCard(theme = theme, selected = theme.id == themeId, modifier = Modifier.weight(1f)) {
                    chooseTheme(theme.id)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    // Your photo: its own row (it needs a photo picked first)
    val photoSelected = themeId == Themes.Photo.id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(if (photoSelected) 2.dp else 0.dp, if (photoSelected) Accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { if (photo == null) openPicker() else chooseTheme(Themes.Photo.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (photo != null) {
            Box(Modifier.size(width = 40.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))) {
                Image(photo!!, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        } else {
            DrawnIcon(RowIcon.Photo)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Your photo as the background", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    photo == null -> "Tap to choose a photo"
                    photoSelected -> "In use"
                    else -> "Tap to use it"
                },
                color = TextDim, fontSize = 13.sp
            )
        }
        if (photoSelected) Text("✓", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }

    // Photo controls: shown once there's a photo
    if (photo != null || photoError) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (photoError) {
                Text("Couldn't open that photo. Try another one.", color = Accent, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 96.dp, height = 72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink)
                ) {
                    photo?.let {
                        Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Choose photo", primary = true) { openPicker() }
                    if (photo != null) {
                        SmallButton("Remove", primary = false) {
                            KeyboardSettings.photoFile(context).delete()
                            photoVersion++
                            if (themeId == Themes.Photo.id) chooseTheme(Themes.Numio.id)
                        }
                    }
                }
            }
            if (photo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dim photo", color = TextMain, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${(dim * 100).toInt()}%", color = TextDim, fontSize = 15.sp)
                }
                Slider(
                    value = dim,
                    onValueChange = { dim = it },
                    onValueChangeFinished = { prefs.edit().putFloat(KeyboardSettings.PHOTO_DIM, dim).apply() },
                    valueRange = 0.1f..0.8f,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )
                Text(
                    "Darker = easier to read the letters. Your photo is kept in Ezhuthola's private " +
                            "storage and never leaves your phone.",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }

    // ---- Tips and About ----
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { tipsOpen = !tipsOpen },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tips and gestures", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chevron(down = !tipsOpen, up = tipsOpen)
        }
        if (tipsOpen) {
            Text(
                "• Hold the space bar to switch to another keyboard.\n" +
                        "• Slide on the space bar to move the cursor.\n" +
                        "• Slide left from backspace to delete whole words.\n" +
                        "• Hold a letter for accents and symbols (hold e for é, a for @).\n" +
                        "• Hold the leaf key to choose a language; tap it to switch.\n" +
                        "• Tap 𝓐 in the top strip for font styles.",
                color = TextDim,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
        }
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpenAbout),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("About and credits", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Licences, word data", color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

// ---- Pieces ----------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        NumioSwitch(checked, onChange)
    }
}

@Composable
private fun NumioSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink,
            checkedTrackColor = Accent,
            checkedBorderColor = Accent,
            uncheckedThumbColor = Color(0xFF8A8A8A),
            uncheckedTrackColor = Color(0xFF333333),
            uncheckedBorderColor = Color(0xFF333333)
        )
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242424)))
}

private enum class RowIcon { Words, Sticker, Clipboard, Photo }

/** A row that opens another page: icon, title, a short line, maybe a badge, and ›. */
@Composable
private fun NavRow(icon: RowIcon, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DrawnIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                badge,
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(Accent)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

/** Small line icons in Numio amber, drawn so the app needs no icon library. */
@Composable
private fun DrawnIcon(icon: RowIcon) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(Accent, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.8f * u, StrokeCap.Round)
        when (icon) {
            RowIcon.Words -> {
                drawPath(Path().apply {
                    moveTo(5 * u, 4 * u); lineTo(15 * u, 4 * u); lineTo(19 * u, 8 * u)
                    lineTo(19 * u, 20 * u); lineTo(5 * u, 20 * u); close()
                }, Accent, style = stroke)
                line(8f, 12f, 16f, 12f); line(8f, 15.5f, 14f, 15.5f)
            }
            RowIcon.Sticker -> {
                drawPath(Path().apply {
                    moveTo(5 * u, 4 * u); lineTo(19 * u, 4 * u); lineTo(19 * u, 13 * u)
                    lineTo(13 * u, 20 * u); lineTo(5 * u, 20 * u); close()
                    moveTo(13 * u, 20 * u); lineTo(13 * u, 14 * u); lineTo(19 * u, 13 * u)
                }, Accent, style = stroke)
                drawCircle(Accent, 1.1f * u, Offset(9.5f * u, 9.5f * u))
                drawCircle(Accent, 1.1f * u, Offset(14.5f * u, 9.5f * u))
            }
            RowIcon.Clipboard -> {
                drawRoundRect(
                    Accent, Offset(5 * u, 6 * u), androidx.compose.ui.geometry.Size(14 * u, 15 * u),
                    androidx.compose.ui.geometry.CornerRadius(2 * u), style = stroke
                )
                drawRoundRect(
                    Accent, Offset(9 * u, 3.5f * u), androidx.compose.ui.geometry.Size(6 * u, 4 * u),
                    androidx.compose.ui.geometry.CornerRadius(1 * u), style = stroke
                )
                line(8.5f, 12.5f, 15.5f, 12.5f); line(8.5f, 16f, 13f, 16f)
            }
            RowIcon.Photo -> {
                drawRoundRect(
                    Accent, Offset(3 * u, 5 * u), androidx.compose.ui.geometry.Size(18 * u, 14 * u),
                    androidx.compose.ui.geometry.CornerRadius(2 * u), style = stroke
                )
                drawPath(Path().apply {
                    moveTo(3 * u, 16 * u); lineTo(8 * u, 11 * u); lineTo(12 * u, 15 * u)
                    lineTo(15 * u, 12 * u); lineTo(21 * u, 17 * u)
                }, Accent, style = stroke)
                drawCircle(Accent, 1.3f * u, Offset(16 * u, 8.5f * u))
            }
        }
    }
}

/** ›, or ⌄ / ⌃ for a row that opens in place. */
@Composable
private fun Chevron(down: Boolean = false, up: Boolean = false) {
    Canvas(Modifier.size(16.dp)) {
        val u = size.width / 16f
        val path = Path().apply {
            when {
                down -> { moveTo(3 * u, 6 * u); lineTo(8 * u, 11 * u); lineTo(13 * u, 6 * u) }
                up -> { moveTo(3 * u, 10 * u); lineTo(8 * u, 5 * u); lineTo(13 * u, 10 * u) }
                else -> { moveTo(6 * u, 3 * u); lineTo(11 * u, 8 * u); lineTo(6 * u, 13 * u) }
            }
        }
        drawPath(path, TextDim, style = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun SmallButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) AccentSoft else Color(0xFF242424),
            contentColor = if (primary) Accent else Color(0xFFC8C8C8)
        )
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** A theme choice: a tiny keyboard drawn in that theme's colours, plus its name. */
@Composable
private fun ThemeCard(theme: KeyboardTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(2.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = theme.name, onClick = onClick)
            .padding(8.dp)
    ) {
        MiniKeyboard(theme)
        Text(
            theme.name,
            color = TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp)
        )
    }
}

@Composable
private fun MiniKeyboard(theme: KeyboardTheme) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.background)
            .padding(horizontal = 5.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        MiniRow(10, 0.dp, theme.key)
        MiniRow(9, 4.dp, theme.key)
        MiniRow(7, 9.dp, theme.key)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MiniKey(theme.accent, Modifier.width(10.dp))
            MiniKey(theme.key, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniRow(count: Int, sidePadding: androidx.compose.ui.unit.Dp, color: Color) {
    Row(Modifier.padding(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { MiniKey(color, Modifier.weight(1f)) }
    }
}

@Composable
private fun MiniKey(color: Color, modifier: Modifier) {
    Box(modifier.height(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
}