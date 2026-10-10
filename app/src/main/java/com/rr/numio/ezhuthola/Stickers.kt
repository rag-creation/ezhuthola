package com.rr.numio.ezhuthola

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.rr.numio.ezhuthola.engine.StickerMath
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Stickers live in the app's private storage (the "stickers" folder, as .webp files),
 * 512×512 like WhatsApp wants. Nothing is downloaded: they're made on the phone,
 * so Ezhuthola still needs no permissions.
 */
class StickerStore(private val context: Context) {

    val dir: File get() = File(context.filesDir, "stickers").apply { mkdirs() }

    /** Newest first. */
    fun list(): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".webp") }
            .sortedByDescending { it.lastModified() }

    /** First time only: a few Ezhuthola text stickers, so the tab isn't empty. */
    fun addSamplesOnce() {
        val marker = File(dir, ".samples2")
        if (marker.exists()) return
        Samples.forEachIndexed { i, (text, color) ->
            val art = StickerArt.render(context, subject = null, caption = Caption(text, font = 0, color = color))
            writeWebp(art, File(dir, "ezhuthola_$i.webp"))
        }
        marker.writeText("1")
    }

    /** Saves a finished sticker and returns its file. */
    fun add(bitmap: Bitmap): File {
        val file = File(dir, "my_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.webp")
        writeWebp(bitmap, file)
        return file
    }

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    companion object {
        private val Samples = listOf(
            "പൊളി" to 0xFFFFD23F.toInt(),
            "കിടു" to 0xFF4FC3F7.toInt(),
            "ശോ!" to 0xFFFF7043.toInt(),
            "എന്താടാ?" to 0xFF81C784.toInt(),
        )

        /** WhatsApp wants 100 KB or less. Text stays lossless (sharp); photos step down in quality until they fit. */
        @Suppress("DEPRECATION") // plain WEBP is the only choice before Android 11
        fun writeWebp(bitmap: Bitmap, file: File, maxBytes: Long = 100_000) {
            val lossless = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            val lossy = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            file.outputStream().use { bitmap.compress(lossless, 100, it) }
            var quality = 90
            while (file.length() > maxBytes && quality >= 30) {
                file.outputStream().use { bitmap.compress(lossy, quality, it) }
                quality -= 10
            }
        }
    }
}

/** Text on a sticker. Position is the centre of the text, as a fraction of the sticker (0..1). */
data class Caption(
    val text: String,
    val font: Int = 0,
    val color: Int = 0xFFFFD23F.toInt(),
    val size: Float = 150f,   // in sticker pixels (the sticker is 512)
    val x: Float = 0.5f,
    val y: Float = 0.5f,
)

/** The Malayalam fonts bundled for stickers (all SIL Open Font License, see licenses/). */
object StickerFonts {
    class Font(val name: String, val asset: String, val weight: Int? = null)

    val all = listOf(
        Font("Baloo", "fonts/BalooChettan2.ttf", weight = 800),
        Font("Manjari", "fonts/Manjari-Bold.ttf"),
        Font("Gayathri", "fonts/Gayathri-Bold.ttf"),
        Font("Chilanka", "fonts/Chilanka-Regular.ttf"),
    )

    private val cache = HashMap<Int, Typeface>()

    fun typeface(context: Context, index: Int): Typeface = synchronized(cache) {
        cache.getOrPut(index) {
            val font = all.getOrElse(index) { all[0] }
            runCatching {
                Typeface.Builder(context.assets, font.asset).apply {
                    font.weight?.let { setFontVariationSettings("'wght' $it") }
                }.build()
            }.getOrNull() ?: Typeface.DEFAULT_BOLD
        }
    }
}

/** Draws stickers: cut-out photo with a white border and soft shadow, plus an outlined caption. */
object StickerArt {
    const val SIZE = 512
    const val BORDER_THIN = 8f          // white outline around a cut-out photo
    const val BORDER_THICK = 15f
    private const val TEXT_WIDTH = 470

