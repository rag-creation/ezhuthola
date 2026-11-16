package com.rr.numio.ezhuthola.engine

import java.io.File
import kotlin.test.Test

/**
 * The word check: takes the most-used Malayalam words, writes each one the way people
 * type it in Manglish (ധനു → dhanu), types that into the engine and checks the word comes first.
 * Writes app/build/word-report.md. Never fails the build; it's for finding problems.
 *
 * Runs only when asked: WORD_REPORT=1 (the "Word check" workflow on GitHub does that).
 * WORD_REPORT_TOP sets how many words (default 5000).
 */
class WordReportTest {

    @Test fun wordReport() {
        if (System.getenv("WORD_REPORT") != "1") return
        val top = System.getenv("WORD_REPORT_TOP")?.toIntOrNull() ?: 5000

        val rules = MalayalamRules.fromJson(res("/ezhuthola_rules.json"))
        val freqLines = res("/ml_words.tsv").lineSequence() + res("/ml_extra_words.tsv").lineSequence()
        val frequencies = WordFrequencies.fromTsv(freqLines)
        val english = EnglishInMalayalam.fromTsv(res("/en_ml_words.tsv").lineSequence())
        val suggester = MalayalamSuggester(MalayalamEngine(rules), frequencies, english = english)

        // The most-used words of the main list (real usage counts), longest-first ties don't matter.
        val words = res("/ml_words.tsv").lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 2 && it[0].length >= 2 && it[0].all(::isMalayalam) }
            .map { it[0] to (it[1].trim().toIntOrNull() ?: 0) }
            .sortedByDescending { it.second }
            .take(top)
            .toList()

        data class Miss(val rank: Int, val word: String, val count: Int, val typed: String, val got: String, val inStrip: Boolean, val gotReal: Boolean)
        val misses = mutableListOf<Miss>()
        val shortMisses = mutableListOf<Miss>()
        var firstOk = 0
        var stripOk = 0
        var shortOk = 0
        words.forEachIndexed { i, (word, count) ->
            val typed = Manglish.of(word)
            val s = suggester.suggest(typed)
            if (s.best == word) firstOk++ else {
                misses += Miss(i + 1, word, count, typed, s.best, word in s.words, frequencies.of(s.best) > 0)
            }
            if (word in s.words) stripOk++
            // The same word typed with short vowels, as many people do: aana → ana, veedu → vidu.
            val short = Manglish.short(typed)
            if (short != typed) {
                val s2 = suggester.suggest(short)
                if (word in s2.words) shortOk++ else {
                    shortMisses += Miss(i + 1, word, count, short, s2.best, false, frequencies.of(s2.best) > 0)
                }
            } else shortOk++
        }

        val pct = { n: Int -> "%.1f%%".format(100.0 * n / words.size) }
        val made = misses.filter { !it.gotReal }
        val other = misses.filter { it.gotReal }
        val out = StringBuilder()
        out.appendLine("# Ezhuthola word check")
        out.appendLine()
        out.appendLine("Top ${words.size} most-used words, each typed the usual Manglish way.")
        out.appendLine()
        out.appendLine("| | |")
        out.appendLine("|---|---|")
        out.appendLine("| Right word first | ${firstOk} (${pct(firstOk)}) |")
        out.appendLine("| Right word somewhere in the strip | ${stripOk} (${pct(stripOk)}) |")
        out.appendLine("| Typed with short vowels, still in the strip | ${shortOk} (${pct(shortOk)}) |")
        out.appendLine("| Wrong: a made-up spelling came first | ${made.size} |")
        out.appendLine("| Another real word came first | ${other.size} |")
        out.appendLine()
        fun table(title: String, note: String, list: List<Miss>, max: Int) {
            out.appendLine("## $title (${list.size})")
            out.appendLine()
            out.appendLine(note)
            out.appendLine()
            out.appendLine("| # | Word | Used | Typed | Came first | In strip |")
            out.appendLine("|---|---|---|---|---|---|")
            list.take(max).forEach {
                out.appendLine("| ${it.rank} | ${it.word} | ${it.count} | ${it.typed} | ${it.got} | ${if (it.inStrip) "yes" else "**no**"} |")
            }
            if (list.size > max) out.appendLine("\n…and ${list.size - max} more.")
            out.appendLine()
        }
        table(
            "Made-up spelling came first",
            "The most likely real bugs: the word is common, but a spelling that isn't a word won.",
            made, 300
        )
        table(
            "Another real word came first",
            "Often fine (two real words typed the same, like kalam: കാലം / കളം), but check the common ones.",
            other, 300
        )
        table(
            "Short vowels: not in the strip at all",
            "Typed with short vowels (aa → a, ee → i, oo → u) and the word didn't show up.",
            shortMisses, 200
        )

