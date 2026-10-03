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

## Emoji search

**Unicode CLDR** (Common Locale Data Repository) and **Unicode Emoji** data
- © Unicode, Inc. Unicode and the Unicode Logo are registered trademarks of Unicode, Inc.
- License: [Unicode License v3](https://www.unicode.org/license.txt)
- Sources:
  - CLDR annotations, English and Malayalam (`cldr-annotations-full`, `cldr-annotations-derived-full`, CLDR 48): https://github.com/unicode-org/cldr-json
  - `emoji-test.txt`, Unicode Emoji 17.0: https://github.com/unicode-org/unicodetools/tree/main/unicodetools/data/emoji/17.0
- Used for: emoji names and English/Malayalam search keywords

`app/src/main/assets/emoji_keywords.tsv` is built from these files by `tools/build_emoji_data.py`
(emoji order and full forms from `emoji-test.txt`; skin-tone and hair variants left out).

The Manglish search words in `app/src/main/assets/emoji_manglish.tsv` are Ezhuthola's own, under GPL-3.0.

### Unicode License v3

```
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 2015-2024 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

SPDX-License-Identifier: Unicode-3.0
```
