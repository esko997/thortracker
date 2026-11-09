package org.thortracker.system.opn

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.opn.OpnRules.FX_ENV_PERIOD
import org.thortracker.system.opn.OpnRules.FX_ENV_SHAPE
import org.thortracker.system.opn.OpnRules.FX_LFO
import org.thortracker.system.opn.OpnRules.FX_PAN
import org.thortracker.system.opn.OpnRules.FX_PORTAMENTO
import org.thortracker.system.opn.OpnRules.FX_VIBRATO
import org.thortracker.system.opn.OpnRules.FX_VOLUME
import org.thortracker.system.opn.OpnRules.FX_VOLUME_SLIDE
import org.thortracker.system.ym2612.YmOperator

/**
 * The bundled PC-88/98 demos, original music written for Thor Tracker:
 *
 * - [opn] "Night Express" (YM2203), D minor (Dm-B♭-C-A; the bridge Gm-B♭-F-A), 6 ticks per row at the default timer
 *   B (59.77 Hz). FM 1 plays the lead (vibrato, a portamento glide), FM 2 the bass, FM 3 runs in the special mode: one
 *   voice whose four operators sit a chord apart (root, third, fifth, octave), so each FM 3 note is a whole chord. SSG 1
 *   plays arpeggio envelopes, SSG 2 the hardware envelope's saw as a buzz bass and its triangle as a swell pad (with
 *   envelope shape and period locks), SSG 3 the drums from tone sweeps and the noise generator.
 * - [opna] "Paper Lanterns" (YM2608), A major (A-F♯m-D-E; the bridge Bm-D-F♯m-E), 7 ticks per row at timer B 192
 *   (54.2 Hz). FM 1-6 are spread across the stereo field with `8xy` (an E-piano lead, LFO-vibrato strings, FM 3's
 *   special-mode chord stacks, a bass, a bell countermelody and a pad); the LFO runs from the start and speeds up in the
 *   bridge (`Lxx`). The ADPCM-B channel plays the generated kick and snare samples, then a looping sung "ah" sample in
 *   the bridge; the SSG adds arpeggios, the hardware envelope's buzz bass and swell pad and noise hats. No rhythm notes,
 *   so it plays without the rhythm ROM.
 *
 * Both loop to order 1. [shortest] is the minimal valid song the native WAV export test renders.
 */
object OpnDemoSong {
    private const val NIGHT_SPEED = 6
    private const val LANTERN_SPEED = 7
    private const val LANTERN_TIMER_B = 192

    /** A chord of a bar: its root (pitch class 0 = C) and whether it is minor. */
    private class Chord(val root: Int, val minor: Boolean) {
        fun at(octave: Int) = octave * 12 + root
        val third: Int get() = if (minor) 3 else 4
    }

    private fun chord(name: String, minor: Boolean = false) = Chord(n(name + "0"), minor)

    /** A voice whose four carriers (algorithm 7) FM 3's special mode spreads over a chord: [offsets] in 1/64 semitones. */
    private fun stack(name: String, offsets: List<Int>) = OpnFmSound(
        name = name,
        algorithm = 7,
        feedback = 0,
        operators = List(4) { YmOperator(mul = 1, tl = 26, ar = 31, d1r = 7, d2r = 2, sl = 3, rr = 6) },
        ch3 = offsets,
    )

    private val MAJOR_STACK = listOf(0, 4 * 64, 7 * 64, 12 * 64)
    private val MINOR_STACK = listOf(0, 3 * 64, 7 * 64, 12 * 64)

    // ---- Night Express (YM2203) -------------------------------------------------------------------------------------

    private object Night {
        const val LEAD = 0
        const val BASS = 1
        const val STACK = 2
        const val ARP = 3
        const val BUZZ = 4
        const val DRUMS = 5

        val VERSE = listOf(chord("D-", minor = true), chord("A#"), chord("C-"), chord("A-"))
        val BRIDGE = listOf(chord("G-", minor = true), chord("A#"), chord("F-"), chord("A-"))
    }

