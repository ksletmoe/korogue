#!/usr/bin/env bash
# Downloads the DawnLike tileset (CC-BY 4.0, see demo/assets/dawnlike/ATTRIBUTION.md) into
# demo/assets/dawnlike/, for the animation showcase demo (krogue-aqo). Not vendored in git —
# this script is the source of truth; re-run it any time to (re)populate the directory.
#
#   demo/scripts/fetch-dawnlike.sh          # skip if already fetched
#   demo/scripts/fetch-dawnlike.sh --force  # re-download and re-extract
set -euo pipefail

SOURCE_URL="https://opengameart.org/sites/default/files/DawnLike_5.zip"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="$SCRIPT_DIR/../assets/dawnlike"
MARKER="$DEST_DIR/Characters/Player0.png" # present once a real extract has landed

if [[ "${1:-}" == "--force" ]]; then
  find "$DEST_DIR" -mindepth 1 ! -name ATTRIBUTION.md ! -name .gitkeep -exec rm -rf {} +
elif [[ -f "$MARKER" ]]; then
  echo "DawnLike already present at $DEST_DIR (use --force to re-fetch)."
  exit 0
fi

TMP_ZIP="$(mktemp -t dawnlike-XXXXXX.zip)"
trap 'rm -f "$TMP_ZIP"' EXIT

echo "Downloading $SOURCE_URL ..."
curl -fSL --max-time 60 -o "$TMP_ZIP" "$SOURCE_URL"

echo "Extracting into $DEST_DIR ..."
unzip -q -o "$TMP_ZIP" -d "$DEST_DIR"

echo "Done. $(find "$DEST_DIR" -type f -name '*.png' | wc -l | tr -d ' ') PNGs available."
