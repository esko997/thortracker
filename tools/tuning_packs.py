#!/usr/bin/env python3
"""Offline, independently authored .thorscales v1 pack producer (Python 3.9+).

bundled: reproduce stock, mathematical EDO and licensed DaMuSc packs from
checked-in inputs; no network access or upstream conversion software.
local: preserve every .scl in an explicitly supplied local directory, including
invalid Scala files and duplicate musical content. UTF-8 is decoded strictly;
BOM, CRLF, empty descriptions and control characters remain in the raw payload.
Display metadata is not pre-escaped: the application's UI escapes controls.

This tool is part of Thor Tracker, under the repository's GPL-3.0 licence.
DaMuSc data remains CC BY 4.0; see third_party/damusc/NOTICE.
"""

import argparse
import csv
from decimal import Decimal, ROUND_HALF_EVEN, localcontext
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/damusc"
ASSETS = ROOT / "app/src/main/assets/tunings"
REVISION = "9a4589b99d229691fbefd99b4174e39814789a16"
SOURCE_URL = "https://github.com/jomimc/DaMuSc/tree/" + REVISION
MAX_PACK = 16 * 1024 * 1024
MAX_ENTRIES = 10000
MAX_SCL = 64 * 1024
MAX_ATTRIBUTION = 16 * 1024
PACK_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z", re.ASCII)
STOCK = ("19-tet.scl", "22-edo.scl", "24-tet.scl", "just-5-limit.scl")
# User-selected 424-record edition; the complete licensed CSV remains vendored.
EXCLUDED_MEASUREMENTS = {13, 17, 18, 22, 24, 28, 29, 30, 31, 37}
DAMUSC_CREDIT = (
    "DaMuSc: Database of Musical Scales. Database compilation, annotations and "
    "metadata © 2022–2026 John M. McBride and contributors. CC BY 4.0, "
    "https://creativecommons.org/licenses/by/4.0/ . Source: " + SOURCE_URL + ". "
    "McBride JM, Passmore S, Tlusty T (2023), Convergent evolution in a large "
    "cross-cultural database of musical scales, PLoS ONE 18(12): e0284851, "
    "https://doi.org/10.1371/journal.pone.0284851 . "
    "Selected 424 measured records from M0001–M0434, excluding M0013, M0017, M0018, "
    "M0022, M0024, M0028, M0029, M0030, M0031 and M0037. Thor Tracker summed adjacent "
    "intervals into cumulative cents without rounding; appended 1200 cents only "
    "for Octave_modified=Y; preserved other periods. Fresh labels and region "
    "categories from licensed metadata; no deduplication, normalization or variants. "
    "Per-entry credits identify original sources and changes. No underlying "
    "publications, evidence images or audio included; no endorsement implied. "
    "Supplied without warranties. Full licence and upstream statement accompany "
    "this pack in LICENSE-CC-BY-4.0.txt and DaMuSc-LICENSE.txt."
)
LOCAL_CREDIT = (
    "User-supplied local collection; redistribution rights not verified. "
    "Original relative identities and UTF-8 Scala content preserved without "
    "normalization or deduplication. Importing does not grant redistribution rights."
)


def utf8_file(path, limit):
    """Bound allocation and preserve bytes rather than universal-newline reading."""
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"{path}: exceeds {limit} UTF-8 bytes; nothing omitted")
    return data.decode("utf-8")


def description(scl):
    # Match Scala's first non-comment line, including an empty description.
    for line in scl.removeprefix("\ufeff").split("\n"):
        line = line.removesuffix("\r")
        if not line.lstrip().startswith("!"):
            return line
    return ""


def entry(identity, name, category, scl, attribution):
    return dict(id=identity, name=name, category=category, scl=scl, attribution=attribution)


def pack(identity, name, attribution, entries):
    return dict(version=1, id=identity, name=name, attribution=attribution, entries=entries)


