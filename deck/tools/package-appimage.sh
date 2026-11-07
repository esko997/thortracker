#!/usr/bin/env bash
# Packages the release build of the Deck frontend as dist/thor-tracker-<version>-x86_64.AppImage (+ .sha256):
# the executable with fonts/, tunings/ and the SDL3 it was linked against (deps/lib) in lib/, found via its rpath.
# Usage: deck/tools/package-appimage.sh <version>   (after :deck:linkReleaseExecutableLinuxX64)
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
version=${1:?usage: package-appimage.sh <version>}
bin=build/bin/linuxX64/releaseExecutable
[ -x "$bin/thortracker.kexe" ] || { echo "build :deck:linkReleaseExecutableLinuxX64 first" >&2; exit 1; }
[ -f deps/lib/libSDL3.so.0 ] || { echo "deps/lib/libSDL3.so.0 missing (tools/build-sdl3.sh)" >&2; exit 1; }
app=build/AppDir
rm -rf "$app" && mkdir -p "$app/usr/bin/lib"
cp "$bin/thortracker.kexe" "$app/usr/bin/thortracker"
cp -r "$bin/fonts" "$bin/tunings" "$app/usr/bin/"
cp -L deps/lib/libSDL3.so.0 "$app/usr/bin/lib/"
for f in deps/SDL3-*/LICENSE.txt; do cp "$f" "$app/usr/bin/lib/SDL3-LICENSE.txt"; done
# hUGEDriver, binjgb (MIT: its notice must ship) and Kotlin: the same notice the Android app carries.
cp ../app/src/main/assets/NOTICE.txt "$app/usr/bin/NOTICE.txt"
cp packaging/AppRun packaging/thortracker.desktop packaging/thortracker.svg "$app/"
cp packaging/thortracker.svg "$app/.DirIcon"
chmod -R u=rwX,go=rX "$app"
chmod a+x "$app/AppRun" "$app/usr/bin/thortracker"
tool=build/appimagetool-x86_64.AppImage
if [ ! -x "$tool" ]; then
  curl -sSL -o "$tool" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$tool"
fi
mkdir -p ../dist
out="../dist/thor-tracker-$version-x86_64.AppImage"
# Run the tool without FUSE (CI runners and containers often lack it).
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$tool" --no-appstream "$app" "$out"
(cd ../dist && sha256sum "thor-tracker-$version-x86_64.AppImage" > "thor-tracker-$version-x86_64.AppImage.sha256")
echo "packaged $out"
