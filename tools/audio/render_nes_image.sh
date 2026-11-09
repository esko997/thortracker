#!/usr/bin/env bash
# Renders a lowered NES image through the native engine of its player build: "nes-famistudio" or, for an image
# lowered for the VRC6, VRC7, FDS, 5B, MMC5 or EPSM build, "nes-famistudio-vrc6" / "nes-famistudio-vrc7" /
# "nes-famistudio-fds" / "nes-famistudio-s5b" / "nes-famistudio-mmc5" / "nes-famistudio-epsm", for an N163 build
# "nes-famistudio-n163-1".."-8" (host gcc/g++, the same sources as the hosts; all engines are linked).
# Usage: tools/audio/render_nes_image.sh image.nesimg out.wav [seconds] [rhythm-rom]
# Images: `tools/build.sh exportSamples` writes app/build/samples/thor-quest.nesimg (NES),
# saw-sunrise.nesimg (VRC6), lantern-point.nesimg (VRC7), wavetable-garden-<n>ch.nesimg (N163, n = 1..8),
# side-b.nesimg (FDS), copper-kite.nesimg (5B), tandem-skies.nesimg (MMC5) and expansion-port.nesimg (EPSM).
# EPSM rhythm drums need your own YM2608 rhythm ROM (8192 bytes; never committed): the 4th argument or
# $THOR_RHYTHM_ROM. Without it they are silent.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-nes"
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
includes+=(-I "$cpp/vsu")
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
  "$cpp/vsu/vsu_engine.c" "$cpp/vsu/vsu_machine.c" "$cpp/vsu/vsu_player.c" "$cpp/vsu/vsu_chip.c" "$cpp/vsu/vsu_image.c" \
  "$cpp/vsu/vsu_vgm.c" \
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
  "$root/tools/audio/nes_render.c"; do
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
${CXX:-g++} "${objs[@]}" -lm -pthread -o "$build/nes_render"
exec "$build/nes_render" "$@"
