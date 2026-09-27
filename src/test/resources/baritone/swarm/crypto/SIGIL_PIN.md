# Pinned SIGIL test vectors

`sigil-s1c-vectors.json` is a verbatim copy of `tests/vectors/s1c.json` from
https://github.com/vexrypt-rgb/sigil

| Field | Value |
|---|---|
| sigil commit | `8ca227759c93e5c66b8cbad3153f388411983e33` (branch `swarm/sigil-test-vectors`, https://github.com/vexrypt-rgb/sigil/pull/1) |
| based on | sigil 0.3.0 (`697247b`), protocol S1 |
| file sha256 | `6603b5d101eac2d4861d9de46d49fce8d68616434a44a337db3321aea4d984fd` |
| generator | `tools/make_vectors.py` (records real `sigil.seal_circle` output) |

> PUBLIC TEST-ONLY KEY MATERIAL. The passphrases in the vectors file are
> published test values, not keys. Never use them for real messages.

To update: regenerate/verify in the sigil repo (`python3 -m unittest discover -s tests`),
copy the file here unchanged, and update this table. `SigilVectorsTest` must stay green.
The Java codec is a consumer: sigil (`sigil.py` + README) stays the source of truth.
