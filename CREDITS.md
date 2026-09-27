# Credits

Ezhuthola by Numio is free and open-source software (GPL-3.0).
It stands on the work of these people and projects. Thank you.

## Malayalam and English word frequencies

**FrequencyWords** by **Hermit Dave**
- License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- Based on [OpenSubtitles](https://www.opensubtitles.org/) (2018)
- Source: https://github.com/hermitdave/FrequencyWords
- Used for: ranking Malayalam suggestions and English word completion

`app/src/main/assets/ml_words.tsv` is adapted from `content/2018/ml/ml_full.txt`:
old-style chillu letters converted to atomic chillus (ന് → ൻ at word end),
non-Malayalam entries and word fragments removed, words used fewer than 2 times dropped.

`app/src/main/assets/en_words.tsv` is adapted from `content/2018/en/en_50k.txt`:
fragments and swear words removed, common contractions (don't, I'm…) added back.

These adapted lists are shared under **CC BY-SA 4.0**, as the license requires.