        val file = File(System.getProperty("user.dir"), "build/word-report.md")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        println("Word check: ${pct(firstOk)} first, ${pct(stripOk)} in strip. Report: ${file.path}")
    }

    private fun res(path: String) = javaClass.getResource(path)!!.readText()

    private fun isMalayalam(c: Char) = c in 'ഀ'..'ൿ'
}

/** Malayalam → the Manglish most people would type for it. Only for the word check. */
object Manglish {
    private val consonants = mapOf(
        'ക' to "k", 'ഖ' to "kh", 'ഗ' to "g", 'ഘ' to "gh", 'ങ' to "ng",
        'ച' to "ch", 'ഛ' to "chh", 'ജ' to "j", 'ഝ' to "jh", 'ഞ' to "nj",
        'ട' to "t", 'ഠ' to "th", 'ഡ' to "d", 'ഢ' to "dh", 'ണ' to "n",
        'ത' to "th", 'ഥ' to "th", 'ദ' to "d", 'ധ' to "dh", 'ന' to "n",
        'പ' to "p", 'ഫ' to "f", 'ബ' to "b", 'ഭ' to "bh", 'മ' to "m",
        'യ' to "y", 'ര' to "r", 'റ' to "r", 'ല' to "l", 'ള' to "l",
        'ഴ' to "zh", 'വ' to "v", 'ശ' to "sh", 'ഷ' to "sh", 'സ' to "s", 'ഹ' to "h"
    )
    private val vowels = mapOf(
        'അ' to "a", 'ആ' to "aa", 'ഇ' to "i", 'ഈ' to "ee", 'ഉ' to "u", 'ഊ' to "oo",
        'ഋ' to "ru", 'എ' to "e", 'ഏ' to "e", 'ഐ' to "ai", 'ഒ' to "o", 'ഓ' to "o", 'ഔ' to "au"
    )
    private val signs = mapOf(
        'ാ' to "aa", 'ി' to "i", 'ീ' to "ee", 'ു' to "u", 'ൂ' to "oo", 'ൃ' to "ru",
        'െ' to "e", 'േ' to "e", 'ൈ' to "ai", 'ൊ' to "o", 'ോ' to "o", 'ൌ' to "au", 'ൗ' to "au"
    )
    private val chillus = mapOf('ൻ' to "n", 'ർ' to "r", 'ൽ' to "l", 'ൾ' to "l", 'ൺ' to "n", 'ൿ' to "k")
    private const val VIRAMA = '്'

    /** Consonant groups typed as one: longest first. */
    private val groups: List<Pair<String, String>> = (
        listOf(
            "ന്റ" to "nt", "റ്റ" to "tt", "ങ്ങ" to "ng", "ഞ്ഞ" to "nj", "ഞ്ച" to "nch",
            "ങ്ക" to "nk", "ണ്ട" to "nd", "മ്പ" to "mb", "ന്ത" to "nth", "ക്ഷ" to "ksh",
            "ത്ത" to "tt", "ച്ച" to "chch", "ന്ന" to "nn", "ണ്ണ" to "nn", "ല്ല" to "ll", "ള്ള" to "ll"
        ) + consonants.filterKeys { it !in setOf('ത', 'ച', 'ന', 'ണ', 'ല', 'ള') }
            .map { (c, l) -> "$c$VIRAMA$c" to l.first() + l }   // ക്ക kk, പ്പ pp, ട്ട tt
        ).sortedByDescending { it.first.length }

    fun of(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            val group = groups.firstOrNull { word.startsWith(it.first, i) }
            if (group != null || c in consonants) {
                val (len, latin) = group?.let { it.first.length to it.second } ?: (1 to consonants.getValue(c))
                out.append(latin)
                i += len
                val next = word.getOrNull(i)
                when {
                    next == null -> out.append('a')
                    next in signs -> { out.append(signs.getValue(next)); i++ }
                    next == VIRAMA -> {
                        i++
                        if (i >= word.length) out.append('u')   // word-final ്: samvruthokaram
                    }
                    else -> out.append('a')
                }
                continue
            }
            when (c) {
                in vowels -> out.append(vowels.getValue(c))
                in chillus -> out.append(chillus.getValue(c))
                'ം' -> out.append('m')
                'ഃ' -> out.append('h')
                else -> {}
            }
            i++
        }
        return out.toString()
    }

    /** The same, typed with short vowels: aa → a, ee → i, oo → u. */
    fun short(typed: String): String = typed.replace("aa", "a").replace("ee", "i").replace("oo", "u")
}
