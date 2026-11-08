package org.thortracker.system.ym2612

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ym2612.YmRules.FX_BRIGHTNESS
import org.thortracker.system.ym2612.YmRules.FX_PAN
import org.thortracker.system.ym2612.YmRules.FX_PORTAMENTO
import org.thortracker.system.ym2612.YmRules.FX_SLIDE_DOWN
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
 * hats, then pitched-noise toms in the fill. The chip's LFO runs at rate 2 for the lead's and strings' vibrato.
 */
internal object YmDemoSong {
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
