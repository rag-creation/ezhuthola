#!/usr/bin/env python3
"""
Builds app/src/main/assets/en_ml_words.tsv: common English words spelled the way
Malayalis write English in Malayalam script (four → ഫോർ, media → മീഡിയ, important → ഇമ്പോർട്ടന്റ്).

Input:
  - CMU Pronouncing Dictionary (BSD licence): how each English word sounds.
    https://github.com/cmusphinx/cmudict  (cmudict.dict)
  - app/src/main/assets/en_words.tsv: which English words are common (FrequencyWords, CC BY-SA 4.0).
  - app/src/main/assets/en_extra_words.tsv: Ezhuthola's own app and phone words (whatsapp, selfie).
  - tools/en_ml_overrides.tsv: Ezhuthola's own hand-written spellings where the rules miss.

Output lines: english<TAB>malayalam<TAB>uses per million (from en_words.tsv).

Usage: python3 tools/make_en_malayalam.py path/to/cmudict.dict
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
MAX_WORDS = 25000

VIRAMA = "്"

CONSONANT = {
    "B": "ബ", "CH": "ച", "D": "ഡ", "DH": "ദ", "F": "ഫ", "G": "ഗ", "HH": "ഹ", "JH": "ജ",
    "K": "ക", "L": "ല", "M": "മ", "N": "ന", "NG": "ങ", "P": "പ", "R": "റ", "S": "സ",
    "SH": "ഷ", "T": "ട", "TH": "ത", "V": "വ", "W": "വ", "Y": "യ", "Z": "സ", "ZH": "ഷ",
}
VOWELS = {"AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"}
# Vowels after which a final n / l is written ൺ / ൾ (one → വൺ, phone → ഫോൺ, call → കോൾ, school → സ്കൂൾ).
BACK_N = {"AH", "AA", "AO", "OW", "AW", "UW", "UH", "ER"}
BACK_L = {"AO", "OW", "UW", "UH", "AA", "ER"}
# Vowels after which t is written റ്റ (it → ഇറ്റ്, light → ലൈറ്റ്); after the others ട്ട (cut → കട്ട്).
FRONT_T = {"IH", "IY", "EH", "AE", "EY", "AY", "OY"}

# (independent letter, vowel sign)
VOWEL_FORMS = {
    "AE": ("ആ", "ാ"), "AO": ("ഓ", "ോ"), "AW": ("ഔ", "ൗ"), "AY": ("ഐ", "ൈ"),
    "EH": ("എ", "െ"), "EY": ("എയ്", "േ"), "IH": ("ഇ", "ി"), "OW": ("ഓ", "ോ"),
    "OY": ("ഓയ്", "ോയ്"), "UH": ("ഉ", "ു"), "UW": ("ഊ", "ൂ"),
}


def vowel_letters(word):
    """The vowel spellings of a word, to tell the 'o' in hot (ഹോട്ട്) from the 'a' in car (കാർ)."""
    w = word
    if len(w) > 3 and w.endswith("e") and w[-2] not in "aeiouy" and not w.endswith("le"):
        w = w[:-1]  # silent e: five, more, phone
    return [(mo.group(0), mo.end()) for mo in re.finditer(r"[aeiou]+|(?<=[^aeiou])y", w)]


class Phone:
    def __init__(self, sym, stress):
        self.sym = sym
        self.stress = stress          # None for consonants, 0/1/2 for vowels
        self.vowel = sym in VOWELS
        self.letter = ""              # for vowels: first letter of its spelling ('o' in hot), if known
        self.after = ""               # the letter right after that spelling

    @property
    def stressed(self):
        return bool(self.stress)


def to_malayalam(word, phones):
    """phones: ARPAbet like ['F', 'AO1', 'R']."""
    ps = []
    for p in phones:
        mo = re.match(r"([A-Z]+)(\d)?$", p)
        ps.append(Phone(mo.group(1), int(mo.group(2)) if mo.group(2) is not None else None))
    vowels = [p for p in ps if p.vowel]
    spelled = vowel_letters(word)
    if len(spelled) == len(vowels):
        for p, (letters, end) in zip(vowels, spelled):
            p.letter = letters[0]
            p.after = word[end] if end < len(word) else ""

    out = []
    i = 0
    n = len(ps)
    while i < n:
        p = ps[i]
        if p.vowel:
            prev = ps[i - 1] if i > 0 else None
            nxt = ps[i + 1] if i + 1 < n else None
            initial = i == 0
            if prev is not None and prev.vowel:
                # Two vowels in a row get a glide: media → മീഡിയ, power → പവർ, fire → ഫയർ, player → പ്ലെയർ.
                if prev.sym == "AW" and out[-1].endswith("ൗ"):
                    out[-1] = out[-1][:-1]
                    out.append("വ")
                elif prev.sym == "AY" and out[-1].endswith("ൈ"):
                    out[-1] = out[-1][:-1]
                    out.append("യ")
                elif prev.sym == "ER" and out[-1].endswith("റ"):
                    pass                      # battery → ബാറ്ററി: the r already joins the vowel
                elif prev.sym == "EY" and out[-1].endswith("േ"):
                    out[-1] = out[-1][:-1] + "െ"
                    out.append("യ")
                else:
                    out.append("യ")
                initial = False
                independent = False
            else:
                independent = initial
            out.append(vowel(p, nxt, ps, i, independent, word))
            i += 1
            continue

        j = i
        while j < n and not ps[j].vowel:
            j += 1
        before = ps[i - 1] if i > 0 else None
        after = ps[j] if j < n else None
        out.append(cluster(ps[i:j], before, after, word, ps, j))
        i = j
    return "".join(out)


def vowel(p, nxt, ps, i, independent, word):
    s = p.sym
    following_consonants = []
    k = i + 1
    while k < len(ps) and not ps[k].vowel:
        following_consonants.append(ps[k].sym)
        k += 1
    word_final_next = k == len(ps)
    if s == "AA":
        ind, sign = ("ഓ", "ോ") if p.letter == "o" else ("ആ", "ാ")
    elif s == "AH":
        if p.stressed:
            ind, sign = ("ആ", "ാ") if (p.letter == "a" and word.startswith("w")) else ("അ", "")
        else:
            ind, sign = ("എ", "") if p.letter == "a" else ("അ", "")
            # -le ending: apple → ആപ്പിൾ, people → പീപ്പിൾ
            if following_consonants == ["L"] and word_final_next and word.endswith("le"):
                ind, sign = ("ഇ", "ി")
    elif s == "AO":
        ind, sign = ("ആ", "ാ") if (p.letter == "a" and p.after not in ("l", "w", "u")) else ("ഓ", "ോ")
    elif s == "IY":
        ind, sign = ("ഈ", "ീ") if p.stress == 1 else ("ഇ", "ി")   # need → നീഡ്, lady → ലേഡി
    elif s == "IH":
        ind, sign = ("ഇ", "ി")
    elif s == "EY":
        # train → ട്രെയിൻ, name → നെയിം, mail → മെയിൽ; but late → ലേറ്റ്, lady → ലേഡി
        if following_consonants[:1] in (["N"], ["M"], ["L"]) and len(following_consonants) <= 2 and (word_final_next or len(following_consonants) == 2):
            ind, sign = ("എയി", "െയി")
        else:
            ind, sign = ("എയ്" if word_final_next or following_consonants else "ഏ", "േ")
    elif s == "OY":
        if following_consonants:
            ind, sign = ("ഓയി", "ോയി")   # point → പോയിന്റ്, coin → കോയിൻ
        elif nxt is None:
            ind, sign = ("ഓയ്", "ോയ്")   # boy → ബോയ്
        else:
            ind, sign = ("ഓ", "ോ")       # lawyer → ലോയർ (the glide adds യ)
    elif s == "ER":
        nxt_c = following_consonants[0] if following_consonants else None
        if p.stressed and nxt_c in ("N", "L") and len(following_consonants) <= 2 and word_final_next:
            return "ഏ" if independent else "േ"     # turn → ടേൺ, girl → ഗേൾ, world → വേൾഡ്
        r = "റ" if (nxt is not None and nxt.vowel) else "ർ"
        return ("എ" if independent else "") + r
    else:
        ind, sign = VOWEL_FORMS[s]
    return ind if independent else sign


def back_vowel(v):
    """After these, a final n is ൺ (one → വൺ, phone → ഫോൺ) and nt / nd are ണ്ട (count → കൗണ്ട്)."""
    if v is None or not v.vowel:
        return False
    if v.sym == "AH":
        return v.stressed
    if v.sym == "AA":
        return True
    return v.sym in ("AO", "OW", "AW", "UW", "UH", "ER")


def cluster(cs, before, after, word, ps, end):
    """Consonants between two vowels (or at the start / end of the word)."""
    final = after is None
    syms = [c.sym for c in cs]
    pieces = []      # list of (text, join_with_next)
    k = 0
    m = len(syms)
    vb = before if (before is not None and before.vowel) else None
    while k < m:
        c = syms[k]
        nxt = syms[k + 1] if k + 1 < m else None
        prev = syms[k - 1] if k > 0 else None
        last = k == m - 1
        first = k == 0
        back = back_vowel(vb)

        if c == "N":
            if nxt == "T":
                pieces.append(("ണ്ട" if back else "ന്റ", not (k + 1 == m - 1)))
                k += 2
                continue
            if nxt == "D":
                pieces.append(("ണ്ട" if back and vb is not None and vb.stressed else "ൻഡ", not (k + 1 == m - 1)))
                k += 2
                continue
            if nxt in ("K", "G"):
                pieces.append(("ങ", True))
            elif last and not final:
                pieces.append(("ണ" if back and first else "ന", False))
            elif last and final:
                if vb is not None and vb.sym == "AH" and not vb.stressed:
                    # seven → സെവൻ, chicken → ചിക്കൻ; but button → ബട്ടൺ, person → പേഴ്സൺ
                    pc = ps[ps.index(vb) - 1].sym if ps.index(vb) > 0 else ""
                    pieces.append(("ൺ" if pc in ("T", "S", "Z", "P", "B", "F", "SH") else "ൻ", False))
                else:
                    pieces.append(("ൺ" if back else "ൻ", False))
            else:
                pieces.append(("ൺ" if back else "ൻ", False))
            k += 1
            continue

        if c == "NG":
            if nxt in ("K", "G"):
                pieces.append(("ങ", True))
            elif last and final:
                pieces.append(("ങ്", False))
            elif last:
                pieces.append(("ങ്ങ", False))
            else:
                pieces.append(("ങ്", False))
            k += 1
            continue

        if c == "L":
            if last and not final:
                pieces.append(("ല", False))
            elif first and vb is not None:
                ends_le = vb.sym == "AH" and not vb.stressed and word.endswith("le")   # apple → ആപ്പിൾ
                back_l = vb.sym in ("AO", "OW", "UW", "UH", "AA", "ER") or ends_le
                pieces.append(("ൾ" if back_l else "ൽ", False))
            elif first:
                pieces.append(("ല", not last))
            else:
                pieces.append(("ല", not last))       # cluster like pl, bl, fl: joins (പ്ല)
            k += 1
            continue

        if c == "R":
            if not first:
                pieces.append(("ര", False))         # tr, pr, br: joins the consonant before (ട്ര)
            elif last and not final:
                pieces.append(("റ", False))         # very → വെറി
            elif vb is None:
                pieces.append(("റ", True))
            else:
                pieces.append(("ർ", False))         # car, park, short
            k += 1
            continue

        if c == "M":
            if last and final:
                pieces.append(("ം", False))
            else:
                pieces.append(("മ", not last))
            k += 1
            continue

        if c == "T":
            if prev == "S" and nxt == "R":
                pieces.append(("ട", True))          # street → സ്ട്രീറ്റ്
            elif prev == "K" and not final and last:
                pieces.append(("ട", False))         # doctor → ഡോക്ടർ
            elif prev in ("S", "K", "P", "F"):
                pieces.append(("റ്റ", not last))    # test, fact, except, left, after
            elif prev in ("R", "L"):
                pieces.append(("ട്ട", not last))    # party, short, salt
            elif first and vb is not None and (last or nxt in ("S", "Z")):
                # it → ഇറ്റ്, light → ലൈറ്റ്, quality → ക്വാളിറ്റി; but cut → കട്ട്, hot → ഹോട്ട്
                front = vb.sym in FRONT_T or (vb.sym == "AH" and not vb.stressed)
                pieces.append(("റ്റ" if front else "ട്ട", not last))
            else:
                pieces.append(("ട", not last))
            k += 1
            continue

        if c in ("K", "P", "CH") and last and (
            (first and vb is not None) or prev in ("L", "R")
        ):
            single = c == "K" and final and vb is not None and vb.sym == "IH" and not vb.stressed
            pieces.append(({"K": "ക" if single else "ക്ക", "P": "പ്പ", "CH": "ച്ച"}[c], False))
            k += 1
            continue

        if c in ("TH", "DH") and vb is not None and first and (final or not last):
            pieces.append(("ത്ത", not last))       # with → വിത്ത്, birthday → ബർത്ത്ഡേ
            k += 1
            continue

        pieces.append((CONSONANT[c], not last))
        k += 1

    text = ""
    for idx, (piece, joins) in enumerate(pieces):
        text += piece
        if joins and idx + 1 < len(pieces):
            text += VIRAMA
    if final and not text.endswith(("ൻ", "ൺ", "ൽ", "ൾ", "ർ", "ം", VIRAMA)):
        text += VIRAMA
    return text


def pick_pronunciation(variants):
    """cmudict lists the reduced form first for some words ('and' AH0 N D); prefer a stressed one."""
    for v in variants:
        if any(p.endswith("1") for p in v):
            return v
    return variants[0]


def load_cmudict(path):
    prons = {}
    for line in open(path, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        head, *phones = line.split()
        word = re.sub(r"\(\d+\)$", "", head)
        prons.setdefault(word, []).append(phones)
    return prons


def main():
    cmu = load_cmudict(sys.argv[1])
    overrides = {}
    for line in open(ROOT / "tools/en_ml_overrides.tsv", encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            continue
        en, ml = line.rstrip("\n").split("\t")[:2]
        overrides[en] = ml
    counts = []
    for line in open(ASSETS / "en_words.tsv", encoding="utf-8"):
        w, _, c = line.rstrip("\n").partition("\t")
        if c.isdigit():
            counts.append((w, int(c)))
    total = sum(c for _, c in counts)
    rows = []
    for w, c in counts:
        if len(rows) >= MAX_WORDS:
            break
        if not re.fullmatch(r"[a-z]{2,}", w) and w not in overrides:
            continue
        if w in overrides:
            ml = overrides[w]
        elif w in cmu:
            ml = to_malayalam(w, pick_pronunciation(cmu[w]))
        else:
            continue
        rows.append(f"{w}\t{ml}\t{c / total * 1e6:.1f}")
    # Ezhuthola's own English extras (app and phone words: whatsapp, instagram, selfie…),
    # which the 2018 subtitle list barely has.
    have = {r.split("\t")[0] for r in rows}
    for line in open(ASSETS / "en_extra_words.tsv", encoding="utf-8"):
        w, _, c = line.rstrip("\n").partition("\t")
        w = w.strip().lower()
        if not re.fullmatch(r"[a-z]{2,}", w) or w in have:
            continue
        if w in overrides:
            ml = overrides[w]
        elif w in cmu:
            ml = to_malayalam(w, pick_pronunciation(cmu[w]))
        else:
            continue
        have.add(w)
        rows.append(f"{w}\t{ml}\t20.0")   # treated as common: people type these every day
    out = ASSETS / "en_ml_words.tsv"
    out.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} words to {out}")


if __name__ == "__main__":
    main()
