# Releasing Fetch

## Version numbers

`versionName` and `versionCode` live in `app/build.gradle.kts`.

- **versionName** is semantic: `MAJOR.MINOR.PATCH`, with `-beta.N` for pre-releases
  (`1.2.0-beta.8`). The in-app updater compares release tags by this, so a tag must match it
  with a leading `v` (`v1.2.0-beta.8`).
- **versionCode** is `MAJOR × 1000 + MINOR × 100 + N`, where `N` counts every build of that
  minor line in order: its betas, then the stable release, then patch releases.

| versionName    | versionCode |
|----------------|-------------|
| 1.2.0-beta.1   | 1201        |
| 1.2.0-beta.8   | 1208        |
| 1.2.0          | 1209        |
| 1.2.1          | 1210        |
| 1.3.0-beta.1   | 1301        |

`N` must stay below 100. If a minor line ever needs more builds than that, move to the next
minor version. `scripts/release-metadata.sh` refuses a versionCode that doesn't go up.

The fork did not restart at `0.1.0`. Testers already have `1.2.0-beta.x` installed. Android
won't install a lower versionCode over a higher one, and the updater would treat `0.1.0` as
older than what they have.

## Signing

The release build signs with the key from these repository secrets:

| Secret                           | Contents                                  |
|----------------------------------|-------------------------------------------|
| `FETCH_RELEASE_KEYSTORE_BASE64`  | The `.jks` keystore, base64-encoded       |
| `FETCH_RELEASE_KEY_ALIAS`        | Key alias                                 |
| `FETCH_RELEASE_KEY_PASSWORD`     | Key password                              |
| `FETCH_RELEASE_STORE_PASSWORD`   | Keystore password                         |

Without them the build falls back to the debug key, and the "Release signing" step in the
workflow log says so. An install signed with the debug key can't be updated in place by one
signed with the release key, so set the secrets before handing builds to anyone. Keep a copy of
the keystore outside GitHub: if it is lost, every user has to uninstall to move to a new key.

## Publishing a release

1. Bump `versionName` and `versionCode` as described above and merge to `main`.
2. Go to **Actions → Release → Run workflow**. Enter the tag (`v1.2.0-beta.9`), and tick
   "pre-release" for betas.
3. The workflow builds the per-ABI APKs and a universal APK, names them
   `fetch-<tag>-<abi>.apk`, and attaches them to a new GitHub release with generated notes.

## Device smoke test

Run this on an Android TV, a Fire TV and a Google TV device before marking a release stable.
Install the release APK over the previous release, not on a clean device, so an update that
won't install in place shows up here.

- [ ] First launch: first-run setup opens and finishes with Back and OK only.
- [ ] Install Cinemeta and a debrid stream addon from Sources, then from the phone QR page.
- [ ] Play a movie with Best match: the Play button names the file, and playback starts.
- [ ] Bare torrent results don't appear in Sources.
- [ ] External player hand-off: with mpv or Just Player installed, "Ask every time" offers it,
  and the external player starts the same stream.
- [ ] Trakt sign-in completes with the device code, and a finished episode scrobbles.
- [ ] The Watch Next row on the launcher shows the title you just stopped.
- [ ] QR phone config: the page opens on a phone on the same network, and a change made there
  shows on the TV.

## Package names

The TV build is `com.fetch.tv` and the Play Store flavor is `com.fetch.app`. As of 2026-10-26
neither has a public Play Store listing. Check again before a store submission, since an
unlisted or removed app can still hold a package name.
