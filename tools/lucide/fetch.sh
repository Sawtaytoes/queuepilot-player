#!/usr/bin/env bash
# Vendor the icons named in icons.txt from the official lucide-static npm package.
# The tarball is checked against the registry's published integrity before use, and
# nothing from it is executed. Then run generate.py to rebuild the drawables.
set -euo pipefail
version=1.61.0
integrity='sha512-yLAiQB3MWUJEstsEDAwenSA3m7vr1tdLvgnC16Q/XMxRRma0DWwMs8M2bWlvgShrIs1m2mWMJ+0AiahqQO4GrA=='
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/lucide.tgz" "https://registry.npmjs.org/lucide-static/-/lucide-static-$version.tgz"
actual="sha512-$(openssl dgst -sha512 -binary "$work/lucide.tgz" | base64 -w0)"
[[ "$actual" == "$integrity" ]] || { echo "Integrity mismatch: $actual" >&2; exit 1; }
mkdir "$work/x"
tar -xzf "$work/lucide.tgz" -C "$work/x"
cp "$work/x/package/LICENSE" "$here/LICENSE"
rm -f "$here"/svg/*.svg
grep -v '^#' "$here/icons.txt" | while read -r name; do
  [[ -n "$name" ]] && cp "$work/x/package/icons/$name.svg" "$here/svg/$name.svg"
done
echo "Vendored lucide-static $version"
