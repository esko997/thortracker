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
# N163: <dir>/nes_n163.wav, nes_n163_trace.txt (per-frame APU + N163 port writes) and nes_n163.nsf (the 8-channel N163
# test song, expansion byte $7B = $10).
# FDS: <dir>/nes_fds.wav, nes_fds_trace.txt (per-frame APU + FDS register writes) and nes_fds.nsf (the FDS test song,
# expansion byte $7B = $04).
# Sunsoft 5B: <dir>/nes_s5b.wav, nes_s5b_trace.txt (per-frame APU + 5B writes) and nes_s5b.nsf (the 5B test song,
# expansion byte $7B = $20); nes_s5b_fallback_test: the seek fallback keeps a square's held manual-period sound (reads
# s5b-manual-copies.nesimg from the samples).
# EPSM: <dir>/nes_epsm.wav (all channels), nes_epsm_tonal.wav (rhythm drums muted), nes_epsm_trace.txt (per-frame APU +
# $401C-$401F writes) and nes_epsm.nsf (the EPSM test song, expansion byte $7B = $80). The YM2608 rhythm ROM is not
# part of Thor Tracker: set THOR_RHYTHM_ROM=/path/to/your/ym2608_adpcm_rom.bin (8192 bytes, SHA-256 checked) for the
# private rhythm checks (EPSM and OPNA), which then also write nes_epsm_rhythm.wav (rhythm drums only), put the drums
# into nes_epsm.wav and write opna_rhythm.wav; without it those checks are skipped with a notice. Keep the ROM outside
# the repository and give a ROM run an evidence directory outside it too (e.g. tools/audio/run_native_host_test.sh
# /tmp/thor-evidence), so no drum render sits in the worktree.
# MMC5: <dir>/nes_mmc5.wav, nes_mmc5_trace.txt (per-frame APU + MMC5 writes) and nes_mmc5.nsf (the MMC5 test song,
# expansion byte $7B = $08).
# MMC5 / N163 mix budget (nes_mix_budget_test): end-to-end swings of every voice at 8-96 kHz stay inside the output,
# rendered relative levels, and the MMC5 PCM lane's mute (a muted DAC mixes as its centre).
# SID: <dir>/sid_gt_8580.wav, sid_gt_6581.wav, sid_tone_8580.wav, sid_trace.txt (per-tick SID writes) and
# sid_gt.sid (GoatTracker's own packing of the test song), plus cost per tick and the realtime factor.
# OPL3: <dir>/opl3_song.wav, opl3_tone.wav, opl3_trace.txt (per-tick player writes, compared with the Python
# reference player's) and opl3_song.vgm (the VGM export of the test song).
# OPN / OPNA (opn_machine_test, images built in C): one <tone>.wav (the engine's render, 48 kHz stereo) and <tone>.vgm
# (the same image's VGM) per measured tone - opn_fm_a4, opn_fm_c2, opn_fm_c6, opn_ssg_a4, opn_ssg_b0, opn_fm3_special
# and the opna_ ones plus opna_adpcm_root / opna_adpcm_up12 -, opn_csm.wav / opna_csm.wav, opn_song.wav / .vgm / .bin
# and opna_song.wav / .vgm / .bin (the test songs and their images) and opn_evidence.txt (what each file holds). Then
# check them independently (Python 3 stdlib only): python3 tools/audio/opn_reference_check.py <dir>
# YM2612 (SGDK's XGM2 driver; tools/audio/ym_reference_check.py reads these): <dir>/ym_image.bin, ym_trace.txt
# (per-tick player writes and PCM commands, = the Python reference player's), ym_song.wav, ym_song.vgm, ym_song.xgm
# and ym_song.xgc for the tonal test song; the same for the PCM song (ym_pcm_image.bin, ym_pcm_trace.txt,
# ym_pcm_song.wav/.vgm/.xgm/.xgc); ym_song_pal.wav, ym_pal_song.vgm/.xgm/.xgc and the auditions ym_tone_fm.wav,
# ym_tone_psg.wav and ym_tone_pcm.wav.
# Master System (Banjo; needs app/src/main/cpp/generated/banjo_driver_image.h from tools/audio/prepare_sms.py):
# <dir>/sms_psg.wav, sms_gg.wav, sms_fm.wav, sms_drums.wav (the test songs) and sms_fm.vgm (the VGM export).
# AY-3-8910 / YM2149 (ZX Spectrum 128, MSX, Atari ST, Amstrad CPC): per variant key k in zx128, msx, atari-st, cpc:
# ay_<k>.wav (30 s of
# the test song; the CPC's in stereo, left A + B, right B + C), ay_<k>_trace.txt (per-tick player writes, = the Python reference player's), ay_<k>.vgm (the VGM
# export), ay_<k>_image.bin (the image), ay_<k>_tone.wav (the A-4 audition and its release) and ay_<k>_ayumi.wav (the
# same register writes replayed into the vendored ayumi, third_party/ayumi, linked into ay_machine_test only): the
# inputs of tools/audio/ay_reference_check.py.
# PC Engine: <dir>/pce_song.wav, pce_tone.wav, pce_trace.txt (per-tick player writes and every timer interrupt's
# writes with their CPU cycles, = the Python reference player's), pce_song.vgm (the VGM export of the test song) and
# pce_song.bin (the image), the inputs of tools/audio/pce/pce_reference_check.py.
# SCC / SCC+: <dir>/scc_song.wav, scc_plus_song.wav (30 s), scc_tone.wav (A-4 + release), scc_trace.txt (per tick:
# position, then the player writes port:reg=val; = the Python reference player's), scc_chip_trace.txt (a scripted
# register sequence and the core's level events), scc_song.vgm, scc_plus_song.vgm (the VGM exports) and scc_song.bin,
# scc_plus_song.bin (the images), the inputs of tools/audio/scc_reference_check.py.
# OPM: <dir>/opm_song.wav, opm_tone.wav, opm_trace.txt (per-tick player writes, = the Python reference player's),
# opm_song.vgm (the VGM export of the test song) and opm_song.bin (the image), the inputs of
# tools/audio/opm/opm_reference_check.py.
# WAV export (chip_wav.c): renders every demo image exportSamples wrote (app/build/samples, or $THOR_SAMPLES; run
# `tools/build.sh exportSamples` first) and writes <dir>/wav_<demo>.wav (once through and a 5 s fade).
# POKEY: <dir>/pokey_song.bin, pokey_song_pal.bin (the test images), pokey_trace.txt, pokey_trace_pal.txt (per-tick and
# per-interrupt player writes with their machine cycles), pokey_porta.bin and pokey_trace_porta.txt (sticky portamento
# over samples), pokey_song.vgm, pokey_song.wav, pokey_tone.wav and pokey_chip_cases.txt (chip scenarios: writes and
# every output change), the inputs of the Python reference checks.
# The engines are C except reSID, ymfm and Mednafen's PC Engine PSG (C++): C sources (emu2413, the VRC7's FM chip,
# included) build with $CC, reSID, ymfm, the PSG and their C wrappers with $CXX (the PSG through pce/pce_psg.cpp, which
# includes the vendored pce_psg.cpp against its shim headers), and the tests link with $CXX (libstdc++), as the app
# (CMake) and the Deck (deck/build.gradle.kts) do.
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
includes=(-I "$cpp" -I "$cpp/third_party/binjgb/src" -I "$cpp/nes" -I "$cpp/sid" -I "$cpp/opl3" -I "$cpp/ym" -I "$cpp/sms" -I "$cpp/ay"
  -I "$cpp/pce" -I "$cpp/scc" -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf" -I "$cpp/third_party/resid-1.0"
  -I "$cpp/third_party/nuked-opl3" -I "$cpp/third_party/ymfm" -I "$cpp/third_party/mednafen-pce-psg/shim"
  -I "$root/third_party/ayumi" -I "$root/app/src/test/cpp")