    /** "Night Express", the YM2203 demo. */
    fun opn(): Song {
        val blank = OpnSystem.blank()
        val slots = Slots(blank.opn)
        val major = slots.add(stack("Major stack", MAJOR_STACK))
        val minor = slots.add(stack("Minor stack", MINOR_STACK))
        val s = Sounds(slots, major, minor)
        return blank.copy(
            title = "Night Express",
            artist = "Thor Tracker",
            comment = "YM2203 demo: FM lead and bass, FM 3 special-mode chord stacks, SSG arpeggio envelopes, the hardware " +
                "envelope's buzz bass and swell pad, tone-sweep and noise drums. Loops to order 1.",
            ticksPerRow = NIGHT_SPEED,
            loopOrder = 1,
            patterns = listOf(nightIntro(s), nightTheme(s), nightBridge(s)),
            orders = listOf(0, 1, 2, 1),
            sound = slots.sound.copy(fm3Mode = OpnFm3Mode.SPECIAL),
        )
    }

    /** The sounds a demo uses, by slot. */
    private class Sounds(slots: Slots, val majorStack: Int, val minorStack: Int) {
        val lead = slots.find(OpnPart.FM, "Lead")
        val bass = slots.find(OpnPart.FM, "Finger bass")
        val epiano = slots.find(OpnPart.FM, "E-piano")
        val strings = slots.find(OpnPart.FM, "Strings")
        val bell = slots.find(OpnPart.FM, "Bell")
        val pad = slots.find(OpnPart.FM, "Pad")
        val arpMajor = slots.find(OpnPart.SSG, "SSG arp major")
        val arpMinor = slots.find(OpnPart.SSG, "SSG arp minor")
        val buzz = slots.find(OpnPart.SSG, "SSG buzz bass")
        val swell = slots.find(OpnPart.SSG, "SSG swell pad")
        val ssgKick = slots.find(OpnPart.SSG, "SSG kick")
        val ssgSnare = slots.find(OpnPart.SSG, "SSG snare")
        val ssgHat = slots.find(OpnPart.SSG, "SSG hat")

        fun stack(c: Chord) = if (c.minor) minorStack else majorStack
        fun arp(c: Chord) = if (c.minor) arpMinor else arpMajor
    }

    private fun nightIntro(s: Sounds) = PatternBuilder(OpnChip.YM2203, "Intro").apply {
        for (bar in 0 until 4) {
            val c = Night.VERSE[bar]
            nightBass(s, bar, c)
            ssgArp(Night.ARP, s, bar, c, 0x0B)
            if (bar >= 2) {
                nightStacks(s, bar, c)
                nightDrums(s, bar)
            } else {
                // The train starts: hats only.
                for (row in 2 until 16 step 4) note(Night.DRUMS, bar * 16 + row, n("C-6"), s.ssgHat)
            }
        }
        // A snare roll into the theme, getting louder.
        for ((row, volume) in listOf(56 to 0x08, 58 to 0x0A, 60 to 0x0C, 61 to 0x0D, 62 to 0x0E, 63 to 0x0F)) {
            note(Night.DRUMS, row, n("G-4"), s.ssgSnare)
            fx(Night.DRUMS, row, 0, FX_VOLUME, volume)
        }
    }.build()

    private fun nightTheme(s: Sounds) = PatternBuilder(OpnChip.YM2203, "Theme").apply {
        for (bar in 0 until 4) {
            val c = Night.VERSE[bar]
            nightBass(s, bar, c)
            nightStacks(s, bar, c)
            ssgArp(Night.ARP, s, bar, c, 0x0B)
            nightDrums(s, bar)
            // The SSG's hardware envelope saw doubles the bass an octave up.
            note(Night.BUZZ, bar * 16, c.at(3), s.buzz)
            note(Night.BUZZ, bar * 16 + 8, c.at(3), s.buzz)
            cut(Night.BUZZ, bar * 16 + 14)
        }
        fx(Night.BUZZ, 0, 0, FX_VOLUME, 0x0C)
        lead(Night.LEAD, s.lead, listOf(
            0 to "A-4", 4 to "D-5", 6 to "E-5", 8 to "F-5", 14 to "E-5",
            16 to "D-5", 22 to "F-5", 24 to "A-5", 30 to "G-5",
            32 to "G-5", 36 to "E-5", 38 to "C-5", 40 to "E-5", 44 to "G-5",
            48 to "A-5", 56 to "C#6", 58 to "A-5", 60 to "E-5", 62 to "C#5",
        ))
        fx(Night.LEAD, 0, 0, FX_VOLUME, 0x78)
        for (row in listOf(8, 24, 48)) fx(Night.LEAD, row, 1, FX_VIBRATO, 0x52)
    }.build()

