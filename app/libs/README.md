# Prebuilt AARs

These binaries are checked in because they need a native toolchain (NDK, CMake, FFmpeg and codec
sources) that the normal build doesn't have. They are built from the Media3 decoder extensions and
must match the app's Media3 version (`media3` in `gradle/libs.versions.toml`, 1.8.0 at the time of
writing).

| File                              | Source                                                                 | SHA-256                                                            |
|-----------------------------------|------------------------------------------------------------------------|--------------------------------------------------------------------|
| `lib-decoder-av1-release.aar`     | `androidx/media` `libraries/decoder_av1`                               | `e4f929a8132fdd2e9f8f65ad731c5e27f9251eced72aab3138e4520ab0e13e5f` |
| `lib-decoder-ffmpeg-release.aar`  | `androidx/media` `libraries/decoder_ffmpeg` (decoders below)           | `abd3ca3c4814006808fe500101b1b4d765a891fd470c22622fcd8aafb68eee72` |
| `lib-decoder-iamf-release.aar`    | `androidx/media` 1.8.0 `libraries/decoder_iamf` with `AOMediaCodec/libiamf` `4826ee2`; natives rebuilt for 16 KB pages (#663) | `db3c1dfc3966a8ac518cc6763695a58777a3c1c67853b44b3717befc2fc5bb0b` |
| `lib-decoder-mpegh-release.aar`   | `androidx/media` `libraries/decoder_mpegh`                             | `e21884ef2f0e88b97a6baa0442d3e63696d64fd6743c30675661501d9079b114` |
| `nextlib-mediainfo-local.aar`     | Local fork of nextlib's `mediainfo` module, with FFmpeg linked statically | `509e645e0f4232bdd3c6e1b2e4b82c3686140a0355f13656890f3641d8b7fb53` |

The upstream commit each was built from was not recorded when they were added, except the IAMF
one, whose native libraries were rebuilt with `scripts/rebuild_iamf_natives.sh` (its Java classes
are the original ones). When you rebuild
one, put the source commit next to it here and update its checksum (`sha256sum app/libs/*.aar`).

`USE_LOCAL_FFMPEG_DECODER=true` builds the FFmpeg extension from `ffmpeg-decoder-downmix/` instead
of using the prebuilt AAR.

## FFmpeg extension

The binary FFmpeg extension was built with the following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble the `.aar`:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## 16 KB pages

Every 64-bit native library has to be linked for 16 KB memory pages, or a 16 KB-page device (some
Android 15+ phones, Google TV from Android 16) refuses to load it (#663). CI runs
`scripts/check_16kb_alignment.py` on the release APK; run it on an AAR before checking it in:

```
python3 scripts/check_16kb_alignment.py app/libs/*.aar
```

A library that fails has to be relinked with `-Wl,-z,max-page-size=16384` (the NDK's default from
r28 on). Raising the alignment field in the ELF header alone isn't enough: the segments' offsets
have to line up with it too.
