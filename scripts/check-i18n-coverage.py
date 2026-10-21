#!/usr/bin/env python3
"""Fail when a key in a module's English strings.xml is missing from a locale.

Android's own `MissingTranslation` lint covers this, but it is downgraded to a
warning in build.gradle.kts and `lintDebug` only runs on PRs — so direct pushes
to main ship untranslated keys unnoticed. That is how 37 keys drifted out of all
11 locales at once (feature/keys 19, feature/settings 13, feature/rdp 5).

This runs in the fast per-push `checks` job instead: pure stdlib XML, ~1s.

`translatable="false"` keys are exempt, as are modules with no values-<loc>/
directory at all (a module that ships no translations is not "drifting").

It also fails on an unescaped apostrophe in any string. aapt2 rejects a bare
`'` at resource-flatten time ("Invalid unicode escape sequence in string"),
which only surfaces ~20 minutes later in `:app:merge*DebugResources` on the
build job — that is how #744's French `settings_ambiwidth_subtitle` (`d'Asie`)
died after passing every other check. Escape it as `\'` (or wrap the whole
string in double quotes). A double quote is legal unescaped, so only `'` is
flagged.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LOCALES = ["ar", "bn", "de", "es", "fr", "hi", "ja", "ko", "pt", "ru", "zh"]


def keys(path):
    """Translatable resource names in one strings.xml, or None if absent."""
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    return {
        el.get("name")
        for el in root
        if el.tag in ("string", "plurals", "string-array")
        and el.get("translatable") != "false"
    }


def raw_apostrophes(path):
    """1-based line numbers where a bare (unescaped) apostrophe appears in
    element content. aapt2 rejects these at flatten time. Comments and
    attribute values are stripped first; `&apos;`/`&#39;` contain no literal
    apostrophe character, so they pass untouched."""
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", lambda m: re.sub(r"[^\n]", " ", m.group()), text, flags=re.S)
    text = re.sub(r'="[^"]*"', lambda m: " " * len(m.group()), text)
    hits = []
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(r"(?<!\\)'", line):
            hits.append(i)
    return hits


def main():
    missing = 0
    for source in sorted(REPO.glob("**/src/main/res/values/strings.xml")):
        res = source.parent.parent
        module = str(res.relative_to(REPO)).replace("/src/main/res", "")
        english = keys(source)
        if not english:
            continue
        for locale in LOCALES:
            translated = keys(res / f"values-{locale}/strings.xml")
            if translated is None:
                continue  # ponytail: module ships no translations at all — not drift
            gap = sorted(english - translated)
            if gap:
                missing += len(gap)
                print(f"✖ {module} values-{locale}: {len(gap)} missing — {', '.join(gap)}")

    if missing:
        print(
            f"\n{missing} missing translations. Add each key to the locale's "
            f"strings.xml (translated, not copied from English), then rerun "
            f"scripts/i18n_export.py and commit docs/i18n/strings.json."
        )

    bad = 0
    for path in sorted(REPO.glob("**/src/main/res/values*/strings.xml")):
        for line in raw_apostrophes(path):
            bad += 1
            print(f"✖ {path}:{line}: unescaped apostrophe — aapt2 rejects it at "
                  f"flatten time; escape as \\' or wrap the string in double quotes")
    if bad:
        print(f"\n{bad} unescaped apostrophes (see #744).")
    if missing or bad:
        return 1
    print("✓ Every English key is present in all 11 locales.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
