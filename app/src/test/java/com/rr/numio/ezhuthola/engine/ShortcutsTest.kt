package com.rr.numio.ezhuthola.engine

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ShortcutsTest {
    @Test fun manglishIsCleanedUp() {
        assertEquals("myre", Shortcuts.clean(" Myre "))
        assertNull(Shortcuts.clean("my re"))
        assertNull(Shortcuts.clean("മൈര്"))
        assertNull(Shortcuts.clean(""))
    }

    @Test fun linesRoundTrip() {
        val lines = setOf(Shortcuts.line("മൈര്", "myre"), Shortcuts.line("പട്ടി", "patti"), "broken")
        val parsed = Shortcuts.parse(lines)
        assertEquals(mapOf("മൈര്" to "myre", "പട്ടി" to "patti"), parsed)
        assertEquals(listOf("മൈര്"), Shortcuts.byTyped(parsed)["myre"])
    }
}
