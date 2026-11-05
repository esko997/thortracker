# GB/hUGE backend: `.uge` export and preview data contract

Code: `app/src/main/java/org/thortracker/model/` (document, rules, validation, project format) and
`app/src/main/java/org/thortracker/backend/` (`HugeLowering`, `UgeExporter`, `HugeLimits`).

## Targets and sources

| Target | Version | Source consulted |
|---|---|---|
| hUGETracker / `uge2source` | 1.0.11 (tag `v1.0.11`, commit `0623f3f7da0a8ac5b30b5f6aa92debd3db45efc5`) | `src/song.pas` (`TSongV6`, `WriteSongToStream`, `ReadSongFromStream`), `src/hugedatatypes.pas`, `src/codegen.pas` (`RenderSongToGBDKC`, `InstrumentToBytes`), `src/instruments.pas`, `src/utils.pas`, manual `uge-format.md` and `effect-reference.md` |
| hUGEDriver | 6.1.3, as vendored unmodified by gbc-puzzler (`third_party/hUGEDriver`) | `rgbds/hUGEDriver.asm` (effect jump table, `tick_time`, `get_note_poly`, instrument loads), `include/hUGEDriver.h` |

hUGETracker and hUGEDriver are dedicated to the public domain (hUGETracker README "License"). No upstream code is
copied into the app; the exporter is an independent implementation of the documented binary layout. fur2uge
(MIT, potatoTeto) was read only to cross-check the layout and the B/D parameter convention; nothing from it is used.
Upstream checkouts for research live in the git-ignored `research/upstream/`.

Evidence gathered while writing the exporter:
- An independent parser of the v6 layout consumed the three v6 sample songs shipped with hUGETracker 1.0.11
  (`FADE - Microplastics in the Air`, `FADE - Strap in and Suit Up`, `Tempest - Kekri`) exactly to end of file.
- A hand-built file in this layout was converted by the gbc-puzzler-pinned `.tools/uge2source` (1.0.11): order
  lists, `order_cnt`, `DN(...)` rows (including a cut as `DN(___,0,0xE00)`), and instrument bytes such as duty
  `{8,10,241,0,128}`, wave `{200,64,0,0,128}` and noise `{210,0,197,0,0}` came out as specified below. Those byte
  values are asserted in `HugeLoweringTest.instrumentBytesMatchUge2source`.
- `src/test/resources/uge2source/song_thor_groove.c` is the pinned `uge2source` output for the demo `.uge`
  written by `UgeExporter`. `Uge2sourceFixtureTest` requires the preview lowering to equal it (rows, order lists,
  `order_cnt`, tempo, instrument structs, waves). Regenerate it when the demo changes (command in the test).
- hUGETracker's sample songs are not checked in: their authors' licences are not stated in the repository.

## Document model (GB/hUGE)

- Four channels: Pulse 1, Pulse 2, Wave, Noise. Patterns store 64 rows, one note/instrument/effect per channel-row;
  `Pattern.length` (1..64, all channels) is how many of them play. Rows past it are kept but hidden.
- `Cell.note`: `-1` empty, `-2` explicit cut (displayed as **OFF**), `0..71` = C-3..B-8 (hUGE numbering, `C_3 = 0`). Noise uses the same
  numbers; the driver maps them to NR43 (`get_note_poly`).
- `Cell.instrument`: 1..15 selects a slot of the channel's typed bank. 0 is legato: hUGEDriver clears the trigger
  bit and does not reload the instrument, so the pitch changes without restarting. After a cut (`note_cut` zeroes
  NRx2/NR32) or before any instrument has played on the channel, such a note is silent.
- `Cell.effect`/`param`: raw hUGE effect nibble and byte. `000` is no effect.
- Selecting effect type 0 before entering a parameter is a view-local editor choice, not new song data: `000` still lowers as no effect. OFF remains an immediate `E00` cut, not a release envelope.
- Typed banks of exactly 15 instruments; 16 waves x 32 samples of 0..15.
- `Song.orders`: one pattern index per order position shared by all channels. `Song.loopOrder`: where playback
  continues after the last order.

## Lowering (shared by preview and export)

`HugeLowering.lower(song)` validates with `SongValidator.errors` and throws `IllegalArgumentException` (all
messages, newline-separated) for any error. Otherwise it returns `HugeData`:

- Track index of song pattern `p`, channel `c` is `p * 4 + c` for every pattern, used or not. Loop-variant tracks
  (below) are appended after them and referenced only at the final order position.
