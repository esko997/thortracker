#!/bin/sh
# NextUI runs a Pak synchronously, then owns launcher handback. Do not restart its boot script/services.
set -eu
PAK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export THORTRACKER_DOCUMENTS="${THORTRACKER_DOCUMENTS:-${SDCARD_PATH:-/mnt/SDCARD}/ThorTunez}"
export THORTRACKER_DATA="${THORTRACKER_DATA:-$THORTRACKER_DOCUMENTS/.thortracker}"
mkdir -p "$THORTRACKER_DOCUMENTS" "$THORTRACKER_DATA"
LOG_DIR="${LOGS_PATH:-$THORTRACKER_DATA}"
mkdir -p "$LOG_DIR"
# Keep NextUI HOME: audiomon writes its ALSA/Bluetooth routing into $USERDATA_PATH/.asoundrc.
export LD_LIBRARY_PATH="/usr/trimui/lib:${SYSTEM_PATH:-/mnt/SDCARD/.system/tg5040}/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-mali}"
export SDL_AUDIODRIVER="${SDL_AUDIODRIVER:-alsa}"
cd "$THORTRACKER_DATA"
if command -v syncsettings.elf >/dev/null 2>&1; then syncsettings.elf; fi
child=
finish() {
  if [ -n "$child" ]; then kill -TERM "$child" 2>/dev/null || :; wait "$child" 2>/dev/null || :; fi
  if command -v syncsettings.elf >/dev/null 2>&1; then syncsettings.elf; fi
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$PAK_DIR/thortracker" "$@" >"$LOG_DIR/ThorTracker.txt" 2>&1 &
child=$!
status=0
wait "$child" || status=$?
child=
exit "$status"
