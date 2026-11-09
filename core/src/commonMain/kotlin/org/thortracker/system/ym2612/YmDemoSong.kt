package org.thortracker.system.ym2612

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ym2612.YmRules.FX_BRIGHTNESS
import org.thortracker.system.ym2612.YmRules.FX_CUT
import org.thortracker.system.ym2612.YmRules.FX_DELAY
import org.thortracker.system.ym2612.YmRules.FX_PAN
import org.thortracker.system.ym2612.YmRules.FX_PORTAMENTO
import org.thortracker.system.ym2612.YmRules.FX_SLIDE_DOWN
import org.thortracker.system.ym2612.YmRules.FX_SPEED
import org.thortracker.system.ym2612.YmRules.FX_VIBRATO
import org.thortracker.system.ym2612.YmRules.FX_VOLUME
import org.thortracker.system.ym2612.YmRules.FX_VOLUME_SLIDE

/**
 * The bundled Mega Drive demo: "Neon Causeway", A minor with a major dominant (Am-F-G-E; the bridge Dm-G-C-E), 6 ticks
 * per row at 60 Hz (150 BPM in sixteenths). Intro, theme A, bridge, theme A again and a fill, looping to theme A.
 * FM 1 is the square lead (vibrato, a portamento glide, a slide and fade in the fill), FM 2 the slap bass with
 * brightness-locked octave pops, FM 3 and FM 4 brass stabs panned left and right (strings in the bridge), FM 5 the
 * swept FM kick, FM 6 the FM snare, toms and crash; PSG 1 plays chord arpeggios from its arpeggio envelope, PSG 2
 * echoes the lead three rows late, PSG 3 doubles the arpeggio an octave up in the bridge, and the Noise channel plays
 * hats, then pitched-noise toms in the fill. The chip's LFO runs at rate 2 for the lead's and strings' vibrato. PCM 1-3
 * stay empty (FM 6 needs the DAC), so the music is what it was before the XGM2 driver.
 *
 * Beside it: "Night Ferry" ([xgm2Demo]), the XGM2 fixture with PCM kits and FM 3 special mode, and the shortest songs
 * the native WAV test renders ([shortest], [pcmShortest], [fm3Shortest]).
 */
object YmDemoSong {
    private const val SPEED = 6

    private val PRESET_SLOTS = YmPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Lead")
    private val BASS = slot("Slap bass")
    private val BRASS = slot("Brass")
    private val STRINGS = slot("Strings")
    private val KICK = slot("Kick")
    private val SNARE = slot("Snare")
    private val TOM = slot("Tom")
    private val CRASH = slot("Crash")
    private val PSG_LEAD = slot("PSG lead")
    private val ARP_MAJOR = slot("PSG arp major")
    private val ARP_MINOR = slot("PSG arp minor")
    private val HAT = slot("Noise hat")
    private val OPEN_HAT = slot("Noise open hat")
    private val NOISE_TOM = slot("Noise tom")

    private const val LEAD_CH = 0
    private const val BASS_CH = 1
    private const val BRASS_L = 2
    private const val BRASS_R = 3
    private const val KICK_CH = 4
    private const val SNARE_CH = 5
    private const val ARP_CH = 6
    private const val ECHO_CH = 7
    private const val ARP2_CH = 8
    private const val NOISE_CH = 9

    /** A chord of a bar: bass root, the stab pair (third and fifth), the arpeggio root and whether it is minor. */
    private class Chord(val bass: String, val third: String, val fifth: String, val arp: String, val minor: Boolean)

    private val VERSE = listOf(
        Chord("A-2", "C-5", "E-5", "A-4", true), Chord("F-2", "A-4", "C-5", "F-4", false),
        Chord("G-2", "B-4", "D-5", "G-4", false), Chord("E-2", "G#4", "B-4", "E-4", false),
    )
    private val BRIDGE = listOf(
        Chord("D-2", "F-4", "A-4", "D-5", true), Chord("G-2", "B-4", "D-5", "G-4", false),
        Chord("C-3", "E-4", "G-4", "C-5", false), Chord("E-2", "G#4", "B-4", "E-4", false),
    )

