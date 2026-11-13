# Troubleshooting

Start with the [release notes](https://github.com/esko997/thortracker/releases/latest) for the installed version and the relevant [platform setup](/downloads/). Keep a copy of your project before trying a different version or clearing app data.

## An Android update will not install

Official APKs use the production release key. A debug build or a build older than 0.8.4 may have a different signature and require a one-time uninstall. **Back up projects and export your keymap first**: uninstalling removes private recovery and settings. Confirm the APK matches your device's architecture and verify its checksum. The [Android installation instructions](/downloads/#install) contain the certificate fingerprint.

## Steam or NextUI cannot start the app

On Deck, check that the AppImage is executable and the Steam shortcut points to the stable link you created. Confirm that Steam Input passes controller buttons through. See the [Linux reference](/deck-reference/) for keyboard mappings and file locations.

On Brick Hammer, use the **NextUI tg5040** package. The launcher must be at `Tools/tg5040/ThorTracker.pak/launch.sh` below the SD-card root. Use **Quit** to return to NextUI. Logs are written to `ThorTracker.txt` in NextUI's log directory. The application uses NextUI's installed SDL and Mali libraries.

## A note or channel is silent

- Check the selected system, channel and instrument. A sound must be playable on that channel: for example, PC Engine noise instruments need PSG 5 or 6.
- Load a demo for that system and hold **X** to audition with playback stopped.
- Check the instrument envelope, sample mapping and required firmware. Some instruments or partner channels are silent by design.
- Game Boy instrument **0** is legato: after a cut it can remain silent because it does not retrigger the instrument. See [sound and export rules](/manual/#sound-and-export-rules).
- EPSM / OPNA rhythm drums require an imported YM2608 rhythm ROM. See [firmware requirements](../../docs/AUDIO_ENGINE.md#the-ym2608-rhythm-rom-shared-firmware). Do not attach proprietary ROM data to an issue.

For crackles, dropouts or unexpected level differences, include the sound system, device, app version, reproduction steps and diagnostics in a bug report.

## Controls or saved work look wrong

Use **Menu → Controls** to inspect the actual bindings. Imported custom keymaps retain conflicts rather than silently stealing a button, so a newer action may be unbound. Hold the modifier before the action button and check which view has controller focus.

Recovery is separate from an external Save. The previous-project slot holds only one replacement, and undo history is not serialized. If a newer project will not open in an older version, do not edit its version number: use a compatible release and keep the original file. See [saving and recovery](/files/#saving-and-recovery).

<!-- include: README.md :: About, diagnostics and bug reports -->

## Make a useful report

Open the [issue form](https://github.com/esko997/thortracker/issues/new/choose). Include what you expected, what happened, and the smallest sequence that reproduces it. State the release version, device/OS, sound system and whether the problem concerns editing, live playback or exported audio. Attach a small `.thor` project when you can share it; review diagnostics before posting. Never include signing keys, private paths you do not want public, or proprietary firmware.

Documentation corrections also belong in the [source repository](https://github.com/esko997/thortracker).
