# Landing page screenshots

The screens on the website's landing page (`web/apps/web/src/pages/LandingPage.tsx`) are the
desktop app's real ones, captured under Xvfb. To redo them after a UI change:

```bash
./gradlew -Pfetch.desktopOnly=true :desktop:createDistributable
scripts/landing-screenshots/capture.sh
```

That writes `web/apps/web/public/landing/tv-{profiles,home,details,player}.webp` (1600 wide) and
`-800.webp` copies, plus the poster strip in `posters/`. Set `WORK=<dir>` to keep the raw 1920x1080
PNGs somewhere you choose.

What it shows, and why it's safe to publish:

- **The demo profile** (`seed.py`): three profiles, one a kids profile with PIN 1234, Continue
  Watching part-way through three films, and settings that skip first-run setup and use the
  built-in player. It is a fresh `XDG_CONFIG_HOME`, so your own config is never read.
- **The only addon** (`addon.mjs`, on 127.0.0.1:7171) lists public-domain films (Night of the
  Living Dead, Nosferatu, The General, His Girl Friday) and Blender open movies (Big Buck Bunny,
  Sintel, Tears of Steel, Spring), with no ratings. Cinemeta and TMDB are not installed, so no
  catalog artwork can appear.
- **The art** (`make-art.sh`) is placeholder posters and backdrops drawn with ImageMagick in the
  app's style, and the "film" is a 96 minute still, so the player has a real seek bar.
- **The room** (`tv-room.jpg`) is a generated image of a TV on a wall; its screen is the
  1665x885 rectangle at (512, 157) that `capture.sh` composites into.

Needs Xvfb, xdotool, ImageMagick, ffmpeg, node, python3 and libmpv (`libmpv2` on Debian/Ubuntu)
for the built-in player. The clicks are fixed points in a 1920x1080 window, so a moved button
means adjusting them in `capture.sh`.
