#!/usr/bin/env bash
# Builds SDL3 from its official release tarball into deck/deps (headers in deps/SDL3-<version>/include, the shared
# library in deps/lib), so the Deck frontend links against and bundles the same SDL3. Needs cmake, a C compiler and
# the development headers of SDL's Linux backends (see .github/workflows/build.yml for the Ubuntu package list).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
version=3.2.18
# Built outside the repository: SDL's source includes an Android sample project (with its own Gradle wrapper).
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
rm -rf deps/lib && mkdir -p deps/lib
curl -sSL "https://github.com/libsdl-org/SDL/releases/download/release-$version/SDL3-$version.tar.gz" | tar xz -C "$work"
cmake -S "$work/SDL3-$version" -B "$work/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$work/install" \
  -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TESTS=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_EXAMPLES=OFF \
  -DSDL_WAYLAND=ON -DSDL_X11=ON -DSDL_PIPEWIRE=ON -DSDL_PULSEAUDIO=ON -DSDL_ALSA=ON -DSDL_HIDAPI=ON
cmake --build "$work/build"
cmake --install "$work/build"
rm -rf "deps/SDL3-$version" && mkdir -p "deps/SDL3-$version"
cp -r "$work/install/include" "deps/SDL3-$version/include"
cp "$work/SDL3-$version/LICENSE.txt" "deps/SDL3-$version/LICENSE.txt"
cp -L "$work/install/lib/libSDL3.so.0" deps/lib/libSDL3.so.0
ln -sf libSDL3.so.0 deps/lib/libSDL3.so
echo "SDL3 $version built into deck/deps"
