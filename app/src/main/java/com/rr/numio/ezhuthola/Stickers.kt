package com.rr.numio.ezhuthola

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * Stickers live in the app's private storage (files/stickers/*.webp), 512×512 like WhatsApp wants.
 * Nothing is downloaded: they're made on the phone, so Ezhuthola still needs no permissions.
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
        val marker = File(dir, ".samples")
        if (marker.exists()) return
        Samples.forEachIndexed { i, (text, color) ->
            save(renderTextSticker(text, color), File(dir, "ezhuthola_$i.webp"))
        }
        marker.writeText("1")
    }

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    companion object {
        const val SIZE = 512

        private val Samples = listOf(
            "പൊളി" to 0xFFFFD23F.toInt(),
            "കിടു" to 0xFF4FC3F7.toInt(),
            "ശോ!" to 0xFFFF7043.toInt(),
            "എന്താടാ?" to 0xFF81C784.toInt(),
        )

        /** WhatsApp asks for WebP under 100 KB. Lossless keeps text edges sharp and is small here. */
        @Suppress("DEPRECATION") // plain WEBP is the only choice before Android 11
        fun save(bitmap: Bitmap, file: File) {
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                         else Bitmap.CompressFormat.WEBP
            file.outputStream().use { bitmap.compress(format, 100, it) }
        }

        /** Big word with a thick white outline and a soft shadow: the classic sticker look. */
        fun renderTextSticker(text: String, color: Int, typeface: Typeface = Typeface.DEFAULT_BOLD): Bitmap {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                this.color = color
                textAlign = Paint.Align.CENTER
            }
            // Biggest size that fits inside the sticker with room for the outline.
            var size = 220f
            fill.textSize = size
            while (size > 40f && fill.measureText(text) > SIZE - 90) {
                size -= 6f
                fill.textSize = size
            }
            val outline = Paint(fill).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = size * 0.16f
                strokeJoin = Paint.Join.ROUND
                setShadowLayer(14f, 0f, 6f, 0x66000000)
            }
            val fm = fill.fontMetrics
            val y = SIZE / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, SIZE / 2f, y, outline)
            canvas.drawText(text, SIZE / 2f, y, fill)
            return bitmap
        }
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
