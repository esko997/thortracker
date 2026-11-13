# Download & install

Download **1.0.0** from the [official release](https://github.com/esko997/thortracker/releases/tag/v1.0.0). Choose the package for your device; each download includes a SHA-256 checksum file.

| Device | Release asset | Setup |
| --- | --- | --- |
| Android 8+, arm64-v8a or x86_64 | `thor-tracker-<version>.apk` | Sideload the APK; see Android below. |
| Steam Deck / x86_64 Linux | `thor-tracker-<version>-x86_64.AppImage` | Make executable, then add to Steam if wanted. |
| Brick Hammer with NextUI (`tg5040`) | `thor-tracker-<version>-brick-nextui.zip` | Extract the Pak at the SD-card root. |
| Brick local Pak install / Pak Store format | `ThorTracker.pak.zip` | Extract **inside** `Tools/tg5040/ThorTracker.pak/`; do not extract this ZIP at the SD-card root. |

Both NextUI archives contain the same application. Choose the archive layout that matches
your installation method. Projects and exports are stored separately in
`/mnt/SDCARD/ThorTunez`; settings and recovery files use its `.thortracker` subdirectory.

Back up projects before downgrading: older releases may not support newer project formats.

<!-- include: docs/MANUAL.md :: Install -->

<!-- include: docs/MANUAL.md :: Steam Deck -->

For native menus, file locations, keyboard mappings and platform limitations, see the [Linux reference](/deck-reference/).

<!-- include: docs/MANUAL.md :: Brick Hammer / NextUI -->

<!-- include: docs/MANUAL.md :: Colour themes -->

## After installation

Start with [your first phrase](/getting-started/). If the buttons differ on your handheld, use [Controls → rebinding](/controls/). For installation failures or missing audio, see [troubleshooting](/troubleshooting/).
