# Prebuilt AARs

These binaries are checked in because they need a native toolchain (NDK, CMake, FFmpeg and codec
sources) that the normal build doesn't have. They are built from the Media3 decoder extensions and
must match the app's Media3 version (`media3` in `gradle/libs.versions.toml`, 1.8.0 at the time of
writing).

| File                              | Source                                                                 | SHA-256                                                            |
|-----------------------------------|------------------------------------------------------------------------|--------------------------------------------------------------------|
| `lib-decoder-av1-release.aar`     | `androidx/media` `libraries/decoder_av1`                               | `e4f929a8132fdd2e9f8f65ad731c5e27f9251eced72aab3138e4520ab0e13e5f` |
| `lib-decoder-ffmpeg-release.aar`  | `androidx/media` `libraries/decoder_ffmpeg` (decoders below)           | `abd3ca3c4814006808fe500101b1b4d765a891fd470c22622fcd8aafb68eee72` |
| `lib-decoder-iamf-release.aar`    | `androidx/media` `libraries/decoder_iamf`                              | `087a237d730868144d16aceea40a87992699efbff511245e81cdc0c71dbe7c96` |
| `lib-decoder-mpegh-release.aar`   | `androidx/media` `libraries/decoder_mpegh`                             | `e21884ef2f0e88b97a6baa0442d3e63696d64fd6743c30675661501d9079b114` |
| `nextlib-mediainfo-local.aar`     | Local fork of nextlib's `mediainfo` module, with FFmpeg linked statically | `509e645e0f4232bdd3c6e1b2e4b82c3686140a0355f13656890f3641d8b7fb53` |

The upstream commit each was built from was not recorded when they were added. When you rebuild
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