    fun build(): Song {
        val blank = YmSystem.blank()
        return blank.copy(
            title = "Neon Causeway",
            artist = "Thor Tracker",
            comment = "Mega Drive demo: slap bass, FM brass and lead, PSG arpeggio envelopes, FM drums, pitched noise toms. " +
                "Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(intro(), themeA(), bridge(), fill()),
            orders = listOf(0, 1, 2, 1, 3),
            sound = blank.ym.copy(lfo = 0x0A),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            bass(bar, VERSE[bar])
            arpeggio(ARP_CH, bar, VERSE[bar])
            hats(bar)
            val r = bar * 16
            note(KICK_CH, r, KICK_NOTE, KICK)
            if (bar >= 2) {
                note(KICK_CH, r + 8, KICK_NOTE, KICK)
                note(SNARE_CH, r + 4, SNARE_NOTE, SNARE)
                note(SNARE_CH, r + 12, SNARE_NOTE, SNARE)
            }
        }
        // A snare roll into theme A, getting louder.
        for ((row, volume) in listOf(56 to 0x58, 58 to 0x60, 60 to 0x68, 61 to 0x70, 62 to 0x78, 63 to 0x7F)) {
            note(SNARE_CH, row, SNARE_NOTE, SNARE)
            fx(SNARE_CH, row, 0, FX_VOLUME, volume)
        }
    }.build()

    private fun themeA() = PatternBuilder("Theme A").apply {
        for (bar in 0 until 4) {
            bass(bar, VERSE[bar])
            stabs(bar, VERSE[bar])
            arpeggio(ARP_CH, bar, VERSE[bar])
            drums(bar)
        }
        // The crash lands on the downbeat; the snare comes back on beat 2.
        note(SNARE_CH, 0, CRASH_NOTE, CRASH)
        val melody = listOf(
            0 to "A-4", 4 to "C-5", 6 to "E-5", 8 to "A-5", 14 to "G-5",
            16 to "F-5", 22 to "E-5", 24 to "C-5", 30 to "A-4",
            32 to "B-4", 36 to "D-5", 38 to "G-5", 44 to "A-5", 46 to "B-5",
            48 to "G#5", 56 to "B-5", 58 to "A-5", 60 to "G#5", 62 to "E-5",
        )
        lead(melody)
        echo(melody)
        fx(LEAD_CH, 0, 0, FX_VOLUME, 0x78)
        // Vibrato on the held notes; the next note restarts its phase.
        for (row in listOf(8, 16, 24, 48)) fx(LEAD_CH, row, 1, FX_VIBRATO, 0x52)
        fx(LEAD_CH, 32, 1, FX_VIBRATO, 0x50)
    }.build()

    private fun bridge() = PatternBuilder("Bridge").apply {
        for (bar in 0 until 4) {
            val chord = BRIDGE[bar]
            bass(bar, chord)
            arpeggio(ARP_CH, bar, chord)
            arpeggio(ARP2_CH, bar, chord, octave = 12)
            drums(bar)
            // Strings hold the chord's third and fifth, left and right.
            val r = bar * 16
            note(BRASS_L, r, n(chord.third) - 12, STRINGS)
            note(BRASS_R, r, n(chord.fifth) - 12, STRINGS)
            cut(BRASS_L, r + 15)
            cut(BRASS_R, r + 15)
        }
        fx(BRASS_L, 0, 0, FX_PAN, 0x10)
        fx(BRASS_R, 0, 0, FX_PAN, 0x01)
        fx(BRASS_L, 0, 1, FX_VOLUME, 0x68)
        fx(BRASS_R, 0, 1, FX_VOLUME, 0x68)
        // A soaring line: long notes with vibrato, two glides without new attacks.
        lead(listOf(0 to "F-5", 8 to "E-5", 12 to "D-5", 16 to "D-5", 24 to "B-4", 32 to "C-5", 40 to "E-5", 48 to "G#5"))
        fx(LEAD_CH, 0, 1, FX_VIBRATO, 0x63)
        note(LEAD_CH, 20, n("G-5"), 0)
        fx(LEAD_CH, 20, 0, FX_PORTAMENTO, 0x20)
        fx(LEAD_CH, 24, 0, FX_PORTAMENTO, 0x00)
        note(LEAD_CH, 44, n("G-5"), 0)
        fx(LEAD_CH, 44, 0, FX_PORTAMENTO, 0x18)
        fx(LEAD_CH, 48, 0, FX_PORTAMENTO, 0x00)
        note(LEAD_CH, 56, n("B-5"), LEAD)
        cut(LEAD_CH, 62)
    }.build()