    private fun nightBridge(s: Sounds) = PatternBuilder(OpnChip.YM2203, "Bridge").apply {
        for (bar in 0 until 4) {
            val c = Night.BRIDGE[bar]
            nightBass(s, bar, c)
            // Whole-bar chords from FM 3 alone.
            note(Night.STACK, bar * 16, c.at(4), s.stack(c))
            ssgArp(Night.ARP, s, bar, c, 0x09)
            nightDrums(s, bar)
            // The hardware envelope's triangle swells the chord's third in and out.
            note(Night.BUZZ, bar * 16, c.at(4) + c.third, s.swell)
        }
        fx(Night.STACK, 0, 0, FX_VOLUME, 0x60)
        fx(Night.BUZZ, 0, 0, FX_VOLUME, 0x0D)
        // The last bar: a falling saw at a fixed period instead (shape and period locks).
        fx(Night.BUZZ, 48, 0, FX_ENV_SHAPE, 0x08)
        fx(Night.BUZZ, 48, 1, FX_ENV_PERIOD, 0x06)
        cut(Night.BUZZ, 63)
        lead(Night.LEAD, s.lead, listOf(0 to "D-5", 8 to "C-5", 12 to "A#4", 16 to "D-5", 24 to "F-5", 32 to "F-5", 48 to "C#6"))
        fx(Night.LEAD, 0, 0, FX_VOLUME, 0x74)
        fx(Night.LEAD, 0, 1, FX_VIBRATO, 0x43)
        // A glide from F up to A without a new attack.
        note(Night.LEAD, 40, n("A-5"), 0)
        fx(Night.LEAD, 40, 0, FX_PORTAMENTO, 0x18)
        fx(Night.LEAD, 48, 0, FX_PORTAMENTO, 0x00)
        fx(Night.LEAD, 48, 1, FX_VIBRATO, 0x63)
        cut(Night.LEAD, 62)
    }.build()

    /** Root and octave in a running eighth-note pattern. */
    private fun PatternBuilder.nightBass(s: Sounds, bar: Int, c: Chord) {
        for (row in 0 until 16 step 2) note(Night.BASS, bar * 16 + row, c.at(2) + if (row % 4 == 2) 12 else 0, s.bass)
        if (bar == 0) fx(Night.BASS, 0, 0, FX_VOLUME, 0x7A)
    }

    /** Chord stabs from FM 3 on the off-beats. */
    private fun PatternBuilder.nightStacks(s: Sounds, bar: Int, c: Chord) {
        val r = bar * 16
        for (row in listOf(2, 6, 10)) {
            note(Night.STACK, r + row, c.at(4), s.stack(c))
            cut(Night.STACK, r + row + 2)
        }
        if (bar == 0 || bar == 2) fx(Night.STACK, r + 2, 0, FX_VOLUME, 0x5C)
    }

    /** SSG kick on 1 and 3 (and the "and" of 3 every other bar), noise snare on 2 and 4, hats between. */
    private fun PatternBuilder.nightDrums(s: Sounds, bar: Int) {
        val r = bar * 16
        note(Night.DRUMS, r, n("C-3"), s.ssgKick)
        note(Night.DRUMS, r + 8, n("C-3"), s.ssgKick)
        if (bar % 2 == 1) note(Night.DRUMS, r + 11, n("C-3"), s.ssgKick)
        note(Night.DRUMS, r + 4, n("G-4"), s.ssgSnare)
        note(Night.DRUMS, r + 12, n("G-4"), s.ssgSnare)
        for (row in listOf(2, 6, 10, 14)) note(Night.DRUMS, r + row, n("C-6"), s.ssgHat)
        if (bar == 0) fx(Night.DRUMS, r, 0, FX_VOLUME, 0x0F)
    }

