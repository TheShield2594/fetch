#!/usr/bin/env python3
"""Checks that every 64-bit native library is ready for 16 KB memory pages (#663).

Android 15+ devices (and Google TV devices from Android 16) can run with 16 KB pages. On those,
a `.so` whose LOAD segments are aligned to less than 16 KB fails to load, whatever store the app
came from. Two things have to hold:

1. ELF: every PT_LOAD segment of each arm64-v8a and x86_64 library has p_align >= 16384
   (linked with `-Wl,-z,max-page-size=16384`, the default from NDK r28).
2. APK: each library stored uncompressed (`useLegacyPackaging = false`, as Fetch ships them)
   starts at a 16 KB-aligned offset in the zip, so it can be mapped straight from the APK.
   AGP 8.5.1+ does this when it packages; `zipalign -c -P 16 -v 4` checks the same thing.

32-bit ABIs (armeabi-v7a, x86) are reported but never fail: 16 KB pages are 64-bit only.

Usage:
    scripts/check_16kb_alignment.py app/build/outputs/apk/release/*.apk
    scripts/check_16kb_alignment.py app/libs/*.aar        # ELF check only
    scripts/check_16kb_alignment.py path/to/dir           # every .so under it

Exits 1 when a 64-bit library fails either check.
"""

from __future__ import annotations

import os
import struct
import sys
import zipfile

PAGE = 16 * 1024
CHECKED_ABIS = {"arm64-v8a", "x86_64"}
PT_LOAD = 1


def load_alignments(data: bytes) -> list[int] | None:
    """p_align of each PT_LOAD segment, or None when [data] isn't an ELF file."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type != PT_LOAD:
            continue
        if is64:
            p_align, = struct.unpack_from(endian + "Q", data, off + 0x30)
        else:
            p_align, = struct.unpack_from(endian + "I", data, off + 0x1C)
        aligns.append(p_align)
    return aligns


def abi_of(path: str) -> str:
    parts = path.replace("\\", "/").split("/")
    return parts[-2] if len(parts) >= 2 else "?"


def data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where an entry's bytes start: after its local header, which has its own extra field."""
    fp = archive.fp
    assert fp is not None
    fp.seek(info.header_offset)
    header = fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_entry(label: str, abi: str, data: bytes, zip_offset: int | None, stored: bool | None) -> list[str]:
    problems = []
    aligns = load_alignments(data)
    if aligns is None:
        return [f"{label}: not an ELF file"]
    smallest = min(aligns) if aligns else 0
    if smallest < PAGE:
        problems.append(f"{label}: LOAD segments aligned to {smallest} bytes (need {PAGE})")
    if zip_offset is not None and stored and zip_offset % PAGE != 0:
        problems.append(f"{label}: stored uncompressed at offset {zip_offset}, not 16 KB-aligned in the APK")
    return problems


def scan_zip(path: str) -> tuple[int, list[str], list[str]]:
    is_apk = path.endswith(".apk") or path.endswith(".aab")
    checked, failures, notes = 0, [], []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            abi = abi_of(info.filename)
            label = f"{os.path.basename(path)}!{info.filename}"
            stored = info.compress_type == zipfile.ZIP_STORED
            offset = data_offset(archive, info) if is_apk else None
            problems = check_entry(label, abi, archive.read(info), offset, stored)
            if abi in CHECKED_ABIS:
                checked += 1
                failures += problems
            else:
                notes += problems
    return checked, failures, notes


def scan_dir(path: str) -> tuple[int, list[str], list[str]]:
    checked, failures, notes = 0, [], []
    for root, _, files in os.walk(path):
        for name in files:
            if not name.endswith(".so"):
                continue
            full = os.path.join(root, name)
            abi = abi_of(full)
            with open(full, "rb") as handle:
                problems = check_entry(full, abi, handle.read(), None, None)
            if abi in CHECKED_ABIS:
                checked += 1
                failures += problems
            else:
                notes += problems
    return checked, failures, notes


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    total, failures, notes = 0, [], []
    for target in argv:
        if os.path.isdir(target):
            result = scan_dir(target)
        elif zipfile.is_zipfile(target):
            result = scan_zip(target)
        else:
            print(f"Skipping {target}: not a directory, APK or AAR")
            continue
        total += result[0]
        failures += result[1]
        notes += result[2]
    for note in notes:
        print(f"note (32-bit, not required): {note}")
    for failure in failures:
        print(f"FAIL {failure}")
    if failures:
        print(f"\n{len(failures)} problem(s) in {total} 64-bit libraries checked.")
        return 1
    print(f"All {total} 64-bit native libraries are 16 KB page-aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