    private fun fill() = PatternBuilder("Fill").apply {
        for (bar in 0 until 2) {
            bass(bar, VERSE[bar])
            stabs(bar, VERSE[bar])
            arpeggio(ARP_CH, bar, VERSE[bar])
            drums(bar)
        }
        for (bar in 2 until 4) bass(bar, VERSE[bar])
        // FM toms fall across the stereo field while pitched-noise toms answer (PSG 3 is silent here).
        val toms = listOf(32 to "D-3", 36 to "C-3", 40 to "A-2", 44 to "G-2", 48 to "E-3", 50 to "D-3", 52 to "C-3", 54 to "A-2")
        for ((row, name) in toms) note(SNARE_CH, row, n(name), TOM)
        fx(SNARE_CH, 32, 0, FX_PAN, 0x10)
        fx(SNARE_CH, 40, 0, FX_PAN, 0x01)
        fx(SNARE_CH, 48, 0, FX_PAN, 0x11)
        for (row in listOf(34, 38, 42, 46)) note(NOISE_CH, row, n("A-3") - (row - 34) / 2, NOISE_TOM)
        for (row in 56 until 64 step 2) note(SNARE_CH, row, SNARE_NOTE, SNARE)
        for (row in listOf(32, 40, 48, 56)) note(KICK_CH, row, KICK_NOTE, KICK)
        note(NOISE_CH, 56, HAT_NOTE, OPEN_HAT)
        // The lead holds G#5, then dives and fades into the loop.
        note(LEAD_CH, 32, n("G#5"), LEAD)
        fx(LEAD_CH, 32, 1, FX_VIBRATO, 0x63)
        fx(LEAD_CH, 48, 0, FX_SLIDE_DOWN, 0x06)
        fx(LEAD_CH, 48, 1, FX_VOLUME_SLIDE, 0x03)
        cut(LEAD_CH, 63)
        fx(LEAD_CH, 63, 0, FX_SLIDE_DOWN, 0x00)
        fx(LEAD_CH, 63, 1, FX_VOLUME, 0x7F)
    }.build()

    // ---- "Night Ferry": PCM 1-3 and FM 3 special mode ---------------------------------------------------------------

    private const val CH3_MINOR = 40
    private const val CH3_MAJOR = 41
    private const val KIT = 48
    private const val SOFT_KIT = 49
    private const val BASS_KIT = 50

    private const val FM3_CH = 2
    private const val STAB_CH = 3
    private const val PCM_DRUMS = YmRules.PCM_FIRST
    private const val PCM_HATS = YmRules.PCM_FIRST + 1
    private const val PCM_BASS = YmRules.PCM_FIRST + 2

    /**
     * An additive FM 3 voice whose four operators sound a chord in special mode: root, [third] semitones, the fifth and
     * the octave (that one 3/64 sharp for a slow beat). Normal mode would play four unison carriers.
     */
    private fun chordVoice(name: String, third: Int) = YmFmInstrument(
        name = name, algorithm = 7, feedback = 0,
        operators = List(4) { YmOperator(mul = 1, tl = 30, ar = 28, d1r = 5, d2r = 1, sl = 3, rr = 6) },
        ch3 = listOf(0, third * 64, 7 * 64, 12 * 64 + 3),
    )

    /** A chord of a bar of Night Ferry: its root for the bass and FM 3, and whether the triad is minor. */
    private class Ferry(val root: String, val minor: Boolean)

