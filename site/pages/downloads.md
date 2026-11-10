# Download & install

Get builds from [official GitHub Releases](https://github.com/esko997/thortracker/releases/latest), not development artifacts or paths in old verification logs. Read that release's notes and choose the package for your device. The same release page carries SHA-256 checksum sidecars.

| Device | Release asset | Setup |
| --- | --- | --- |
| Android 8+, arm64-v8a or x86_64 | `thor-tracker-<version>.apk` | Sideload the APK; see Android below. |
| Steam Deck / x86_64 Linux | `thor-tracker-<version>-x86_64.AppImage` | Make executable, then add to Steam if wanted. |
| Brick Hammer with NextUI (`tg5040`) | `thor-tracker-<version>-brick-nextui.zip` | Extract the Pak at the SD-card root. |

Back up named `.thor` projects and export your keymap before changing installations. Recovery is useful, but it is not an external backup. An older release may not understand a newer project or backend format.

The sections below are included directly from the current installation instructions. They can describe source changes newer than the release you choose; always check the release notes.

<!-- include: README.md :: Install -->

<!-- include: README.md :: Steam Deck -->

For native menus, file locations, keyboard mappings and platform limitations, see the [Linux reference](/deck-reference/).

<!-- include: README.md :: Brick Hammer / NextUI -->

<!-- include: README.md :: Colour themes -->

## After installation

Start with [your first phrase](/getting-started/). If the buttons differ on your handheld, use [Controls → rebinding](/controls/). For installation failures or missing audio, see [troubleshooting](/troubleshooting/).
