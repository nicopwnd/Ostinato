# Pinned SIGIL test vectors

These files are consumed by the Java codec tests. The Java codec is a consumer:
sigil (`sigil.py`, `codebook.py` + README) stays the source of truth.

> PUBLIC TEST-ONLY KEY MATERIAL. The passphrases in the vector files are
> published test values, not keys. Never use them for real messages.

## S1C: `sigil-s1c-vectors.json`

Verbatim copy of `tests/vectors/s1c.json` from https://github.com/vexrypt-rgb/sigil

| Field | Value |
|---|---|
| sigil commit | `8ca227759c93e5c66b8cbad3153f388411983e33` (https://github.com/vexrypt-rgb/sigil/pull/1) |
| based on | sigil 0.3.0 (`697247b`), protocol S1 |
| file sha256 | `6603b5d101eac2d4861d9de46d49fce8d68616434a44a337db3321aea4d984fd` |
| generator | `tools/make_vectors.py` (records real `sigil.seal_circle` output) |
| test | `SigilVectorsTest` |

## S2C: `sigil-s2c-vectors.json`

Verbatim copy of `tests/vectors/s2c.json` (21 positive, 21 negative, 4 whole-message).

| Field | Value |
|---|---|
| sigil commit | `d773a35` on `main` (merge of https://github.com/vexrypt-rgb/sigil/pull/3), sigil 0.4.0, protocol S2 |
| file sha256 | `ce040981b51cc7dff64c2ffeba20322f5dc43731a8d31f430de9d99c58ea2c0e` |
| generator | `tools/make_vectors.py --suite s2c` (records real `sigil.seal_circle_s2` output) |
| test | `SigilS2CVectorsTest` |

## Codebook v2 lexicon: `src/main/resources/baritone/swarm/crypto/sigil-lexicon-v2.txt`

Verbatim copy of sigil's `lexicon_v2.txt` at the same commit (`d773a35`).
sha256 `46137094c869354a1c280884c80157d093f271e6bcab0a353516d5a524ec2278`. This value is
checked at load time (`SigilCodebookV2.LEXICON_SHA256`) and matches `lexicon_v2_sha256` in the S2C vectors.

## Codebook v2 expand cases: `sigil-codebook-v2-cases.json`

Derived fixture. It records sigil's own `codebook.compress_v2`/`expand_v2` results for
23 texts, plus `expand_v2` on 400 random 0xC2 streams (seed 20260927), including which
streams sigil rejects.

| Field | Value |
|---|---|
| sigil commit | `d773a35` |
| file sha256 | `f8e45af44d99f91296ab7338df326e200673f38bcd8766c9d2d0c65124226759` |
| generator | `python3 make_codebook_v2_cases.py /path/to/sigil > sigil-codebook-v2-cases.json` (script next to this file) |
| test | `SigilCodebookV2Test` |

To update: regenerate and verify in the sigil repo (`python3 -m unittest discover -s tests`),
copy the files here unchanged, and update these tables. All of the tests named above must stay green.
