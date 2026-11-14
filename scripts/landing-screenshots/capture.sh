#!/usr/bin/env bash
# Captures the landing page's screenshots from the real desktop app.
#
#   ./gradlew -Pfetch.desktopOnly=true :desktop:createDistributable
#   scripts/landing-screenshots/capture.sh
#
# Runs the Linux build under Xvfb at 1920x1080 against a throwaway config (see
# seed.py) whose only addon is addon.mjs: public-domain films and Blender open
# movies on placeholder art, so no copyrighted artwork can appear. Takes four
# screens (profiles, Home, a title page, the player), puts each on the TV in
# tv-room.jpg, and writes them to web/apps/web/public/landing/ as WebP, at 1600
# and 800 wide. The raw 1920x1080 PNGs stay in the work directory.
#
# Needs: Xvfb, xdotool, ImageMagick, ffmpeg, cwebp (or ImageMagick's WebP),
# node, python3, and libmpv for the built-in player (Debian/Ubuntu: libmpv2).
# The clicks are at fixed points of a 1920x1080 window; if a layout moves,
# adjust them below.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
APP="$ROOT/desktop/build/compose/binaries/main/app/fetch/bin/fetch"
OUT="$ROOT/web/apps/web/public/landing"
WORK=${WORK:-$(mktemp -d)}
DISPLAY_NUM=${DISPLAY_NUM:-:99}

[ -x "$APP" ] || { echo "Build the desktop app first: ./gradlew -Pfetch.desktopOnly=true :desktop:createDistributable" >&2; exit 1; }

cleanup() {
  pkill -f "$APP" 2>/dev/null || true; pkill -f "com.fetch.desktop.MainKt" 2>/dev/null || true
  [ -n "${ADDON_PID:-}" ] && kill "$ADDON_PID" 2>/dev/null || true
  [ -n "${XVFB_PID:-}" ] && kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

"$HERE/make-art.sh" "$WORK"

Xvfb "$DISPLAY_NUM" -screen 0 1920x1080x24 -nolisten tcp >/dev/null 2>&1 &
XVFB_PID=$!
node "$HERE/addon.mjs" "$WORK" >"$WORK/addon.log" 2>&1 &
ADDON_PID=$!
export DISPLAY=$DISPLAY_NUM
sleep 2

# Fresh config, start the app, wait for its window and make it 1920x1080.
launch() {
  pkill -f "$APP" 2>/dev/null || true; pkill -f "com.fetch.desktop.MainKt" 2>/dev/null || true
  sleep 2
  rm -rf "$WORK/cfg"
  python3 "$HERE/seed.py" "$WORK/cfg" "$1" >/dev/null
  XDG_CONFIG_HOME="$WORK/cfg" "$APP" >"$WORK/app.log" 2>&1 &
  local win=""
  for _ in $(seq 1 60); do
    win=$(xdotool search --name '^Fetch$' 2>/dev/null | head -1) && [ -n "$win" ] && break
    sleep 1
  done
  [ -n "$win" ] || { echo "The app's window never appeared; see $WORK/app.log" >&2; exit 1; }
  sleep 3
  xdotool windowmove "$win" 0 0 windowsize "$win" 1920 1080
  sleep 6
  park
}
park() { xdotool mousemove 1910 1070; sleep 1; }
shot() { import -window root -crop 1920x1080+0+0 "$WORK/$1.png"; }

# Who's watching?, with the first profile focused.
launch picker
xdotool mousemove 701 563; sleep 1.5
shot profiles

# Home, then the hero's Details, then Resume in the built-in player.
launch remember
shot home
xdotool mousemove 373 531 click 1; sleep 6; park
shot details
xdotool mousemove 349 547 click 1; sleep 15
xdotool mousemove 960 700; sleep 1.5
shot player

# Onto the TV. Its screen in tv-room.jpg is 1665x885 at (512,157); a 16:9 screen
# fills it with a sliver cropped top and bottom, a touch dimmer, with a sheen.
mkdir -p "$OUT"
convert -size 1665x885 gradient:'rgba(255,255,255,0.07)-rgba(255,255,255,0)' "$WORK/sheen.png"
for s in profiles home details player; do
  convert "$WORK/$s.png" -resize 1665x885^ -gravity center -extent 1665x885 -modulate 92 \
    "$WORK/sheen.png" -compose screen -composite "$WORK/screen.png"
  convert "$HERE/tv-room.jpg" "$WORK/screen.png" -gravity northwest -geometry +512+157 \
    -compose over -composite -resize 1600x "$WORK/tv-$s.png"
  if command -v cwebp >/dev/null; then
    cwebp -quiet -q 80 "$WORK/tv-$s.png" -o "$OUT/tv-$s.webp"
  else
    convert "$WORK/tv-$s.png" -quality 80 "$OUT/tv-$s.webp"
  fi
  convert "$WORK/tv-$s.png" -resize 800x -quality 78 "$OUT/tv-$s-800.webp"
done

# The tracking section's poster strip.
mkdir -p "$OUT/posters"
for id in nosferatu general hgf tos spring bbb; do
  convert "$WORK/art/poster_$id.jpg" -resize 240x360 -quality 76 "$OUT/posters/$id.webp"
done

echo "Screenshots in $OUT (raw PNGs in $WORK)"
