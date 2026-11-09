#!/usr/bin/env bash
# Renders a lowered YM2612 image through the native "ym2612-fm" engine (host gcc/g++, the same sources as the hosts).
# Usage: tools/audio/render_ym_image.sh image.bin out.wav [seconds] [mute=0,5,10] [--vgm out.vgm] [--xgm out.xgm]
#        [--xgc out.xgc] [title=..] [author=..] [date=..]
# Images are layout 2 (1188864 bytes); mute takes tracker channel indices 0-12 (FM 1-6, PSG 1-3, Noise, PCM 1-3);
# .xgm/.xgc are XGM2 (SGDK's XGM2 driver plays them, XGM1's cannot).
# The host test writes its songs' images as <dir>/ym_image.bin and <dir>/ym_pcm_image.bin
# (tools/audio/run_native_host_test.sh <dir>).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-ym"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/ay" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/third_party/chips" -I "$cpp/third_party/binjgb/src"
  -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3" -I "$cpp/third_party/ymfm")
includes+=(-I "$cpp/sms")
includes+=(-I "$cpp/pce" -I "$cpp/third_party/mednafen-pce-psg/shim")
includes+=(-I "$cpp/scc")
includes+=(-I "$cpp/opm")
includes+=(-I "$cpp/opn")
includes+=(-I "$cpp/tia" -I "$cpp/third_party/stella-tia" -I "$cpp/third_party/stella-tia/shim")
includes+=(-I "$cpp/cbm" -I "$cpp/pokey")
includes+=(-I "$cpp/ws")
includes+=(-I "$cpp/saa")
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
  "$cpp/ws/ws_chip.c" "$cpp/ws/ws_machine.c" "$cpp/ws/ws_player.c" "$cpp/ws/ws_image.c" "$cpp/ws/ws_apu.c" "$cpp/ws/ws_vgm.c" \
  "$cpp/saa/saa_chip.c" "$cpp/saa/saa_machine.c" "$cpp/saa/saa_player.c" "$cpp/saa/saa_image.c" "$cpp/saa/saa_vgm.c" \
  "$cpp/saa/saa_core.c" \
  "$cpp/cbm/cbm_chip.c" "$cpp/cbm/cbm_machine.c" "$cpp/cbm/cbm_player.c" "$cpp/cbm/cbm_tables.c" "$cpp/cbm/cbm_image.c" \
  "$cpp/cbm/cbm_vic.c" "$cpp/cbm/cbm_ted.c" \
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
  "$root/tools/audio/ym_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
for src in "$cpp/sid/sid_resid.cpp" "$cpp"/third_party/resid-1.0/{sid,voice,wave,envelope,filter,dac,extfilt,pot}.cc \
  "$cpp/ym/ym_ymfm.cpp" "$cpp/nes/nes_epsm_ymfm.cpp" "$cpp/opm/opm_ymfm.cpp" "$cpp/opn/opn_ymfm.cpp" "$cpp/third_party/ymfm/ymfm_adpcm.cpp" "$cpp/third_party/ymfm/ymfm_ssg.cpp" "$cpp/pce/pce_psg.cpp" "$cpp/tia/tia_stella.cpp"; do
  o="$build/$(basename "${src%.*}").o"
  ${CXX:-g++} -std=c++17 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/ym_render"
exec "$build/ym_render" "$@"
