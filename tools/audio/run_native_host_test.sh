#!/usr/bin/env bash
# Builds and runs the native preview-machine regression test on the host (gcc, no Android).
# Usage: tools/audio/run_native_host_test.sh [evidence-dir]   (default: evidence/audio)
# Output: pass/fail per check, seek and per-tick timings, <dir>/host_demo.wav (48 kHz stereo)
# and <dir>/host_trace.txt (per-tick hUGEDriver APU register writes) for reference comparison.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-$root/evidence/audio}"
build="$root/build/host-audio"
mkdir -p "$out" "$build"
cpp="$root/app/src/main/cpp"
${CC:-gcc} -std=gnu11 -O2 -Wall -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable \
  -Wno-sign-compare -Wno-missing-field-initializers \
  -I "$cpp" -I "$cpp/third_party/binjgb/src" \
  "$cpp/huge_machine.c" "$cpp/third_party/binjgb/src/common.c" "$root/app/src/test/cpp/huge_machine_test.c" \
  -lm -o "$build/huge_machine_test"
exec "$build/huge_machine_test" "$out"
