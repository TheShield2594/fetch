#!/usr/bin/env bash
# Rebuilds the native libraries inside app/libs/lib-decoder-iamf-release.aar with 16 KB page
# alignment (#663), keeping its Java classes. The AAR's libiamf.so was linked with 4 KB pages,
# which a 16 KB-page device refuses to load.
#
# Sources, pinned to what the original AAR was built from:
#   - androidx/media 1.8.0, libraries/decoder_iamf/src/main/jni (the JNI wrapper)
#   - AOMediaCodec/libiamf 4826ee2 (its exported symbols match the original libiamf.so exactly)
#
# Needs: git, cmake, ninja, zip and the NDK in $ANDROID_NDK_HOME (r28+; r29 was used).
# Usage: scripts/rebuild_iamf_natives.sh [workdir]
set -euo pipefail

MEDIA3_TAG=1.8.0
LIBIAMF_COMMIT=4826ee2
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR="$ROOT/app/libs/lib-decoder-iamf-release.aar"
WORK="${1:-$(mktemp -d)}"
NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an NDK r28 or newer}"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d media ]; then
  git clone -q --depth 1 --branch "$MEDIA3_TAG" --filter=blob:none --sparse https://github.com/androidx/media.git media
  git -C media sparse-checkout set libraries/decoder_iamf
fi
rm -rf jni && mkdir jni
cp media/libraries/decoder_iamf/src/main/jni/* jni/
git clone -q https://github.com/AOMediaCodec/libiamf.git jni/libiamf
git -C jni/libiamf checkout -q "$LIBIAMF_COMMIT"

rm -rf out && mkdir -p out/jni
for abi in "${ABIS[@]}"; do
  cmake -S jni -B "build-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" > /dev/null
  ninja -C "build-$abi" iamfJNI > /dev/null
  mkdir -p "out/jni/$abi"
  "$STRIP" --strip-unneeded -o "out/jni/$abi/libiamfJNI.so" "build-$abi/libiamfJNI.so"
  "$STRIP" --strip-unneeded -o "out/jni/$abi/libiamf.so" "$(find "build-$abi" -name libiamf.so | head -1)"
done

(cd out && zip -q -X "$AAR" jni/*/libiamf.so jni/*/libiamfJNI.so)
python3 "$ROOT/scripts/check_16kb_alignment.py" "$AAR"
sha256sum "$AAR"
