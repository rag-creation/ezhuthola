package com.rr.numio.ezhuthola

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app you open from the launcher: setup, a place to try the keyboard, and About. */
class MainActivity : ComponentActivity() {

    // Bumped whenever the user comes back (from Settings or the keyboard picker),
    // so the setup steps re-check themselves and tick off.
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SetupScreen(
                refreshKey = refresh,
                isEnabled = ::isKeyboardEnabled,
                isSelected = ::isKeyboardSelected,
                onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                onSwitch = {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    // The keyboard picker is a dialog, so closing it doesn't trigger onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh++
    }

    /** Step 1 done: Ezhuthola is turned on in the phone's keyboard list. */
    private fun isKeyboardEnabled(): Boolean =
        getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .any { it.packageName == packageName }

    /** Step 2 done: Ezhuthola is the keyboard currently in use. */
    private fun isKeyboardSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
}

// ---- Numio palette ---------------------------------------------------------

private val Bg = Color(0xFF0B0B0B)
private val Card = Color(0xFF1A1A1A)
private val CardBorder = Color(0xFF262626)
private val TextMain = Color(0xFFF2F2F2)
private val TextDim = Color(0xFFA8A8A8)
private val Accent = Color(0xFFF5C427)
private val AccentSoft = Color(0x2EF5C427)

// ---- Screen ------------------------------------------------------------------

@Composable
fun SetupScreen(
    refreshKey: Int,
    isEnabled: () -> Boolean,
    isSelected: () -> Boolean,
    onEnable: () -> Unit,
    onSwitch: () -> Unit
) {
    val enabled = remember(refreshKey) { isEnabled() }
    val selected = remember(refreshKey) { isSelected() }
    var testText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Header()

        Spacer(Modifier.height(10.dp))
        SectionLabel("SETUP")

        StepCard(
            number = 1,
            title = "Turn on Ezhuthola",
            subtitle = if (enabled) "Enabled in your keyboard list" else "Allow it in your phone's keyboard settings",
            done = enabled,
            buttonLabel = "Open",
            active = true,
            onClick = onEnable
        )
        StepCard(
            number = 2,
            title = "Switch to Ezhuthola",
            subtitle = if (selected) "It's your keyboard now" else "Pick it as the keyboard you type with",
            done = selected,
            buttonLabel = "Switch",
            active = enabled,
            onClick = onSwitch
        )

        if (enabled && selected) AllSetCard()

        Spacer(Modifier.height(10.dp))
        SectionLabel("TRY IT")
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            placeholder = { Text("Try typing \"njan\"…", color = TextDim) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedContainerColor = Card,
                unfocusedContainerColor = Card,
                focusedBorderColor = Accent,
                unfocusedBorderColor = CardBorder,
                cursorColor = Accent
            )
        )

        Spacer(Modifier.height(10.dp))
        KeyboardSettingsSections()   // Typing + Keyboard look (SettingsSections.kt)

        Spacer(Modifier.height(10.dp))
        SectionLabel("PRIVACY")
        PrivacyCard()

        Spacer(Modifier.height(18.dp))
        Footer()
    }
}

// ---- Pieces ----------------------------------------------------------------

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Card),
            contentAlignment = Alignment.Center
        ) {
            OlaIcon(onYellow = false, modifier = Modifier.size(width = 56.dp, height = 48.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Ezhuthola", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "എഴുത്തോല · BY NUMIO",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Type Malayalam in English letters. No ads, no tracking, no internet.",
        color = TextDim,
        fontSize = 15.sp,
        lineHeight = 21.sp
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    buttonLabel: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active || done) 1f else 0.45f)
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .border(1.dp, if (done) Accent.copy(alpha = 0.35f) else CardBorder, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Number badge, or a tick once the step is done
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (done) Accent else AccentSoft),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                CheckMark(Color(0xFF141414))
            } else {
                Text("$number", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (!done) {
            Button(
                onClick = onClick,
                enabled = active,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF141414),
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor = TextDim
                )
            ) {
                Text(buttonLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AllSetCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AccentSoft)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OlaIcon(onYellow = false, modifier = Modifier.size(width = 34.dp, height = 29.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("You're all set!", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tap the leaf key to switch between English and മലയാളം.",
                color = TextMain,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        ShieldIcon(Accent)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("No internet permission", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Everything you type stays on this phone. No ads, no tracking, and the code is open for anyone to check.",
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun Footer() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("v0.2 · Free & open source (GPL-3.0)", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text("Made by a Keralite 💛", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Word data: FrequencyWords by Hermit Dave (CC BY-SA 4.0), based on OpenSubtitles\n" +
                "Emoji names and keywords: Unicode CLDR and Unicode Emoji data " +
                "© Unicode, Inc. (Unicode License v3)",
            color = TextDim.copy(alpha = 0.7f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---- Small drawn icons -------------------------------------------------------

@Composable
private fun CheckMark(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 24f
        drawPath(
            Path().apply {
                moveTo(5 * u, 12.5f * u)
                lineTo(9.5f * u, 17 * u)
                lineTo(19 * u, 7.5f * u)
            },
            color,
            style = Stroke(width = 2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun ShieldIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(
            Path().apply {
                moveTo(12 * u, 3 * u)
                lineTo(19 * u, 6 * u)
                lineTo(19 * u, 12 * u)
                cubicTo(19 * u, 16.5f * u, 16 * u, 19.8f * u, 12 * u, 21 * u)
                cubicTo(8 * u, 19.8f * u, 5 * u, 16.5f * u, 5 * u, 12 * u)
                lineTo(5 * u, 6 * u)
                close()
            },
            color,
            style = stroke
        )
        drawPath(
            Path().apply {
                moveTo(9 * u, 12 * u)
                lineTo(11 * u, 14 * u)
                lineTo(15 * u, 10 * u)
            },
            color,
            style = stroke
        )
    }
}