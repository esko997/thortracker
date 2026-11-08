#!/usr/bin/env bash
# Renders a lowered SID image through the native "c64-sid" engine (host gcc/g++, the same sources as the hosts).
# Usage: tools/audio/render_sid_image.sh image.sidimg out.wav [seconds] [model=6581|8580] [mute=123]
# Images: `tools/build.sh exportSamples` writes app/build/samples/raster-run.sidimg (the SID demo song).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-sid"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/sid" -I "$cpp/nes" -I "$cpp/third_party/chips" -I "$cpp/third_party/binjgb/src"
  -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0")
objs=()
for src in "$cpp/chip_engine.c" "$cpp/cpu6502.c" "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c" \
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c" \
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c" "$root/tools/audio/sid_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "$cpp/sid/sid_resid.cpp" "$cpp"/third_party/resid-1.0/{sid,voice,wave,envelope,filter,dac,extfilt,pot}.cc; do
  o="$build/$(basename "${src%.*}").o"
  ${CXX:-g++} -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CXX:-g++} "${objs[@]}" -lm -o "$build/sid_render"
exec "$build/sid_render" "$@"
