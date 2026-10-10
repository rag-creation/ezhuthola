package com.rr.numio.ezhuthola

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rr.numio.ezhuthola.engine.StickerMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Make a sticker": pick a photo, cut out what you want (trace, magic wand, eraser, shapes),
 * add a Malayalam caption, save. Everything happens on the phone; no permissions needed
 * (Android's photo picker hands over just the one photo you choose).
 */
class StickerMakerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StickerMaker(onFinish = ::finish) }
    }
}

// ---- Look (same palette as the app screen) ----------------------------------

private val Bg = Color(0xFF0B0B0B)
private val Card = Color(0xFF1A1A1A)
private val CardBorder = Color(0xFF2A2A2A)
private val TextMain = Color(0xFFF2F2F2)
private val TextDim = Color(0xFFA8A8A8)
private val Accent = Color(0xFFF5C427)

private const val WORK = 1024           // the photo is cut at 1024×1024, the sticker is 512×512
private const val MAX_UNDO = 12

private enum class Stage { PICK, CUT, CAPTION }
private enum class Tool(val label: String, val tip: String) {
    TRACE("Trace", "Draw around what you want to keep"),
    WAND("Magic", "Tap the background to remove it"),
    ERASE("Erase", "Rub out what you don't want"),
    RESTORE("Restore", "Paint back what you removed"),
    SHAPE("Shape", "Cut into a shape"),
}
private enum class Shape(val label: String) { CIRCLE("Circle"), ROUNDED("Square"), HEART("Heart"), STAR("Star") }

private val CaptionColors = listOf(
    0xFFFFD23F, 0xFFFFFFFF, 0xFF111111, 0xFFFF5252, 0xFF4FC3F7, 0xFF81C784, 0xFFFF80AB, 0xFFB388FF
).map { it.toInt() }

/**
 * The picture being cut and what's been cut away. The mask is a plain byte array
 * (0 = cut away, 255 = kept) so every step is simple maths we control; Android only
 * draws the finger paths. [preview] is a fresh bitmap after every change.
 */
private class Cutter(val work: Bitmap) {
    private val pixels: IntArray = IntArray(WORK * WORK).also { work.getPixels(it, 0, WORK, 0, 0, WORK, WORK) }
    /** Transparent parts of the picture (the empty bars, or a PNG that's already cut out) can't be kept. */
    private val photoAlpha = ByteArray(WORK * WORK) { (pixels[it] ushr 24).toByte() }
    val mask: ByteArray = photoAlpha.copyOf()
    private val history = ArrayDeque<ByteArray>()

    var preview: Bitmap = makePreview()
        private set

    val canUndo get() = history.isNotEmpty()

    fun redraw() { preview = makePreview() }