def encoded_pack(value):
    """Enforce codec structural boundaries, never musical-validity filtering."""
    if not PACK_ID.fullmatch(value["id"]):
        raise ValueError("Pack id must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    if len(value["entries"]) > MAX_ENTRIES:
        raise ValueError(f"Pack has more than {MAX_ENTRIES} entries; nothing omitted")
    if len(value["attribution"].encode("utf-8")) > MAX_ATTRIBUTION:
        raise ValueError("Pack attribution exceeds 16 KiB")
    seen = set()
    for item in value["entries"]:
        identity = item["id"]
        if not identity or identity in seen:
            raise ValueError(f"Empty or duplicate entry id: {identity!r}")
        seen.add(identity)
        if len(item["scl"].encode("utf-8")) > MAX_SCL:
            raise ValueError(f"{identity!r}: Scala payload exceeds 64 KiB")
        if len(item["attribution"].encode("utf-8")) > MAX_ATTRIBUTION:
            raise ValueError(f"{identity!r}: attribution exceeds 16 KiB")
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_PACK:
        raise ValueError("Encoded pack exceeds 16 MiB; nothing omitted")
    return data


def cents_text(value):
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text if "." in text else text + ".0"


def scala(identity, name, degrees):
    # Only generated labels pass here; do not transform user payloads.
    name = " ".join(name.splitlines())
    return "\n".join(["! " + identity, "! Generated independently by Thor Tracker", name,
                      str(len(degrees)), "! Cumulative cents; zero is implicit"] +
                     [cents_text(c) for c in degrees]) + "\n"


def checked_sources():
    manifest = json.loads((SOURCE / "SOURCE.json").read_text(encoding="utf-8"))
    expected = {"Data/measured_scales.csv", "Metadata/sources.csv", "Metadata/column_key.csv",
                "LICENSE.md", "LICENSE-CC-BY-4.0.txt", "LICENSE-MIT.txt"}
    if manifest["revision"] != REVISION or set(manifest["files"]) != expected:
        raise ValueError("DaMuSc source manifest does not match the pinned input set")
    for name, expected_hash in manifest["files"].items():
        actual = hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
        if actual != expected_hash:
            raise ValueError(f"Pinned DaMuSc input hash mismatch: {name}")


def csv_rows(name):
    with (SOURCE / name).open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def measured_pack():
    rows = csv_rows("Data/measured_scales.csv")
    identities = [row["MeasuredID"] for row in rows]
    expected = {f"M{n:04d}" for n in range(1, 435)}
    requested = {f"M{n:04d}" for n in range(1, 435) if n not in EXCLUDED_MEASUREMENTS}
    if len(identities) != 434 or set(identities) != expected:
        raise ValueError("Pinned DaMuSc must contain each of M0001–M0434 exactly once")
    if len(requested) != 424 or not requested.issubset(identities):
        raise ValueError("Requested 424-identity coverage is incomplete")
    sources = {row["RefID"]: row["Reference_Full"] for row in csv_rows("Metadata/sources.csv")}
    entries, unsupported, periods, counts = [], [], [], []
    octave_added = 0
    for row in sorted(rows, key=lambda r: r["MeasuredID"]):
        if row["MeasuredID"] not in requested:
            continue
        identity = "measured/" + row["MeasuredID"] + ".scl"
        degrees = []
        cumulative = Decimal(0)
        # Decimal context is ample for all pinned integer/decimal measurements.
        with localcontext() as context:
            context.prec = 50
            for interval in row["Intervals"].split(";"):
                cumulative += Decimal(interval)
                degrees.append(cumulative)
        appended = row["Octave_modified"] == "Y"
        if appended:
            degrees.append(Decimal(1200))
            octave_added += 1
        change = ("Appended 1200.0 cents because Octave_modified=Y." if appended else
                  "No terminal octave added; final cumulative measurement is the period.")
        name = f"{row['Name']} — {row['Country']} [{row['MeasuredID']}]"
        reference = sources[row["RefID"]]
        credit = (f"DaMuSc {row['MeasuredID']}; RefID={row['RefID']}; revision {REVISION}. "
                  f"Original source: {reference}. Measurement Reference: {row['Reference']}. "
                  "Changes: adjacent Intervals summed exactly into cumulative cents, "
                  f"new display label and region category. {change} "
                  "Database metadata © 2022–2026 John M. McBride and contributors; "
                  "CC BY 4.0, https://creativecommons.org/licenses/by/4.0/ .")
        entries.append(entry(identity, name, row["Region"], scala(identity, name, degrees), credit))
        periods.append(degrees[-1])
        counts.append(len(degrees))
        # Report unsupported records, retaining their exact data and identity.
        if (not 1 <= len(degrees) <= 256 or any(not c.is_finite() for c in degrees) or
                any(c <= p for p, c in zip([Decimal(0)] + degrees, degrees)) or
                degrees[-1] / len(degrees) < 1):
            unsupported.append(row["MeasuredID"])
    print(f"DaMuSc: {len(entries)} records; requested coverage {len(requested)}/424; "
          f"{octave_added} flagged octaves; {sum(p != 1200 for p in periods)} non-octave periods; "
          f"degrees {min(counts)}..{max(counts)}; periods {min(periods)}..{max(periods)} cents")
    print("DaMuSc structural tuning constraints: " +
          ("unsupported records retained: " + ", ".join(unsupported) if unsupported else
           "no unsupported records (actual application parser verification is separate)"))
    return pack("damusc", "DaMuSc measured scales", DAMUSC_CREDIT, entries)


def edo_pack():
    entries = []
    for divisions in range(1, 73):
        identity = f"edos/edo-{divisions:02d}.scl"
        name = f"{divisions}-EDO"
        with localcontext() as context:
            context.prec = 50
            degrees = [(Decimal(1200 * k) / divisions).quantize(Decimal("0.000000001"),
                       rounding=ROUND_HALF_EVEN) for k in range(1, divisions + 1)]
        entries.append(entry(identity, name, "Equal divisions of the octave",
                             scala(identity, name, degrees),
                             f"Independent mathematical generation: 1200*k/{divisions} cents, "
                             f"k=1..{divisions}; nine decimal places, round half to even."))
    return pack("edo", "Equal divisions: 1–72 EDO",
                "Independently generated by Thor Tracker from 1200*k/N cents, N=1..72. "
                "Rounded to nine decimal places (half to even); final period exactly 1200 cents. "
                "No third-party scale files, descriptions or historical annotations used. "
                "Generator is GPL-3.0; numerical values are mathematical facts.", entries)


def stock_pack():
    entries = []
    for name in STOCK:
        if name == "22-edo.scl":
            with localcontext() as context:
                context.prec = 50
                degrees = [(Decimal(1200 * k) / 22).quantize(Decimal("0.000001"),
                           rounding=ROUND_HALF_EVEN) for k in range(1, 23)]
            scl = scala(name, "22 equal divisions of the octave", degrees)
            credit = ("Independently regenerated by Thor Tracker: 1200*k/22 cents, "
                      "k=1..22; six decimal places, round half to even. Numeric values "
                      "and generic description match the existing stock tuning. "
                      "Earlier third-party annotations replaced with fresh comments.")
        else:
            scl = utf8_file(ASSETS / name, MAX_SCL)
            credit = ("Existing Thor Tracker stock asset: tunings/" + name +
                      "; mathematical values, generic description and Scala bytes unchanged.")
        entries.append(entry(name, description(scl), "Stock", scl, credit))
    return pack("stock", "Stock tunings", "The four existing Thor Tracker stock tunings. "
                "22-EDO independently regenerated with the same six-decimal values and "
                "generic description, replacing earlier annotations with fresh comments. "
                "The other three mathematical stock assets are unchanged.", entries)


def bundled(args):
    checked_sources()
    packs = [stock_pack(), edo_pack(), measured_pack()]
    # Validate all packs before replacing any generated asset.
    outputs = {p["id"] + ".thorscales": encoded_pack(p) for p in packs}
    outputs["22-edo.scl"] = packs[0]["entries"][1]["scl"].encode("utf-8")
    outputs["NOTICE"] = (SOURCE / "NOTICE").read_bytes()
    outputs["LICENSE-CC-BY-4.0.txt"] = (SOURCE / "LICENSE-CC-BY-4.0.txt").read_bytes()
    outputs["DaMuSc-LICENSE.txt"] = (SOURCE / "LICENSE.md").read_bytes()
    outputs["LICENSE-MIT.txt"] = (SOURCE / "LICENSE-MIT.txt").read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        (args.output / name).write_bytes(data)
    for value in packs:
        print(f"{value['id']}: {len(value['entries'])} entries -> {args.output / (value['id'] + '.thorscales')}")


def local_pack(args):
    if args.input.is_symlink() or not args.input.is_dir():
        raise ValueError("Input must be an explicit local directory, not a symlink")
    if args.output.suffix != ".thorscales":
        raise ValueError("Output path must end in .thorscales")
    files = []

    def unreadable(error):
        raise error

    for directory, dirs, names in os.walk(args.input, followlinks=False, onerror=unreadable):
        for name in dirs:
            if (Path(directory) / name).is_symlink():
                raise ValueError(f"Symlink directory not followed: {Path(directory) / name}")
        for name in names:
            if Path(name).suffix.lower() == ".scl":
                path = Path(directory) / name
                if path.is_symlink() or not path.is_file():
                    raise ValueError(f"Scale input is not a regular non-symlink file: {path}")
                files.append(path)
                if len(files) > MAX_ENTRIES:
                    raise ValueError(f"More than {MAX_ENTRIES} .scl files; nothing omitted")
    entries = []
    for path in sorted(files, key=lambda p: p.relative_to(args.input).as_posix()):
        identity = path.relative_to(args.input).as_posix()
        scl = utf8_file(path, MAX_SCL)
        category = path.relative_to(args.input).parent.as_posix()
        entries.append(entry(identity, description(scl), "" if category == "." else category,
                             scl, "User-supplied file; original comments and notices retained in Scala content."))
    attribution = LOCAL_CREDIT
    if args.attribution_file:
        attribution += "\n\n" + utf8_file(args.attribution_file, MAX_ATTRIBUTION)
    data = encoded_pack(pack(args.id, args.name, attribution, entries))
    args.output.write_bytes(data)
    print(f"User pack: {len(entries)} entries, {len(data)} UTF-8 bytes -> {str(args.output)!r}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    builtins = commands.add_parser("bundled", help="Reproduce licensed default packs entirely offline")
    builtins.add_argument("--output", type=Path, default=ASSETS,
                          help="Output directory (default: checked-in tuning assets)")
    builtins.set_defaults(run=bundled)
    local = commands.add_parser("local", help="Pack a user-supplied local .scl tree; no downloads")
    local.add_argument("--input", type=Path, required=True,
                       help="Explicit root directory; choose scales/ rather than its parent to avoid source duplicates")
    local.add_argument("--output", type=Path, required=True, help="Required .thorscales output path")
    local.add_argument("--id", required=True, help="Stable safe pack id, distinct from stock, edo and damusc")
    local.add_argument("--name", required=True, help="Collection display name")
    local.add_argument("--attribution-file", type=Path,
                       help="Optional local UTF-8 notice added verbatim to pack attribution")
    local.set_defaults(run=local_pack)
    args = parser.parse_args()
    try:
        if args.command == "local" and args.id in {"stock", "edo", "damusc"}:
            raise ValueError("User pack id must not shadow a bundled pack id")
        args.run(args)
    except (OSError, ValueError, KeyError, ArithmeticError) as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()
