#!/usr/bin/env bash
# Renders a lowered WonderSwan image through the native "ws-wonderswan" / "ws-wonderswan-color" engine (by the image's
# model byte; host gcc/g++, the same sources as the hosts).
# Usage: tools/audio/render_ws_image.sh image.wsimg out.wav [seconds] [mute=25] [--vgm out.vgm] [title=..] [author=..] [date=..]
# mute digits: 1-4 = CH1-CH4, 5 = Hyper Voice. The app's sample exports include ws-demo.wsimg, wsc-demo.wsimg,
# ws-shortest.wsimg and wsc-shortest.wsimg (SampleExportTool); the host test writes ws_song.bin, wsc_song.bin,
# wsc_vgm.bin and ws_tone.bin (tools/audio/run_native_host_test.sh <dir>).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-ws"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/ws" -I "$cpp/third_party/chips"
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
  "$cpp/ws/ws_chip.c" "$cpp/ws/ws_machine.c" "$cpp/ws/ws_player.c" "$cpp/ws/ws_image.c" "$cpp/ws/ws_apu.c" "$cpp/ws/ws_vgm.c" \
  "$root/tools/audio/ws_render.c"; do
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
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/ws_render"
exec "$build/ws_render" "$@"
