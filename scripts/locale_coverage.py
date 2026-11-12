#!/usr/bin/env python3
"""Report how much of the TV app's English text each locale translates, and check the locale list.

    scripts/locale_coverage.py                 # table of every locale, worst first
    scripts/locale_coverage.py --min 30        # also fail if an offered locale is under 30%
    scripts/locale_coverage.py --markdown      # the table as Markdown (for a CI summary)

Coverage counts translatable <string>, <plurals> and <string-array> resources in
app/src/main/res/values*/strings*.xml. A resource counts as translated when the locale defines
it, whatever the text. Strings marked translatable="false" are left out.

It always fails when res/xml/locale_config.xml (which also drives the in-app language picker)
offers a locale with no resource folder. Folders that are not offered are listed as hidden: they
stay for devices already set to that language, but are not offered until more is translated.
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
TRANSLATABLE_TAGS = ("string", "plurals", "string-array")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Android keeps the pre-ISO-639 folder names for these languages.
LEGACY_FOLDER = {"he": "iw", "id": "in", "yi": "ji"}


def resource_names(directory):
    names = set()
    for path in sorted(glob.glob(os.path.join(directory, "strings*.xml"))):
        for element in ET.parse(path).getroot():
            if element.tag in TRANSLATABLE_TAGS and element.get("translatable") != "false":
                names.add(f"{element.tag}/{element.get('name')}")
    return names


def folder_for(tag):
    """values-* folder name for a BCP 47 tag from locale_config.xml (es-419 → b+es+419)."""
    parts = tag.split("-")
    language = LEGACY_FOLDER.get(parts[0], parts[0])
    if len(parts) == 1:
        return f"values-{language}"
    if len(parts) == 2 and re.fullmatch(r"[A-Z]{2}", parts[1]):
        return f"values-{language}-r{parts[1]}"
    return "values-b+" + "+".join([parts[0], *parts[1:]])


def offered_locales():
    root = ET.parse(os.path.join(RES, "xml", "locale_config.xml")).getroot()
    return [e.get(f"{ANDROID_NS}name") for e in root if e.tag == "locale"]


def locale_folders():
    folders = set()
    for path in glob.glob(os.path.join(RES, "values-*")):
        if glob.glob(os.path.join(path, "strings*.xml")):
            folders.add(os.path.basename(path))
    return folders


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--min", type=float, default=0, help="fail if an offered locale is below this percentage")
    parser.add_argument("--markdown", action="store_true", help="print the table as Markdown")
    args = parser.parse_args()

    english = resource_names(os.path.join(RES, "values"))
    offered = [tag for tag in offered_locales() if tag != "en"]
    folders = locale_folders()
    errors = []

    offered_folders = {}
    for tag in offered:
        folder = folder_for(tag)
        if folder not in folders:
            errors.append(f"locale_config.xml offers {tag} but there is no {folder}")
        offered_folders[folder] = tag
    rows = []
    for folder in sorted(folders):
        translated = len(resource_names(os.path.join(RES, folder)) & english)
        tag = offered_folders.get(folder)
        rows.append((100.0 * translated / len(english), tag, folder, translated))
    rows.sort(key=lambda row: (row[1] is None, row[0]))

    if args.markdown:
        print(f"| Locale | Folder | Translated | Coverage |\n|---|---|---|---|")
        for percent, tag, folder, translated in rows:
            label = tag or "hidden"
            print(f"| {label} | `{folder}` | {translated} / {len(english)} | {percent:.0f}% |")
    else:
        print(f"{len(english)} translatable English resources\n")
        for percent, tag, folder, translated in rows:
            label = tag or "hidden"
            print(f"{label:8} {folder:20} {translated:5} / {len(english)}  {percent:5.1f}%")

    for percent, tag, _, _ in rows:
        if tag and percent < args.min:
            errors.append(f"{tag} is {percent:.1f}% translated, under the {args.min:g}% minimum")
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
