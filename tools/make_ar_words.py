#!/usr/bin/env python3
"""
Builds app/src/main/assets/ar_words.tsv (Arabic word completion) from FrequencyWords
content/2018/ar/ar_50k.txt (Hermit Dave, CC BY-SA 4.0, from OpenSubtitles 2018).

Keeps plain Arabic words only: no punctuation, digits, Latin letters or diacritics
(harakat are optional when typing, so the bare spelling is what gets suggested).
Words in ar_quiet_words.txt (if present) are left out.

Usage: python3 tools/make_ar_words.py path/to/ar_50k.txt
"""
import os
import re
import sys

LETTERS = re.compile(r"^[ء-غف-ي]+$")
SINGLE_OK = {"و", "ب", "ك", "ل"}  # one-letter words that really are words
MIN_COUNT = 30

here = os.path.dirname(os.path.abspath(__file__))
assets = os.path.join(here, "..", "app", "src", "main", "assets")

quiet_path = os.path.join(assets, "ar_quiet_words.txt")
quiet = set()
if os.path.exists(quiet_path):
    with open(quiet_path, encoding="utf-8") as f:
        quiet = {line.strip() for line in f if line.strip() and not line.startswith("#")}

seen = set()
out = []
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        parts = line.rsplit(" ", 1)
        if len(parts) != 2:
            continue
        word, count = parts[0].strip().replace("ـ", ""), int(parts[1])
        if count < MIN_COUNT or not LETTERS.match(word) or word in quiet or word in seen:
            continue
        if len(word) == 1 and word not in SINGLE_OK:
            continue
        seen.add(word)
        out.append(f"{word}\t{count}")

with open(os.path.join(assets, "ar_words.tsv"), "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
print(f"{len(out)} words")
