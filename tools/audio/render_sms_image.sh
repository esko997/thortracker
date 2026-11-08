#!/usr/bin/env bash
# Renders a lowered Master System image through the native "sms-banjo" engine (host gcc, the same sources as the hosts;
# needs app/src/main/cpp/generated/banjo_driver_image.h from tools/audio/prepare_sms.py).
# Usage: tools/audio/render_sms_image.sh image.smsimg out.wav [seconds] [mute=0123] [--vgm out.vgm] [title=..] [author=..] [date=..]
# SampleExportTool writes the demos' images (harbour-lights-<system>.smsimg).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build="$root/build/host-audio/render-sms"
mkdir -p "$build"
cpp="$root/app/src/main/cpp"
includes=(-I "$cpp" -I "$cpp/sms" -I "$cpp/ym" -I "$cpp/third_party/chips" -I "$cpp/third_party/blip_buf")
objs=()
for src in "$cpp/sms/sms_chip.c" "$cpp/sms/sms_machine.c" "$cpp/sms/sms_image.c" "$cpp/sms/sms_vgm.c" "$cpp/ym/ym_psg.c" \
  "$cpp/third_party/emu2413/emu2413.c" "$cpp/third_party/blip_buf/blip_buf.c" "$cpp/cpuz80.c" "$cpp/vgm_gd3.c" \
  "$root/tools/audio/sms_render.c"; do
  o="$build/$(basename "${src%.*}").o"
  ${CC:-gcc} -std=gnu11 -O2 -DNDEBUG=1 -w "${includes[@]}" -c "$src" -o "$o"
  objs+=("$o")
done
${CC:-gcc} "${objs[@]}" -lm -o "$build/sms_render"
exec "$build/sms_render" "$@"