    /** Kept parts bright, removed parts faint (so you can see what Restore would bring back). */
    private fun makePreview(): Bitmap {
        val out = IntArray(WORK * WORK)
        for (i in out.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 0) continue
            val keep = mask[i].toInt() and 0xFF
            val shown = (a * (60 + (195 * keep) / 255)) / 255
            out[i] = (shown shl 24) or (p and 0xFFFFFF)
        }
        return Bitmap.createBitmap(out, WORK, WORK, Bitmap.Config.ARGB_8888)
    }

    /** The cut-out as a normal picture: the photo with everything removed made transparent. */
    fun cutBitmap(): Bitmap {
        val out = IntArray(WORK * WORK) { i ->
            val p = pixels[i]
            val a = ((p ushr 24) * (mask[i].toInt() and 0xFF)) / 255
            (a shl 24) or (p and 0xFFFFFF)
        }
        return Bitmap.createBitmap(out, WORK, WORK, Bitmap.Config.ARGB_8888)
    }

    private fun saveUndo() {
        history.addLast(mask.copyOf())
        while (history.size > MAX_UNDO) history.removeFirst()
    }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        last.copyInto(mask)
    }

    fun reset() {
        saveUndo()
        photoAlpha.copyInto(mask)
    }

    /** Draws [draw] on a blank picture and returns its coverage, 0..255 per pixel. */
    private fun coverage(draw: (android.graphics.Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(WORK, WORK, Bitmap.Config.ARGB_8888)
        draw(android.graphics.Canvas(bitmap))
        val drawn = IntArray(WORK * WORK)
        bitmap.getPixels(drawn, 0, WORK, 0, 0, WORK, WORK)
        bitmap.recycle()
        return ByteArray(WORK * WORK) { (drawn[it] ushr 24).toByte() }
    }

    /** Keep only what's inside [path] (trace, shapes). Returns how many pixels are left. */
    fun keepInside(path: android.graphics.Path): Int {
        saveUndo()
        val inside = coverage { it.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }) }
        var left = 0
        for (i in mask.indices) {
            val v = ((mask[i].toInt() and 0xFF) * (inside[i].toInt() and 0xFF)) / 255
            mask[i] = v.toByte()
            if (v > 0) left++
        }
        return left
    }

    /** Eraser (keep = false) or restore brush (keep = true). */
    fun brush(path: android.graphics.Path, width: Float, keep: Boolean) {
        saveUndo()
        val stroke = coverage {
            it.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.BLACK
            })
        }
        for (i in mask.indices) {
            val m = mask[i].toInt() and 0xFF
            val s = stroke[i].toInt() and 0xFF
            if (s == 0) continue
            mask[i] = if (keep) min(max(m, s), photoAlpha[i].toInt() and 0xFF).toByte()
                      else ((m * (255 - s)) / 255).toByte()
        }
    }

    fun wand(x: Int, y: Int, tolerance: Int): Int {
        saveUndo()
        return StickerMath.magicWand(pixels, mask, WORK, WORK, x, y, tolerance)
    }

    /** Shape centred on what's kept so far, as big as it. */
    fun shape(shape: Shape): Int {
        val box = StickerMath.bounds(mask, WORK, WORK) ?: return 0
        val cx = (box[0] + box[2]) / 2f
        val cy = (box[1] + box[3]) / 2f
        val r = max(box[2] - box[0], box[3] - box[1]) / 2f
        return keepInside(shapePath(shape, cx, cy, r))
    }
}

private fun shapePath(shape: Shape, cx: Float, cy: Float, r: Float) = android.graphics.Path().apply {
    when (shape) {
        Shape.CIRCLE -> addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        Shape.ROUNDED -> addRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.3f, r * 0.3f, android.graphics.Path.Direction.CW)
        Shape.HEART -> {
            moveTo(cx, cy + r * 0.95f)
            cubicTo(cx - r * 1.35f, cy + r * 0.05f, cx - r * 0.75f, cy - r * 1.15f, cx, cy - r * 0.45f)
            cubicTo(cx + r * 0.75f, cy - r * 1.15f, cx + r * 1.35f, cy + r * 0.05f, cx, cy + r * 0.95f)
            close()
        }
        Shape.STAR -> {
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) r else r * 0.48f
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val x = cx + radius * cos(angle).toFloat()
                val y = cy + radius * sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}

/** The chosen photo, upright, fitted inside a 1024 square (transparent around it). */
private fun loadPhoto(context: Context, uri: Uri): Bitmap? = runCatching {
    val photo: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > 2048) {
                val s = 2048f / big
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
    }
    val work = Bitmap.createBitmap(WORK, WORK, Bitmap.Config.ARGB_8888)
    val scale = WORK.toFloat() / max(photo.width, photo.height)
    val w = photo.width * scale
    val h = photo.height * scale
    android.graphics.Canvas(work).drawBitmap(
        photo, null, RectF((WORK - w) / 2, (WORK - h) / 2, (WORK + w) / 2, (WORK + h) / 2),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    work
}.getOrNull()

// ---- Screen ---------------------------------------------------------------------

