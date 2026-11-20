package com.rr.numio.ezhuthola

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rr.numio.ezhuthola.engine.Shortcuts
import com.rr.numio.ezhuthola.engine.UserWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Same Numio palette as the rest of the app screen
private val Bg = Color(0xFF0B0B0B)
private val Card = Color(0xFF1A1A1A)
private val CardBorder = Color(0xFF262626)
private val TextMain = Color(0xFFF2F2F2)
private val TextDim = Color(0xFFA8A8A8)
private val TextFaint = Color(0xFF7A7A7A)
private val Accent = Color(0xFFF5C427)
private val AccentSoft = Color(0x2EF5C427)
private val Ink = Color(0xFF141414)

enum class WordsTab { English, Malayalam, Missing }

/**
 * Words the keyboard already knows from its own lists. Your dictionary hides these (they're
 * kept, just not listed), so the list shows the words that are really yours: names, slang,
 * Manglish. Loaded once, off the main thread.
 */
object KnownWords {
    @Volatile private var english: Set<String>? = null
    @Volatile private var malayalam: Set<String>? = null

    fun english(context: Context): Set<String> =
        english ?: load(context, "en_words.tsv").also { english = it }

    fun malayalam(context: Context): Set<String> =
        malayalam ?: load(context, "ml_words.tsv").also { malayalam = it }

    private fun load(context: Context, name: String): Set<String> =
        runCatching {
            context.assets.open(name).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.substringBefore('\t').trim() }
                    .toHashSet()
            }
        }.getOrDefault(emptySet())
}

/** Your own words (both languages, common ones left out) and the missing list's size. */
fun wordCounts(context: Context): Pair<Int, Int> {
    val shortcuts = LearnedWords.shortcuts(context)
    val en = LearnedWords.dictionary(context, malayalam = false)
        .count { it !in KnownWords.english(context) }
    val ml = LearnedWords.dictionary(context, malayalam = true)
        .count { it !in KnownWords.malayalam(context) || it in shortcuts }
    return (en + ml) to LearnedWords.missing(context).size
}

