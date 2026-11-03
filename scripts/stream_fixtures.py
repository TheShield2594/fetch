#!/usr/bin/env python3
"""Fetch real addon stream responses and save them, trimmed and scrubbed, as parser test fixtures.

StreamTitleParserFixturesTest (shared/src/test) reads these to check what the parser makes of
real addon text: multi-line titles, emoji badges, sizes and seeders in odd places, and each
addon's own way of marking cached results.

    scripts/stream_fixtures.py                      # write to shared/src/test/resources/streams
    scripts/stream_fixtures.py --out /tmp/streams   # write elsewhere
    scripts/stream_fixtures.py --only comet         # one addon

Torrentio and Comet are captured from their public instances. MediaFusion, Jackettio and
AIOStreams need a configured addon (and usually a debrid account), so they are only captured
when their manifest URL is set:

    MEDIAFUSION_URL=https://mediafusion.example/<config>/manifest.json \\
    JACKETTIO_URL=https://jackettio.example/<config>/manifest.json \\
    AIOSTREAMS_URL=https://aiostreams.example/stremio/<uuid>/<config>/manifest.json \\
        scripts/stream_fixtures.py

Set TORRENTIO_URL or COMET_URL the same way to capture a debrid-configured instance, which is
the only way to get cached ([RD+], ⚡) results.

Nothing account-specific is kept: playable URLs are replaced by placeholders, tracker lists and
proxy headers are dropped, and the configured addon path (where debrid keys live) is scrubbed
from every string. Check the diff before committing anyway.
"""

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request

USER_AGENT = "Fetch-fixtures/1.0"

# The addons the parser has to cope with. A default is only given where a public instance
# answers without configuration.
ADDONS = {
    "torrentio": ("TORRENTIO_URL", "https://torrentio.strem.fun/manifest.json"),
    "comet": ("COMET_URL", "https://comet.feels.legal/manifest.json"),
    "mediafusion": ("MEDIAFUSION_URL", None),
    "jackettio": ("JACKETTIO_URL", None),
    "aiostreams": ("AIOSTREAMS_URL", None),
}

# A spread of releases: an old catalogue film (remuxes, foreign dubs), recent blockbusters (DV,
# HDR10+, Atmos, WEB-DLs), episodes of a long-running and a recent show, and an anime episode.
TITLES = [
    ("movie", "tt0111161"),       # The Shawshank Redemption
    ("movie", "tt15239678"),      # Dune: Part Two
    ("movie", "tt15398776"),      # Oppenheimer
    ("series", "tt0903747:1:1"),  # Breaking Bad S01E01
    ("series", "tt3581920:1:1"),  # The Last of Us S01E01
    ("series", "tt22248376:1:1"), # Frieren: Beyond Journey's End S01E01
]

# Enough of each response to cover its variety without bloating the repo.
MAX_STREAMS = 25

# Hints the parser reads; the rest (proxy headers, notWebReady...) is dropped.
KEPT_HINTS = {"bingeGroup", "filename", "videoSize"}


def addon_base(manifest_url):
    url = manifest_url.strip()
    if url.startswith("stremio://"):
        url = "https://" + url[len("stremio://"):]
    return re.sub(r"/manifest\.json$", "", url.rstrip("/"))


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def scrub_text(text, secrets):
    for secret in secrets:
        text = text.replace(secret, "<config>")
    return text


def scrub(stream, addon, index, secrets):
    out = {}
    for key in ("name", "title", "description"):
        if isinstance(stream.get(key), str):
            out[key] = scrub_text(stream[key], secrets)
    if stream.get("infoHash"):
        out["infoHash"] = stream["infoHash"]
    if stream.get("fileIdx") is not None:
        out["fileIdx"] = stream["fileIdx"]
    for key in ("url", "externalUrl"):
        # Debrid links carry the account's key or a signed token: keep only that there was one.
        if stream.get(key):
            out[key] = f"https://example.invalid/{addon}/{index}"
    hints = stream.get("behaviorHints") or {}
    kept = {k: v for k, v in hints.items() if k in KEPT_HINTS}
    if "bingeGroup" in kept:
        kept["bingeGroup"] = scrub_text(kept["bingeGroup"], secrets)
    if kept:
        out["behaviorHints"] = kept
    return out


def secrets_for(base):
    """The configured part of an addon URL: everything in the path, and each segment of it."""
    path = urllib.parse.urlparse(base).path.strip("/")
    if not path:
        return []
    parts = [p for p in path.split("/") if len(p) >= 8]
    return sorted({path, *parts}, key=len, reverse=True)


def capture(addon, manifest_url):
    base = addon_base(manifest_url)
    secrets = secrets_for(base)
    out = {}
    for kind, video_id in TITLES:
        try:
            response = fetch_json(f"{base}/stream/{kind}/{urllib.parse.quote(video_id)}.json")
        except Exception as error:  # one title failing shouldn't lose the others
            print(f"{addon} {video_id}: {error}", file=sys.stderr)
            continue
        streams = response.get("streams") or []
        if not streams:
            print(f"{addon} {video_id}: no streams", file=sys.stderr)
            continue
        out[video_id.replace(":", "_")] = {
            "streams": [scrub(s, addon, i, secrets) for i, s in enumerate(streams[:MAX_STREAMS])]
        }
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--out", default=os.path.join(root, "shared/src/test/resources/streams"))
    parser.add_argument("--only", choices=sorted(ADDONS), action="append")
    args = parser.parse_args()
    written = 0
    for addon, (env, default) in ADDONS.items():
        if args.only and addon not in args.only:
            continue
        manifest_url = os.environ.get(env) or default
        if not manifest_url:
            print(f"{addon}: skipped, set {env} to a configured manifest URL", file=sys.stderr)
            continue
        responses = capture(addon, manifest_url)
        if not responses:
            continue
        directory = os.path.join(args.out, addon)
        os.makedirs(directory, exist_ok=True)
        for name, response in responses.items():
            path = os.path.join(directory, f"{name}.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(response, f, ensure_ascii=False, indent=1)
                f.write("\n")
            print(f"{path}: {len(response['streams'])} streams")
            written += 1
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
