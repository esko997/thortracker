# Make music, one row at a time.

<p class="lead">Thor Tracker is a controller-first music tracker. Write patterns, shape instruments and arrange songs with chip-specific sound engines, complementary views on two displays, and Scala tuning support.</p>

<div class="actions"><a href="/downloads/">Download & install</a><a href="/getting-started/#a-first-session">Make your first song</a></div>

**Thor Tracker 1.0.0** is available for Android, x86_64 Linux and Brick Hammer with NextUI.

<figure class="hero-shot">
<img src="../../docs/media/brick-pattern.png" alt="Thor Tracker running on Brick Hammer: the bundled Thor Groove demo playing in the four-channel Game Boy pattern editor.">
<figcaption>Brick Hammer screenshot.</figcaption>
</figure>

## Choose your instrument bench

<div class="platform-list"><a href="/downloads/#install"><strong>Android handhelds</strong><span>APK · single or dual screen</span></a><a href="/downloads/#steam-deck"><strong>Steam Deck / Linux</strong><span>x86_64 AppImage · one view at a time</span></a><a href="/downloads/#brick-hammer--nextui"><strong>Brick Hammer</strong><span>NextUI tg5040 · ARM64 Pak</span></a></div>

Navigate, edit, audition and arrange without leaving the controller. Start with [a first session](/getting-started/#a-first-session), then use the [pattern workflow](/getting-started/#pattern-workflow) and [controller reference](/controls/) for block editing, transport and view shortcuts.

## One song, complementary views

Keep Pattern beside Keyboard for note entry, or Pattern beside Instrument for sound design. Each display has its own view and cursor; controller focus is explicit, so direct editor touch does not redirect the controller. With one display, switch views using the same editing commands. See [views and displays](/getting-started/#views-and-displays).

<figure class="hero-shot">
<img src="../../docs/media/pattern-and-keyboard-dual-screen.png" alt="Pattern and Keyboard views on two displays: notes entered from Keyboard appear in the Pulse 2 pattern channel.">
<figcaption>Pattern + Keyboard: note entry beside the pattern.</figcaption>
</figure>

## Work with the chip, not around it

Each song targets one [sound system](/systems/), not a generic synth with a different skin. SID programs, FM operators, wavetable banks and sample mappings follow their own chip and driver. Channel layouts, shared resources, effects and exports remain system-specific.

Use [project files](/files/) to keep editable work, then export audio or a supported system format. A register log is not a ready-to-run game driver; [export formats and limits](/files/#choose-a-delivery-format) explain the distinction.

## Shape the sound

<figure class="hero-shot">
<img src="../../docs/media/brick-gb-sound.png" alt="Game Boy Sound panel on Brick Hammer, with a green pixel-style timbre display and musical controls beside the unchanged instrument list.">
<figcaption>Brick Hammer screenshot: Game Boy Sound panel.</figcaption>
</figure>

Start with musical controls in **Sound** to adjust timbre, envelope or modulation, then open **Advanced** for detailed chip parameters. They edit the same patch, share Undo, and do not require conversion between two instrument formats. Follow the [instrument workflow](/getting-started/#instrument-workflow); use the [sound-system references](/systems/) for each chip's controls and limits.

## Compose beyond twelve equal steps

Load a **Scala `.scl`** file or choose a tuning from the library. Set its root and pitch-accuracy limit, then enter reachable degrees or fit existing notes. The selected chip and driver still determine which pitches can play. See [microtuning](/getting-started/#microtuning-song-tuning-from-a-scala-scl-file).

## A manual beside the machine

- [User guide](/getting-started/): first song, pattern editing, instruments, arrangement and tuning.
- [Projects & exports](/files/): save, recover, convert and deliver music.
- [Troubleshooting](/troubleshooting/): installation, silence, firmware and useful bug reports.
- [Complete manual](/manual/): detailed editing, instrument and format reference.
- [Source & licences](/licenses/): free software, bundled components and source pins.

Use a physical controller or touch where available; [input and navigation](/controls/) explains their differences.
