#!/usr/bin/env bash
# Builds the Arch Linux package (fetch-<version>-1-x86_64.pkg.tar.zst) from the desktop app image.
#
#   scripts/build-arch-package.sh <app-image.tar.gz> <out-dir>
#
# The tarball is the one the workflows make from :desktop:createDistributable (a top-level fetch/
# directory). makepkg runs in the archlinux:base-devel container, so this works on any host with
# Docker; on Arch itself, set FETCH_ARCH_NATIVE=1 to run makepkg directly.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tarball="$(realpath "${1:?app image tarball}")"
out="${2:?output directory}"
mkdir -p "$out"
out="$(realpath "$out")"

# pacman's pkgver can't hold a '-'. Dropping it keeps a beta older than its stable release:
# 1.5.0-beta.2 becomes 1.5.0beta.2, and vercmp sorts a trailing letter before the bare 1.5.0.
version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*$/\1/p' "$root/app/build.gradle.kts" | head -n 1)"
[[ -n "$version_name" ]] || { echo "Could not read versionName from app/build.gradle.kts" >&2; exit 1; }
pkgver="${version_name//-/}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$root/desktop/packaging/arch/PKGBUILD" "$root/desktop/packaging/arch/fetch.desktop" "$work/"
cp "$root/LICENSE" "$root/desktop/icons/fetch.png" "$work/"
cp "$tarball" "$work/fetch-linux-x64.tar.gz"

if [[ "${FETCH_ARCH_NATIVE:-}" == "1" ]]; then
    (cd "$work" && FETCH_PKGVER="$pkgver" PKGDEST="$out" makepkg --nodeps --force --noconfirm)
else
    # makepkg refuses to run as root, so build as a user with the caller's uid (1000 when the
    # caller is root), which leaves the package owned by the caller. --nodeps: the runtime
    # dependencies aren't needed to package.
    docker run --rm \
        -v "$work:/build" -v "$out:/out" \
        -e FETCH_PKGVER="$pkgver" -e HOST_UID="$(( $(id -u) == 0 ? 1000 : $(id -u) ))" \
        archlinux:base-devel \
        bash -euc '
            useradd -m -u "$HOST_UID" -o builder
            chown -R builder /build /out
            cd /build
            runuser -u builder -- env FETCH_PKGVER="$FETCH_PKGVER" PKGDEST=/out \
                makepkg --nodeps --force --noconfirm
        '
fi

ls -l "$out"/*.pkg.tar.zst