includes+=(-I "$cpp/opm")
includes+=(-I "$cpp/opn")
includes+=(-I "$cpp/pokey")
c_engines=(
  "$cpp/chip_engine.c" "$cpp/chip_wav.c" "$cpp/cpu6502.c"
  "$cpp/huge_machine.c" "$cpp/huge_chip.c" "$cpp/third_party/binjgb/src/common.c"
  "$cpp/nes/nes_machine.c" "$cpp/nes/nes_image.c" "$cpp/nes/nes_apu.c" "$cpp/nes/nes_vrc6.c" "$cpp/nes/nes_vrc7.c" "$cpp/nes/nes_n163.c"
  "$cpp/nes/nes_fds.c" "$cpp/nes/nes_s5b.c" "$cpp/nes/nes_epsm_unit.c" "$cpp/third_party/emu2413/emu2413.c" "$cpp/chip_opll.c"
  "$cpp/nes/nes_mmc5.c" "$cpp/nes/nes_mmc5_pcm.c"
  "$cpp/nes/nes_players.c"
  "$cpp/nes/nes_chip.c" "$cpp/third_party/blip_buf/blip_buf.c"
  "$cpp/sid/sid_machine.c" "$cpp/sid/sid_image.c" "$cpp/sid/sid_chip.c"
  "$cpp/opl3/opl3_chip.c" "$cpp/opl3/opl3_machine.c" "$cpp/opl3/opl3_player.c" "$cpp/opl3/opl3_image.c"
  "$cpp/opl3/opl3_vgm.c" "$cpp/third_party/nuked-opl3/opl3.c" "$cpp/vgm_gd3.c"
  "$cpp/cpuz80.c" "$cpp/ym/ym_chip.c" "$cpp/ym/ym_machine.c" "$cpp/ym/ym_player.c" "$cpp/ym/ym_image.c"
  "$cpp/ym/ym_xgm.c" "$cpp/ym/ym_vgm.c" "$cpp/ym/ym_psg.c"
  "$cpp/pokey/pokey_engine.c" "$cpp/pokey/pokey_machine.c" "$cpp/pokey/pokey_player.c" "$cpp/pokey/pokey_chip.c"
  "$cpp/pokey/pokey_image.c" "$cpp/pokey/pokey_vgm.c"
  "$cpp/ym2608_rhythm_rom.c" "$cpp/opn/opn_image.c" "$cpp/opn/opn_player.c" "$cpp/opn/opn_vgm.c" "$cpp/opn/opn_chip.c"
  "$cpp/opn/opn_machine.c"
  "$cpp/opm/opm_chip.c" "$cpp/opm/opm_machine.c" "$cpp/opm/opm_player.c" "$cpp/opm/opm_image.c" "$cpp/opm/opm_vgm.c"
  "$cpp/sms/sms_chip.c" "$cpp/sms/sms_machine.c" "$cpp/sms/sms_image.c" "$cpp/sms/sms_vgm.c"
  "$cpp/ay/ay_chip.c" "$cpp/ay/ay_machine.c" "$cpp/ay/ay_player.c" "$cpp/ay/ay_psg.c" "$cpp/ay/ay_image.c" "$cpp/ay/ay_vgm.c"
  "$cpp/pce/pce_chip.c" "$cpp/pce/pce_machine.c" "$cpp/pce/pce_player.c" "$cpp/pce/pce_image.c" "$cpp/pce/pce_vgm.c"
  "$cpp/scc/scc_chip.c" "$cpp/scc/scc_machine.c" "$cpp/scc/scc_player.c" "$cpp/scc/scc_image.c" "$cpp/scc/scc_vgm.c"
  "$cpp/scc/scc_k051649.c"
)
cxx_engines=("$cpp/sid/sid_resid.cpp" "$cpp/ym/ym_ymfm.cpp" "$cpp/nes/nes_epsm_ymfm.cpp" "$cpp/opm/opm_ymfm.cpp" "$cpp/opn/opn_ymfm.cpp" "$cpp/pce/pce_psg.cpp")
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
for t in huge_machine_test nes_machine_test nes_vrc6_test nes_vrc7_test nes_n163_test nes_fds_test nes_s5b_test \
  nes_s5b_fallback_test nes_epsm_test nes_mmc5_test nes_mmc5_pcm_test nes_mix_budget_test sid_machine_test \
  opl3_machine_test ym_machine_test sms_machine_test ay_machine_test pce_machine_test scc_machine_test opm_machine_test opn_machine_test pokey_machine_test wav_render_test; do
  "$CC" -std=gnu11 -O2 "${warn[@]}" "${includes[@]}" -c "$root/app/src/test/cpp/$t.c" -o "$build/obj/$t.o"
  "$CXX" "$build/obj/$t.o" "${objs[@]}" -lm -pthread -o "$build/$t"
