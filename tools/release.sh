#!/usr/bin/env bash
# Builds the signed release APK into dist/thor-tracker-<versionName>.apk with its .sha256.
# Usage: tools/release.sh
# Signing key: $THORTRACKER_SIGNING_DIR (default ${XDG_DATA_HOME:-$HOME/.local/share}/thortracker/signing) holding
# thortracker-release.p12 and store-password (one line), both private to their owner and outside the repository.
# The APK must verify as signed by exactly the Thor Tracker release certificate and not be debuggable; there is no
# debug-key fallback. No publishing or upload. Uses the host JDK/SDK when java is on PATH, else the Ubuntu
# distrobox (like tools/build.sh).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
repo=$(pwd -P)
# SHA-256 of the release signing certificate (keytool -list -v); a different key must never ship as Thor Tracker.
expected_cert=d12064692cbaa5c2feecf3cf5a2d3719a088e7bafe1ffa55e1f00aeecafed469

die() { printf 'release: %s\n' "$*" >&2; exit 1; }

dir=${THORTRACKER_SIGNING_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/thortracker/signing}
dir=$(realpath -- "$dir") || die "$dir does not exist"
case "$dir/" in "$repo"/*) die "$dir is inside the repository; keep signing files out of it" ;; esac
for p in "$dir" "$dir/thortracker-release.p12" "$dir/store-password"; do
  [ -e "$p" ] && [ -r "$p" ] || die "$p is missing or unreadable"
  [ "$(stat -c %u -- "$p")" = "$(id -u)" ] || die "$p is not owned by you"
  mode=$(stat -c %a -- "$p")
  (( (8#$mode & 8#077) == 0 )) || die "$p is accessible to other users (mode $mode); chmod go= it"
done
awk 'NR == 1 { n = length($0) } /\r/ { bad = 1 } END { exit !(NR == 1 && n >= 6 && !bad) }' "$dir/store-password" ||
  die "$dir/store-password must hold exactly one line: the keystore password"

version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versionName \"$version\" in app/build.gradle.kts is not x.y.z"

tools/build.sh --no-daemon assembleRelease -PthortrackerSigningDir="$dir"
apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || die "$apk was not built (is the signing config applied?)"

android_home=${ANDROID_HOME:-$HOME/Android/Sdk}
build_tools=$(ls -d "$android_home"/build-tools/* | sort -V | tail -1)
if command -v java >/dev/null 2>&1; then run=(); else run=(distrobox enter ubuntu --); fi
certs=$("${run[@]}" "$build_tools/apksigner" verify --print-certs "$apk") || die "$apk does not verify"
grep -qi "certificate SHA-256 digest: $expected_cert" <<< "$certs" || die "$apk is not signed by the Thor Tracker release key:
$certs"
[ "$(grep -c 'certificate SHA-256 digest' <<< "$certs")" = 1 ] || die "$apk has more than one signer"
badging=$("${run[@]}" "$build_tools/aapt2" dump badging "$apk")
grep -q "^package: name='org.thortracker'.* versionName='$version'" <<< "$badging" || die "unexpected package or version: $(head -1 <<< "$badging")"
! grep -q "application-debuggable" <<< "$badging" || die "$apk is debuggable"

mkdir -p dist
out=dist/thor-tracker-$version.apk
cp -- "$apk" "$out"
(cd dist && sha256sum -- "thor-tracker-$version.apk" > "thor-tracker-$version.apk.sha256")
printf 'release: %s signed by %s\n' "$out" "$expected_cert"
cat "$out.sha256"
