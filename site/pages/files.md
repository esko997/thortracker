# Projects & exports

Keep a **`.thor` project** as your editable master. Exports are delivery formats, not a substitute for the document and its instruments, arrangement and tuning provenance. Back up named projects outside app-private recovery storage.

## Choose a delivery format

All systems can render **WAV**. Other formats depend on the song's system:

| System | System-specific export |
| --- | --- |
| Game Boy / Game Boy Color | hUGETracker `.uge` v6 |
| NES, including expansion variants | `.nsf`, FamiStudio `.txt` |
| C64 SID | `.sid`, `.prg`, GoatTracker `.sng` |
| Mega Drive | `.vgm`, XGM2 `.xgm`, compiled XGM2 `.xgc` |
| Master System / Game Gear / SMS FM Unit | WLA-DX `.asm`, SDAS `.s`, `.vgm` |
| OPL3, AY/YM, PC Engine, SCC/SCC+, OPM, OPN/OPNA, POKEY, WonderSwan/Color, Virtual Boy, SAA1099 | `.vgm` |
| Atari 2600 TIA, VIC-20, C16 / Plus/4 | WAV; no additional chip-log or executable export |
| Game Boy Advance / Maxmod | Eight-channel FastTracker II `.xm` for Maxmod-based GBA games |

This index follows the [backend export declarations](../../core/src/commonMain/kotlin/org/thortracker/system/). Format-specific restrictions still apply: a VGM register log is not a game-driver binary, NSF players need to support the selected expansion, and proprietary firmware is not bundled. Consult the [system guide](/systems/) and [audio export contracts](/audio-reference/) before integrating music into a game.

GBA export is currently **PCM/sample-only**; it does not include the hardware PSG
channels. Its verified target is mmutil 1.10.1 / Maxmod 1.0.15, not every engine that
accepts XM. WAV export records audio and does not create a game playback driver.

Keymaps use **`thor-tracker-keymap.json`** and can be exported/imported from Controls. Tuning collections use **`.thorscales`**; individual scales use **Scala `.scl`**. These are separate from a song's `.thor` document. [Microtuning](/manual/#microtuning-song-tuning-from-a-scala-scl-file) describes provenance, pitch limits and collection management.

<!-- include: README.md :: Export to WAV -->

<!-- include: README.md :: Converting between systems -->

<!-- include: README.md :: Saving and recovery -->

## Game Boy export details

The [UGE reference](/uge-reference/) is generated from `docs/UGE_EXPORT.md`: its format, effect and validation rules remain the canonical contract. The [audio reference](/audio-reference/) documents each system's native renderer and exports.
