#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -eq 0 ]; then set -- :core:jvmTest testDebugUnitTest assembleDebug; fi
# Enter the container before creating a scope: limiting the podman client would not limit its payload.
if ! command -v java >/dev/null 2>&1; then
  exec distrobox enter ubuntu -- "$root/tools/build.sh" "$@"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
# Keep compiler scratch off RAM-backed /tmp; Java does not honor TMPDIR on its own.
export TMPDIR="${TMPDIR:-${XDG_CACHE_HOME:-$HOME/.cache}/thortracker/tmp}"
mkdir -p "$TMPDIR"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=\"$TMPDIR\""
# Worktree builds share a lock, but persistent Gradle/Kotlin daemons otherwise accumulate between runs.
set -- --no-daemon -Pkotlin.compiler.execution.strategy=in-process "$@"
run=(flock /tmp/sortie-gradle.lock "$root/gradlew" -p "$root" --console=plain "$@")
if [[ "$(uname -s)" == Linux && "${GITHUB_ACTIONS:-false}" != true ]]; then
  case "$(stat -f -c %T "$TMPDIR")" in
    tmpfs|ramfs) echo "Build scratch must be disk-backed; set TMPDIR to an SSD directory." >&2; exit 1 ;;
  esac
  available=0
  while read -r key value rest; do
    if [[ "$key" == MemAvailable: ]]; then available="$value"; break; fi
  done < /proc/meminfo
  if (( available < 8 * 1024 * 1024 )); then
    echo "Build deferred: need at least 8 GiB MemAvailable (currently $((available / 1024)) MiB)." >&2
    exit 1
  fi
  # Fail closed if the user manager cannot enforce these limits. CI uses its runner's resource policy.
  exec systemd-run --user --scope --quiet \
    -p MemoryHigh=3G -p MemoryMax=4G -p MemorySwapMax=0 -p CPUQuota=200% \
    nice -n 10 ionice -c 2 -n 7 "${run[@]}"
fi
exec "${run[@]}"
