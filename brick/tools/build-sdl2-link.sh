#!/usr/bin/env bash
# CI-only ARM64 link dependency. This upstream SDL is NEVER packaged or used on the device:
# NextUI's vendor SDL2 supplies its Mali video backend. No proprietary SDK/binary is downloaded.
set -euo pipefail
[[ ${CI:-} == true ]] || { echo 'Kotlin/Native ARM64 toolchain builds are CI-only' >&2; exit 1; }
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
version=2.30.8
sha=380c295ea76b9bd72d90075793971c8bcb232ba0a69a9b14da4ae8f603350058
mkdir -p deps/lib build/sdl2-link
archive="deps/SDL2-$version.tar.gz"
if [[ ! -f $archive ]]; then
  curl --fail --location --retry 5 --output "$archive.part" "https://www.libsdl.org/release/SDL2-$version.tar.gz"
  echo "$sha  $archive.part" | sha256sum --check --status
  mv "$archive.part" "$archive"
fi
echo "$sha  $archive" | sha256sum --check --status
tar -xzf "$archive" -C deps
# Kotlin 2.1.0's pinned LLVM and ARM64 glibc2.25/GCC8.3 toolchain, downloaded by :core:compileKotlinLinuxArm64.
konan="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"
llvm="$konan/llvm-16.0.0-x86_64-linux-essentials-80/bin"
gcc="$konan/aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2"
sysroot="$gcc/aarch64-unknown-linux-gnu/sysroot"
[[ -x $llvm/clang && -d $sysroot ]] || { echo 'Run :core:compileKotlinLinuxArm64 first' >&2; exit 1; }
export CC="$llvm/clang --target=aarch64-unknown-linux-gnu --sysroot=$sysroot --gcc-toolchain=$gcc"
export AR="$llvm/llvm-ar" RANLIB="$llvm/llvm-ranlib"
export CFLAGS='-O2 -fPIC'
export LDFLAGS="-fuse-ld=lld"
# Build upstream implementations only for linking, with no host X11/Wayland/audio dependency leakage.
(cd build/sdl2-link && ../../deps/SDL2-$version/configure --host=aarch64-unknown-linux-gnu   --enable-shared --disable-static --disable-rpath --disable-video-x11 --disable-video-wayland   --disable-video-opengl --disable-video-opengles --disable-video-vulkan --disable-alsa   --disable-pulseaudio --disable-jack --disable-pipewire --disable-esd --disable-arts   --disable-nas --disable-sndio --disable-fusionsound --disable-libudev --disable-dbus --disable-ibus   --disable-hidapi --disable-video-kmsdrm && make -j2)
cp -L build/sdl2-link/build/.libs/libSDL2-2.0.so.0 deps/lib/libSDL2-2.0.so.0
ln -sfn libSDL2-2.0.so.0 deps/lib/libSDL2.so
cp build/sdl2-link/include/SDL_config.h "deps/SDL2-$version/include/SDL_config.h"
