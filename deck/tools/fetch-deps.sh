#!/usr/bin/env bash
# Fetches what the deck module builds against into deck/deps (gitignored), for quick local builds
# (CI and releases build SDL3 from source instead: tools/build-sdl3.sh):
#  - SDL3 headers from the official release tarball (zlib license),
#  - a libSDL3.so to link against: copied from the Steam Deck (SteamOS ships SDL3 system-wide; the binary uses the
#    Deck's own copy at run time). Usage: deck/tools/fetch-deps.sh [user@deck-host]
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
version=3.2.18
host=${1:-deck@192.168.1.20}
mkdir -p deps/lib
if [ ! -d "deps/SDL3-$version/include" ]; then
  curl -sSL "https://github.com/libsdl-org/SDL/releases/download/release-$version/SDL3-$version.tar.gz" | tar xz -C deps "SDL3-$version/include" "SDL3-$version/LICENSE.txt"
fi
if [ ! -f deps/lib/libSDL3.so ]; then
  scp -q "$host:/usr/lib/libSDL3.so.0" deps/lib/libSDL3.so.0
  ln -sf libSDL3.so.0 deps/lib/libSDL3.so
fi
ls deps deps/lib
