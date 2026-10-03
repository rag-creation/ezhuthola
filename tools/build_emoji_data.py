#!/usr/bin/env python3
"""
Builds app/src/main/assets/emoji_keywords.tsv: the data behind emoji search.

Inputs (both from Unicode, under the Unicode License v3 — credited in CREDITS.md):
  1. emoji-test.txt (Unicode Emoji 17.0)
     https://github.com/unicode-org/unicodetools/blob/main/unicodetools/data/emoji/17.0/emoji-test.txt
     Gives the order emojis appear in (same as the picker) and their full form (with U+FE0F).
  2. CLDR annotations, JSON edition (cldr-json), English + Malayalam, base + derived
     https://github.com/unicode-org/cldr-json
       cldr-json/cldr-annotations-full/annotations/{en,ml}/annotations.json
       cldr-json/cldr-annotations-derived-full/annotationsDerived/{en,ml}/annotations.json

Usage:
  python3 tools/build_emoji_data.py <emoji-test.txt> <cldr-json folder> > app/src/main/assets/emoji_keywords.tsv

Output: one line per emoji, in picker order:
  emoji <TAB> English name <TAB> English keywords (| separated) <TAB> Malayalam name <TAB> Malayalam keywords
Skin-tone and hair-style variants are left out: search shows the plain emoji.
"""
import json
import re
import sys

SKIN_TONES = set(range(0x1F3FB, 0x1F400))
HAIR = set(range(0x1F9B0, 0x1F9B4))


def load(path, top):
    with open(path, encoding="utf-8") as f:
        return json.load(f)[top]["annotations"]


def main():
    test_file, cldr = sys.argv[1], sys.argv[2].rstrip("/")
    ann = {}
    for lang in ("en", "ml"):
        merged = {}
        merged.update(load(f"{cldr}/cldr-annotations-derived-full/annotationsDerived/{lang}/annotations.json", "annotationsDerived"))
        merged.update(load(f"{cldr}/cldr-annotations-full/annotations/{lang}/annotations.json", "annotations"))
        ann[lang] = merged

    line_re = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#")
    out = sys.stdout
    count = 0
    for line in open(test_file, encoding="utf-8"):
        m = line_re.match(line)
        if not m:
            continue
        cps = [int(c, 16) for c in m.group(1).split()]
        if len(cps) > 1 and any(c in SKIN_TONES or c in HAIR for c in cps):
            continue  # variant: the base emoji is enough for search
        emoji = "".join(chr(c) for c in cps)
        key = emoji.replace("️", "")
        cols = [emoji]
        for lang in ("en", "ml"):
            a = ann[lang].get(key) or ann[lang].get(emoji) or {}
            name = (a.get("tts") or [""])[0]
            words = [w for w in a.get("default", []) if w != name and "\t" not in w and "|" not in w]
            cols += [name, "|".join(words)]
        if not cols[1]:
            continue  # no English name: not searchable
        out.write("\t".join(c.replace("‌", "") if i > 2 else c for i, c in enumerate(cols)) + "\n")
        count += 1
    print(f"{count} emojis", file=sys.stderr)


if __name__ == "__main__":
    main()
