#!/usr/bin/env bash
# Fetch the pinned SGDK commit (Stephane Dallongeville, MIT: the XGM Z80 driver the "ym2612-fm" engine runs, its
# assembler sjasm and the reference converter xgmtool) into a persistent cache, build sjasm and xgmtool for the host,
# and print the cache directory on the last stdout line: <dir>/src = the SGDK checkout, <dir>/bin/sjasm,
# <dir>/bin/xgmtool. prepare_ym.py runs this unless given --sgdk DIR.
#
# Cache: ${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/sgdk-<commit>. Idempotent: a complete
# build is reused. The checkout must be the pinned commit (git verifies the object hashes). Needs git, gcc, g++.
set -euo pipefail

COMMIT=9a78a44143a867cd8c5053b95cd061a21f0b73c2
URL=https://github.com/Stephane-D/SGDK.git

cache="${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/sgdk-${COMMIT:0:8}"
src="$cache/src"
bin="$cache/bin"
stamp="$bin/.thor-tracker-built"

log() { echo "fetch_sgdk: $*" >&2; }

if [[ -f "$stamp" && "$(cat "$stamp")" == "$COMMIT" && -x "$bin/sjasm" && -x "$bin/xgmtool" ]]; then
    echo "$cache"
    exit 0
fi

mkdir -p "$cache"
if [[ ! -d "$src/.git" ]]; then
    log "cloning $URL"
    git clone -q --filter=blob:none "$URL" "$src"
fi
git -C "$src" fetch -q origin "$COMMIT" 2>/dev/null || true
git -C "$src" -c advice.detachedHead=false checkout -q "$COMMIT"
[[ "$(git -C "$src" rev-parse HEAD)" == "$COMMIT" ]] || { log "checkout is not $COMMIT"; exit 1; }

mkdir -p "$bin"
log "building sjasm (tools/sjasm, zlib) and xgmtool (tools/xgmtool, MIT)"
# sjasm's sources assume MAX_PATH from Windows headers (its CMake defines MAX_PATH=MAXPATHLEN).
g++ -O2 -w -DMAX_PATH=MAXPATHLEN -include sys/param.h -o "$bin/sjasm" "$src"/tools/sjasm/src/*.cpp
gcc -O2 -w -o "$bin/xgmtool" "$src"/tools/xgmtool/src/*.c -lm
echo "$COMMIT" > "$stamp"
echo "$cache"