    /**
     * A cut-out made ready for a sticker: trimmed to what's left (the transparent parts of [cut]),
     * scaled to fill the sticker, with a white border (0 = none) and shadow. Null if nothing is left.
     */
    fun cutOut(cut: Bitmap, border: Float = BORDER_THIN): Bitmap? {
        val w = cut.width
        val h = cut.height
        val box = StickerMath.bounds(alphaOf(cut), w, h) ?: return null
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        val (left, top, scale) = StickerMath.fit(bw, bh, SIZE, if (border > 0f) border.toInt() + 16 else 8).toList()

        val subject = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(subject).drawBitmap(
            cut,
            Rect(box[0], box[1], box[2], box[3]),
            RectF(left, top, left + bw * scale, top + bh * scale),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        if (border <= 0f) return subject

        // White border around the outside edge only (holes inside are filled first),
        // so letters and rings don't each get their own border.
        val alpha = alphaOf(subject)
        val outer = StickerMath.fillHoles(alpha, SIZE, SIZE)
        val ring = StickerMath.outline(outer, SIZE, SIZE, border)
        // Holes stay see-through: no white fill inside a ring or between letters.
        for (i in ring.indices) {
            if (outer[i].toInt() == -1 && (alpha[i].toInt() and 0xFF) <= 16) ring[i] = alpha[i]
        }
        val ringBitmap = Bitmap.createBitmap(
            IntArray(SIZE * SIZE) { ((ring[it].toInt() and 0xFF) shl 24) or 0xFFFFFF },
            SIZE, SIZE, Bitmap.Config.ARGB_8888
        )
        // Soft shadow under the border.
        val offset = IntArray(2)
        val shadow = ringBitmap.extractAlpha(
            Paint().apply { maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL) }, offset
        )
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawBitmap(shadow, offset[0].toFloat(), offset[1] + 4f, Paint().apply { color = 0x59000000 })
            drawBitmap(ringBitmap, 0f, 0f, null)
            drawBitmap(subject, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        shadow.recycle()
        ringBitmap.recycle()
        subject.recycle()
        return out
    }

    /** The finished 512×512 sticker: the cut-out (if any) with the caption (if any) on top. */
    fun render(context: Context, subject: Bitmap?, caption: Caption?): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        subject?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        caption?.let { drawCaption(context, canvas, it) }
        return out
    }

    /** Big text with a thick white outline and a soft shadow: the classic sticker look. */
    fun drawCaption(context: Context, canvas: Canvas, caption: Caption) {
        val text = caption.text.trim()
        if (text.isEmpty()) return
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = StickerFonts.typeface(context, caption.font)
            color = caption.color
            textSize = caption.size
        }
        // Shrink until every word fits on a line and all lines fit on the sticker.
        fun fits(layout: StaticLayout) =
            layout.height <= SIZE - 40 &&
                text.split(' ', '\n').all { fill.measureText(it) <= TEXT_WIDTH }
        var layout = layoutOf(text, fill)
        while (!fits(layout) && fill.textSize > 28f) {
            fill.textSize -= 6f
            layout = layoutOf(text, fill)
        }
        val stroke = TextPaint(fill).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = fill.textSize * 0.17f
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
            setShadowLayer(fill.textSize * 0.07f, 0f, fill.textSize * 0.03f, 0x66000000)
        }
        val strokeLayout = layoutOf(text, stroke)
        // Keep the whole caption on the sticker, wherever it was dragged.
        val half = layout.height / 2f
        val cx = caption.x * SIZE
        val cy = (caption.y * SIZE).coerceIn(half + 8f, SIZE - half - 8f)
        canvas.save()
        canvas.translate(cx - TEXT_WIDTH / 2f, cy - half)
        strokeLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutOf(text: String, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, TEXT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()

    private fun alphaOf(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return ByteArray(w * h) { (pixels[it] ushr 24).toByte() }
    }
}

