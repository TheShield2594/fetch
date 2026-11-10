# Fetch for desktop

A personal desktop tool for the owner and friends: browse addons, pick a source and play it in
mpv's own window (the default; the built-in player is opt-in), with watch state, profiles,
addons and settings kept in step with the TV app through the Fetch website. It is not a product
that tracks the TV app feature for feature, and desktop work never gates a TV release.

Linux is the first target. The app is Compose Multiplatform (JVM) and builds without the Android
SDK:

```bash
./gradlew -Pfetch.desktopOnly=true :desktop:run    # run it
./gradlew -Pfetch.desktopOnly=true :desktop:test   # tests
```

[docs/desktop.md](../docs/desktop.md) covers installing, packaging, where settings are kept and what
works. Code that both apps need lives in [`shared/`](../shared); desktop clients that duplicate
the TV's move there when they are next touched.
