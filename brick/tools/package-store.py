#!/usr/bin/env python3
"""Repackage a manual-install ZIP for Pak Store without rebuilding its executable."""
import hashlib
import json
import pathlib
import sys
import zipfile


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: python3 brick/tools/package-store.py <manual-install.zip>")
    root = pathlib.Path(__file__).resolve().parents[2]
    metadata = (root / "pak.json").read_bytes()
    manifest = json.loads(metadata)
    prefix = f"Tools/tg5040/{manifest['name']}.pak/"
    filename = manifest["release_filename"]
    if pathlib.Path(filename).name != filename or not filename.endswith(".zip"):
        raise SystemExit("release_filename must be a ZIP basename")
    destination = root / "dist" / filename
    source = pathlib.Path(sys.argv[1])
    if source.resolve() == destination.resolve():
        raise SystemExit("Input must be the manual-install ZIP, not the store ZIP")
    with zipfile.ZipFile(source) as original:
        entries = original.infolist()
        names = [entry.filename for entry in entries]
        required = {prefix + name for name in ("BUILD-INFO", "launch.sh", "thortracker")}
        if not required.issubset(names) or len(names) != len(set(names)):
            raise SystemExit("Missing Pak files or duplicate archive entries")
        for entry in entries:
            relative = entry.filename.removeprefix(prefix)
            if (not entry.filename.startswith(prefix) or entry.is_dir()
                    or relative.startswith("/") or ".." in pathlib.PurePosixPath(relative).parts
                    or relative == "pak.json"):
                raise SystemExit(f"Unexpected manual-install ZIP entry: {entry.filename}")
        build = original.read(prefix + "BUILD-INFO").decode("utf-8")
        if build.splitlines()[0] != "Thor Tracker " + manifest["version"].removeprefix("v"):
            raise SystemExit("pak.json version does not match the packaged BUILD-INFO")
        stamp = original.getinfo(prefix + "BUILD-INFO").date_time
        destination.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as store:
            for entry in sorted(entries, key=lambda item: item.filename):
                info = zipfile.ZipInfo(entry.filename[len(prefix):], entry.date_time)
                info.create_system = 3
                info.external_attr = entry.external_attr
                info.compress_type = zipfile.ZIP_DEFLATED
                store.writestr(info, original.read(entry))
            info = zipfile.ZipInfo("pak.json", stamp)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            store.writestr(info, metadata)
    with destination.open("rb") as archive:
        digest = hashlib.file_digest(archive, "sha256").hexdigest()
    destination.with_suffix(".zip.sha256").write_text(f"{digest}  {destination.name}\n")
    print(f"packaged {destination}: {digest}")


if __name__ == "__main__":
    main()
