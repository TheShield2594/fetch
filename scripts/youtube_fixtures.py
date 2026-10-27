#!/usr/bin/env python3
"""Fetch real YouTube TV (TVHTML5) responses and save them, trimmed, as parser test fixtures.

Signed out, this saves search, a search continuation, a channel page and its tab pages, a
playlist and `next`. With YT_ACCESS_TOKEN set to a signed-in TV access token it also saves the
home, subscriptions and history feeds.

    scripts/youtube_fixtures.py                        # write to app/src/test/resources/youtube
    scripts/youtube_fixtures.py --out /tmp/youtube     # write elsewhere (the scheduled check does)

The trim keeps every key YouTubeResponseParser reads. It drops tracking, logging, styling and
accessibility data, keeps only the widest thumbnail, and shortens long lists to their first
items plus the last one, where YouTube puts the continuation.
"""

import argparse
import json
import os
import sys
import urllib.request

INNERTUBE_URL = "https://www.youtube.com/youtubei/v1"
# Kept in step with YouTubeService.
CLIENT_NAME = "TVHTML5"
CLIENT_NAME_ID = "7"
CLIENT_VERSION = "7.20260901.15.00"
USER_AGENT = (
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
    "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
)

SEARCH_QUERY = "nasa"
CHANNEL_ID = "UCLA_DiR1FfKNvjuUpBHmylQ"  # NASA

DROPPED_KEYS = {
    "trackingParams", "clickTrackingParams", "serializedContextData", "loggingContext",
    "loggingDirectives", "accessibility", "accessibilityData", "accessibilityText", "effects",
    "focusAppearance", "hoverAppearance", "darkColorPalette", "lightColorPalette",
    "watchEndpointSupportedOnesieConfig", "playerParams", "ustreamerConfig",
    "vssLoggingContext", "qoeLoggingContext", "sequenceParams", "styling", "metadataStyling",
    "responseContext", "frameworkUpdates", "adSignalsInfo", "topbar", "microformat",
    "attestation", "playerOverlays", "engagementPanels", "onResponseReceivedEndpoints",
    "transcriptRenderer", "reelPlayerOverlayRenderer", "commonConfig",
}
LIST_HEAD = 5


def innertube(endpoint, params, token=None):
    body = {
        "context": {
            "client": {
                "clientName": CLIENT_NAME,
                "clientVersion": CLIENT_VERSION,
                "clientScreen": "WATCH",
                "userAgent": USER_AGENT,
                "tvAppInfo": {"appQuality": "TV_APP_QUALITY_FULL_ANIMATION", "zylonLeftNav": True},
                "webpSupport": False,
                "animatedWebpSupport": True,
                "hl": "en",
                "gl": "US",
                "utcOffsetMinutes": 0,
            },
            "user": {"enableSafetyMode": False, "lockedSafetyMode": False},
        },
        "racyCheckOk": True,
        "contentCheckOk": True,
        **params,
    }
    headers = {
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT,
        "X-YouTube-Client-Name": CLIENT_NAME_ID,
        "X-YouTube-Client-Version": CLIENT_VERSION,
        "Origin": "https://www.youtube.com",
        "Referer": "https://www.youtube.com/tv",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"{INNERTUBE_URL}/{endpoint}?prettyPrint=false",
        data=json.dumps(body).encode(),
        headers=headers,
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def trim(value):
    if isinstance(value, dict):
        out = {}
        for key, child in value.items():
            if key in DROPPED_KEYS:
                continue
            if key in ("thumbnails", "sources") and isinstance(child, list) and child:
                # The parser only ever picks the widest.
                child = [max(child, key=lambda t: t.get("width", 0) if isinstance(t, dict) else 0)]
            out[key] = trim(child)
        return out
    if isinstance(value, list):
        if len(value) > LIST_HEAD + 1:
            value = value[:LIST_HEAD] + value[-1:]
        return [trim(child) for child in value]
    return value


def find_all(value, key):
    """Every value under [key] anywhere in [value], in document order."""
    if isinstance(value, dict):
        for k, child in value.items():
            if k == key:
                yield child
            yield from find_all(child, key)
    elif isinstance(value, list):
        for child in value:
            yield from find_all(child, key)


def first_continuation(response):
    for item in find_all(response, "continuationItemRenderer"):
        endpoint = item.get("continuationEndpoint", {})
        token = endpoint.get("continuationCommand", {}).get("token")
        if token:
            return token
        for command in endpoint.get("commandExecutorCommand", {}).get("commands", []):
            token = command.get("continuationCommand", {}).get("token")
            if token:
                return token
    for data in find_all(response, "nextContinuationData"):
        if data.get("continuation"):
            return data["continuation"]
    return None


def channel_tabs(response):
    """The channel's tab links (title, browse id, params): the untitled row of channel tiles."""
    tabs = []
    for tile in find_all(response, "tileRenderer"):
        if tile.get("contentType") != "TILE_CONTENT_TYPE_CHANNEL":
            continue
        endpoint = tile.get("onSelectCommand", {}).get("browseEndpoint", {})
        if endpoint.get("browseId") != CHANNEL_ID or not endpoint.get("params"):
            continue
        titles = [t for m in find_all(tile, "tileMetadataRenderer") for t in [m.get("title", {})]]
        title = next((t.get("simpleText") or "".join(r.get("text", "") for r in t.get("runs", [])) for t in titles), "")
        tabs.append((title or f"tab{len(tabs)}", endpoint["browseId"], endpoint["params"]))
    return tabs


def first_playlist(response):
    for endpoint in find_all(response, "browseEndpoint"):
        browse_id = endpoint.get("browseId", "")
        if browse_id.startswith("VLPL"):
            return browse_id
    return None


def first_video(response):
    for endpoint in find_all(response, "watchEndpoint"):
        if endpoint.get("videoId"):
            return endpoint["videoId"]
    return None


def fetch_all(token=None):
    """name -> response. Names are the fixture file names, without .json."""
    out = {}
    search = innertube("search", {"query": SEARCH_QUERY})
    out["search"] = search
    continuation = first_continuation(search)
    if continuation:
        out["search_continuation"] = innertube("search", {"continuation": continuation})
    channel = innertube("browse", {"browseId": CHANNEL_ID})
    out["channel"] = channel
    for title, browse_id, params in channel_tabs(channel):
        name = "".join(c for c in title.lower() if c.isalnum())
        out[f"channel_tab_{name}"] = innertube("browse", {"browseId": browse_id, "params": params})
    playlist_id = first_playlist(channel)
    if playlist_id:
        out["playlist"] = innertube("browse", {"browseId": playlist_id})
    video_id = first_video(channel) or first_video(search)
    if video_id:
        out["next"] = innertube("next", {"videoId": video_id})
    if token:
        out["home"] = innertube("browse", {"browseId": "default"}, token)
        out["subscriptions"] = innertube("browse", {"browseId": "FEsubscriptions"}, token)
        out["history"] = innertube("browse", {"browseId": "FEhistory"}, token)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--out", default=os.path.join(root, "app/src/test/resources/youtube"))
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    responses = fetch_all(os.environ.get("YT_ACCESS_TOKEN"))
    for name, response in responses.items():
        path = os.path.join(args.out, f"{name}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(trim(response), f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"{path}: {os.path.getsize(path)} bytes")
    return 0 if responses else 1


if __name__ == "__main__":
    sys.exit(main())
