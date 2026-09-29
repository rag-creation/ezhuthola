package com.rr.numio.ezhuthola

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
 * The "TYPING" and "KEYBOARD LOOK" sections of the app screen.
 * Changes are saved straight away; the keyboard picks them up the next time it opens.
 */
@Composable
fun KeyboardSettingsSections() {
    val context = LocalContext.current
    val prefs = remember { KeyboardSettings.prefs(context) }
    val scope = rememberCoroutineScope()

    var vibrate by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.VIBRATE, true)) }
    var sound by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.KEY_SOUND, false)) }
    var clipboard by remember { mutableStateOf(prefs.getBoolean(KeyboardSettings.CLIPBOARD_HISTORY, true)) }
    var themeId by remember { mutableStateOf(prefs.getString(KeyboardSettings.THEME, Themes.Numio.id)!!) }
    var dim by remember { mutableFloatStateOf(prefs.getFloat(KeyboardSettings.PHOTO_DIM, 0.45f)) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var photoError by remember { mutableStateOf(false) }

    // The saved photo, loaded in the background (null if none).
    val photo by produceState<ImageBitmap?>(null, photoVersion) {
        value = withContext(Dispatchers.IO) { KeyboardSettings.loadPhoto(context)?.asImageBitmap() }
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
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        ToggleRow("Vibrate on keypress", null, vibrate) {
            vibrate = it; prefs.edit().putBoolean(KeyboardSettings.VIBRATE, it).apply()
        }
        ToggleRow("Key sound", null, sound) {
            sound = it; prefs.edit().putBoolean(KeyboardSettings.KEY_SOUND, it).apply()
        }
        ToggleRow(
            "Clipboard history",
            "Copied text waits in the clipboard panel. Passwords and OTPs are never saved.",
            clipboard
        ) {
            clipboard = it; prefs.edit().putBoolean(KeyboardSettings.CLIPBOARD_HISTORY, it).apply()
        }
        Text(
            "Tip: hold the space bar to switch to another keyboard.",
            color = TextDim,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }

    // ---- KEYBOARD LOOK ----
    Spacer(Modifier.height(10.dp))
    Label("KEYBOARD LOOK")
    Themes.all.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    selected = theme.id == themeId,
                    photo = photo,
                    modifier = Modifier.weight(1f)
                ) {
                    if (theme.usesPhoto && photo == null) openPicker() else chooseTheme(theme.id)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
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
            Text("Your photo", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
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
private fun ThemeCard(
    theme: KeyboardTheme,
    selected: Boolean,
    photo: ImageBitmap?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Accent else CardBorder,
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        MiniKeyboard(theme, if (theme.usesPhoto) photo else null)
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(theme.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (theme.usesPhoto && photo == null) "Tap to choose" else theme.subtitle,
                    color = TextDim,
                    fontSize = 12.sp
                )
            }
            if (selected) Text("✓", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiniKeyboard(theme: KeyboardTheme, photo: ImageBitmap?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (theme.usesPhoto && photo == null) Color(0xFF2A2A2A) else theme.background)
    ) {
        if (photo != null) {
            Image(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MiniRow(10, 0.dp, theme.key)
            MiniRow(9, 6.dp, theme.key)
            MiniRow(7, 14.dp, theme.key)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                MiniKey(theme.specialKey, Modifier.width(16.dp))
                MiniKey(theme.accent, Modifier.width(14.dp))
                MiniKey(theme.key, Modifier.weight(1f))
                MiniKey(theme.specialKey, Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun MiniRow(count: Int, sidePadding: androidx.compose.ui.unit.Dp, color: Color) {
    Row(Modifier.padding(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { MiniKey(color, Modifier.weight(1f)) }
    }
}

@Composable
private fun MiniKey(color: Color, modifier: Modifier) {
    Box(modifier.height(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
}