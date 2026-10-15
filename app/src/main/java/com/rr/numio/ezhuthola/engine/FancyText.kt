package com.rr.numio.ezhuthola.engine

/**
 * Font styles for English letters (𝐁𝐨𝐥𝐝, 𝕆𝕦𝕥𝕝𝕚𝕟𝕖, ⓑⓤⓑⓑⓛⓔ…). There's no real font here:
 * each style is a set of Unicode characters that look like styled letters, so the text keeps
 * its look in any app. Letters and digits a style doesn't have are typed as they are.
 *
 * Note for users: screen readers and search can't read these characters as normal letters,
 * so they're for fun, not for names, addresses or anything someone needs to find.
 */
object FancyText {

    class Style(
        val id: String,
        val name: String,
        private val map: (Char) -> String?
    ) {
        /** One character in this style (unchanged if the style doesn't have it). */
        fun apply(c: Char): String = map(c) ?: c.toString()

        fun apply(text: String): String = buildString { text.forEach { append(apply(it)) } }

        /** The style's name written in the style itself, for the chooser. */
        val preview: String get() = apply(name)
    }

    /** A run of letters starting at a code point: A–Z, a–z and 0–9 laid out in order. */
    private fun run(upper: Int?, lower: Int?, digits: Int? = null, holes: Map<Char, Int> = emptyMap()) =
        { c: Char ->
            val cp = when {
                c in holes -> holes.getValue(c)
                c in 'A'..'Z' && upper != null -> upper + (c - 'A')
                c in 'a'..'z' && lower != null -> lower + (c - 'a')
                c in '0'..'9' && digits != null -> digits + (c - '0')
                else -> null
            }
            cp?.let { String(Character.toChars(it)) }
        }

    // Small capitals: there's no single run of these, so they're listed one by one.
    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"

    private val circledDigits = mapOf('0' to 0x24EA) + ('1'..'9').associateWith { 0x2460 + (it - '1') }

    val Bold = Style("bold", "Bold", run(0x1D400, 0x1D41A, 0x1D7CE))
    val Italic = Style("italic", "Italic", run(0x1D434, 0x1D44E, holes = mapOf('h' to 0x210E)))
    val Script = Style("script", "Script", run(0x1D4D0, 0x1D4EA))
    val Outline = Style(
        "outline", "Outline",
        run(
            0x1D538, 0x1D552, 0x1D7D8,
            holes = mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119,
                'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124)
        )
    )
    val Gothic = Style("gothic", "Gothic", run(0x1D56C, 0x1D586))
    val Sans = Style("sans", "Sans", run(0x1D5D4, 0x1D5EE, 0x1D7EC))
    val Mono = Style("mono", "Mono", run(0x1D670, 0x1D68A, 0x1D7F6))
    val Bubble = Style("bubble", "Bubble", run(0x24B6, 0x24D0, holes = circledDigits))
    val Blocks = Style("blocks", "Blocks") { c ->
        // Only capitals exist (🅰), so small letters use them too.
        when (c) {
            in 'A'..'Z' -> String(Character.toChars(0x1F170 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1F170 + (c - 'a')))
            else -> null
        }
    }
    val SmallCaps = Style("smallcaps", "Small caps") { c ->
        when (c) {
            in 'a'..'z' -> SMALL_CAPS[c - 'a'].toString()
            else -> null   // capitals stay capitals
        }
    }
    val Wide = Style("wide", "Wide", run(0xFF21, 0xFF41, 0xFF10))

    val all = listOf(Bold, Italic, Script, Outline, Gothic, Sans, Mono, Bubble, Blocks, SmallCaps, Wide)

    fun byId(id: String?): Style? = all.firstOrNull { it.id == id }
}
