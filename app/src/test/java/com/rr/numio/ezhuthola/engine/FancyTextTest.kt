package com.rr.numio.ezhuthola.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FancyTextTest {

    @Test fun boldLettersAndDigits() {
        assertEquals("𝐇𝐞𝐥𝐥𝐨 𝟐𝟎𝟐𝟔", FancyText.Bold.apply("Hello 2026"))
    }

    @Test fun italicUsesTheSpecialH() {
        assertEquals("ℎ𝑒𝑦", FancyText.Italic.apply("hey"))
    }

    @Test fun outlineFillsTheGaps() {
        assertEquals("ℂℍℕℙℚℝℤ", FancyText.Outline.apply("CHNPQRZ"))
        assertEquals("𝔸𝕓𝟙", FancyText.Outline.apply("Ab1"))
    }

    @Test fun bubbleDigits() {
        assertEquals("ⓗⓘ ①⓪", FancyText.Bubble.apply("hi 10"))
    }

    @Test fun blocksAreAlwaysCapitals() {
        assertEquals(FancyText.Blocks.apply("OK"), FancyText.Blocks.apply("ok"))
    }

    @Test fun smallCapsKeepsCapitals() {
        assertEquals("Hᴇʟʟᴏ", FancyText.SmallCaps.apply("Hello"))
    }

    @Test fun wideText() {
        assertEquals("Ｗｉｄｅ１", FancyText.Wide.apply("Wide1"))
    }

    @Test fun otherCharactersStayAsTheyAre() {
        assertEquals("𝙷𝚒! മ", FancyText.Mono.apply("Hi! മ"))
    }

    @Test fun findsStylesById() {
        FancyText.all.forEach { assertNotNull(FancyText.byId(it.id)) }
    }
}
