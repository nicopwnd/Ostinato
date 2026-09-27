#!/usr/bin/env python3
"""Regenerate the Ostinato settings screen's setting descriptions from the Javadoc in Settings.java.

    python scripts/gen_setting_descriptions.py

Writes src/launch/resources/assets/baritone/ostinato/setting-descriptions.json ({settingName: plain text}).
Run it after adding or re-documenting a setting; SettingDescriptionsTest fails when the file is stale.
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'api', 'java', 'baritone', 'api', 'Settings.java')
OUT = os.path.join(ROOT, 'src', 'launch', 'resources', 'assets', 'baritone', 'ostinato', 'setting-descriptions.json')

PATTERN = re.compile(r'((?:/\*\*(?:(?!\*/).)*\*/\s*)*)((?:@\w+\s*)*)public final Setting<(.+?)> (\w+) = new Setting<>\((.*?)\);[^\n]*$',
                     re.S | re.M)


def clean(block):
    lines = [re.sub(r'^\s*\*\s?', '', l) for l in block.split('\n')]
    doc = ' '.join(l.strip() for l in lines if l.strip())
    doc = re.sub(r'\{@(?:link|linkplain)\s+#?([^}]*)\}', r'\1', doc)
    doc = re.sub(r'\{@(?:code|literal)\s+([^}]*)\}', r'\1', doc)
    doc = re.sub(r'<li>', ' - ', doc)
    doc = re.sub(r'<[^>]+>', ' ', doc)
    doc = doc.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
    doc = doc.replace('\u2014', '-').replace('\u2013', '-').replace('\u2019', "'")
    return re.sub(r'\s+', ' ', doc).strip()


def main():
    src = open(SRC, encoding='utf-8').read()
    out = {}
    for m in PATTERN.finditer(src):
        docs, ann, _typ, name, _default = m.groups()
        if 'JavaOnly' in ann:
            continue
        blocks = re.findall(r'/\*\*(.*?)\*/', docs, re.S)
        out[name] = clean(blocks[-1]) if blocks else ''
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, indent=1, ensure_ascii=True, sort_keys=False)
        f.write('\n')
    print('%d settings, %d without a description -> %s' % (len(out), sum(1 for v in out.values() if not v), os.path.relpath(OUT, ROOT)))


if __name__ == '__main__':
    main()