/** What happened when a sticker was tapped. */
enum class StickerResult { STICKER, IMAGE, NOT_SUPPORTED }

/**
 * Sends a sticker the way Gboard does: hand the app a content:// link to the file
 * (commitContent). WhatsApp asks for "image/webp.wasticker" and shows it as a real sticker;
 * other apps that take images get a normal WebP picture.
 */
object StickerSender {
    private const val WHATSAPP_STICKER = "image/webp.wasticker"

    fun send(context: Context, ic: InputConnection?, info: EditorInfo?, file: File): StickerResult {
        if (ic == null || info == null) return StickerResult.NOT_SUPPORTED
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        val mime = listOf(WHATSAPP_STICKER, "image/webp").firstOrNull { wanted ->
            accepted.any { ClipDescription.compareMimeTypes(wanted, it) }
        } ?: return StickerResult.NOT_SUPPORTED

        val uri = FileProvider.getUriForFile(context, context.packageName + ".stickers", file)
        val content = InputContentInfoCompat(uri, ClipDescription("Sticker", arrayOf(mime)), null)
        val sent = InputConnectionCompat.commitContent(
            ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
        )
        return when {
            !sent -> StickerResult.NOT_SUPPORTED
            mime == WHATSAPP_STICKER -> StickerResult.STICKER
            else -> StickerResult.IMAGE
        }
    }
}

/**
 * "Add your own": stickers people already have (pictures, or a WhatsApp-style .wastickers / .zip
 * pack someone shared). They're added as they are, fitted to 512×512 with transparency kept.
 * The files come in through Android's picker, so Ezhuthola still needs no permissions.
 */
object StickerImport {
    private const val MAX_STICKERS = 120
    private const val MAX_ENTRY_BYTES = 5_000_000

    /** Pictures picked from the gallery or files. Returns how many were added. */
    fun pictures(context: Context, uris: List<Uri>): Int {
        val store = StickerStore(context)
        var added = 0
        for (uri in uris.take(MAX_STICKERS)) {
            val picture = decode(context, uri, maxSide = 1024) ?: continue
            store.add(fitted(picture))
            added++
        }
        return added
    }

    /** Files picked in the Files app: sticker packs (.wastickers / .zip) or single sticker pictures. */
    fun files(context: Context, uris: List<Uri>): Int {
        var added = 0
        for (uri in uris.take(MAX_STICKERS)) {
            val isZip = context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            } ?: false
            added += if (isZip) pack(context, uri) else pictures(context, listOf(uri))
        }
        return added
    }

    /** A sticker pack: a .wastickers or .zip file full of .webp / .png stickers. */
    fun pack(context: Context, uri: Uri): Int {
        val store = StickerStore(context)
        var added = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (added < MAX_STICKERS) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    val isPicture = name.endsWith(".webp") || name.endsWith(".png") ||
                        name.endsWith(".jpg") || name.endsWith(".jpeg")
                    if (entry.isDirectory || !isPicture || name.substringAfterLast('/').startsWith("tray")) continue
                    val bytes = readLimited(zip) ?: continue
                    val picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    if (max(picture.width, picture.height) < 128) continue // pack icons, not stickers
                    store.add(fitted(picture))
                    added++
                }
            }
        }
        return added
    }

    /** Reads one zip entry, refusing anything unreasonably big. */
    private fun readLimited(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray()
    }

    /** Fits a picture inside the 512 sticker square, centred, keeping its see-through parts. */
    fun fitted(picture: Bitmap): Bitmap {
        val size = StickerArt.SIZE
        val scale = size.toFloat() / max(picture.width, picture.height)
        val w = picture.width * scale
        val h = picture.height * scale
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            picture, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /** Opens a picture the right way up, no bigger than [maxSide]. Animated stickers give their first frame. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    return@runCatching if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > maxSide) {
                val s = maxSide.toFloat() / big
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
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
    }.getOrNull()
}
