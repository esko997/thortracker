#!/usr/bin/env bash
# Renders a lowered VIC-20 or C16/Plus/4 image through the native "vic20-vic" / "c16-ted" engine (the image's header
# byte 5 picks the chip; host gcc/g++, the same sources as the hosts; chip_engine.c registers every engine, so all of
# them are linked).
# Usage: tools/audio/render_cbm_image.sh image.cbimg out.wav [seconds] [mute=13]
# Images: `tools/build.sh exportSamples` writes app/build/samples/vic-demo.cbimg, vic-shortest.cbimg, ted-demo.cbimg and
# ted-shortest.cbimg. mute= lists lanes (1-based) to leave out: VIC 1 bass, 2 alto, 3 soprano, 4 noise; TED 1 voice 1,
# 2 voice 2, 3 DAC.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-cbm"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/cbm" -I "$cpp/opl3" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/ym" -I "$cpp/third_party/chips"
  -I "$cpp/third_party/binjgb/src" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3"
  -I "$cpp/third_party/ymfm")
objs=()
for src in "$cpp/chip_engine.c" "$cpp/cpu6502.c" "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c" \
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_vrc7.c" \
  "$cpp/third_party/emu2413/emu2413.c" "$cpp/chip_opll.c" "$cpp/nes/nes_players.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c" \
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c" \
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c" "$cpp/opl3/opl3_vgm.c" \
  "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c" "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" \
  "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c" "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c" \
  "$cpp/cbm/cbm_chip.c" "$cpp/cbm/cbm_machine.c" "$cpp/cbm/cbm_player.c" "$cpp/cbm/cbm_tables.c" "$cpp/cbm/cbm_image.c" \
  "$cpp/cbm/cbm_vic.c" "$cpp/cbm/cbm_ted.c" \
  "$root/tools/audio/cbm_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "$cpp/sid/sid_resid.cpp" "$cpp"/third_party/resid-1.0/{sid,voice,wave,envelope,filter,dac,extfilt,pot}.cc \
  "$cpp/ym/ym_ymfm.cpp" "$cpp/third_party/ymfm/ymfm_adpcm.cpp" "$cpp/third_party/ymfm/ymfm_ssg.cpp"; do
  o="$build/$(basename "${src%.*}").o"
  ${CXX:-g++} -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/cbm_render"
exec "$build/cbm_render" "$@"
