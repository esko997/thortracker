#!/usr/bin/env bash
# Builds and runs the native chip-engine regression tests on the host (gcc/g++, no Android).
# Usage: tools/audio/run_native_host_test.sh [evidence-dir]   (default: evidence/audio)
# Game Boy: pass/fail per check, seek and per-tick timings, <dir>/host_demo.wav (48 kHz stereo)
# and <dir>/host_trace.txt (per-tick hUGEDriver APU register writes) for reference comparison.
# NES: <dir>/nes_ft.wav, <dir>/nes_trace.txt (per-frame APU writes) and <dir>/nes_ft.nsf (test song).
# VRC6: <dir>/nes_vrc6.wav, nes_vrc6_trace.txt (per-frame APU + VRC6 writes) and nes_vrc6.nsf (the VRC6 test song, expansion
# byte $7B = $01).
# EPSM: <dir>/nes_epsm.wav (all channels), nes_epsm_tonal.wav (rhythm drums muted), nes_epsm_trace.txt (per-frame APU +
# $401C-$401F writes) and nes_epsm.nsf (the EPSM test song, expansion byte $7B = $80). The YM2608 rhythm ROM is not
# part of Thor Tracker: set THOR_EPSM_RHYTHM_ROM=/path/to/your/ym2608_adpcm_rom.bin (8192 bytes, SHA-256 checked) for
# the private rhythm checks, which then also write nes_epsm_rhythm.wav (rhythm drums only) and put the drums into
# nes_epsm.wav; without it those checks are skipped with a notice. Keep the ROM outside the repository and give a ROM run
# an evidence directory outside it too (e.g. tools/audio/run_native_host_test.sh /tmp/thor-evidence), so no drum render
# sits in the worktree.
# SID: <dir>/sid_gt_8580.wav, sid_gt_6581.wav, sid_tone_8580.wav, sid_trace.txt (per-tick SID writes) and
# sid_gt.sid (GoatTracker's own packing of the test song), plus cost per tick and the realtime factor.
# OPL3: <dir>/opl3_song.wav, opl3_tone.wav, opl3_trace.txt (per-tick player writes, compared with the Python
# reference player's) and opl3_song.vgm (the VGM export of the test song).
# YM2612: <dir>/ym_song.wav, ym_song_pal.wav, ym_tone_fm.wav, ym_tone_psg.wav, ym_trace.txt (per-tick player
# writes, = the Python reference player's), ym_song.vgm, ym_song.xgm and ym_song.bin (the XGC; = xgmtool's) and
# ym_image.bin (the image).
# OPM: <dir>/opm_song.wav, opm_tone.wav, opm_trace.txt (per-tick player writes, = the Python reference player's),
# opm_song.vgm (the VGM export of the test song) and opm_song.bin (the image), the inputs of
# tools/audio/opm/opm_reference_check.py.
# WAV export (chip_wav.c): renders every demo image exportSamples wrote (app/build/samples, or $THOR_SAMPLES; run
# `tools/build.sh exportSamples` first) and writes <dir>/wav_<demo>.wav (once through and a 5 s fade).
# The engines are C except reSID and ymfm (C++17): C sources build with $CC, reSID, ymfm and their C wrappers with
# $CXX, and the tests link with $CXX (libstdc++), as the app (CMake) and the Deck (deck/build.gradle.kts) do.
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
includes=(-I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/opm"
  -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3"
  -I "$cpp/third_party/ymfm"
  -I "$root/app/src/test/cpp")
c_engines=(
  "$cpp/chip_engine.c" "$cpp/chip_wav.c" "$cpp/cpu6502.c"
  "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c"
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_epsm_unit.c"
  "$cpp/nes/nes_players.c"
  "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c"
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c"
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c"
  "$cpp/opl3/opl3_vgm.c" "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c"
  "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c"
  "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c"
  "$cpp/opm/opm_chip.c" "$cpp/opm/opm_machine.c" "$cpp/opm/opm_player.c" "$cpp/opm/opm_image.c" "$cpp/opm/opm_vgm.c"
)
cxx_engines=("$cpp/sid/sid_resid.cpp" "$cpp/ym/ym_ymfm.cpp" "$cpp/nes/nes_epsm_ymfm.cpp" "$cpp/opm/opm_ymfm.cpp")
for f in ymfm_adpcm ymfm_ssg; do cxx_engines+=("$cpp/third_party/ymfm/$f.cpp"); done
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
for t in huge_machine_test nes_machine_test nes_vrc6_test nes_epsm_test sid_machine_test opl3_machine_test ym_machine_test \
  opm_machine_test wav_render_test; do
  "$CC" -std=gnu11 -O2 "${warn[@]}" "${includes[@]}" -c "$root/app/src/test/cpp/$t.c" -o "$build/obj/$t.o"
  "$CXX" "$build/obj/$t.o" "${objs[@]}" -lm -o "$build/$t"
done
status=0
"$build/huge_machine_test" "$out" || status=1
"$build/nes_machine_test" "$out" || status=1
"$build/nes_vrc6_test" "$out" || status=1
"$build/nes_epsm_test" "$out" || status=1
"$build/sid_machine_test" "$out" || status=1
"$build/opl3_machine_test" "$out" || status=1
"$build/ym_machine_test" "$out" || status=1
"$build/opm_machine_test" "$out" || status=1
"$build/wav_render_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
exit $status
