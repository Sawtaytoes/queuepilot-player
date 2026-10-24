#!/usr/bin/env bash
# Vendor Outfit, Charcuterie's font.sans, as Android cannot read the library's WOFF2 files.
# The variable TTF and its SIL OFL 1.1 license come from the google/fonts repository at a
# pinned commit, and the font is checked against its known SHA-256 before use.
set -euo pipefail
commit=5f246070882b903ed95a911dba83d9d4a6836152
sha256=fc7287273e66929776e2ba54f144fe699080bec29f61bf649d70d871468aeade
here=$(cd "$(dirname "$0")" && pwd)
font="$here/../../android/app/src/main/res/font/outfit_variable.ttf"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base="https://raw.githubusercontent.com/google/fonts/$commit/ofl/outfit"
curl -fsSL -o "$work/outfit.ttf" "$base/Outfit%5Bwght%5D.ttf"
curl -fsSL -o "$here/OFL.txt" "$base/OFL.txt"
actual=$(sha256sum "$work/outfit.ttf" | cut -d' ' -f1)
[[ "$actual" == "$sha256" ]] || { echo "Checksum mismatch: $actual" >&2; exit 1; }
cp "$work/outfit.ttf" "$font"
echo "Vendored Outfit from google/fonts $commit"
