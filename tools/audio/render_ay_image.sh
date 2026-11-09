#!/usr/bin/env bash
# Renders a lowered AY-3-8910 / YM2149 image through the native engine of its variant ("ay-zx128", "ay-msx",
# "ay-atari-st", "ay-cpc", from image byte 5; host gcc/g++, the same sources as the hosts; chip_engine.c registers every engine,
# so all of them are linked).
# Usage: tools/audio/render_ay_image.sh image.ayimg out.wav [seconds] [mute=13] [--vgm out.vgm] [title=..] [author=..] [date=..]
# mute= lists channels 1-3 (A, B, C) to leave out of the mix.
# Images: `tools/build.sh exportSamples` writes app/build/samples/copper-lane-{zx128,msx,atari-st,cpc}.ayimg (the demos);
# the host test writes its test song's images as <dir>/ay_<variant>_image.bin (tools/audio/run_native_host_test.sh <dir>).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-ay"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/ay" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/third_party/chips" -I "$cpp/third_party/binjgb/src"
  -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3" -I "$cpp/third_party/ymfm")
includes+=(-I "$cpp/sms" -I "$cpp/pce" -I "$cpp/third_party/mednafen-pce-psg/shim")
objs=()
for src in "$cpp/chip_engine.c" "$cpp/cpu6502.c" "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c" \
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_vrc7.c" "$cpp/nes/nes_n163.c" \
  "$cpp/nes/nes_fds.c" "$cpp/nes/nes_s5b.c" "$cpp/nes/nes_epsm_unit.c" "$cpp/third_party/emu2413/emu2413.c" "$cpp/chip_opll.c" \
  "$cpp/nes/nes_mmc5.c" "$cpp/nes/nes_mmc5_pcm.c" \
  "$cpp/nes/nes_players.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c" \
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c" \
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c" "$cpp/opl3/opl3_vgm.c" \
  "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c" "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" \
  "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c" "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c" \
  "$cpp/sms/sms_machine.c" "$cpp/sms/sms_image.c" "$cpp/sms/sms_chip.c" "$cpp/sms/sms_vgm.c" \
  "$cpp/ay/ay_chip.c" "$cpp/ay/ay_machine.c" "$cpp/ay/ay_player.c" "$cpp/ay/ay_psg.c" "$cpp/ay/ay_image.c" "$cpp/ay/ay_vgm.c" \
  "$cpp/pce/pce_chip.c" "$cpp/pce/pce_machine.c" "$cpp/pce/pce_player.c" "$cpp/pce/pce_image.c" "$cpp/pce/pce_vgm.c" \
  "$root/tools/audio/ay_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "$cpp/sid/sid_resid.cpp" "$cpp"/third_party/resid-1.0/{sid,voice,wave,envelope,filter,dac,extfilt,pot}.cc \
  "$cpp/ym/ym_ymfm.cpp" "$cpp/nes/nes_epsm_ymfm.cpp" "$cpp/third_party/ymfm/ymfm_adpcm.cpp" "$cpp/third_party/ymfm/ymfm_ssg.cpp" "$cpp/pce/pce_psg.cpp"; do
  o="$build/$(basename "${src%.*}").o"
  ${CXX:-g++} -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/ay_render"
exec "$build/ay_render" "$@"
