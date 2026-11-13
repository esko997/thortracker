# Thor Tracker

A controller-first chiptune tracker for Android, Steam Deck / Linux and Brick Hammer with NextUI. Write patterns, shape instruments, arrange songs and export music for classic consoles and computers.

> **AI disclosure:** AI tools are used in the development of this project.

**[Download](https://github.com/esko997/thortracker/releases/latest) · [Documentation](https://esko997.github.io/thortracker/) · [User guide](https://esko997.github.io/thortracker/getting-started/)**

- **Chip-specific backends:** Game Boy, NES and expansions, C64 SID, Yamaha FM, Sega PSG, wavetable systems and more. GBA/Maxmod adds eight sample voices and XM export.
- **Sound and Advanced:** musical instrument panels and detailed native editors work on the same patch.
- **One screen or two:** switch views on a single display, or pair independent editors on dual-screen Android devices.
- **Controller and touch:** rebindable controls, direct touch editing and an Android controller dock.
- **Scales and tunings:** Scala `.scl` import and a built-in tuning library, within each backend's pitch capabilities.
- **Editable projects and exports:** save `.thor` projects, render WAV, or export the selected system's music formats.

<img src="docs/media/pattern-and-keyboard-dual-screen.png" width="620" alt="Pattern and Keyboard views on two displays">

*Pattern and Keyboard on dual displays.*

## Install

Download a package from [Releases](https://github.com/esko997/thortracker/releases/latest). SHA-256 checksum files accompany the release assets.

| Platform | Package and installation |
| --- | --- |
| Android 8+ (arm64-v8a / x86_64) | Install `thor-tracker-<version>.apk`. |
| Steam Deck / x86_64 Linux | Make `thor-tracker-<version>-x86_64.AppImage` executable and launch it. It can also be added to Steam as a non-Steam game. |
| Brick Hammer / NextUI (`tg5040`) | Extract `thor-tracker-<version>-brick-nextui.zip` at the SD-card root, then launch ThorTracker from **Tools**. |

The separate **`ThorTracker.pak.zip`** is the Pak Store-format archive: its contents go **inside** `Tools/tg5040/ThorTracker.pak/`, not at the SD-card root.

See the [installation guide](https://esko997.github.io/thortracker/downloads/) for upgrades, Android signing compatibility, storage locations and Steam shortcut setup. The [first-session guide](https://esko997.github.io/thortracker/getting-started/#a-first-session) covers editing, audition and arrangement.

## Build from source

### Android

Requirements: **JDK 17**, **Android SDK platform 35**, Android build tools, **NDK 27.0.12077973** and **CMake 3.22.1**. The repository includes the Gradle wrapper; set `ANDROID_HOME` to your SDK location.

```sh
./tools/build.sh assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Run the core and Android host tests with:

```sh
./tools/build.sh :core:jvmTest testDebugUnitTest
```

The Linux build wrapper requires a working systemd user manager and at least **8 GiB available RAM**. It bounds build resources and keeps compiler scratch on disk. See the [full build reference](docs/MANUAL.md#build-from-source) for environment options, sample generation and release builds.

### Linux and NextUI

GitHub Actions builds the Linux AppImage and ARM64 NextUI Pak. See the [Linux build reference](deck/README.md#build-and-run) and [build workflow](.github/workflows/build.yml) for toolchains and packaging. Prefer CI artifacts on memory-constrained machines; Kotlin/Native linking is resource-intensive.

## Documentation and support

- [User guide](https://esko997.github.io/thortracker/getting-started/) — patterns, instruments, arrangement, displays and tuning.
- [Input and controls](https://esko997.github.io/thortracker/controls/) — controller bindings and touchscreen operation.
- [Sound systems](https://esko997.github.io/thortracker/systems/) — chip-specific capabilities and limitations.
- [Projects and exports](https://esko997.github.io/thortracker/files/) — saving, recovery and delivery formats.
- [Complete manual source](docs/MANUAL.md) · [audio-engine reference](docs/AUDIO_ENGINE.md) · [documentation build](site/README.md).
- [Report a bug](https://github.com/esko997/thortracker/issues/new/choose) — include the app version, device, sound system and reproduction steps.

## License

Thor Tracker is free software under **[GPL-3.0-or-later](LICENSE)**. Bundled third-party components retain their own licences; see [notices and source credits](https://esko997.github.io/thortracker/licenses/).
