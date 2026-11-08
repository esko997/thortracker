#!/usr/bin/env bash
# Fetch the pinned GoatTracker 2.77 release (Lasse Öörni / Cadaver; the source of the SID player and
# the reference packer the SID tools use) into a persistent cache, build its command-line packer
# gt2reloc and gtasm (tools/audio/sid/gtasm.c: a front end for the 6502 assembler GoatTracker embeds,
# the one player.s is written for), and print the directory holding both on the last stdout line.
# prepare_sid_player.py runs this unless given --goattracker DIR.
#
# Cache: ${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/goattracker-2.77
# (GoatTracker_2.77.zip, src/ = the extracted release, bin/ = gt2reloc + gtasm). Idempotent: a
# verified archive is not downloaded again and a complete build is reused (rebuilt when gtasm.c
# changes). The archive must match the pinned sha256 (the digest SourceForge lists for the file); a
# mismatching download is deleted and the script fails.
#
# gt2reloc links GoatTracker's SDL 1.2 front-end library (bme), so it needs sdl-config and a C/C++
# compiler; without them on the host the build runs in the `ubuntu` distrobox (install with
# `distrobox enter ubuntu -- sudo apt-get install -y build-essential libsdl1.2-compat-dev`).
# gt2reloc keeps file names in 60-byte buffers: run it from the song's directory with short
# relative names. Needs curl and unzip.
set -euo pipefail

VERSION=2.77
ASSET=GoatTracker_${VERSION}.zip
URL="https://downloads.sourceforge.net/project/goattracker2/GoatTracker%202/${VERSION}/${ASSET}"
SHA256=96c2bd6a6ab3aca2f5bb18b1c764ac6ea69ac245cae14002a72cd87c554561ef

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
gtasm_src="$here/sid/gtasm.c"
cache="${THOR_TRACKER_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thor-tracker}/goattracker-${VERSION}"
archive="$cache/$ASSET"
src="$cache/src"
bin="$cache/bin"
stamp="$bin/.thor-tracker-built"
want="$SHA256 $(cat "$gtasm_src" "${BASH_SOURCE[0]}" | sha256sum | cut -d' ' -f1)"

log() { echo "fetch_goattracker: $*" >&2; }

verify() {
    local actual
    actual=$(sha256sum "$1" | cut -d' ' -f1)
    if [[ "$actual" != "$SHA256" ]]; then
        log "sha256 mismatch for $1: got $actual, expected $SHA256; deleting it"
        rm -f "$1"
        return 1
    fi
}

if [[ -f "$stamp" && "$(cat "$stamp")" == "$want" && -x "$bin/gt2reloc" && -x "$bin/gtasm" ]]; then
    echo "$bin"
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

rm -rf "$src" "$bin" "$cache/build"
mkdir -p "$src" "$bin" "$cache/build/linux"
unzip -q "$archive" -d "$src"
[[ -f "$src/src/player.s" && -f "$src/src/gt2reloc.c" ]] || { log "$ASSET has no src/player.s"; exit 1; }

# Build in a scratch copy: the release ships prebuilt objects, and its makefile would regenerate
# goatdata.c (the embedded player.s) from goattrk2.dat; the shipped goatdata.c is used as is.
cp -r "$src/src" "$cache/build/src"
find "$cache/build/src" -name '*.o' -delete
cp "$gtasm_src" "$cache/build/src/asm/gtasm_main.c"
build_cmd="set -e; cd '$cache/build/src'
make -o goatdata.c -o goattrk2.dat ../linux/gt2reloc -j4 >'$cache/build/make.log' 2>&1 || { tail -20 '$cache/build/make.log' >&2; exit 1; }
cc -O2 -w -Iasm -o '$bin/gtasm' asm/gtasm_main.c asm/asmtab.c asm/chnkpool.c asm/expr.c asm/lexyy.c asm/log.c asm/membuf.c asm/membufio.c asm/namedbuf.c asm/parse.c asm/pc.c asm/vec.c
cp ../linux/gt2reloc '$bin/gt2reloc'"
if command -v sdl-config >/dev/null 2>&1 && command -v g++ >/dev/null 2>&1; then
    bash -c "$build_cmd"
else
    log "building in the ubuntu distrobox (no sdl-config/g++ on the host)"
    distrobox enter ubuntu -- bash -c "$build_cmd"
    # gt2reloc needs the container's SDL 1.2 at run time: a wrapper runs it there (same cwd; $HOME
    # and /tmp are shared). gtasm has no such dependency.
    mv "$bin/gt2reloc" "$bin/gt2reloc.bin"
    cat > "$bin/gt2reloc" <<'EOF'
#!/usr/bin/env bash
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec distrobox enter ubuntu -- bash -c 'cd "$1" && shift && exec "$0" "$@"' "$here/gt2reloc.bin" "$PWD" "$@"
EOF
    chmod +x "$bin/gt2reloc"
fi
echo "$want" > "$stamp"
log "built $bin/gt2reloc and $bin/gtasm"
echo "$bin"
