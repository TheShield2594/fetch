#!/usr/bin/env bash
# Builds a signed, single-version APT repository from one .deb.
#
#   scripts/build-apt-repo.sh <fetch.deb> <output-dir>
#
# Needs: apt-utils (apt-ftparchive), gnupg, and the signing key already imported into the
# keyring. APT_GPG_KEY_ID names that key; APT_GPG_PASSPHRASE is used when set.
#
# The output is stateless: only the given .deb is kept in pool/, so the published branch never
# accumulates old ~70 MB packages. Users track "latest"; there is no downgrade path via apt.
set -euo pipefail

deb=${1:?usage: build-apt-repo.sh <fetch.deb> <output-dir>}
out=${2:?usage: build-apt-repo.sh <fetch.deb> <output-dir>}
: "${APT_GPG_KEY_ID:?APT_GPG_KEY_ID must name the signing key}"

suite=stable
comp=main
arch=amd64

rm -rf "$out"
mkdir -p "$out/pool/$comp" "$out/dists/$suite/$comp/binary-$arch"
cp "$deb" "$out/pool/$comp/"

cd "$out"
apt-ftparchive packages "pool/$comp" > "dists/$suite/$comp/binary-$arch/Packages"
gzip -9kf "dists/$suite/$comp/binary-$arch/Packages"

apt-ftparchive \
  -o "APT::FTPArchive::Release::Origin=Fetch" \
  -o "APT::FTPArchive::Release::Label=Fetch" \
  -o "APT::FTPArchive::Release::Suite=$suite" \
  -o "APT::FTPArchive::Release::Codename=$suite" \
  -o "APT::FTPArchive::Release::Architectures=$arch" \
  -o "APT::FTPArchive::Release::Components=$comp" \
  release "dists/$suite" > "dists/$suite/Release"

gpg_args=(--batch --yes --pinentry-mode loopback --local-user "$APT_GPG_KEY_ID")
if [ -n "${APT_GPG_PASSPHRASE:-}" ]; then
  gpg_args+=(--passphrase "$APT_GPG_PASSPHRASE")
fi
gpg "${gpg_args[@]}" --clearsign -o "dists/$suite/InRelease" "dists/$suite/Release"
gpg "${gpg_args[@]}" --armor --detach-sign -o "dists/$suite/Release.gpg" "dists/$suite/Release"

# Binary (dearmored) public key for Signed-By.
gpg --batch --export "$APT_GPG_KEY_ID" > fetch.gpg

# Stop Pages from running Jekyll over the repo.
touch .nojekyll
echo "APT repository written to $out"
