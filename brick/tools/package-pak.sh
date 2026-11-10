#!/usr/bin/env bash
# CI release binary -> SD-card-root ZIP. Never bundle the build-time SDL, vendor libs or user firmware/data.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
version=${1:?usage: brick/tools/package-pak.sh <version>}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected x.y.z version' >&2; exit 1; }
bin=brick/build/bin/linuxArm64/releaseExecutable
[[ -x $bin/thortracker.kexe ]] || { echo 'Build :brick:linkReleaseExecutableLinuxArm64 in CI first' >&2; exit 1; }
mkdir -p dist brick/build
stage=$(mktemp -d brick/build/pak.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
pak="$stage/Tools/tg5040/ThorTracker.pak"
mkdir -p "$pak"
cp "$bin/thortracker.kexe" "$pak/thortracker"
cp -R "$bin/fonts" "$bin/tunings" "$pak/"
cp brick/packaging/launch.sh "$pak/launch.sh"
cp app/src/main/assets/NOTICE.txt LICENSE "$pak/"
chmod 755 "$pak/thortracker" "$pak/launch.sh"
# Device glibc2.33, libstdc++6.0.28. Fail CI rather than shipping an incompatible host-linked binary.
python3 - "$pak/thortracker" <<'PY'
import re, subprocess, sys
binary = sys.argv[1]
header = subprocess.check_output(['readelf', '-h', binary], text=True)
if not re.search(r'Machine:\s+AArch64', header):
    raise SystemExit('Pak executable is not ARM64')
dynamic = subprocess.check_output(['readelf', '-d', binary], text=True)
needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
allowed = {'libSDL2-2.0.so.0', 'libstdc++.so.6', 'libgcc_s.so.1', 'libm.so.6', 'libc.so.6',
           'libpthread.so.0', 'libdl.so.2', 'librt.so.1', 'ld-linux-aarch64.so.1'}
if 'libSDL2-2.0.so.0' not in needed or set(needed) - allowed:
    raise SystemExit('Unexpected runtime libraries: ' + ', '.join(needed))
versions = subprocess.check_output(['readelf', '--version-info', binary], text=True)
for prefix, ceiling in [('GLIBC', (2,33)), ('GLIBCXX', (3,4,28)), ('CXXABI', (1,3,12))]:
    found = {tuple(map(int, v.split('.'))) for v in re.findall(prefix + r'_([0-9.]+)', versions)}
    if found and max(found) > ceiling:
        raise SystemExit(f'{prefix} requirement {max(found)} exceeds Brick {ceiling}')
print('ARM64 runtime requirements:', ', '.join(needed))
print(versions)
PY
commit=$(git rev-parse HEAD)
epoch=${SOURCE_DATE_EPOCH:-$(git show -s --format=%ct HEAD)}
printf 'Thor Tracker %s\nSource %s\nTarget linuxArm64 / NextUI tg5040 Brick\nRuntime SDL2 2.30.8 (device vendor library, not bundled)\n' "$version" "$commit" > "$pak/BUILD-INFO"
# Sorted entries, fixed timestamps/modes and no host paths make packaging reproducible for identical input bytes.
python3 - "$stage" "dist/thor-tracker-$version-brick-nextui.zip" "$epoch" <<'PY'
import pathlib, sys, time, zipfile
root, destination, epoch = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
stamp = time.gmtime(max(epoch, 315532800))[:6]
with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        info = zipfile.ZipInfo(path.relative_to(root).as_posix(), stamp)
        info.create_system = 3
        mode = 0o755 if path.name in ('launch.sh', 'thortracker') else 0o644
        info.external_attr = (0o100000 | mode) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, path.read_bytes())
PY
(cd dist && sha256sum "thor-tracker-$version-brick-nextui.zip" > "thor-tracker-$version-brick-nextui.zip.sha256")
echo "packaged dist/thor-tracker-$version-brick-nextui.zip"
