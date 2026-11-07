#!/usr/bin/env bash
# Fetch the pinned FamiStudio 4.5.3 Linux release (the reference the NES tools export with) into a
# persistent cache and print the directory that contains FamiStudio.dll on the last stdout line.
# nes_golden.py and nes_reference_check.py run this unless given --famistudio DIR; elsewhere:
#
#   tools/audio/prepare_nes_player.py --famistudio "$(tools/audio/fetch_famistudio.sh)"
#
# Cache: ${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/famistudio-4.5.3
# (FamiStudio453-LinuxAMD64.zip + app/). Idempotent: a verified archive is not downloaded again and a
# complete extraction is reused. The archive must match the pinned sha256 (the digest GitHub lists
# for the release asset); a mismatching download is deleted and the script fails.
# Needs curl and unzip. Running FamiStudio needs .NET 8 (`dotnet`, e.g. in the ubuntu distrobox).
set -euo pipefail

VERSION=4.5.3
ASSET=FamiStudio453-LinuxAMD64.zip
URL="https://github.com/BleuBleu/FamiStudio/releases/download/${VERSION}/${ASSET}"
SHA256=9133afed2fb384663b0735d8e950cdc2714849f4fd476b16de8d56e5e81da0ad

cache="${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/famistudio-${VERSION}"
archive="$cache/$ASSET"
app="$cache/app"
stamp="$app/.thor-tracker-extracted"

log() { echo "fetch_famistudio: $*" >&2; }

verify() {
    local actual
    actual=$(sha256sum "$1" | cut -d' ' -f1)
    if [[ "$actual" != "$SHA256" ]]; then
        log "sha256 mismatch for $1: got $actual, expected $SHA256; deleting it"
        rm -f "$1"
        return 1
    fi
}

if [[ -f "$stamp" && "$(cat "$stamp")" == "$SHA256" && -f "$app/FamiStudio.dll" ]]; then
    echo "$app"
    exit 0
fi

mkdir -p "$cache"
if [[ -f "$archive" ]] && verify "$archive"; then
    log "using cached $archive"
else
    log "downloading $URL"
    curl -fL --retry 3 -o "$archive.part" "$URL"
    verify "$archive.part" || { log "download of $URL failed verification"; exit 1; }
    mv "$archive.part" "$archive"
fi

staging="$cache/app.extracting"
rm -rf "$staging" "$app"
mkdir "$staging"
unzip -q "$archive" -d "$staging"
if [[ ! -f "$staging/FamiStudio.dll" ]]; then
    log "$ASSET has no FamiStudio.dll at its top level"
    exit 1
fi
echo "$SHA256" > "$staging/.thor-tracker-extracted"
mv "$staging" "$app"
log "extracted to $app"
echo "$app"
