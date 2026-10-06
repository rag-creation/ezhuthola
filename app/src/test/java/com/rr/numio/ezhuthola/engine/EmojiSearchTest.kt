package com.rr.numio.ezhuthola.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiSearchTest {

    // Unit tests run from the app/ folder, so the real asset files are read directly.
    private fun asset(name: String) = File("src/main/assets/$name").readLines().asSequence()

    private val search = EmojiSearch(
        EmojiSearch.parseKeywords(asset("emoji_keywords.tsv")),
        EmojiSearch.parseManglish(asset("emoji_manglish.tsv")),
    )

    private val suggester = MalayalamSuggester(
        MalayalamEngine(MalayalamRules.fromJson(File("src/main/assets/ezhuthola_rules.json").readText())),
        WordFrequencies.fromTsv(asset("ml_words.tsv")),
    )

    /** Same as the keyboard: English + Manglish (through the Malayalam engine). */
    private fun find(query: String) = search.search(query, { suggester.suggest(it).words })

    private fun assertFirst(emoji: String, query: String, within: Int = 3) {
        val results = find(query)
        assertTrue(
            emoji in results.take(within),
            "\"$query\": expected $emoji in the first $within, got ${results.take(10)}"
        )
    }

    @Test fun english() {
        assertFirst("❤️", "love", within = 6)
        assertFirst("😡", "angry")
        assertFirst("😢", "sad", within = 6)
        assertFirst("👍", "thumbs up", within = 1)
        assertFirst("❤️", "red heart", within = 1)
        assertFirst("🇮🇳", "india", within = 1)
        assertFirst("😂", "lol", within = 4)
    }

    @Test fun halfTypedWordsWork() {
        assertFirst("🎂", "birthd")
        assertTrue(find("ang").isNotEmpty())
    }

    @Test fun manglish() {
        assertFirst("😂", "chiri", within = 1)
        assertFirst("👍", "kollam", within = 1)
        assertFirst("🔥", "pwoli", within = 1)
        assertFirst("😘", "umma", within = 1)
        assertFirst("🐘", "aana", within = 1)
        assertFirst("😭", "karachil", within = 1)
    }

    @Test fun manglishThroughUnicodeMalayalamKeywords() {
        // Not in Ezhuthola's own list: found through CLDR's Malayalam keywords.
        assertTrue(find("hrudayam").isNotEmpty(), "hrudayam → ഹൃദയം")
        assertTrue(find("kidilan").isNotEmpty())
    }

    @Test fun malayalamScript() {
        assertFirst("❤️", "ഹൃദയം", within = 6)
        assertFirst("👍", "കൊള്ളാം", within = 1)
    }

    @Test fun twoWordManglish() {
        assertFirst("😵‍💫", "kili poyi", within = 1)
    }

    @Test fun nothingForNonsense() {
        assertEquals(emptyList(), find("   "))
        assertEquals(emptyList(), find("zzqxj"))
    }

    /** The Malayalam shown under the search box. */
    private fun reading(query: String) = search.bestReading(query, suggester.suggest(query).words)

    @Test fun readingOnlyForRealMalayalam() {
        assertEquals("ചിരി", reading("chiri"))
        assertEquals(null, reading("love"), "English word: no made-up Malayalam")
        assertEquals(null, reading("angry"), "English word: no made-up Malayalam")
        assertEquals(null, reading("happy"), "English word: no made-up Malayalam")
    }

    @Test fun wholeWordsDontMatchLongerWords() {
        // പുച്ഛം (contempt) used to also match പൂച്ചമുഖം (cat face).
        assertFirst("😏", "puchcham", within = 2)
        assertTrue(find("puchcham").none { it in listOf("😽", "😸", "😾") }, "no cats for puchcham: ${find("puchcham")}")
        assertFirst("❤️", "sneham", within = 3)
        assertFirst("😂", "chir", within = 3)   // half-typed still works
    }

    @Test fun loveIsAboutHearts() {
        assertTrue("🏩" !in find("love").take(8), "love hotel shouldn't crowd out the hearts")
        assertTrue(find("love hotel").firstOrNull() == "🏩", "but it's still there when you ask for it")
    }
}
