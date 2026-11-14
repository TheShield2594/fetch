"""Seeds a throwaway XDG config for the desktop app: the demo addon only, three
profiles (one a PIN-locked kids profile) and some Continue Watching."""
import hashlib, json, os, sys, time

root = os.path.join(sys.argv[1], "fetch")
remember = sys.argv[2] == "remember"
os.makedirs(os.path.join(root, "profiles", "2"), exist_ok=True)
os.makedirs(os.path.join(root, "profiles", "3"), exist_ok=True)
BASE = "http://127.0.0.1:7171"

def pin_hash(pin, iterations=20_000):
    salt = os.urandom(16)
    return f"v1:{iterations}:{salt.hex()}:{hashlib.pbkdf2_hmac('sha256', pin.encode(), salt, iterations, 32).hex()}"

def dump(name, obj, d=root):
    with open(os.path.join(d, name), "w") as f:
        json.dump(obj, f, indent=2)

dump("addons.json", {"addons": [BASE], "disabled": []})
dump("profiles.json", {
    "profiles": [
        {"id": 1, "name": "Sam", "colorHex": "#E53935", "avatarId": "fetch:fox"},
        {"id": 2, "name": "Jess", "colorHex": "#FFB300", "avatarId": "fetch:owl"},
        {"id": 3, "name": "Kids", "colorHex": "#43A047", "avatarId": "fetch:dinosaur",
         "pinHash": pin_hash("1234"),
         "limits": {"maxRating": None, "kidsProfile": True, "dailyMinutes": 90}},
    ],
    "lastProfileId": 1,
    "rememberLastProfile": remember,
})

now = int(time.time() * 1000)
def entry(i, name, pos_min, dur_min, ago_h):
    return {"contentId": f"fetchdemo:{i}", "contentType": "movie", "name": name,
            "poster": f"{BASE}/art/poster_{i}.jpg?v=2", "backdrop": f"{BASE}/art/bg_{i}.jpg?v=2",
            "videoId": f"fetchdemo:{i}", "positionMs": pos_min * 60_000, "durationMs": dur_min * 60_000,
            "lastWatched": now - ago_h * 3_600_000}
dump("progress.json", {
    "entries": [
        entry("notld", "Night of the Living Dead", 41, 96, 2),
        entry("hgf", "His Girl Friday", 63, 92, 26),
        entry("sintel", "Sintel", 4, 15, 50),
    ],
    "watched": ["fetchdemo:general"],
    "finished": [{"contentId": "fetchdemo:general", "contentType": "movie", "name": "The General", "at": now - 90_000_000}],
})
dump("library.json", {"items": [
    {"id": "fetchdemo:nosferatu", "type": "movie", "name": "Nosferatu", "poster": f"{BASE}/art/poster_nosferatu.jpg?v=2",
     "background": f"{BASE}/art/bg_nosferatu.jpg?v=2", "releaseInfo": "1922", "genres": ["Horror"], "addonBaseUrl": BASE, "addedAt": now - 5_000_000},
    {"id": "fetchdemo:tos", "type": "movie", "name": "Tears of Steel", "poster": f"{BASE}/art/poster_tos.jpg?v=2",
     "background": f"{BASE}/art/bg_tos.jpg?v=2", "releaseInfo": "2012", "genres": ["Sci-Fi"], "addonBaseUrl": BASE, "addedAt": now - 9_000_000},
]})
print("seeded", root)
dump("settings.json", {"experience": {"mode": "ADVANCED"}, "player": {"builtInPlayer": True, "builtInGpu": False, "fullscreen": False}})
