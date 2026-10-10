package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipboardHistoryTest {

    private val hour = 60 * 60_000L

    @Test fun newestFirstAndCopyAgainMovesToTop() {
        val h = ClipboardHistory()
        h.add("one", 1)
        h.add("two", 2)
        h.add("one", 3)
        assertEquals(listOf("one", "two"), h.all(3).map { it.text })
    }

    @Test fun unpinnedClipsExpireAfterAnHour() {
        val h = ClipboardHistory()
        h.add("old", 0)
        h.togglePin("old")
        h.add("gone", 0)
        assertEquals(listOf("old"), h.all(hour + 1).map { it.text })
    }

    @Test fun clearAllKeepsPinned() {
        val h = ClipboardHistory()
        h.add("keep", 1); h.togglePin("keep")
        h.add("drop", 2)
        h.clearUnpinned()
        assertEquals(listOf("keep"), h.all(3).map { it.text })
    }

    @Test fun onlyPinnedClipsAreSavedAndComeBack() {
        val h = ClipboardHistory()
        h.add("line one\nline two\twith tab", 5); h.togglePin("line one\nline two\twith tab")
        h.add("not pinned", 6)
        assertTrue(h.dirty)
        val loaded = ClipboardHistory.fromText(h.toText())
        assertEquals(listOf("line one\nline two\twith tab"), loaded.all(7).map { it.text })
    }

    @Test fun recentClipsAreSavedAndComeBackUnpinned() {
        val h = ClipboardHistory()
        h.add("pin me", 1); h.togglePin("pin me")
        h.add("recent\twith tab", 2)
        val loaded = ClipboardHistory.fromText(h.toText())
        loaded.mergeFrom(ClipboardHistory.recentFromText(h.recentToText(3)), 3)
        assertEquals(listOf("pin me", "recent\twith tab"), loaded.all(3).map { it.text })
        assertEquals(listOf(true, false), loaded.all(3).map { it.pinned })
    }

    @Test fun savedRecentClipsStillExpireAfterAnHour() {
        val h = ClipboardHistory()
        h.add("old", 0)
        val back = ClipboardHistory.recentFromText(h.recentToText(1))
        assertEquals(emptyList(), back.all(2 * 60 * 60_000L).map { it.text })
    }

    @Test fun mergeKeepsWhatWasCopiedWhileLoading() {
        val now = ClipboardHistory()
        now.add("new copy", 10)
        val saved = ClipboardHistory.recentFromText("5\tolder copy")
        now.mergeFrom(saved, 11)
        assertEquals(listOf("new copy", "older copy"), now.all(11).map { it.text })
    }
}
