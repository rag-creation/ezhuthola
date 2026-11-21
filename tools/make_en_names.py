#!/usr/bin/env python3
"""Move people's names out of English suggestions (Heywood, Haywood, Harwood).

The subtitle word list (en_words.tsv) is full of movie characters' surnames and first names.
They are real, but rarely what anyone types in a chat, so they crowd the strip ("heyw" offers
three surnames). This moves names that are not also ordinary words into en_names.txt:
still fine to type and never "corrected", but never suggested.

Kept: the 10,000 most common words (Sam, David, London, Sunday stay), anything in the ENABLE
word list or Ezhuthola's own lists, and the KEEP words below.

Name lists (public domain, from the SCOWL source, https://github.com/en-wl/wordlist, branch v1,
folder other/): census-names-1990.zip (US Census Bureau 1990 names), mwords.tar.gz (Moby names
by Grady Ward: 3897male.nam, 4946fema.len, 21986na.mes), enable2k.zip (ENABLE word.lst).

Usage: python3 tools/make_en_names.py <folder with census/, mwords/ and enable/ unpacked>
"""
import glob
import sys
from pathlib import Path

KEEP_TOP = 10_000
KEEP = {"dec", "uber", "eid", "ramadan", "oman", "lanka"}

src = Path(sys.argv[1])
assets = Path(__file__).resolve().parent.parent / "app/src/main/assets"

names = set()
for f in ["census/dist.all.last", "census/dist.male.first", "census/dist.female.first"]:
    for line in open(src / f, encoding="latin-1"):
        if line.strip():
            names.add(line.split()[0].lower())
for f in glob.glob(str(src / "mwords/*")):
    if any(k in f for k in ("male.nam", "fema.len", "na.mes")):
        for line in open(f, encoding="latin-1"):
            w = line.strip()
            if w and " " not in w:
                names.add(w.lower())

real = {l.strip().lower() for l in open(src / "enable/word.lst", encoding="latin-1")}
for f in ("en_known_words.txt", "en_extra_words.tsv", "en_manglish_words.tsv"):
    for line in open(assets / f, encoding="utf-8"):
        if line.strip() and not line.startswith("#"):
            real.add(line.split("\t")[0].strip().lower())

lines = open(assets / "en_words.tsv", encoding="utf-8").read().splitlines()
kept, moved = [], []
rank = 0
for line in lines:
    if not line.strip() or line.startswith("#"):
        kept.append(line)
        continue
    w = line.split("\t")[0]
    if rank >= KEEP_TOP and w in names and w not in real and w not in KEEP and "'" not in w:
        moved.append(w)
    else:
        kept.append(line)
    rank += 1

(assets / "en_words.tsv").write_text("\n".join(kept) + "\n", encoding="utf-8")
header = [
    "# People's names from the subtitle list (Heywood, Haywood, Harwood): fine to type, never suggested.",
    "# Made by tools/make_en_names.py from FrequencyWords (CC BY-SA 4.0), with public-domain name lists",
    "# (US Census 1990 names, Moby names by Grady Ward) used to spot them. One word per line.",
]
(assets / "en_names.txt").write_text("\n".join(header + moved) + "\n", encoding="utf-8")
print(f"moved {len(moved)} names, kept {rank - len(moved)} words")
