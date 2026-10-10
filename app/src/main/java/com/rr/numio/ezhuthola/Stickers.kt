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
import java.io.File

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
        val file = File(dir, "my_${System.currentTimeMillis()}.webp")
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
    private const val BORDER = 14f      // white outline around a cut-out photo
    private const val MARGIN = 30       // room for the border and its shadow
    private const val TEXT_WIDTH = 470

    /**
     * A cut-out made ready for a sticker: everything outside [mask] removed, trimmed to what's
     * left, scaled to fill the sticker, with a white border and shadow. Null if nothing is left.
     *
     * @param work the picture being cut (any size)
     * @param mask ALPHA_8, same size: 0 = cut away
     */
    fun cutOut(work: Bitmap, mask: Bitmap, border: Boolean = true): Bitmap? {
        val w = work.width
        val h = work.height
        val cut = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(cut).apply {
            drawBitmap(work, 0f, 0f, null)
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        val box = StickerMath.bounds(alphaOf(cut), w, h) ?: return null
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        val (left, top, scale) = StickerMath.fit(bw, bh, SIZE, if (border) MARGIN else 8).toList()

        val subject = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(subject).drawBitmap(
            cut,
            Rect(box[0], box[1], box[2], box[3]),
            RectF(left, top, left + bw * scale, top + bh * scale),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        cut.recycle()
        if (!border) return subject

        // White border: the shape grown by BORDER pixels.
        val ring = StickerMath.outline(alphaOf(subject), SIZE, SIZE, BORDER)
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
