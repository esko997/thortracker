#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -eq 0 ]; then set -- :core:jvmTest testDebugUnitTest assembleDebug; fi
if command -v java >/dev/null 2>&1; then
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  exec flock /tmp/sortie-gradle.lock "$root/gradlew" -p "$root" --console=plain "$@"
fi
exec distrobox enter ubuntu -- bash -c 'export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"; exec flock /tmp/sortie-gradle.lock "$1/gradlew" -p "$1" --console=plain "${@:2}"' thor-tracker-build "$root" "$@"