- Rows are hUGE `dn` bytes: `note | (instr & 0x10) << 3`, `(instr << 4) | effect`, `param`; no note = 90.
- A cut (`-2`) becomes note 90 with effect `E00` (cut on tick 0). It therefore needs its row's effect slot and no
  instrument; anything else on a cut is a validation error rather than a dropped value.
- Instruments use the GBDK struct layouts of `hUGEDriver.h` (6 bytes each, subpattern pointer always 0):
  - duty: `NR10, NR11, NR12, 0, 0, highmask` with NR10 = `sweepTime << 4 | (sweepDirection == 0 ? 8 : 0) | sweepShift`,
    NR11 = `duty << 6 | length & 63`, NRx2 = `volume << 4 | envelopeDirection << 3 | envelopePace`,
    highmask = `0x80 | lengthEnabled << 6`;
  - wave: `NR31 (length), NR32 (waveVolume << 5), wave, 0, 0, highmask`;
  - noise: `NR42, 0, 0, length & 63 | lengthEnabled << 6 | noiseWidth << 7, 0, 0`.
- Waves: 16 bytes each, earlier sample in the high nibble.
- Direction fields: 0 = decrease, 1 = increase (hUGE `stDown`/`stUp`; for the sweep, `stDown` sets NR10 bit 3).

### Pattern length

hUGE patterns are always 64 rows. `HugeRules.playedTracks` lowers a pattern with `length < 64` by blanking rows
`length..63` and, unless row `length - 1` already has `Dxx` (xx >= 1) or any `Bxx`, injecting `D01` (break to row 0
of the next order) into the first channel whose effect slot is empty and which is not a cut. No free slot is a
validation error; nothing is overwritten. Per-cell validation covers only played rows. A `Dxx` whose target row is
past the length of the order it leads to (next order, explicit `Bxx` target, or the loop target after the last order)
is an error.

The final order's loop plan (below) treats row `length - 1` as its last row. Its injected `B(L+1)` ends the pattern
by itself, so the loop variant carries `B` instead of `D01` and needs only one free slot.

### Looping

The driver advances past the last order to order 0. For `loopOrder = 0` nothing is added. For `loopOrder = L > 0`
the final order's pattern gets, in a copy used only at that order position:
- every `B00` ("next order") rewritten to `B(L+1)`;
- on the last row and on every row with `Dxx` (xx >= 1) that has no explicit `Bxx`, an injected `B(L+1)` in the
  first channel (Pulse 1 first) whose effect slot is empty and which is not a cut. `B` and `D` on one row compose
  in the driver (B sets the order, D the row).
- If such a row has no free slot, validation reports the conflict ("needs a free effect slot on row ...").
  Nothing is overwritten.

## Pinned-driver effect semantics enforced by validation

Parameters are raw. Facts from `hUGEDriver.asm` 6.1.3 that the editor shows via `Songs.describeEffect`:
- `Bxx` is 1-based: `B00` = continue with the next order, `B01` = order 0, `Bnn` = order nn-1. `Bxx > orders` is rejected.
- `Dxx` is 1-based: `D01` = next order, row 0; `D00` does nothing and is rejected; above `D40` is rejected.
- `6xx` (call routine) is rejected: projects have no routines and uge2source's GBDK output passes `NULL` routines.
- Rejected because the driver would ignore or misinterpret them: `1xx/2xx/300` with speed 0, `4x0`, `700`, `F00`,
  `Axy` on Wave or with both digits non-zero, `Cxy` on Wave with x != 0, `9xx` on Wave above `0F`,
  `9xx` on Noise other than `00`/`08`, an instrument without a note, and a sweep instrument (time and shift non-zero)
  played on a channel other than Pulse 1 (only CH1 has NR10).
- Pitch effects on Noise are rejected: `0xy` (non-zero), `1xx`, `2xx`, `3xx`, `4xy`. On CH4,
  `update_channel_freq.update_channel4` expects a note ID, but `fx_arpeggio`/`fx_vibrato` pass a computed period
  (its low byte is read as a note), `fx_porta_*` add to the stored NR43 value, and `fx_toneporta` suppresses the
  trigger. The driver's own subpattern path special-cases CH4; the effect paths do not.
- Vibrato `4xy`: the pitch is raised by y on ticks where (running tick counter AND x) = 0, so x = 0 is a constant
  detune and 1/3/7/F raise it 1 of every 2/4/8/16 ticks. `Cxy` restarts Pulse/Noise only if the channel's last
  note loaded an instrument; on Wave it only rewrites NR32 (no restart).