/** The Words page: English | മലയാളം | Missing. Opened from "Words" on the main screen. */
@Composable
fun WordsScreen(refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(WordsTab.English) }
    var version by remember { mutableIntStateOf(0) }
    val missingCount by produceState(0, refreshKey, version) {
        value = withContext(Dispatchers.IO) { LearnedWords.missing(context).size }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTopBar("Words", onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Card)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TabButton("English", tab == WordsTab.English, Modifier.weight(1f)) { tab = WordsTab.English }
            TabButton("മലയാളം", tab == WordsTab.Malayalam, Modifier.weight(1f)) { tab = WordsTab.Malayalam }
            TabButton("Missing", tab == WordsTab.Missing, Modifier.weight(1f), badge = missingCount) {
                tab = WordsTab.Missing
            }
        }
        when (tab) {
            WordsTab.English -> DictionaryTab(malayalam = false, refreshKey, version) { version++ }
            WordsTab.Malayalam -> DictionaryTab(malayalam = true, refreshKey, version) { version++ }
            WordsTab.Missing -> MissingTab(refreshKey, version) { version++ }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PageTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val u = size.width / 22f
                drawPath(
                    Path().apply { moveTo(14 * u, 4 * u); lineTo(7 * u, 11 * u); lineTo(14 * u, 18 * u) },
                    TextMain,
                    style = Stroke(width = 2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabButton(text: String, on: Boolean, modifier: Modifier, badge: Int = 0, onClick: () -> Unit) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = if (on) Accent else TextDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (badge > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$badge",
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Accent)
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * One language of "Your dictionary" (issue #7): find or add a word, your words as chips
 * (tap to change, × to delete). Words the keyboard already knows are hidden behind "Show".
 * Like before, this only leaves requests; the keyboard applies them (see LearnedWords).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryTab(malayalam: Boolean, refreshKey: Int, outsideChanges: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var input by remember(malayalam) { mutableStateOf("") }
    var typedAs by remember(malayalam) { mutableStateOf("") }      // Malayalam: how you type it
    var editing by remember(malayalam) { mutableStateOf<String?>(null) }
    var error by remember(malayalam) { mutableStateOf<String?>(null) }
    var showAll by remember(malayalam) { mutableStateOf(false) }
    var showCommon by remember(malayalam) { mutableStateOf(false) }

    val words by produceState(emptyList<String>(), refreshKey, outsideChanges, version, malayalam) {
        value = withContext(Dispatchers.IO) { LearnedWords.dictionary(context, malayalam) }
    }
    val known by produceState<Set<String>?>(null, malayalam) {
        value = withContext(Dispatchers.IO) {
            if (malayalam) KnownWords.malayalam(context) else KnownWords.english(context)
        }
    }
    val shortcuts = remember(version, outsideChanges) { LearnedWords.shortcuts(context) }
    val isYours = { w: String -> known?.let { w !in it } ?: true || (malayalam && w in shortcuts) }
    val yours = words.filter(isYours)
    val common = words.filterNot(isYours)

    fun changed() { version++; onChanged() }

    fun save() {
        val word = if (malayalam) UserWords.cleanMalayalam(input) else UserWords.clean(input)
        val manglish = if (malayalam && typedAs.isNotBlank()) Shortcuts.clean(typedAs) else null
        when {
            malayalam && typedAs.isNotBlank() && manglish == null ->
                error = "How you type it: English letters only, no spaces."
            word == null -> error = if (malayalam) {
                "Type one word in Malayalam letters (switch the keyboard to Malayalam)."
            } else {
                "Type one word, without spaces."
            }
            // Already there: fine when you're giving it a "how you type it".
            word in words && word != editing && manglish == null ->
                error = "\"$word\" is already in your words."
            else -> {
                val old = editing
                if (old != null) LearnedWords.change(context, old, word, malayalam)
                else LearnedWords.add(context, word, malayalam)
                if (malayalam) {
                    if (old != null && old != word) LearnedWords.setShortcut(context, old, null)
                    LearnedWords.setShortcut(context, word, manglish)
                }
                input = ""; typedAs = ""; editing = null; error = null
                changed()
            }
        }
    }

    fun edit(word: String) {
        editing = word; input = word; typedAs = shortcuts[word].orEmpty(); error = null
    }

    fun delete(word: String) {
        LearnedWords.remove(context, word, malayalam)
        if (malayalam) LearnedWords.setShortcut(context, word, null)
        if (editing == word) { editing = null; input = ""; typedAs = "" }
        changed()
    }

    // ---- Find or add ----
    if (!malayalam) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WordField(
                value = input,
                onChange = { input = it; error = null },
                placeholder = if (editing != null) "Change \"$editing\"" else "Find or add a word",
                modifier = Modifier.weight(1f),
                onDone = ::save
            )
            Spacer(Modifier.width(8.dp))
            PillButton(if (editing != null) "Save" else "Add", strong = false, onClick = ::save)
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Card)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row {
                WordField(
                    value = input,
                    onChange = { input = it; error = null },
                    placeholder = if (editing != null) "Change \"$editing\"" else "മലയാളം വാക്ക്",
                    modifier = Modifier.weight(1f),
                    onDone = ::save
                )
                Spacer(Modifier.width(8.dp))
                WordField(
                    value = typedAs,
                    onChange = { typedAs = it; error = null },
                    placeholder = "you type…",
                    modifier = Modifier.width(124.dp),
                    onDone = ::save
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Type it the way you do in chats, and it comes first.",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                PillButton(if (editing != null) "Save" else "Add", strong = true, onClick = ::save)
            }
        }
    }
    if (editing != null) {
        Text(
            "Cancel",
            color = TextDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { editing = null; input = ""; typedAs = ""; error = null }
                .padding(vertical = 4.dp, horizontal = 4.dp)
        )
    }
    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }

    // ---- Your words ----
    // Typing in the box also finds: the chips narrow down to the ones that match.
    val query = input.trim().lowercase()
    val queryTyped = typedAs.trim().lowercase()
    val matches = if (editing == null && (query.isNotEmpty() || queryTyped.isNotEmpty())) {
        yours.filter { w ->
            (query.isNotEmpty() && w.lowercase().contains(query)) ||
                    (queryTyped.isNotEmpty() && shortcuts[w]?.contains(queryTyped) == true)
        }
    } else {
        yours
    }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
        Label("YOUR WORDS")
        Spacer(Modifier.width(8.dp))
        Text(
            if (malayalam) "${yours.size}" else "${yours.size} · never autocorrected",
            color = TextFaint,
            fontSize = 13.sp
        )
    }
    when {
        yours.isEmpty() -> Text(
            if (malayalam) "No words yet. Add names and words the keyboard gets wrong."
            else "No words yet. Add names, slang and Manglish you use.",
            color = TextDim, fontSize = 14.sp, lineHeight = 20.sp
        )
        matches.isEmpty() -> Text(
            "Not in your words yet. Tap Add to add it.",
            color = TextDim, fontSize = 14.sp
        )
        else -> {
            val shown = if (showAll || matches.size <= CHIPS_SHOWN) matches else matches.take(CHIPS_SHOWN)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { w ->
                    WordChip(w, if (malayalam) shortcuts[w] else null, dim = false, onEdit = { edit(w) }, onDelete = { delete(w) })
                }
            }
            if (matches.size > CHIPS_SHOWN) {
                TextLink(if (showAll) "Show fewer" else "Show all ${matches.size}") { showAll = !showAll }
            }
        }
    }

    // ---- Words the keyboard knew anyway ----
    if (common.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Card)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (malayalam) "Picked from suggestions" else "Common words you kept",
                        color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (malayalam) "${common.size} words Ezhuthola learned, hidden"
                        else "${common.size} normal English words, hidden",
                        color = TextDim, fontSize = 13.sp
                    )
                }
                TextLink(if (showCommon) "Hide" else "Show") { showCommon = !showCommon }
            }
            if (showCommon) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    common.forEach { w ->
                        WordChip(w, if (malayalam) shortcuts[w] else null, dim = true, onEdit = { edit(w) }, onDelete = { delete(w) })
                    }
                }
            }
        }
    }
}

