"""
Regenerate sigil-codebook-v2-cases.json from a sigil checkout:

    python3 make_codebook_v2_cases.py /path/to/sigil > sigil-codebook-v2-cases.json

Records sigil's own codebook.compress_v2 / expand_v2 results (and expand_v2
failures on random streams) so the Java expander can be checked byte-for-byte.
"""
import json
import random
import sys

sys.path.insert(0, sys.argv[1])
import codebook  # noqa: E402

TEXTS = [
    "", "a", "the", "The Quick brown fox", "mine diamond ore at 100 64 -200",
    "stash at -1234567 70 99999999", "x 0 63 64 -1 -64 2147483647 -2147483648",
    "hello, world. ok? yes! no; maybe: fine", "line one\nline two\n\nend",
    "path/to-file (copy) +1 'quoted' \"double\"", "caf\u00e9 na\u00efve \u4e16\u754c \U0001F600",
    "supercalifragilisticexpialidocious_and_more", "a,b a , b a  b", "\u00b2 \u2167 \u0661\u0662",
    "_underscore__ trailing space ", " leading", "tab\tseparated\tvalues", "%$#@&*[]{}<>|~`^=",
    "go to the nether portal and wait for me", "iron_ingot x64, gold_block x9",
    "build region 3 layer 7 of 12", "12ab 3.5 -0 007 1e5", "\u00c9t\u00e9 \u00c0 PARIS",
]


def case(text):
    packed = codebook.compress_v2(text)
    try:
        return {"text": text, "hex": packed.hex(), "expanded": codebook.expand_v2(packed)}
    except Exception as e:  # compress can emit a raw chunk split mid-UTF-8
        return {"text": text, "hex": packed.hex(), "error": type(e).__name__}


def fuzz(rng, n):
    out = []
    for _ in range(n):
        data = b"\xc2" + bytes(rng.randrange(256) for _ in range(rng.randrange(0, 24)))
        try:
            out.append({"hex": data.hex(), "expanded": codebook.expand_v2(data)})
        except Exception as e:
            out.append({"hex": data.hex(), "error": type(e).__name__})
    return out


rng = random.Random(20260927)
doc = {
    "format": "sigil-codebook-v2-cases",
    "lexicon_v2_sha256": codebook.hashlib_sha256_hex(codebook._LEXICON_PATH.read_bytes()),
    "texts": [case(t) for t in TEXTS],
    "fuzz": fuzz(rng, 400),
}
json.dump(doc, sys.stdout, ensure_ascii=False, indent=1)
sys.stdout.write("\n")
