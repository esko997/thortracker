#!/usr/bin/env bash
# Builds the YM2612 engine benchmark (tools/audio/ym_bench.c) for the host and, with the NDK, as a static arm64
# Android executable to push to a device:
#   tools/audio/build_ym_bench.sh [out-dir]          (default /tmp/ym_bench)
#   adb push <out-dir>/ym_bench-arm64 /data/local/tmp/ && adb shell /data/local/tmp/ym_bench-arm64 30 48000 10
# Builds -O2 (the flags of the app's CMake targets) and -O3 variants. ymfm is C++: linked with the static libc++.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-/tmp/ym_bench}"
mkdir -p "$out/obj"
cpp="$root/app/src/main/cpp"
ndk="${ANDROID_NDK:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
includes=(-I "$cpp" -I "$cpp/ym" -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/ymfm"
  -I "$root/app/src/test/cpp")
csrc=("$cpp/ym/ym_machine.c" "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c" "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_psg.c" "$cpp/cpuz80.c"
  "$cpp/third_party/blip_buf/blip_buf.c" "$root/tools/audio/ym_bench.c")
cxxsrc=("$cpp/ym/ym_ymfm.cpp" "$cpp/third_party/ymfm/ymfm_adpcm.cpp" "$cpp/third_party/ymfm/ymfm_ssg.cpp")

build() { # name cc cxx opt [extra link flags...]
    local name=$1 cc=$2 cxx=$3 opt=$4
    shift 4
    local objs=()
    for s in "${csrc[@]}"; do
        "$cc" -std=gnu11 "$opt" -DNDEBUG=1 -w "${includes[@]}" -c "$s" -o "$out/obj/$name-$(basename "${s%.*}").o"
        objs+=("$out/obj/$name-$(basename "${s%.*}").o")
    done
    for s in "${cxxsrc[@]}"; do
        "$cxx" -std=c++17 "$opt" -DNDEBUG=1 -w "${includes[@]}" -c "$s" -o "$out/obj/$name-$(basename "${s%.*}").o"
        objs+=("$out/obj/$name-$(basename "${s%.*}").o")
    done
    "$cxx" "${objs[@]}" -lm "$@" -o "$out/$name"
    echo "$out/$name"
}

build ym_bench-host "${CC:-gcc}" "${CXX:-g++}" -O2
if [[ -x "$bin/aarch64-linux-android33-clang" ]]; then
    build ym_bench-arm64 "$bin/aarch64-linux-android33-clang" "$bin/aarch64-linux-android33-clang++" -O2 -static -static-libstdc++
    build ym_bench-arm64-O3 "$bin/aarch64-linux-android33-clang" "$bin/aarch64-linux-android33-clang++" -O3 -static -static-libstdc++
else
    echo "no NDK at $ndk: host build only" >&2
fi
