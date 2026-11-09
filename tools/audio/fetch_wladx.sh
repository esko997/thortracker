#!/usr/bin/env bash
# Fetch the pinned WLA-DX release (Ville Helin, GPL-2.0-or-later: the assembler and linker Banjo is written for; a
# build tool only, nothing of it ends up in Thor Tracker) into a persistent cache, build wla-z80 and wlalink for the
# host and print the cache directory on the last stdout line: <dir>/bin/wla-z80, <dir>/bin/wlalink.
# prepare_sms.py runs this unless given --wladx DIR.
#
# Cache: ${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/wladx-<commit>. Idempotent: a complete
# build is reused. The checkout must be the pinned commit (git verifies the object hashes). Needs git, cmake and a C
# compiler.
set -euo pipefail

COMMIT=91c52b1f4ef3cc8ba3c0638f7536539579af6a9f # tag v10.7
URL=https://github.com/vhelin/wla-dx.git

cache="${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/wladx-${COMMIT:0:8}"
src="$cache/src"
bin="$cache/bin"
stamp="$bin/.thor-tracker-built"

log() { echo "fetch_wladx: $*" >&2; }

if [[ -f "$stamp" && "$(cat "$stamp")" == "$COMMIT" && -x "$bin/wla-z80" && -x "$bin/wlalink" ]]; then
    echo "$cache"
    exit 0
fi

mkdir -p "$cache"
if [[ ! -d "$src/.git" ]]; then
    log "cloning $URL"
    git clone -q --filter=blob:none "$URL" "$src"
fi
git -C "$src" fetch -q origin "$COMMIT" 2>/dev/null || git -C "$src" fetch -q origin
git -C "$src" checkout -q --detach "$COMMIT"
[[ "$(git -C "$src" rev-parse HEAD)" == "$COMMIT" ]] || { log "checkout is not $COMMIT"; exit 1; }

log "building wla-z80 and wlalink"
rm -rf "$cache/build"
cmake -S "$src" -B "$cache/build" -DCMAKE_BUILD_TYPE=Release -DGDB_DEBUGGING=OFF >&2
cmake --build "$cache/build" --config Release --target wla-z80 wlalink -j 2 >&2
mkdir -p "$bin"
for tool in wla-z80 wlalink; do
    found="$(find "$cache/build" "$src/binaries" -type f -name "$tool" -perm -u+x 2>/dev/null | head -1)"
    [[ -n "$found" ]] || { log "$tool was not built"; exit 1; }
    cp "$found" "$bin/$tool"
done
echo "$COMMIT" > "$stamp"
echo "$cache"
