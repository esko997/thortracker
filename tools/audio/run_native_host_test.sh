#!/usr/bin/env bash
# Builds and runs the native chip-engine regression tests on the host (gcc/g++, no Android).
# Usage: tools/audio/run_native_host_test.sh [evidence-dir]   (default: evidence/audio)
# Game Boy: pass/fail per check, seek and per-tick timings, <dir>/host_demo.wav (48 kHz stereo)
# and <dir>/host_trace.txt (per-tick hUGEDriver APU register writes) for reference comparison.
# NES: <dir>/nes_ft.wav, <dir>/nes_trace.txt (per-frame APU writes) and <dir>/nes_ft.nsf (test song).
# VRC6: <dir>/nes_vrc6.wav, nes_vrc6_trace.txt (per-frame APU + VRC6 writes) and nes_vrc6.nsf (the VRC6 test song, expansion
# byte $7B = $01).
# VRC7: <dir>/nes_vrc7.wav, nes_vrc7_trace.txt (per-frame APU + VRC7 writes) and nes_vrc7.nsf (the VRC7 test song,
# expansion byte $7B = $02).
# SID: <dir>/sid_gt_8580.wav, sid_gt_6581.wav, sid_tone_8580.wav, sid_trace.txt (per-tick SID writes) and
# sid_gt.sid (GoatTracker's own packing of the test song), plus cost per tick and the realtime factor.
# OPL3: <dir>/opl3_song.wav, opl3_tone.wav, opl3_trace.txt (per-tick player writes, compared with the Python
# reference player's) and opl3_song.vgm (the VGM export of the test song).
# YM2612: <dir>/ym_song.wav, ym_song_pal.wav, ym_tone_fm.wav, ym_tone_psg.wav, ym_trace.txt (per-tick player
# writes, = the Python reference player's), ym_song.vgm, ym_song.xgm and ym_song.bin (the XGC; = xgmtool's) and
# ym_image.bin (the image).
# WAV export (chip_wav.c): renders every demo image exportSamples wrote (app/build/samples, or $THOR_SAMPLES; run
# `tools/build.sh exportSamples` first) and writes <dir>/wav_<demo>.wav (once through and a 5 s fade).
# POKEY: <dir>/pokey_song.bin, pokey_song_pal.bin (the test images), pokey_trace.txt, pokey_trace_pal.txt (per-tick and
# per-interrupt player writes with their machine cycles), pokey_porta.bin and pokey_trace_porta.txt (sticky portamento
# over samples), pokey_song.vgm, pokey_song.wav, pokey_tone.wav and pokey_chip_cases.txt (chip scenarios: writes and
# every output change), the inputs of the Python reference checks.
# The engines are C except reSID and ymfm (C++17): C sources (emu2413, the VRC7's FM chip, included) build with $CC,
# reSID, ymfm and their C wrappers with $CXX, and the tests link with $CXX (libstdc++), as the app (CMake) and the Deck
# (deck/build.gradle.kts) do.
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
includes=(-I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/pokey"
  -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0" -I "$cpp/third_party/nuked-opl3"
  -I "$cpp/third_party/ymfm" -I "$root/app/src/test/cpp")
c_engines=(
  "$cpp/chip_engine.c" "$cpp/chip_wav.c" "$cpp/cpu6502.c"
  "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c"
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_vrc7.c"
  "$cpp/third_party/emu2413/emu2413.c" "$cpp/chip_opll.c" "$cpp/nes/nes_players.c"
  "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c"
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c"
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c"
  "$cpp/opl3/opl3_vgm.c" "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c"
  "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c"
  "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c"
  "$cpp/pokey/pokey_engine.c" "$cpp/pokey/pokey_machine.c" "$cpp/pokey/pokey_player.c" "$cpp/pokey/pokey_chip.c"
  "$cpp/pokey/pokey_image.c" "$cpp/pokey/pokey_vgm.c"
)
cxx_engines=("$cpp/sid/sid_resid.cpp" "$cpp/ym/ym_ymfm.cpp")
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
for t in huge_machine_test nes_machine_test nes_vrc6_test nes_vrc7_test sid_machine_test opl3_machine_test ym_machine_test \
  pokey_machine_test wav_render_test; do
  "$CC" -std=gnu11 -O2 "${warn[@]}" "${includes[@]}" -c "$root/app/src/test/cpp/$t.c" -o "$build/obj/$t.o"
  "$CXX" "$build/obj/$t.o" "${objs[@]}" -lm -pthread -o "$build/$t"
done
status=0
"$build/huge_machine_test" "$out" || status=1
"$build/nes_machine_test" "$out" || status=1
"$build/nes_vrc6_test" "$out" || status=1
"$build/nes_vrc7_test" "$out" || status=1
"$build/sid_machine_test" "$out" || status=1
"$build/opl3_machine_test" "$out" || status=1
"$build/pokey_machine_test" "$out" || status=1
"$build/ym_machine_test" "$out" || status=1
"$build/wav_render_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
exit $status
