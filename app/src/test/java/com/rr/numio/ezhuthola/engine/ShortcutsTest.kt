package com.rr.numio.ezhuthola.engine

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ShortcutsTest {
    @Test fun manglishIsCleanedUp() {
        assertEquals("ammachy", Shortcuts.clean(" Ammachy "))
        assertNull(Shortcuts.clean("my re"))
        assertNull(Shortcuts.clean("അമ്മച്ചി"))
        assertNull(Shortcuts.clean(""))
    }

    @Test fun linesRoundTrip() {
        val lines = setOf(Shortcuts.line("അമ്മച്ചി", "ammachy"), Shortcuts.line("പൂച്ച", "poocha"), "broken")
        val parsed = Shortcuts.parse(lines)
        assertEquals(mapOf("അമ്മച്ചി" to "ammachy", "പൂച്ച" to "poocha"), parsed)
        assertEquals(listOf("അമ്മച്ചി"), Shortcuts.byTyped(parsed)["ammachy"])
    }
}
