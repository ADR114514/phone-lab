#!/usr/bin/env bash
# Pull experiment data from a connected debug device into data/raw/<app>/
# Usage: ./tools/pull-data.sh <package> <power|topic07|vibro>
set -euo pipefail
PKG="${1:?package name required}"
APP="${2:?app tag required}"
case "$APP" in
    power|topic07|vibro) ;;
    *) echo "Invalid app tag: expected power, topic07, or vibro" >&2; exit 2 ;;
esac
DEST="$(cd "$(dirname "$0")/.." && pwd)/data/raw/$APP"
mkdir -p "$DEST"
adb exec-out "run-as $PKG tar cf - files/experiments" | tar xf - -C "$DEST"
echo "Done -> $DEST"
