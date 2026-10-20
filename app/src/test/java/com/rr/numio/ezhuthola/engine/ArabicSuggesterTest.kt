package com.rr.numio.ezhuthola.engine

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ArabicSuggesterTest {

    private val words = listOf("من", "أنا", "مدرسة", "مرحبا", "مساء", "إلى", "انتظر", "مدرس")

    @Test
    fun typedWordStaysAsTyped() {
        val s = ArabicSuggester(words).suggest("مر")
        assertEquals("مر", s.typed)
        assertEquals("مر", s.best) // never autocorrected
    }

    @Test
    fun completesMostCommonFirst() {
        val s = ArabicSuggester(words).suggest("م")
        assertEquals(listOf("من", "مدرسة", "مرحبا", "مساء", "مدرس"), s.words)
    }

    @Test
    fun hamzaIsOptional() {
        // "انا" typed without hamza: أنا is offered first.
        assertEquals("أنا", ArabicSuggester(words).suggest("انا").words.first())
        assertTrue("إلى" in ArabicSuggester(words).suggest("ال").words)
    }

    @Test
    fun taMarbutaIsOptional() {
        assertEquals(listOf("مدرسة"), ArabicSuggester(words).suggest("مدرسه").words)
    }

    @Test
    fun theTypedWordIsNotRepeated() {
        assertFalse("من" in ArabicSuggester(words).suggest("من").words)
    }

    @Test
    fun pickedWordsComeFirst() {
        val user = UserWords().apply { learn("مدرس"); learn("مدرس") }
        assertEquals("مدرس", ArabicSuggester(words, user).suggest("مد").words.first())
    }

    @Test
    fun normalizeFoldsHamzaAndDropsHarakat() {
        assertEquals("مدرسه", ArabicSuggester.normalize("مَدْرَسَة"))
        assertEquals("اسلام", ArabicSuggester.normalize("إسلام"))
    }
}