    /** The chord from the SSG's arpeggio envelope, twice a bar. */
    private fun PatternBuilder.ssgArp(channel: Int, s: Sounds, bar: Int, c: Chord, volume: Int) {
        for (row in listOf(0, 8)) note(channel, bar * 16 + row, c.at(5), s.arp(c))
        if (bar == 0) fx(channel, 0, 0, FX_VOLUME, volume)
    }

    private fun PatternBuilder.lead(channel: Int, slot: Int, notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(channel, row, n(name), slot)
    }

    // ---- Paper Lanterns (YM2608) ------------------------------------------------------------------------------------

    private object Lantern {
        const val MELODY = 0
        const val STRINGS = 1
        const val STACK = 2
        const val BASS = 3
        const val BELL = 4
        const val PAD = 5
        const val ARP = 6
        const val SSG_BASS = 7
        const val HATS = 8
        const val ADPCM = 15

        val VERSE = listOf(chord("A-"), chord("F#", minor = true), chord("D-"), chord("E-"))
        val BRIDGE = listOf(chord("B-", minor = true), chord("D-"), chord("F#", minor = true), chord("E-"))

        /** Stereo placement of FM 1-6, set on every pattern's first row so a seek or the loop hears the same mix. */
        val PANS = listOf(MELODY to 0x11, STRINGS to 0x01, STACK to 0x11, BASS to 0x11, BELL to 0x10, PAD to 0x01)
    }

    /** "Paper Lanterns", the YM2608 demo. */
    fun opna(): Song {
        val blank = OpnaSystem.blank()
        val slots = Slots(blank.opn)
        val major = slots.add(stack("Major stack", MAJOR_STACK))
        val minor = slots.add(stack("Minor stack", MINOR_STACK))
        val voiceSample = blank.opn.samples.indexOfFirst { it.name == "Voice" }
        check(voiceSample >= 0) { "the starter kit has no Voice sample" }
        val voiceLoop = slots.add(OpnPresets.adpcm(voiceSample, "Voice loop").copy(loop = true, level = 200))
        val s = Sounds(slots, major, minor)
        val kick = slots.find(OpnPart.ADPCM, "Kick")
        val snare = slots.find(OpnPart.ADPCM, "Snare")
        val drums = AdpcmDrums(kick, snare, voiceLoop)
        return blank.copy(
            title = "Paper Lanterns",
            artist = "Thor Tracker",
            comment = "YM2608 demo: six stereo FM channels with the LFO, FM 3 special-mode chord stacks, generated ADPCM-B " +
                "drum and voice samples, SSG arpeggios, hardware-envelope bass and pad, noise hats. No rhythm-ROM drums. " +
                "Loops to order 1.",
            ticksPerRow = LANTERN_SPEED,
            loopOrder = 1,
            patterns = listOf(lanternIntro(s, drums), lanternTheme(s, drums), lanternBridge(s, drums)),
            orders = listOf(0, 1, 2, 1),
            sound = slots.sound.copy(fm3Mode = OpnFm3Mode.SPECIAL, timerB = LANTERN_TIMER_B, lfo = 0x0B),
        )
    }

    private class AdpcmDrums(val kick: Int, val snare: Int, val voice: Int)

    private fun PatternBuilder.lanternPans() {
        for ((channel, pan) in Lantern.PANS) fx(channel, 0, 3, FX_PAN, pan)
    }

    private fun lanternIntro(s: Sounds, d: AdpcmDrums) = PatternBuilder(OpnChip.YM2608, "Intro").apply {
        lanternPans()
        fx(Lantern.STRINGS, 0, 2, FX_LFO, 0x0B)
        for (bar in 0 until 4) {
            val c = Lantern.VERSE[bar]
            lanternBass(s, bar, c)
            lanternPads(s, bar, c)
            ssgArp(Lantern.ARP, s, bar, c, 0x09)
            if (bar >= 2) {
                lanternStacks(s, bar, c)
                adpcmDrums(d, bar)
                hats(s, bar)
            }
        }
        // The bell announces the tune.
        for ((row, name) in listOf(8 to "E-6", 24 to "C#6", 40 to "A-5", 56 to "B-5")) note(Lantern.BELL, row, n(name), s.bell)
        fx(Lantern.BELL, 8, 0, FX_VOLUME, 0x6C)
    }.build()