@Composable
private fun StickerMaker(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.PICK) }
    var cutter by remember { mutableStateOf<Cutter?>(null) }
    var cutPicture by remember { mutableStateOf<Bitmap?>(null) }   // the photo with the cut applied
    var border by remember { mutableFloatStateOf(StickerArt.BORDER_THIN) }
    var subject by remember { mutableStateOf<Bitmap?>(null) }      // trimmed, scaled, with border
    // Re-make the bordered cut-out when the border changes.
    androidx.compose.runtime.LaunchedEffect(cutPicture, border) {
        val picture = cutPicture
        subject = if (picture == null) null else withContext(Dispatchers.Default) { StickerArt.cutOut(picture, border) }
    }
    var caption by remember { mutableStateOf(Caption("", size = 110f, y = 0.84f)) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val work = withContext(Dispatchers.Default) { loadPhoto(context, uri)?.let { Cutter(it) } }
            busy = false
            if (work == null) {
                Toast.makeText(context, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
            } else {
                cutter = work
                stage = Stage.CUT
            }
        }
    }

    BackHandler(enabled = stage != Stage.PICK) {
        stage = if (stage == Stage.CAPTION && cutter != null) Stage.CUT else Stage.PICK
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        TopBar(
            title = when (stage) {
                Stage.PICK -> "Make a sticker"
                Stage.CUT -> "Cut it out"
                Stage.CAPTION -> "Add text"
            },
            onBack = {
                when (stage) {
                    Stage.PICK -> onFinish()
                    Stage.CUT -> stage = Stage.PICK
                    Stage.CAPTION -> stage = if (cutter != null) Stage.CUT else Stage.PICK
                }
            }
        )
        when (stage) {
            Stage.PICK -> PickStage(
                busy = busy,
                onPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onTextOnly = {
                    cutter = null
                    cutPicture = null
                    caption = Caption("", size = 150f, y = 0.5f)
                    stage = Stage.CAPTION
                }
            )
            Stage.CUT -> cutter?.let { c ->
                CutStage(c, busy = busy, onNext = {
                    busy = true
                    scope.launch {
                        val picture = withContext(Dispatchers.Default) {
                            c.cutBitmap().takeIf { StickerArt.cutOut(it, 0f) != null }
                        }
                        busy = false
                        if (picture == null) {
                            Toast.makeText(context, "Nothing is left. Tap Undo or Reset.", Toast.LENGTH_SHORT).show()
                        } else {
                            cutPicture = picture
                            stage = Stage.CAPTION
                        }
                    }
                })
            }
            Stage.CAPTION -> CaptionStage(
                subject = subject,
                border = if (cutPicture != null) border else null,
                onBorder = { border = it },
                caption = caption,
                onCaption = { caption = it },
                onSave = { art ->
                    StickerStore(context).add(art)
                    Toast.makeText(context, "Saved! It's in Ezhuthola's sticker tab.", Toast.LENGTH_LONG).show()
                    onFinish()
                }
            )
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val u = size.width / 24f
                val stroke = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(Path().apply {
                    moveTo(15 * u, 5 * u); lineTo(8 * u, 12 * u); lineTo(15 * u, 19 * u)
                }, TextMain, style = stroke)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PickStage(busy: Boolean, onPhoto: () -> Unit, onTextOnly: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BigChoice("From a photo", "Cut out a face, a pet, anything. Then add Malayalam text.", enabled = !busy, onClick = onPhoto)
        BigChoice("Text only", "Big Malayalam words with a white outline, like പൊളി.", enabled = !busy, onClick = onTextOnly)
        if (busy) Text("Opening photo…", color = Accent, fontSize = 14.sp)
        Text(
            "Tip: if your phone's Gallery can cut a person out of a photo (Samsung, Pixel, Xiaomi and others can), " +
                "save that cut-out and pick it here. Ezhuthola keeps the cut and adds the white border.",
            color = TextDim, fontSize = 13.sp, lineHeight = 18.sp
        )
        Text(
            "Your photos never leave your phone. Ezhuthola has no internet permission.",
            color = TextDim, fontSize = 13.sp, lineHeight = 18.sp
        )
    }
}

@Composable
private fun BigChoice(title: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(18.dp)
    ) {
        Text(title, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text, color = TextDim, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

// ---- Cutting --------------------------------------------------------------------

@Composable
private fun CutStage(c: Cutter, busy: Boolean, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.WAND) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var tolerance by remember { mutableFloatStateOf(32f) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    /** Runs a cut off the main thread, then shows the new preview and what happened. */
    fun change(block: () -> String?) {
        working = true
        scope.launch {
            status = withContext(Dispatchers.Default) {
                runCatching { block().also { c.redraw() } }.getOrElse { "Something went wrong: ${it.message}" }
            }
            working = false
            version++
        }
    }

    Column(Modifier.fillMaxSize()) { // no page scrolling here: every finger on the photo is for cutting
        CutCanvas(c, version, tool, brushDp, enabled = !working && !busy) { action ->
            when (action) {
                is CutAction.Trace -> change {
                    val left = c.keepInside(action.path)
                    if (left == 0) "Nothing inside your line. Tap Undo." else "Kept what's inside your line"
                }
                is CutAction.Brush -> change {
                    c.brush(action.path, action.width, keep = tool == Tool.RESTORE)
                    if (tool == Tool.RESTORE) "Restored" else "Erased"
                }
                is CutAction.Tap -> if (tool == Tool.WAND) change {
                    val n = c.wand(action.x, action.y, tolerance.toInt())
                    if (n == 0) "That spot is already removed" else "Removed ${"%,d".format(n)} pixels"
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tool.entries.forEach { t -> Chip(t.label, selected = t == tool) { tool = t; status = null } }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            status ?: (tool.tip + if (tool != Tool.SHAPE) " · two fingers to zoom" else ""),
            color = if (status != null) Accent else TextDim, fontSize = 13.sp
        )
        when (tool) {
            Tool.WAND -> LabeledSlider("How much", tolerance, 5f..90f) { tolerance = it }
            Tool.ERASE, Tool.RESTORE -> LabeledSlider("Brush size", brushDp, 10f..70f) { brushDp = it }
            Tool.SHAPE -> Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Shape.entries.forEach { s -> Chip(s.label, selected = false) { change { c.shape(s); "${s.label} cut" } } }
            }
            Tool.TRACE -> Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Undo", selected = false, enabled = c.canUndo && !working) { change { c.undo(); "Undone" } }
            Chip("Reset", selected = false, enabled = !working) { change { c.reset(); "Back to the whole photo" } }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNext,
                enabled = !working && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
            ) { Text(if (busy) "Working…" else "Next", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private sealed interface CutAction {
    class Trace(val path: android.graphics.Path) : CutAction
    class Brush(val path: android.graphics.Path, val width: Float) : CutAction
    class Tap(val x: Int, val y: Int) : CutAction
}

/**
 * The photo you're cutting. One finger uses the tool; two fingers zoom and move,
 * so you can get close for small details.
 */
@Composable
private fun CutCanvas(
    c: Cutter,
    version: Int,
    tool: Tool,
    brushDp: Float,
    enabled: Boolean,
    onAction: (CutAction) -> Unit
) {
    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }   // finger path, in screen pixels
    val image = remember(version) { c.preview.asImageBitmap() }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2B2B2B))
    ) {
        val view = with(density) { maxWidth.toPx() }
        val base = view / WORK

        fun toWork(p: Offset) = Offset((p.x - pan.x) / (base * zoom), (p.y - pan.y) / (base * zoom))
        fun clampPan(p: Offset): Offset {
            val min = view - view * zoom
            return Offset(p.x.coerceIn(min, 0f), p.y.coerceIn(min, 0f))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(tool, enabled, brushDp) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var multi = false
                        stroke.clear()
                        stroke.add(down.position)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                multi = true
                                stroke.clear()
                                val centroid = event.calculateCentroid()
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                                val factor = newZoom / zoom
                                zoom = newZoom
                                pan = clampPan((pan - centroid) * factor + centroid + event.calculatePan())
                                event.changes.forEach { it.consume() }
                            } else if (!multi && pressed == 1) {
                                event.changes.firstOrNull { it.pressed }?.let {
                                    stroke.add(it.position)
                                    it.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!multi && enabled && stroke.isNotEmpty()) {
                            val points = stroke.map(::toWork)
                            val moved = stroke.size > 3 &&
                                (stroke.last() - stroke.first()).getDistance() + pathLength(stroke) > 24f
                            when {
                                tool == Tool.WAND && !moved ->
                                    onAction(CutAction.Tap(points.first().x.toInt(), points.first().y.toInt()))
                                tool == Tool.TRACE && moved && points.size > 5 ->
                                    onAction(CutAction.Trace(points.toAndroidPath(close = true)))
                                (tool == Tool.ERASE || tool == Tool.RESTORE) -> {
                                    val widthPx = with(density) { brushDp.dp.toPx() } / (base * zoom)
                                    val path = if (points.size == 1) points.toAndroidPath(close = false).apply {
                                        lineTo(points[0].x + 0.1f, points[0].y)
                                    } else points.toAndroidPath(close = false)
                                    onAction(CutAction.Brush(path, widthPx))
                                }
                            }
                        }
                        stroke.clear()
                    }
                }
        ) {
            drawChecker(size)
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom * base, zoom * base, pivot = Offset.Zero)
            }) {
                drawImage(image, dstSize = IntSize(WORK, WORK), dstOffset = IntOffset.Zero)
            }
            if (stroke.size > 1) {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                    if (tool == Tool.TRACE) close()
                }
                val width = when (tool) {
                    Tool.ERASE, Tool.RESTORE -> with(density) { brushDp.dp.toPx() }
                    else -> with(density) { 3.dp.toPx() }
                }
                val color = when (tool) {
                    Tool.ERASE -> Color(0x99FF5252)
                    Tool.RESTORE -> Color(0x9981C784)
                    else -> Accent
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (!enabled) {
            Text(
                "…", color = Accent, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            )
        }
    }
}

private fun pathLength(points: List<Offset>): Float {
    var total = 0f
    for (i in 1 until points.size) total += (points[i] - points[i - 1]).getDistance()
    return total
}

private fun List<Offset>.toAndroidPath(close: Boolean) = android.graphics.Path().apply {
    moveTo(this@toAndroidPath[0].x, this@toAndroidPath[0].y)
    for (i in 1 until size) lineTo(this@toAndroidPath[i].x, this@toAndroidPath[i].y)
    if (close) close()
}

/** Grey checks: the usual "this part is see-through" background. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChecker(area: Size) {
    val cell = 16.dp.toPx()
    var y = 0f
    var row = 0
    while (y < area.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < area.width) {
            drawRect(Color(0xFF3A3A3A), Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

// ---- Caption ----------------------------------------------------------------------

@Composable
private fun CaptionStage(
    subject: Bitmap?,
    border: Float?,              // null: text-only sticker, no border choice
    onBorder: (Float) -> Unit,
    caption: Caption,
    onCaption: (Caption) -> Unit,
    onSave: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val art = remember(subject, caption) { StickerArt.render(context, subject, caption) }
    val image = remember(art) { art.asImageBitmap() }
    val current by androidx.compose.runtime.rememberUpdatedState(caption)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2B2B2B))
        ) {
            val side = with(LocalDensity.current) { maxWidth.toPx() }
            Canvas(Modifier.fillMaxSize()) { drawChecker(size) }
            Image(
                image, contentDescription = "Sticker preview",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Drag to move the text.
                        detectDragGestures { change, drag ->
                            change.consume()
                            val c = current
                            onCaption(
                                c.copy(
                                    x = (c.x + drag.x / side).coerceIn(0.1f, 0.9f),
                                    y = (c.y + drag.y / side).coerceIn(0.05f, 0.95f)
                                )
                            )
                        }
                    }
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = caption.text,
            onValueChange = { onCaption(caption.copy(text = it.take(40))) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type text (Manglish works: polichu → പൊളിച്ചു)", color = TextDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                focusedBorderColor = Accent, unfocusedBorderColor = CardBorder, cursorColor = Accent
            ),
            maxLines = 2
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StickerFonts.all.forEachIndexed { i, font ->
                val family = remember(i) { FontFamily(StickerFonts.typeface(context, i)) }
                Chip("മലയാളം", selected = caption.font == i, fontFamily = family, description = font.name) {
                    onCaption(caption.copy(font = i))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CaptionColors.forEach { color ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            if (caption.color == color) 3.dp else 1.dp,
                            if (caption.color == color) Accent else CardBorder,
                            CircleShape
                        )
                        .clickable { onCaption(caption.copy(color = color)) }
                )
            }
        }
        LabeledSlider("Text size", caption.size, 50f..220f) { onCaption(caption.copy(size = it)) }
        if (border != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Border", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                listOf("None" to 0f, "Thin" to StickerArt.BORDER_THIN, "Thick" to StickerArt.BORDER_THICK).forEach { (label, size) ->
                    Chip(label, selected = border == size) { onBorder(size) }
                }
            }
        }
        Text("Drag the text on the sticker to move it.", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        val empty = subject == null && caption.text.isBlank()
        Button(
            onClick = { onSave(art) },
            enabled = !empty,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Save sticker", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(20.dp))
    }
}

// ---- Small pieces ------------------------------------------------------------------

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    fontFamily: FontFamily? = null,
    description: String? = null,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Card)
            .border(1.dp, if (selected) Accent else CardBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = if (!enabled) TextDim.copy(alpha = 0.5f) else if (selected) Accent else TextMain,
            fontSize = if (fontFamily != null) 18.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = fontFamily
        )
        if (description != null) Text(description, color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = CardBorder)
        )
    }
}
