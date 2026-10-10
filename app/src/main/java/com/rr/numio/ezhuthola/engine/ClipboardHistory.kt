package com.rr.numio.ezhuthola.engine

/** One copied text. [time] is when it was copied (milliseconds). */
data class Clip(val text: String, val time: Long, val pinned: Boolean = false)

/**
 * Recently copied text, for the clipboard panel.
 *
 * - Newest first; pinned clips stay at the top and never expire.
 * - Unpinned clips disappear after an hour, and only the last [MAX_RECENT] are kept.
 * - Pinned clips are saved with [toText], recent ones with [recentToText] (kept in the app's
 *   private storage so they survive Android closing the keyboard; still gone after an hour).
 */
class ClipboardHistory(clips: List<Clip> = emptyList()) {

    private val list = ArrayList(clips)

    /** True when pinned clips changed since the last [toText]. */
    var dirty = false
        private set

    /** What the panel shows: pinned first, then recent, newest first in each group. */
    fun all(now: Long): List<Clip> {
        prune(now)
        return list.filter { it.pinned } + list.filter { !it.pinned }
    }

    /** The clip copied in the last [withinMs] (for the "just copied" chip), or null. */
    fun latest(now: Long, withinMs: Long = JUST_COPIED_MS): Clip? =
        list.maxByOrNull { it.time }?.takeIf { now - it.time <= withinMs }

    fun add(text: String, now: Long) {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return
        val old = list.firstOrNull { it.text == t }
        if (old != null) list.remove(old)           // copied again: move it to the top
        list.add(0, Clip(t, now, pinned = old?.pinned ?: false))
        if (old?.pinned == true) dirty = true
        prune(now)
    }

    fun togglePin(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i < 0) return
        list[i] = list[i].copy(pinned = !list[i].pinned)
        dirty = true
    }

    fun delete(text: String) {
        val removed = list.firstOrNull { it.text == text } ?: return
        list.remove(removed)
        if (removed.pinned) dirty = true
    }

    /** Recent (unpinned) clips as text, same format as [toText]. */
    fun recentToText(now: Long): String {
        prune(now)
        return list.filter { !it.pinned }.joinToString("\n") { "${it.time}\t${escape(it.text)}" }
    }

    /**
     * Adds clips read back from storage, without overwriting anything already here
     * (something copied while the files were loading stays on top).
     */
    fun mergeFrom(other: ClipboardHistory, now: Long) {
        for (clip in other.list) {
            if (list.none { it.text == clip.text }) list.add(clip)
        }
        list.sortWith(compareByDescending<Clip> { it.time })
        prune(now)
    }

    /** "Clear all": removes everything that isn't pinned. */
    fun clearUnpinned() {
        list.removeAll { !it.pinned }
    }

    /** Pinned clips as text, one per line: time<TAB>text (tabs/newlines escaped). */
    fun toText(): String {
        dirty = false
        return list.filter { it.pinned }.joinToString("\n") { "${it.time}\t${escape(it.text)}" }
    }

    private fun prune(now: Long) {
        list.removeAll { !it.pinned && now - it.time > EXPIRE_MS }
        var recent = 0
        list.removeAll { !it.pinned && ++recent > MAX_RECENT }
    }

    companion object {
        const val JUST_COPIED_MS = 60_000L          // chip in the strip: 1 minute
        private const val EXPIRE_MS = 60 * 60_000L  // unpinned clips: 1 hour
        private const val MAX_RECENT = 20
        private const val MAX_LENGTH = 5_000

        /** Saved recent clips: like [fromText] but unpinned. */
        fun recentFromText(text: String): ClipboardHistory =
            ClipboardHistory(fromText(text).list.map { it.copy(pinned = false) })

        fun fromText(text: String): ClipboardHistory = ClipboardHistory(
            text.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                val time = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
                time?.let { Clip(unescape(line.substring(tab + 1)), it, pinned = true) }
            }.toList()
        )

        private fun escape(s: String) =
            s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

        private fun unescape(s: String) = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> s[i + 1] })
                    i += 2
                } else {
                    append(c); i++
                }
            }
        }
    }
}