    private fun lanternTheme(s: Sounds, d: AdpcmDrums) = PatternBuilder(OpnChip.YM2608, "Theme").apply {
        lanternPans()
        fx(Lantern.STRINGS, 0, 2, FX_LFO, 0x0B)
        for (bar in 0 until 4) {
            val c = Lantern.VERSE[bar]
            lanternBass(s, bar, c)
            lanternPads(s, bar, c)
            lanternStacks(s, bar, c)
            ssgArp(Lantern.ARP, s, bar, c, 0x09)
            adpcmDrums(d, bar)
            hats(s, bar)
            // The hardware envelope's saw as a buzz bass an octave over FM 4.
            note(Lantern.SSG_BASS, bar * 16, c.at(3), s.buzz)
            cut(Lantern.SSG_BASS, bar * 16 + 6)
        }
        fx(Lantern.SSG_BASS, 0, 0, FX_VOLUME, 0x0A)
        lead(Lantern.MELODY, s.epiano, listOf(
            0 to "E-5", 4 to "C#5", 6 to "E-5", 8 to "A-5", 14 to "B-5",
            16 to "C#6", 20 to "B-5", 22 to "A-5", 24 to "F#5",
            32 to "F#5", 36 to "A-5", 38 to "F#5", 40 to "E-5", 46 to "D-5",
            48 to "E-5", 56 to "G#5", 58 to "B-5", 60 to "G#5", 62 to "E-5",
        ))
        fx(Lantern.MELODY, 0, 0, FX_VOLUME, 0x78)
        // The bell answers each phrase from the left.
        for ((row, name) in listOf(10 to "C#6", 26 to "A-5", 42 to "F#5", 58 to "B-5")) note(Lantern.BELL, row, n(name), s.bell)
        fx(Lantern.BELL, 10, 0, FX_VOLUME, 0x64)
    }.build()

    private fun lanternBridge(s: Sounds, d: AdpcmDrums) = PatternBuilder(OpnChip.YM2608, "Bridge").apply {
        lanternPans()
        // A faster LFO: the strings' vibrato quickens.
        fx(Lantern.STRINGS, 0, 2, FX_LFO, 0x0D)
        for (bar in 0 until 4) {
            val c = Lantern.BRIDGE[bar]
            lanternBass(s, bar, c)
            lanternPads(s, bar, c)
            note(Lantern.STACK, bar * 16, c.at(4), s.stack(c))
            ssgArp(Lantern.ARP, s, bar, c, 0x08)
            hats(s, bar)
            // The SSG swell pad holds the chord's fifth.
            note(Lantern.SSG_BASS, bar * 16, c.at(4) + 7, s.swell)
            // The looping "ah" sample sings the root, transposed from its C-4 recording.
            note(Lantern.ADPCM, bar * 16, c.at(4), d.voice)
        }
        fx(Lantern.STACK, 0, 0, FX_VOLUME, 0x58)
        fx(Lantern.SSG_BASS, 0, 0, FX_VOLUME, 0x0C)
        fx(Lantern.ADPCM, 0, 0, FX_VOLUME, 0xC0)
        fx(Lantern.ADPCM, 0, 1, FX_PAN, 0x11)
        cut(Lantern.ADPCM, 63)
        cut(Lantern.SSG_BASS, 63)
        lead(Lantern.MELODY, s.epiano, listOf(0 to "D-5", 8 to "F#5", 12 to "E-5", 16 to "F#5", 24 to "A-5", 32 to "C#6", 40 to "B-5", 44 to "A-5", 48 to "G#5"))
        fx(Lantern.MELODY, 0, 0, FX_VOLUME, 0x74)
        fx(Lantern.MELODY, 32, 0, FX_VIBRATO, 0x42)
        // The last phrase fades out into the theme.
        fx(Lantern.MELODY, 48, 0, FX_VOLUME_SLIDE, 0x02)
        cut(Lantern.MELODY, 63)
        fx(Lantern.MELODY, 63, 0, FX_VOLUME, 0x78)
        fx(Lantern.MELODY, 63, 1, FX_VOLUME_SLIDE, 0x00)
        fx(Lantern.MELODY, 63, 2, FX_VIBRATO, 0x00)
    }.build()

