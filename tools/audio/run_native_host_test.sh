#!/usr/bin/env bash
# Builds and runs the native chip-engine regression tests on the host (gcc, no Android).
# Usage: tools/audio/run_native_host_test.sh [evidence-dir]   (default: evidence/audio)
# Game Boy: pass/fail per check, seek and per-tick timings, <dir>/host_demo.wav (48 kHz stereo)
# and <dir>/host_trace.txt (per-tick hUGEDriver APU register writes) for reference comparison.
# NES: <dir>/nes_ft.wav, <dir>/nes_trace.txt (per-frame APU writes) and <dir>/nes_ft.nsf (test song).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-$root/evidence/audio}"
build="$root/build/host-audio"
mkdir -p "$out" "$build"
cpp="$root/app/src/main/cpp"
flags=(-std=gnu11 -O2 -Wall -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable
  -Wno-sign-compare -Wno-missing-field-initializers -Wno-enum-compare)
engines=(
  "$cpp/chip_engine.c"
  "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c"
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c"
)
includes=(-I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf")
${CC:-gcc} "${flags[@]}" "${includes[@]}" "${engines[@]}" "$root/app/src/test/cpp/huge_machine_test.c" -lm -o "$build/huge_machine_test"
${CC:-gcc} "${flags[@]}" "${includes[@]}" -I "$root/app/src/test/cpp" "${engines[@]}" "$root/app/src/test/cpp/nes_machine_test.c" \
  -lm -o "$build/nes_machine_test"
status=0
"$build/huge_machine_test" "$out" || status=1
"$build/nes_machine_test" "$out" || status=1
exit $status
