package com.rr.numio.ezhuthola

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * The colours of one keyboard theme.
 * [inkOnAccent] is what's drawn on top of the accent colour (the leaf on the yellow key).
 */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val subtitle: String,
    val background: Color,
    val key: Color,
    val specialKey: Color,
    val keyText: Color,
    val hint: Color,
    val accent: Color,
    val inkOnAccent: Color,
    val card: Color,          // clipboard cards etc.
    val usesPhoto: Boolean = false,
)

object Themes {
    val Numio = KeyboardTheme(
        id = "numio", name = "Numio", subtitle = "Black & yellow",
        background = Color(0xFF141414), key = Color(0xFF2A2A2A), specialKey = Color(0xFF1D1D1D),
        keyText = Color(0xFFF2F2F2), hint = Color(0xFF9E9E9E),
        accent = Color(0xFFF5C427), inkOnAccent = Color(0xFF2A1C08), card = Color(0xFF1E1E1E)
    )
    val BlackWhite = KeyboardTheme(
        id = "bw", name = "Black & White", subtitle = "No colour",
        background = Color(0xFF0B0B0B), key = Color(0xFF262626), specialKey = Color(0xFF1A1A1A),
        keyText = Color(0xFFF2F2F2), hint = Color(0xFF9E9E9E),
        accent = Color(0xFFF2F2F2), inkOnAccent = Color(0xFF141414), card = Color(0xFF1A1A1A)
    )
    val Light = KeyboardTheme(
        id = "light", name = "Light", subtitle = "White keys",
        background = Color(0xFFE9E8E3), key = Color(0xFFFFFFFF), specialKey = Color(0xFFD3D2CC),
        keyText = Color(0xFF1A1A1A), hint = Color(0xFF6B6B6B),
        accent = Color(0xFF1A1A1A), inkOnAccent = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF)
    )
    val BlueNight = KeyboardTheme(
        id = "blue", name = "Blue Night", subtitle = "Black & blue",
        background = Color(0xFF10151C), key = Color(0xFF222B37), specialKey = Color(0xFF18202A),
        keyText = Color(0xFFEEF3F8), hint = Color(0xFF8A9AAE),
        accent = Color(0xFF4FA8F7), inkOnAccent = Color(0xFF0B1520), card = Color(0xFF18202A)
    )
    /** Your own photo behind see-through keys. */
    val Photo = KeyboardTheme(
        id = "photo", name = "Your photo", subtitle = "Any picture",
        background = Color(0xFF141414), key = Color(0x29FFFFFF), specialKey = Color(0x59000000),
        keyText = Color(0xFFFFFFFF), hint = Color(0xFFE6E6E6),
        accent = Color(0xFFF5C427), inkOnAccent = Color(0xFF2A1C08), card = Color(0x66000000),
        usesPhoto = true
    )

    val all = listOf(Numio, BlackWhite, Light, BlueNight, Photo)

    fun byId(id: String?): KeyboardTheme = all.firstOrNull { it.id == id } ?: Numio
}

/** The theme the keyboard is drawn with (set once at the top of KeyboardLayout). */
val LocalKeyboardTheme = staticCompositionLocalOf { Themes.Numio }

/** Vibration and click sound on key presses. */
data class KeyFeedback(val vibrate: Boolean = true, val sound: Boolean = false)

val LocalKeyFeedback = staticCompositionLocalOf { KeyFeedback() }

/**
 * Settings shared by the app screen (where you change them) and the keyboard (which reads them
 * each time it opens). Stored in the app's private preferences.
 */
object KeyboardSettings {
    const val FILE = "ezhuthola"
    const val THEME = "theme"
    const val PHOTO_DIM = "photo_dim"            // 0.1 … 0.8
    const val VIBRATE = "vibrate"
    const val KEY_SOUND = "key_sound"
    const val CLIPBOARD_HISTORY = "clipboard_history"
    const val MALAYALAM = "malayalam"

    private const val PHOTO_FILE = "keyboard_photo.jpg"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun photoFile(context: Context) = File(context.filesDir, PHOTO_FILE)

    /**
     * Copies the picked photo into the app's private storage, made smaller (max 1440 px)
     * so it loads fast. Run this off the main thread. Returns false if it couldn't be read.
     */
    fun savePhoto(context: Context, uri: Uri): Boolean = try {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also turns camera photos the right way up.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, 1440f / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeOld(context, uri)
        }
        if (bitmap == null) {
            false
        } else {
            photoFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Android 8–8.1: plain decoding, made smaller. */
    private fun decodeOld(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1440 || bounds.outHeight / (sample * 2) >= 1440) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    fun loadPhoto(context: Context): Bitmap? {
        val file = photoFile(context)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }
}