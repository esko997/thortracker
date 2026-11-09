#!/usr/bin/env bash
# Builds the POKEY engine benchmark (tools/audio/pokey_bench.c) for the host and, with the NDK, as a static
# arm64 Android executable to push to a device:
#   tools/audio/build_pokey_bench.sh [out-dir]          (default /tmp/pokey_bench)
#   adb push <out-dir>/pokey_bench-arm64 /data/local/tmp/ && adb shell /data/local/tmp/pokey_bench-arm64 30
# Builds -O2 (the flags of the app's CMake chip_engines target) and -O3 variants. All C: no C++ runtime.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-/tmp/pokey_bench}"
mkdir -p "$out"
cpp="$root/app/src/main/cpp"
ndk="${ANDROID_NDK:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
includes=(-I "$cpp" -I "$cpp/pokey" -I "$cpp/third_party/blip_buf" -I "$root/app/src/test/cpp")
src=("$cpp/pokey/pokey_machine.c" "$cpp/pokey/pokey_player.c" "$cpp/pokey/pokey_chip.c" "$cpp/pokey/pokey_image.c"
  "$cpp/third_party/blip_buf/blip_buf.c" "$root/tools/audio/pokey_bench.c")

build() { # name cc opt [extra link flags...]
    local name=$1 cc=$2 opt=$3
    shift 3
    "$cc" -std=gnu11 "$opt" -DNDEBUG=1 -w "${includes[@]}" "${src[@]}" -lm "$@" -o "$out/$name"
    echo "$out/$name"
}

build pokey_bench-host "${CC:-gcc}" -O2
if [[ -x "$bin/aarch64-linux-android33-clang" ]]; then
    build pokey_bench-arm64 "$bin/aarch64-linux-android33-clang" -O2 -static
    build pokey_bench-arm64-O3 "$bin/aarch64-linux-android33-clang" -O3 -static
else
    echo "no NDK at $ndk: host build only" >&2
fi