    private val FERRY = listOf(Ferry("A-2", true), Ferry("F-2", false), Ferry("C-3", false), Ferry("G-2", false))

    /**
     * "Night Ferry", the XGM2 demo (not the bundled demo, so Neon Causeway's music is untouched): A minor, Am-F-C-G, speed
     * 6, two patterns arranged 0-1-0-1 looping to order 0. FM 3 in special mode plays each chord from one channel (the
     * voices' operator offsets: root, third, fifth, octave); FM 1 the lead, FM 2 the slap bass in "Deck", FM 4 brass
     * stabs, PSG 1 the arpeggio envelopes. PCM 1 plays the synthesized kick and snare ([YmPcmKit]) with a quieter kit for
     * ghost notes, PCM 2 the hats (one delayed with G03, one stopped by S02), PCM 3 in "Hold" the one-shot PCM bass
     * chromatically (pitch = key − A-2) with OFF and its own F04/F06. FM 6 stays silent: the DAC replaces it.
     */
    fun xgm2Demo(): Song {
        val base = YmSystem.blank()
        val bank = base.ym.instruments.toMutableList()
        bank[CH3_MINOR - 1] = chordVoice("Ch3 minor", 3)
        bank[CH3_MAJOR - 1] = chordVoice("Ch3 major", 4)
        bank[KIT - 1] = YmPcmKit.drums(0)
        bank[SOFT_KIT - 1] = YmPcmKit.drums(0, level = 9, name = "PCM ghost drums")
        bank[BASS_KIT - 1] = YmPcmKit.bassKit(0)
        return base.copy(
            title = "Night Ferry",
            artist = "Thor Tracker",
            comment = "Mega Drive XGM2 demo: FM 3 special-mode chords, synthesized PCM drums and a pitched PCM bass on PCM 1-3 " +
                "(FM 6 is silent: the DAC replaces it). Loops to order 0.",
            ticksPerRow = SPEED,
            loopOrder = 0,
            patterns = listOf(deck(), hold()),
            orders = listOf(0, 1, 0, 1),
            sound = base.ym.copy(instruments = bank, fm3Mode = YmFm3Mode.SPECIAL, pcmSamples = YmPcmKit.samples(), lfo = 0x0A),
        )
    }

    /** What both Night Ferry patterns share: FM 3's chords, PCM drums and hats, the brass stabs and the PSG arpeggio. */
    private fun PatternBuilder.ferryBed() {
        for (bar in 0 until 4) {
            val r = bar * 16
            val chord = FERRY[bar]
            note(FM3_CH, r, n(chord.root) + 12, if (chord.minor) CH3_MINOR else CH3_MAJOR)
            for (beat in listOf(0, 8)) note(PCM_DRUMS, r + beat, YmPcmKit.KICK_KEY, KIT)
            for (beat in listOf(4, 12)) note(PCM_DRUMS, r + beat, YmPcmKit.SNARE_KEY, KIT)
            note(PCM_DRUMS, r + 14, YmPcmKit.SNARE_KEY, SOFT_KIT) // a ghost note from the quieter kit
            for (off in listOf(2, 6, 10, 14)) note(PCM_HATS, r + off, YmPcmKit.HAT_KEY, KIT)
            for (row in listOf(2, 10)) {
                note(STAB_CH, r + row, n(chord.root) + 24 + (if (chord.minor) 3 else 4), BRASS)
                cut(STAB_CH, r + row + 2)
            }
            for (row in listOf(0, 8)) note(ARP_CH, r + row, n(chord.root) + 24, if (chord.minor) ARP_MINOR else ARP_MAJOR)
        }
        fx(FM3_CH, 0, 0, FX_VOLUME, 0x70)
        fx(STAB_CH, 2, 0, FX_PAN, 0x10)
        fx(STAB_CH, 2, 1, FX_VOLUME, 0x68)
        fx(ARP_CH, 0, 0, FX_VOLUME, 0x0A)
        fx(PCM_HATS, 30, 0, FX_DELAY, 0x03) // this hat lands three ticks late
        fx(PCM_HATS, 62, 0, FX_CUT, 0x02) // and this one is stopped after two ticks
    }

