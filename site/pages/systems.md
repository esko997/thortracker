# Sound systems

A song targets one system. Choose it in **Files → New blank song** or explore **Load demo**. Instruments, channel counts, effects, pitch limits and exports belong to that system; shared editing controls do not remove the hardware's limits.

## Supported families

The registry covers **Game Boy / Game Boy Color**, **NES** and its **VRC6, VRC7, N163 (1–8 channels), Sunsoft 5B, FDS, MMC5 and EPSM** variants; **C64 SID**; **OPL3**; **Mega Drive**; **Master System, Game Gear and the SMS FM Unit (melodic or rhythm)**; **AY/YM machines (ZX Spectrum 128, MSX, Atari ST, Amstrad CPC)**; **PC Engine**; **MSX SCC / SCC+**; **YM2151 OPM**; **PC-88/98 OPN / OPNA**; **Atari 8-bit POKEY**; **Atari 2600 TIA (NTSC / PAL)**; **VIC-20 / C16 / Plus/4**; **WonderSwan / WonderSwan Color**; **Virtual Boy**; and **SAA1099 (SAM Coupé / Creative Music System)**.

**Game Boy Advance / Maxmod** adds eight sample voices and `.xm` export for GBA homebrew.

The sections below are generated from the canonical manual, including its limitations and dated status notes. Some older notes describe pre-release work; they are retained, not silently upgraded into hardware-verification claims. Check the [release notes](https://github.com/esko997/thortracker/releases/latest) and the [verification record](/verification/) for the version and platform you use. No original-hardware listening comparison is implied by passing software tests.

<!-- include: README.md :: Multiple effects per row -->

## Game Boy / Game Boy Color

<!-- include: README.md :: Sound and export rules -->

<!-- include: README.md :: Game Boy Advance: -->

<!-- include: README.md :: NES instruments: envelopes -->

<!-- include: README.md :: NES DPCM samples -->

<!-- include: README.md :: NES + Sunsoft 5B -->

<!-- include: README.md :: NES + VRC6 -->

<!-- include: README.md :: NES + VRC7 -->

<!-- include: README.md :: NES + N163 -->

<!-- include: README.md :: NES + FDS -->

<!-- include: README.md :: NES + EPSM -->

<!-- include: README.md :: NES + MMC5 -->

<!-- include: README.md :: C64 SID instruments -->

## OPL3 FM (YMF262)

OPL3 uses Thor Tracker's own player on Nuked-OPL3, not a console's game driver. Songs export as a VGM register log or WAV. The [canonical OPL3 contract](/audio-reference/#opl3-thor-trackers-own-player-on-a-ymf262-opl3-fm) documents voice allocation, two- and four-operator instruments, effects, pitch and export limits. The [project format](/files/#saving-and-recovery) records its nine pattern tracks, up to four effect columns and instrument/chord data. VGM export is not an executable for a target computer.

<!-- include: README.md :: Mega Drive instruments -->

<!-- include: README.md :: Master System, Game Gear -->

<!-- include: README.md :: AY-3-8910 / YM2149 sounds -->

<!-- include: README.md :: PC Engine: -->

<!-- include: README.md :: MSX SCC and SCC+ -->

<!-- include: README.md :: OPM (YM2151) -->

<!-- include: README.md :: PC-88/98: -->

## Atari 8-bit POKEY

One POKEY is supported, not a dual-POKEY stereo board. Thor Tracker's own player drives its cycle-timed POKEY model and exports VGM or WAV. There is **no XEX or SAP export**: those would require an Atari 6502 driver that this system does not provide. See the [POKEY contract](/audio-reference/#pokey-thor-trackers-own-player-on-the-atari-8-bit-pokey-atari-pokey) for joined channels, filtering, samples, clocks and fidelity limits; its demo is **Scanline Cruise**.

<!-- include: README.md :: Atari 2600: -->

<!-- include: README.md :: VIC-20 and C16 / Plus/4 sounds -->

<!-- include: README.md :: WonderSwan and WonderSwan Color: -->

<!-- include: README.md :: Virtual Boy: -->

<!-- include: README.md :: SAA1099: -->

## Shared resources and honest limits

A hardware-shared envelope, filter, waveform bank or master volume is still shared. Editing one lane can intentionally affect another. Loudness differs across chips; do not assume normalization or add gain to compensate for a missing instrument or unavailable firmware.

EPSM and OPNA rhythm drums need a **user-supplied YM2608 rhythm ROM**. It is not included in this site, the source, releases, demos or projects. See the [firmware contract](/audio-reference/#the-ym2608-rhythm-rom-shared-firmware) and the system's in-app help.

For detailed effects, image layouts and export contracts, read the [audio reference](/audio-reference/). The [backend design history](/backend-design/) includes proposals and is not a list of promised features. [Conversion](/files/#converting-between-systems) explains how the app reports unsupported resources before changing a song.
