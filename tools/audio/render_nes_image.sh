#!/usr/bin/env bash
# Renders a lowered NES image through the native "nes-famistudio" engine (host gcc, same sources as the hosts).
# Usage: tools/audio/render_nes_image.sh image.nesimg out.wav [seconds]
# Images: `tools/build.sh exportSamples` writes app/build/samples/thor-quest.nesimg (the NES demo song).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
${CC:-gcc} -std=gnu11 -O2 -Wall -Wno-unused-function -Wno-unused-variable -Wno-sign-compare -Wno-enum-compare \
  -I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf" \
  "$cpp/chip_engine.c" "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c" \
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c" \
  "$root/tools/audio/nes_render.c" -lm -o "$build/nes_render"
exec "$build/nes_render" "$@"