    /** "Deck": the lead over the FM slap bass. */
    private fun deck() = PatternBuilder("Deck").apply {
        ferryBed()
        for (bar in 0 until 4) {
            val r = bar * 16
            val root = n(FERRY[bar].root)
            for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 0, 14 to 12)) note(BASS_CH, r + row, root + offset, BASS)
        }
        fx(BASS_CH, 0, 0, FX_VOLUME, 0x78)
        val melody = listOf(
            0 to "E-5", 4 to "C-5", 6 to "D-5", 8 to "E-5", 12 to "A-4",
            16 to "F-5", 20 to "E-5", 22 to "D-5", 24 to "C-5",
            32 to "E-5", 36 to "G-5", 40 to "E-5", 44 to "D-5",
            48 to "D-5", 52 to "B-4", 56 to "G-4", 60 to "B-4",
        )
        lead(melody)
        fx(LEAD_CH, 0, 0, FX_VOLUME, 0x70)
        for (row in listOf(8, 24, 40)) fx(LEAD_CH, row, 1, FX_VIBRATO, 0x52)
    }.build()

    /** "Hold": the FM bass rests while PCM 3 plays the bass kit; the song speeds up for a bar. */
    private fun hold() = PatternBuilder("Hold").apply {
        ferryBed()
        cut(BASS_CH, 0)
        cut(LEAD_CH, 0)
        val line = listOf(0 to "A-2", 6 to "A-2", 8 to "E-2", 16 to "F-2", 22 to "F-2", 24 to "C-3", 32 to "C-3", 38 to "G-2",
            40 to "E-2", 48 to "G-2", 52 to "A-2", 56 to "B-2")
        for ((row, name) in line) note(PCM_BASS, row, n(name), BASS_KIT)
        cut(PCM_BASS, 62) // OFF stops the one-shot
        fx(PCM_BASS, 32, 0, FX_SPEED, 0x04) // the PCM lane's own Fxx: the song speeds up ...
        fx(PCM_BASS, 48, 0, FX_SPEED, 0x06) // ... and back
    }.build()

    // ---- the shortest songs (the native WAV export test's one-row, one-tick edge cases) ------------------------------

    /** One order of a one-row pattern at speed 1, looping to order 0, holding [note] on [channel] with [instrument]. */
    private fun oneRow(title: String, channel: Int, note: Int, instrument: Int, sound: YmSound): Song {
        val base = YmSystem.blank()
        val held = Cell(note = note, instrument = instrument)
        val tracks = List(YmRules.CHANNELS) { ch -> List(Songs.ROWS) { row -> if (ch == channel && row == 0) held else Cell.BLANK } }
        return base.copy(title = title, ticksPerRow = 1, loopOrder = 0, patterns = listOf(Pattern("Pattern 00", tracks, length = 1)),
            orders = listOf(0), sound = sound)
    }

    /** `ym-shortest.ymimg`: A-4 held on FM 1 with the Lead preset. */
    fun shortest(): Song = oneRow("Mega Drive shortest", LEAD_CH, n("A-4"), LEAD, YmSystem.blank().ym)

    /** `ym-pcm-shortest.ymimg`: the PCM bass kit's A-2 on PCM 1, restarted every tick (one tick per pass). */
    fun pcmShortest(): Song {
        val sound = YmSystem.blank().ym.let { it.copy(instruments = it.instruments.toMutableList().also { b -> b[0] = YmPcmKit.bassKit(0) }, pcmSamples = YmPcmKit.samples()) }
        return oneRow("Mega Drive PCM shortest", YmRules.PCM_FIRST, YmPcmKit.BASS_ROOT, 1, sound)
    }

    /** `ym-fm3-shortest.ymimg`: an A minor chord from FM 3 in special mode, one voice, held. */
    fun fm3Shortest(): Song {
        val sound = YmSystem.blank().ym.let {
            it.copy(instruments = it.instruments.toMutableList().also { b -> b[0] = chordVoice("Ch3 minor", 3) }, fm3Mode = YmFm3Mode.SPECIAL)
        }
        return oneRow("Mega Drive FM 3 shortest", FM3_CH, n("A-3"), 1, sound)
    }

    private val KICK_NOTE = n("C-2")
    private val SNARE_NOTE = n("D-4")
    private val CRASH_NOTE = n("C-6")
    private val HAT_NOTE = n("C-4")

    /** Slap bass: root, root, octave pop, root, root, octave pop, root, fifth, octave; pops brightness-locked. */
    private fun PatternBuilder.bass(bar: Int, chord: Chord) {
        val r = bar * 16
        val root = n(chord.bass)
        for ((row, offset) in listOf(0 to 0, 3 to 0, 4 to 12, 6 to 0, 8 to 0, 10 to 12, 11 to 0, 12 to 7, 14 to 12)) {
            note(BASS_CH, r + row, root + offset, BASS)
            if (offset == 12) fx(BASS_CH, r + row, 0, FX_BRIGHTNESS, 0x68)
        }
        // The bass sits under the lead; every pattern sets it, so a seek or the loop hears the same mix.
        if (bar == 0) fx(BASS_CH, 0, 0, FX_VOLUME, 0x7A)
    }

    /** Brass stabs on the off-beats: the chord's third on the left, its fifth on the right. */
    private fun PatternBuilder.stabs(bar: Int, chord: Chord) {
        val r = bar * 16
        for (row in listOf(2, 7, 10)) {
            note(BRASS_L, r + row, n(chord.third), BRASS)
            note(BRASS_R, r + row, n(chord.fifth), BRASS)
            cut(BRASS_L, r + row + 2)
            cut(BRASS_R, r + row + 2)
        }
        if (bar == 0) {
            fx(BRASS_L, r + 2, 0, FX_PAN, 0x10)
            fx(BRASS_R, r + 2, 0, FX_PAN, 0x01)
            fx(BRASS_L, r + 2, 1, FX_VOLUME, 0x7A)
            fx(BRASS_R, r + 2, 1, FX_VOLUME, 0x7A)
        }
    }

    /** The chord from the arpeggio envelope, twice a bar. */
    private fun PatternBuilder.arpeggio(channel: Int, bar: Int, chord: Chord, octave: Int = 0) {
        val r = bar * 16
        for (row in listOf(0, 8)) note(channel, r + row, n(chord.arp) + octave, if (chord.minor) ARP_MINOR else ARP_MAJOR)
        // The PSG is about 10 dB under the FM at full volume: the arpeggios play at the top, the octave double a little lower.
        if (bar == 0) fx(channel, 0, 0, FX_VOLUME, if (octave == 0) 0x0F else 0x0C)
    }

    private fun PatternBuilder.hats(bar: Int) {
        val r = bar * 16
        for (row in 0 until 16 step 2) {
            note(NOISE_CH, r + row, HAT_NOTE, if (row == 14) OPEN_HAT else HAT)
            if (row != 14) fx(NOISE_CH, r + row, 0, FX_VOLUME, if (row % 4 == 0) 0x0F else 0x0B)
        }
    }

    private fun PatternBuilder.drums(bar: Int) {
        val r = bar * 16
        note(KICK_CH, r, KICK_NOTE, KICK)
        note(KICK_CH, r + 8, KICK_NOTE, KICK)
        if (bar % 2 == 1) note(KICK_CH, r + 11, KICK_NOTE, KICK)
        note(SNARE_CH, r + 4, SNARE_NOTE, SNARE)
        note(SNARE_CH, r + 12, SNARE_NOTE, SNARE)
        hats(bar)
    }

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD_CH, row, n(name), LEAD)
    }

    /** The lead's notes three rows later on PSG 2, quieter. */
    private fun PatternBuilder.echo(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) if (row + 3 < Songs.ROWS) note(ECHO_CH, row + 3, n(name), PSG_LEAD)
        fx(ECHO_CH, 3, 0, FX_VOLUME, 0x0B)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(YmRules.CHANNELS) { Array(Songs.ROWS) { Cell.BLANK } }

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