private const val CHIPS_SHOWN = 60

/**
 * English words you typed that Ezhuthola didn't know, most typed first.
 * Add keeps the word (suggested, never corrected); ✕ hides it for good.
 */
@Composable
private fun MissingTab(refreshKey: Int, outsideChanges: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    val words by produceState(emptyList<Pair<String, Int>>(), refreshKey, outsideChanges, version) {
        value = withContext(Dispatchers.IO) { LearnedWords.missing(context) }
    }
    fun changed() { version++; onChanged() }

    Text(
        if (words.isEmpty()) {
            "English words you type that Ezhuthola doesn't know yet show up here. Type one 3 times and it's learned by itself, or add it here now."
        } else {
            "English words Ezhuthola didn't know. Typed 3 times, a word is learned by itself. Add the real ones now, dismiss the typos."
        },
        color = TextDim,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    if (words.isEmpty()) return

    val shown = if (showAll) words else words.take(MISSING_SHOWN)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
    ) {
        shown.forEachIndexed { i, (word, count) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(word, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text("$count×", color = TextFaint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                PillButton("Add", strong = false, small = true) {
                    LearnedWords.add(context, word); changed()
                }
                CloseButton("Dismiss $word") { LearnedWords.dismiss(context, word); changed() }
            }
            if (i < shown.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242424)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (words.size > MISSING_SHOWN) {
            OutlineButton(
                if (showAll) "Show fewer" else "Show ${words.size - MISSING_SHOWN} more",
                TextMain,
                Modifier.weight(1f)
            ) { showAll = !showAll }
        }
        OutlineButton("Clear list", TextDim, Modifier.weight(1f)) {
            LearnedWords.clearMissing(context); changed()
        }
    }
}

private const val MISSING_SHOWN = 15

// ---- Pieces ----------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
}

@Composable
private fun WordField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = TextDim) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
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
}

@Composable
private fun PillButton(text: String, strong: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (strong) Ink else Accent,
        fontSize = if (small) 14.sp else 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(if (small) 12.dp else 14.dp))
            .background(if (strong) Accent else AccentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 16.dp else 20.dp, vertical = if (small) 8.dp else 14.dp)
    )
}

@Composable
private fun OutlineButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, Color(0xFF333333), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    )
}

/** One of your words as a small pill: tap the word to change it, × to delete it. */
@Composable
private fun WordChip(word: String, typedAs: String?, dim: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = "Change $word", onClick = onEdit)
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(word, color = if (dim) TextDim else TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!typedAs.isNullOrEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(typedAs, color = TextDim, fontSize = 12.sp)
            }
        }
        CloseButton("Delete $word", onClick = onDelete)
    }
}

@Composable
private fun CloseButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 40.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = 1.6.dp.toPx()
            val ink = Color(0xFF8C8C8C)
            drawLine(ink, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