- There is no dedicated retrigger/stutter effect in the supported set. Repeating note rows with nonzero instruments retriggers their sound; instrument 0 is legato and is not an equivalent substitute.
- Warnings (legal, probably unintended):
  - `7xx`/`Exx` not shorter than the song's ticks per row;
  - Noise notes C-3..D#3 and E-8..G-8 (0-3, 64-67): `get_note_poly` gives clock shift 14, which never clocks the
    LFSR (Pan Docs, NR43), so no noise plays; G#8..B-8 (68-71) wrap to the same NR43 as E-3;
  - a note without instrument after a cut/`Exx` or before any instrument on the channel (silent legato), following
    the order list in sequence; `Cxy` (when it can restart) or `Axy` with x > 0 restores sound;
  - estimated uge2source GBDK data (22-byte descriptor and `order_cnt`, 192 bytes per distinct track, order
    pointers, used instruments and waves) larger than one 16 KiB ROM bank.

Limits (`HugeLimits`): at most 127 orders (`order_cnt` byte = orders x 2); at most 272 distinct lowered tracks
referenced by the order list (preview player capacity); names/title/artist/comment at most 255 UTF-8 bytes
(Pascal ShortString).

## `.uge` file written (`UgeExporter.export`)

hUGETracker format version 6, little endian. Enums are 4-byte integers, subranges and booleans 1 byte, ShortString
is a length byte plus 255 zero-padded UTF-8 bytes.

1. `int32` version = 6; ShortString title, artist, comment.
2. 45 instruments (15 duty, 15 wave, 15 noise), 1385 bytes each: `int32` type (0/1/2), ShortString name, `int32`
   length, `bool` length enabled, `uint8` initial volume, `int32` envelope direction (0 = up, 1 = down), `uint8`
   envelope pace, `int32` sweep time, `int32` sweep direction (0 = up, 1 = down), `int32` sweep shift, `uint8`
   duty, `int32` wave output level, `int32` waveform, `int32` noise counter step (0 = 15-bit, 1 = 7-bit),
   `bool` subpattern enabled (false), 64 blank subpattern cells.
3. 16 waves x 32 `uint8` samples.
4. `int32` ticks per row, `bool` timer tempo (false), `int32` timer divider (0).
5. `int32` pattern count, then per lowered track: `int32` key (= track index), 64 cells of `int32` note (90 = none),
   `int32` instrument, `int32` volume (0, unused in v6 song patterns), `int32` effect, `uint8` parameter.
   All song patterns are written, including ones not in the order list, so nothing is lost when opened in hUGETracker.
6. Four order columns (Pulse 1, Pulse 2, Wave, Noise): `int32` length + 1, the track keys, then one `int32` 0
   filler (hUGETracker's documented off-by-one entry, ignored by the tracker and uge2source).
7. 16 empty routines (`int32` 0 each).

Editor-only data (pattern names, root/scale guides, layout, view state) is not exported; the project file keeps it. Pattern scales constrain note entry, not existing notes or raw effect parameters.

## Project file (`ProjectCodec`)

UTF-8 JSON `{"format":"thor-tracker-project","version":4,"backend":"gb-huge","backendVersion":1,"song":{...}}`
with cells as `[note, instrument, effect, param]`. Version 2 adds each pattern's
`scale: {"root":0..11,"kind":"chromatic"|"major"|"natural-minor"|"major-pentatonic"|"minor-pentatonic"}`.
Version 3 adds each pattern's `length` (1..64). Version 4 adds the scale kinds `harmonic-minor`, `melodic-minor`,
`dorian`, `phrygian`, `lydian`, `mixolydian`, `locrian`, `blues`, `whole-tone`, `phrygian-dominant`,
`double-harmonic`, `hungarian-minor`, `hirajoshi` and `miyako-bushi`. Older files remain readable: version 1
defaults to Chromatic and versions 1-2 to 64 rows; a file cannot claim a version older than the fields and scale
kinds it contains. The backend data version remains 1: tracks keep
their 64-row shape and lengths are lowered to standard driver breaks.

Decoding rejects malformed JSON, unknown keys, other formats, newer versions, other backends and structurally
invalid songs with `ProjectFormatException`. Songs with export errors still save and load, so work in progress
survives autosave. New saves always use version 4; earlier apps do not read that newer format.

## Reproducing the sample and conversion

Run `tools/build.sh exportSamples` to write `app/build/samples/thor-groove.thor` and
`app/build/samples/thor-groove.uge`. Sample generation is separate from unit tests.
Convert with the pinned tool, for example from the gbc-puzzler checkout (read-only use):

```sh
.tools/uge2source /path/to/thor-tracker/app/build/samples/thor-groove.uge -b 255 song_thor_groove /tmp/song_thor_groove.c
```
