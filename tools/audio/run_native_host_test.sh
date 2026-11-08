#!/usr/bin/env bash
# Builds and runs the native chip-engine regression tests on the host (gcc/g++, no Android).
# Usage: tools/audio/run_native_host_test.sh [evidence-dir]   (default: evidence/audio)
# Game Boy: pass/fail per check, seek and per-tick timings, <dir>/host_demo.wav (48 kHz stereo)
# and <dir>/host_trace.txt (per-tick hUGEDriver APU register writes) for reference comparison.
# NES: <dir>/nes_ft.wav, <dir>/nes_trace.txt (per-frame APU writes) and <dir>/nes_ft.nsf (test song).
# SID: <dir>/sid_gt_8580.wav, sid_gt_6581.wav, sid_tone_8580.wav, sid_trace.txt (per-tick SID writes) and
# sid_gt.sid (GoatTracker's own packing of the test song), plus cost per tick and the realtime factor.
# The engines are C except reSID (C++17): C sources build with $CC, reSID and its C wrapper with $CXX,
# and the tests link with $CXX (libstdc++), as the app (CMake) and the Deck (deck/build.gradle.kts) do.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-$root/evidence/audio}"
build="$root/build/host-audio"
mkdir -p "$out" "$build/obj"
cpp="$root/app/src/main/cpp"
CC="${CC:-gcc}"
CXX="${CXX:-g++}"
warn=(-Wall -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable -Wno-sign-compare
  -Wno-missing-field-initializers -Wno-enum-compare)
includes=(-I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/third_party/chips"
  -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$root/app/src/test/cpp")
c_engines=(
  "$cpp/chip_engine.c" "$cpp/cpu6502.c"
  "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c"
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c"
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c"
)
cxx_engines=("$cpp/sid/sid_resid.cpp")
for f in sid voice wave envelope filter dac extfilt pot; do cxx_engines+=("$cpp/third_party/resid-1.0/$f.cc"); done
objs=()
for src in "${c_engines[@]}"; do
  o="$build/obj/$(basename "${src%.*}").o"
  "$CC" -std=gnu11 -O2 "${warn[@]}" "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "${cxx_engines[@]}"; do
  o="$build/obj/$(basename "${src%.*}").o"
  "$CXX" -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for t in huge_machine_test nes_machine_test sid_machine_test; do
  "$CC" -std=gnu11 -O2 "${warn[@]}" "${includes[@]}" -c "$root/app/src/test/cpp/$t.c" -o "$build/obj/$t.o"
  "$CXX" "$build/obj/$t.o" "${objs[@]}" -lm -o "$build/$t"
done
status=0
"$build/huge_machine_test" "$out" || status=1
"$build/nes_machine_test" "$out" || status=1
"$build/sid_machine_test" "$out" || status=1
exit $status
