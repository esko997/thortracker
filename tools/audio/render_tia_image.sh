#!/usr/bin/env bash
# Renders a lowered Atari 2600 image through the native "atari2600-tia-ntsc" or "atari2600-tia-pal" engine (picked by
# the image's region byte; host gcc/g++, the same sources as the hosts). No VGM: the VGM format has no TIA.
# Usage: tools/audio/render_tia_image.sh image.tiaimg out.wav [seconds] [mute=12]
# The app's sample exports include tia-demo.tiaimg, tia-pal-demo.tiaimg and tia-shortest.tiaimg (SampleExportTool);
# the host test writes its test song's image as <dir>/tia_song.bin (tools/audio/run_native_host_test.sh <dir>).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-tia"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/opl3" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/ym" -I "$cpp/tia" -I "$cpp/third_party/chips"
  -I "$cpp/third_party/binjgb/src" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3"
  -I "$cpp/third_party/ymfm" -I "$cpp/third_party/stella-tia" -I "$cpp/third_party/stella-tia/shim")
includes+=(-I "$cpp/sms" -I "$cpp/ay" -I "$cpp/pce" -I "$cpp/scc" -I "$cpp/opm" -I "$cpp/opn"
  -I "$cpp/third_party/mednafen-pce-psg/shim")
includes+=(-I "$cpp/pokey")
objs=()
for src in "$cpp/chip_engine.c" "$cpp/cpu6502.c" "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c" \
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_vrc7.c" \
  "$cpp/nes/nes_n163.c" "$cpp/nes/nes_fds.c" "$cpp/nes/nes_s5b.c" "$cpp/nes/nes_epsm_unit.c" \
  "$cpp/nes/nes_mmc5.c" "$cpp/nes/nes_mmc5_pcm.c" \
  "$cpp/third_party/emu2413/emu2413.c" "$cpp/chip_opll.c" "$cpp/nes/nes_players.c" "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c" \
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c" \
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c" "$cpp/opl3/opl3_vgm.c" \
  "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c" "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" \
  "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c" "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c" \
  "$cpp/tia/tia_chip.c" "$cpp/tia/tia_machine.c" "$cpp/tia/tia_player.c" "$cpp/tia/tia_image.c" \
  "$cpp/pokey/pokey_engine.c" "$cpp/pokey/pokey_machine.c" "$cpp/pokey/pokey_player.c" "$cpp/pokey/pokey_chip.c" \
  "$cpp/pokey/pokey_image.c" "$cpp/pokey/pokey_vgm.c" \
  "$cpp/ym2608_rhythm_rom.c" "$cpp/opn/opn_image.c" "$cpp/opn/opn_player.c" "$cpp/opn/opn_chip.c" "$cpp/opn/opn_machine.c" \
  "$cpp/sms/sms_machine.c" "$cpp/sms/sms_image.c" "$cpp/sms/sms_chip.c" "$cpp/sms/sms_vgm.c" \
  "$cpp/ay/ay_chip.c" "$cpp/ay/ay_machine.c" "$cpp/ay/ay_player.c" "$cpp/ay/ay_psg.c" "$cpp/ay/ay_image.c" "$cpp/ay/ay_vgm.c" \
  "$cpp/pce/pce_chip.c" "$cpp/pce/pce_machine.c" "$cpp/pce/pce_player.c" "$cpp/pce/pce_image.c" "$cpp/pce/pce_vgm.c" \
  "$cpp/scc/scc_chip.c" "$cpp/scc/scc_machine.c" "$cpp/scc/scc_player.c" "$cpp/scc/scc_image.c" "$cpp/scc/scc_vgm.c" \
  "$cpp/scc/scc_k051649.c" \
  "$cpp/opm/opm_chip.c" "$cpp/opm/opm_machine.c" "$cpp/opm/opm_player.c" "$cpp/opm/opm_image.c" "$cpp/opm/opm_vgm.c" \
  "$root/tools/audio/tia_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "$cpp/sid/sid_resid.cpp" "$cpp"/third_party/resid-1.0/{sid,voice,wave,envelope,filter,dac,extfilt,pot}.cc \
  "$cpp/nes/nes_epsm_ymfm.cpp" "$cpp/opm/opm_ymfm.cpp" "$cpp/opn/opn_ymfm.cpp" "$cpp/pce/pce_psg.cpp" \
  "$cpp/ym/ym_ymfm.cpp" "$cpp/third_party/ymfm/ymfm_adpcm.cpp" "$cpp/third_party/ymfm/ymfm_ssg.cpp" "$cpp/tia/tia_stella.cpp"; do
  o="$build/$(basename "${src%.*}").o"
  ${CXX:-g++} -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/tia_render"
exec "$build/tia_render" "$@"
