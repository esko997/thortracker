# Sound systems

A song targets one system. Choose it in **Files → New blank song** or explore **Load demo**. Instruments, channel counts, effects, pitch limits and exports belong to that system; shared editing controls do not remove the hardware's limits.

## Supported families

The registry covers **Game Boy / Game Boy Color**, **NES** and its **VRC6, VRC7, N163 (1–8 channels), Sunsoft 5B, FDS, MMC5 and EPSM** variants; **C64 SID**; **OPL3**; **Mega Drive**; **Master System, Game Gear and the SMS FM Unit (melodic or rhythm)**; **AY/YM machines (ZX Spectrum 128, MSX, Atari ST, Amstrad CPC)**; **PC Engine**; **MSX SCC / SCC+**; **YM2151 OPM**; **PC-88/98 OPN / OPNA**; **Atari 8-bit POKEY**; **Atari 2600 TIA (NTSC / PAL)**; **VIC-20 / C16 / Plus/4**; **WonderSwan / WonderSwan Color**; **Virtual Boy**; and **SAA1099 (SAM Coupé / Creative Music System)**.

**Game Boy Advance / Maxmod** adds eight sample voices and `.xm` export for GBA homebrew.

Start with the [instrument workflow](/getting-started/#instrument-workflow) for selecting sounds and using Sound and Advanced on the same patch. The references below describe each system's instruments, effects and export formats; the [instrument panel reference](../../docs/INSTRUMENT_PANELS.md) covers the chip-family Sound controls.

<!-- include: docs/MANUAL.md :: Multiple effects per row -->

## Game Boy / Game Boy Color

<!-- include: docs/MANUAL.md :: Sound and export rules -->

<!-- include: docs/MANUAL.md :: Game Boy Advance: -->

<!-- include: docs/MANUAL.md :: NES instruments: envelopes -->

<!-- include: docs/MANUAL.md :: NES DPCM samples -->

<!-- include: docs/MANUAL.md :: NES + Sunsoft 5B -->

<!-- include: docs/MANUAL.md :: NES + VRC6 -->

<!-- include: docs/MANUAL.md :: NES + VRC7 -->

<!-- include: docs/MANUAL.md :: NES + N163 -->

<!-- include: docs/MANUAL.md :: NES + FDS -->

<!-- include: docs/MANUAL.md :: NES + EPSM -->

<!-- include: docs/MANUAL.md :: NES + MMC5 -->

<!-- include: docs/MANUAL.md :: C64 SID instruments -->

## OPL3 FM (YMF262)

OPL3 uses Thor Tracker's player with Nuked-OPL3. It provides nine pattern tracks, up to four effect columns, two- and four-operator instruments and chords. Songs export as VGM or WAV. See the [OPL3 engine documentation](../../docs/AUDIO_ENGINE.md#opl3-thor-trackers-own-player-on-a-ymf262-opl3-fm) for voice allocation and effect details.

<!-- include: docs/MANUAL.md :: Mega Drive instruments -->

<!-- include: docs/MANUAL.md :: Master System, Game Gear -->

<!-- include: docs/MANUAL.md :: AY-3-8910 / YM2149 sounds -->

<!-- include: docs/MANUAL.md :: PC Engine: -->

<!-- include: docs/MANUAL.md :: MSX SCC and SCC+ -->

<!-- include: docs/MANUAL.md :: OPM (YM2151) -->

<!-- include: docs/MANUAL.md :: PC-88/98: -->

## Atari 8-bit POKEY

POKEY provides four channels, including paired-channel modes, filtering and samples. Songs export as VGM or WAV; XEX and SAP export are not supported. The bundled demo is **Scanline Cruise**. See the [POKEY engine documentation](../../docs/AUDIO_ENGINE.md#pokey-thor-trackers-own-player-on-the-atari-8-bit-pokey-atari-pokey) for clock and rendering details.

<!-- include: docs/MANUAL.md :: Atari 2600: -->

<!-- include: docs/MANUAL.md :: VIC-20 and C16 / Plus/4 sounds -->

<!-- include: docs/MANUAL.md :: WonderSwan and WonderSwan Color: -->

<!-- include: docs/MANUAL.md :: Virtual Boy: -->

<!-- include: docs/MANUAL.md :: SAA1099: -->

## Shared resources

Hardware-shared envelopes, filters, waveform banks and master volume affect all channels that use them. Output levels differ between sound systems.

EPSM and OPNA rhythm drums require a **user-supplied YM2608 rhythm ROM**. See the
[firmware requirements](../../docs/AUDIO_ENGINE.md#the-ym2608-rhythm-rom-shared-firmware)
and in-app help.

[Conversion between systems](/files/#converting-between-systems) describes how
compatibility reports identify unsupported instruments and effects.