    /** Root, root, fifth, octave, root, fifth. */
    private fun PatternBuilder.lanternBass(s: Sounds, bar: Int, c: Chord) {
        for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 7, 8 to 12, 11 to 0, 14 to 7)) note(Lantern.BASS, bar * 16 + row, c.at(2) + offset, s.bass)
        if (bar == 0) fx(Lantern.BASS, 0, 0, FX_VOLUME, 0x7A)
    }

    /** Strings on the chord's third (right), the pad on its fifth (left), a whole bar each. */
    private fun PatternBuilder.lanternPads(s: Sounds, bar: Int, c: Chord) {
        note(Lantern.STRINGS, bar * 16, c.at(4) + c.third, s.strings)
        note(Lantern.PAD, bar * 16, c.at(3) + 7, s.pad)
        if (bar == 0) {
            fx(Lantern.STRINGS, 0, 0, FX_VOLUME, 0x60)
            fx(Lantern.PAD, 0, 0, FX_VOLUME, 0x58)
        }
    }

    /** FM 3 chord stacks on the beats. */
    private fun PatternBuilder.lanternStacks(s: Sounds, bar: Int, c: Chord) {
        val r = bar * 16
        for (row in listOf(0, 8)) {
            note(Lantern.STACK, r + row, c.at(4), s.stack(c))
            cut(Lantern.STACK, r + row + 5)
        }
        if (bar == 0 || bar == 2) fx(Lantern.STACK, r, 0, FX_VOLUME, 0x56)
    }

    /** The generated kick and snare samples on the ADPCM-B channel. */
    private fun PatternBuilder.adpcmDrums(d: AdpcmDrums, bar: Int) {
        val r = bar * 16
        val root = n("C-4")
        note(Lantern.ADPCM, r, root, d.kick)
        note(Lantern.ADPCM, r + 8, root, d.kick)
        if (bar % 2 == 1) note(Lantern.ADPCM, r + 10, root, d.kick)
        note(Lantern.ADPCM, r + 4, root, d.snare)
        note(Lantern.ADPCM, r + 12, root, d.snare)
        if (bar == 0 || bar == 2) fx(Lantern.ADPCM, r, 0, FX_VOLUME, 0xFF)
    }

    /** Noise hats on the off-beats. */
    private fun PatternBuilder.hats(s: Sounds, bar: Int) {
        for (row in listOf(2, 6, 10, 14)) note(Lantern.HATS, bar * 16 + row, n("C-6"), s.ssgHat)
        if (bar == 0) fx(Lantern.HATS, 2, 0, FX_VOLUME, 0x0A)
    }

    // ---- the shortest song --------------------------------------------------------------------------------------------

    /**
     * The shortest valid song of [chip]: one order of one row at speed 1 holding an A-4 on FM 1 (the first FM voice),
     * looping to order 0. The native WAV export test renders its image through many loops.
     */
    fun shortest(chip: OpnChip): Song {
        val blank = chip.system().blank()
        val fm = blank.opn.instruments.indexOfFirst { it is OpnFmSound } + 1
        val tracks = List(chip.channelCount) { ch -> List(Songs.ROWS) { row -> if (ch == 0 && row == 0) Cell(note = n("A-4"), instrument = fm) else Cell.BLANK } }
        return blank.copy(
            title = "Shortest ${chip.label}",
            ticksPerRow = 1,
            loopOrder = 0,
            patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** A song's sound slots: presets found by part and name, extra sounds added in the first empty slots. */
    private class Slots(var sound: OpnSound) {
        fun find(part: OpnPart, name: String): Int {
            val i = sound.instruments.indexOfFirst { it != null && it.part == part && it.name == name }
            check(i >= 0) { "the demo needs the ${part.name} preset \"$name\"" }
            return i + 1
        }

        fun add(ins: OpnInstrument): Int {
            val slot = sound.instruments.indexOfFirst { it == null } + 1
            check(slot > 0) { "no free slot for ${ins.name}" }
            sound = sound.withInstrument(slot, ins)
            return slot
        }
    }

    /** Absolute note of "C-4", "C#4", ... (0 = C-0). */
    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(chip: OpnChip, val title: String) {
        private val tracks = List(chip.channelCount) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun cut(channel: Int, row: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = Cell.CUT, instrument = 0)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build() = Pattern(title, tracks.map { it.toList() })
    }
}
