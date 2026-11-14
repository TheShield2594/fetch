#!/usr/bin/env bash
# Draws the demo profile's placeholder art and the stand-in film for capture.sh.
#
#   scripts/landing-screenshots/make-art.sh <work-dir>
#
# Writes <work-dir>/art/poster_<id>.jpg (600x900), <work-dir>/art/bg_<id>.jpg
# (1920x1080) for every title in titles.txt, and <work-dir>/film.mp4: a 96
# minute still of one backdrop at 1 fps (about 20 MB), so the player has a real
# seek bar and duration to show. Needs ImageMagick and ffmpeg.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${1:?usage: make-art.sh <work-dir>}
FONT="$ROOT/app/src/main/res/font/schibsted_grotesk_variable.ttf"
mkdir -p "$WORK/art"

# A simple motif per title, drawn on the 600x900 poster.
motif() {
  case $1 in
    moon) echo "fill #f2e9d8 fill-opacity 0.85 circle 400,330 400,420" ;;
    arch) echo "fill none stroke #f2e9d8 stroke-opacity 0.7 stroke-width 10 arc 170,170 430,690 180,360" ;;
    rails) echo "stroke #f2e9d8 stroke-opacity 0.6 stroke-width 8 line 120,760 300,260 line 480,760 300,260" ;;
    lines) echo "fill #f2e9d8 fill-opacity 0.75 rectangle 90,250 510,262 rectangle 90,290 430,302 rectangle 90,330 470,342 rectangle 90,370 380,382" ;;
    sun) echo "fill #ffd27a fill-opacity 0.85 circle 300,360 300,470" ;;
    grid) echo "stroke #9fc2cc stroke-opacity 0.45 stroke-width 3 line 0,300 600,300 line 0,380 600,380 line 0,460 600,460 line 150,200 150,600 line 300,200 300,600 line 450,200 450,600" ;;
  esac
}

while IFS='|' read -r id title year credit c1 c2 shape; do
  draw=$(motif "$shape")
  grain=(\( -size 600x900 xc: +noise Random -colorspace gray -evaluate multiply 0.06 \) -compose screen -composite)
  convert -size 600x900 gradient:"$c2-$c1" -draw "$draw" "${grain[@]}" \
    -font "$FONT" -fill '#ffffff' -pointsize 64 -gravity southwest \
    -annotate +44+150 "$(echo "$title" | fold -s -w 14)" \
    -fill '#dcdcdc' -pointsize 26 -annotate +46+90 "$year  ·  $credit" \
    -quality 88 "$WORK/art/poster_$id.jpg"

  # The backdrop: the same motif, larger, on the right, like a still.
  convert -size 600x900 xc:none -draw "$draw" -resize 160% -blur 0x2 "$WORK/art/motif.png"
  convert -size 1080x1920 gradient:"$c2-$c1" -rotate 90 \
    "$WORK/art/motif.png" -gravity east -geometry +180+0 -compose over -composite \
    \( -size 1920x1080 xc: +noise Random -colorspace gray -evaluate multiply 0.05 \) -compose screen -composite \
    -quality 85 "$WORK/art/bg_$id.jpg"
done < "$HERE/titles.txt"
rm -f "$WORK/art/motif.png"

# The player screenshot resumes Night of the Living Dead, so the film is its backdrop.
ffmpeg -loglevel error -y -loop 1 -framerate 1 -i "$WORK/art/bg_notld.jpg" \
  -f lavfi -i anullsrc=r=48000:cl=stereo -t 5760 \
  -vf "scale=1280:720,format=yuv420p" -c:v libx264 -preset veryfast -tune stillimage -crf 30 -g 60 \
  -c:a aac -b:a 16k -shortest "$WORK/film.mp4"

echo "art and film in $WORK"
