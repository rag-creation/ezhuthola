package com.rr.numio.ezhuthola.engine

/**
 * English words written the way Malayalis write them in Malayalam script:
 * four → ഫോർ, media → മീഡിയ, important → ഇമ്പോർട്ടന്റ്.
 *
 * Loaded from en_ml_words.tsv ("english<TAB>malayalam<TAB>uses per million"), which
 * tools/make_en_malayalam.py builds from the CMU Pronouncing Dictionary (BSD licence)
 * and FrequencyWords (CC BY-SA 4.0), plus Ezhuthola's own spellings.
 */
class EnglishInMalayalam(private val words: Map<String, Entry>) {

    class Entry(val malayalam: String, val perMillion: Float)

    /** [typed] as an English word ("Four" → ഫോർ), or null if it isn't one we know. */
    fun of(typed: String): Entry? = words[typed.lowercase()]

    val size: Int get() = words.size

    companion object {
        fun fromTsv(lines: Sequence<String>): EnglishInMalayalam {
            val map = HashMap<String, Entry>(32_000)
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val perMillion = parts[2].toFloatOrNull() ?: continue
                map[parts[0]] = Entry(parts[1], perMillion)
            }
            return EnglishInMalayalam(map)
        }
    }
}
