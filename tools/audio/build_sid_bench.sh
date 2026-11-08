#!/usr/bin/env bash
# Builds the SID engine benchmark (tools/audio/sid_bench.c) for the host and, with the NDK, as a
# static-libc++ arm64 Android executable to push to a device:
#   tools/audio/build_sid_bench.sh [out-dir]          (default /tmp/sid_bench)
#   adb push <out-dir>/sid_bench-arm64 /data/local/tmp/ && adb shell /data/local/tmp/sid_bench-arm64 30
# Builds -O2 (the flags of the app's CMake chip_engines target) and -O3 variants.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-/tmp/sid_bench}"
mkdir -p "$out"
cpp="$root/app/src/main/cpp"
ndk="${ANDROID_NDK:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
includes=(-I "$cpp" -I "$cpp/sid" -I "$cpp/third_party/chips" -I "$cpp/third_party/resid-1.0" -I "$root/app/src/test/cpp")
c_src=("$cpp/cpu6502.c" "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$root/tools/audio/sid_bench.c")
cxx_src=("$cpp/sid/sid_resid.cpp")
for f in sid voice wave envelope filter dac extfilt pot; do cxx_src+=("$cpp/third_party/resid-1.0/$f.cc"); done

build() { # name cc cxx opt [extra link flags...]
    local name=$1 cc=$2 cxx=$3 opt=$4
    shift 4
    local tmp
    tmp=$(mktemp -d)
    for s in "${c_src[@]}"; do "$cc" -std=gnu11 "$opt" -DNDEBUG=1 -w "${includes[@]}" -c "$s" -o "$tmp/$(basename "$s").o"; done
    for s in "${cxx_src[@]}"; do "$cxx" -std=c++17 "$opt" -DNDEBUG=1 -w "${includes[@]}" -c "$s" -o "$tmp/$(basename "$s").o"; done
    "$cxx" "$tmp"/*.o -lm "$@" -o "$out/$name"
    rm -rf "$tmp"
    echo "$out/$name"
}

build sid_bench-host "${CC:-gcc}" "${CXX:-g++}" -O2
if [[ -x "$bin/aarch64-linux-android33-clang" ]]; then
    build sid_bench-arm64 "$bin/aarch64-linux-android33-clang" "$bin/aarch64-linux-android33-clang++" -O2 -static-libstdc++
    build sid_bench-arm64-O3 "$bin/aarch64-linux-android33-clang" "$bin/aarch64-linux-android33-clang++" -O3 -static-libstdc++
else
    echo "no NDK at $ndk: host build only" >&2
fi