done
status=0
"$build/huge_machine_test" "$out" || status=1
"$build/nes_machine_test" "$out" || status=1
"$build/nes_vrc6_test" "$out" || status=1
"$build/nes_vrc7_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
"$build/nes_n163_test" "$out" || status=1
"$build/nes_fds_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
"$build/nes_s5b_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
"$build/nes_s5b_fallback_test" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
"$build/nes_epsm_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
"$build/nes_mmc5_test" "$out" || status=1
"$build/nes_mmc5_pcm_test" || status=1
"$build/nes_mix_budget_test" || status=1
"$build/sid_machine_test" "$out" || status=1
"$build/opl3_machine_test" "$out" || status=1
"$build/pokey_machine_test" "$out" || status=1
"$build/ym_machine_test" "$out" || status=1
"$build/opn_machine_test" "$out" || status=1
"$build/sms_machine_test" "$out" || status=1
"$build/ay_machine_test" "$out" || status=1
"$build/pce_machine_test" "$out" || status=1
"$build/scc_machine_test" "$out" || status=1
"$build/opm_machine_test" "$out" || status=1
"$build/wav_render_test" "$out" "${THOR_SAMPLES:-$root/app/build/samples}" || status=1
echo "OPN / OPNA independent reference check of the evidence: python3 tools/audio/opn_reference_check.py $out"
exit $status
