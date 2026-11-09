package org.thortracker.system.ay

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ay.AyRules.FX_ENV_AUTO
import org.thortracker.system.ay.AyRules.FX_ENV_SHAPE
import org.thortracker.system.ay.AyRules.FX_PORTAMENTO
import org.thortracker.system.ay.AyRules.FX_SLIDE_DOWN
import org.thortracker.system.ay.AyRules.FX_VIBRATO
import org.thortracker.system.ay.AyRules.FX_VOLUME
import org.thortracker.system.ay.AyRules.FX_VOLUME_SLIDE

/**
 * The bundled AY demo, one composition for all four computers: "Copper Lane", D minor (Dm-B♭-C-A; the bridge
 * Gm-C-F-A), 150 BPM in sixteenths on every variant (5 ticks per row at the 50 Hz ZX Spectrum 128, Atari ST and
 * Amstrad CPC, 6 at the MSX's 60 Hz). Intro, theme, bridge, theme again and a fill, looping to the theme.
 *
 * Channel B plays the buzzer bass (the saw envelope itself at the note's pitch, octave pops with `J01`, a saw-up `E0C`
 * turnaround) and so owns the chip's one envelope; in the bridge B switches to a plucked tone bass and hands the
 * envelope to Channel A's synced buzzer lead, whose glides (`3xx`) bend the envelope with the tone — the shared envelope
 * audible on purpose, handed over cleanly (the old owner's note ends on the row the new one starts). Channel A
 * otherwise plays the square lead (vibrato, a fade and a dive in the fill); Channel C plays the arpeggio-envelope chords
 * with the drums cutting in between them (kick, snare, hats: all noise on one channel, so the noise generator never
 * changes hands), toms and a noise-sweep riser into the loop.
 */
internal object AyDemoSong {
    private val PRESET_SLOTS = AyPresets.ALL.mapIndexed { i, p -> p.name to i + 1 }.toMap()
    private fun slot(name: String) = PRESET_SLOTS[name] ?: throw IllegalStateException("Demo needs preset $name")

    private val LEAD = slot("Square lead")
    private val SYNC = slot("Sync buzzer")
    private val BUZZER = slot("Buzzer bass")
    private val PLUCK = slot("Pluck")
    private val ARP_MAJOR = slot("Arp major")
    private val ARP_MINOR = slot("Arp minor")
    private val KICK = slot("Kick")
    private val SNARE = slot("Snare")
    private val TOM = slot("Tom")
    private val HAT = slot("Closed hat")
    private val OPEN_HAT = slot("Open hat")
    private val RISER = slot("Noise sweep")

    private const val LEAD_CH = 0
    private const val BASS_CH = 1
    private const val CHORD_CH = 2

    /** A bar's chord: the bass root, the arpeggio root and whether it is minor. */
    private class Chord(val bass: String, val arp: String, val minor: Boolean)

    private val VERSE = listOf(Chord("D-2", "D-4", true), Chord("A#1", "A#3", false), Chord("C-2", "C-4", false), Chord("A-1", "A-3", false))
    private val BRIDGE = listOf(Chord("G-1", "G-3", true), Chord("C-2", "C-4", false), Chord("F-1", "F-3", false), Chord("A-1", "A-3", false))

    /** Ticks per row giving 150 BPM in sixteenths (10 rows per second) at [variant]'s frame rate. */
    fun speed(variant: AyVariant): Int = kotlin.math.round(variant.tickHz / 10.0).toInt()

    fun build(variant: AyVariant): Song {
        val blank = variant.system.blank()
        return blank.copy(
            title = "Copper Lane",
            artist = "Thor Tracker",
            comment = "${variant.label} demo: buzzer bass handing the shared envelope to a synced lead in the bridge, arpeggio-envelope " +
                "chords with noise drums cutting in, square lead with vibrato, glides and a riser. Loops to order 1.",
            ticksPerRow = speed(variant),
            loopOrder = 1,
            patterns = listOf(intro(), theme(), bridge(), fill()),
            orders = listOf(0, 1, 2, 1, 3),
        )
    }

    private fun intro() = PatternBuilder("Intro").apply {
        for (bar in 0 until 4) {
            buzzerBass(bar, VERSE[bar])
            val r = bar * 16
            if (bar < 2) {
                // Chords alone, then the drums cut in.
                for (row in listOf(0, 4, 8, 12)) stab(r + row, VERSE[bar])
            } else drums(bar, VERSE[bar])
        }
        // The lead's pickup into the theme: a held A with vibrato, then a run up.
        note(LEAD_CH, 48, n("A-4"), LEAD)
        fx(LEAD_CH, 48, 0, FX_VOLUME, 0x0C)
        fx(LEAD_CH, 48, 1, FX_VIBRATO, 0x42)
        note(LEAD_CH, 60, n("C#5"), LEAD)
        note(LEAD_CH, 62, n("E-5"), LEAD)
        fx(CHORD_CH, 0, 0, FX_VOLUME, 0x0D)
    }.build()

    private fun theme() = PatternBuilder("Theme").apply {
        for (bar in 0 until 4) {
            buzzerBass(bar, VERSE[bar])
            drums(bar, VERSE[bar])
        }
        // The turnaround: the last bar's bass buzzes on a rising saw.
        fx(BASS_CH, 56, 1, FX_ENV_SHAPE, 0x0C)
        fx(BASS_CH, 60, 1, FX_ENV_SHAPE, 0x0C)
        lead(listOf(
            0 to "F-5", 4 to "E-5", 6 to "D-5", 8 to "A-5", 14 to "G-5",
            16 to "F-5", 20 to "D-5", 22 to "F-5", 24 to "A#5", 30 to "A-5",
            32 to "G-5", 36 to "E-5", 38 to "C-5", 40 to "G-5", 44 to "E-5", 46 to "G-5",
            48 to "E-5", 52 to "C#5", 54 to "E-5", 56 to "A-5", 60 to "G-5", 62 to "E-5",
        ))
        fx(LEAD_CH, 0, 0, FX_VOLUME, 0x0D)
        // Vibrato on the held notes; each note restarts its phase.
        for (row in listOf(8, 24, 40, 56)) fx(LEAD_CH, row, 1, FX_VIBRATO, 0x52)
        // A glide from G down to E without a new attack.
        fx(LEAD_CH, 44, 0, FX_PORTAMENTO, 0x30)
        note(LEAD_CH, 44, n("E-5"), 0)
        fx(LEAD_CH, 46, 0, FX_PORTAMENTO, 0x00)
        fx(CHORD_CH, 0, 1, FX_VOLUME, 0x0D)
    }.build()

    private fun bridge() = PatternBuilder("Bridge").apply {
        for (bar in 0 until 4) {
            val chord = BRIDGE[bar]
            val r = bar * 16
            // The plucked tone bass: no envelope, so the lead may own it.
            for ((row, offset) in listOf(0 to 0, 3 to 0, 6 to 12, 8 to 0, 11 to 0, 14 to 7)) note(BASS_CH, r + row, n(chord.bass) + 12 + offset, PLUCK)
            drums(bar, chord)
        }
        fx(BASS_CH, 0, 0, FX_VOLUME, 0x0E)
        // The synced buzzer lead, two octaves of growl under each note, gliding between some of them.
        for ((row, name) in listOf(0 to "D-4", 8 to "F-4", 12 to "E-4", 16 to "E-4", 24 to "G-4", 32 to "A-4", 40 to "C-5", 44 to "A-4", 48 to "C#5")) {
            note(LEAD_CH, row, n(name), SYNC)
        }
        fx(LEAD_CH, 0, 0, FX_VOLUME, 0x0F)
        note(LEAD_CH, 20, n("C-4"), 0)
        fx(LEAD_CH, 20, 0, FX_PORTAMENTO, 0x20)
        fx(LEAD_CH, 24, 0, FX_PORTAMENTO, 0x00)
        note(LEAD_CH, 56, n("E-4"), 0)
        fx(LEAD_CH, 56, 0, FX_PORTAMENTO, 0x18)
        fx(LEAD_CH, 32, 1, FX_VIBRATO, 0x31)
        // The lead lets go of the envelope before the theme's buzzer bass takes it back.
        cut(LEAD_CH, 62)
        fx(LEAD_CH, 62, 0, FX_PORTAMENTO, 0x00)
        fx(CHORD_CH, 0, 1, FX_VOLUME, 0x0C)
    }.build()

    private fun fill() = PatternBuilder("Fill").apply {
        for (bar in 0 until 4) buzzerBass(bar, VERSE[bar])
        for (bar in 0 until 2) drums(bar, VERSE[bar])
        // Toms falling across bar 3, then the noise riser into the loop.
        for ((row, name) in listOf(32 to "D-4", 36 to "C-4", 40 to "A-3", 44 to "G-3", 46 to "F-3")) note(CHORD_CH, row, n(name), TOM)
        note(CHORD_CH, 48, n("C-4"), RISER)
        fx(CHORD_CH, 48, 0, FX_VOLUME, 0x0F)
        cut(CHORD_CH, 63)
        // The lead holds D, then dives and fades into the loop.
        note(LEAD_CH, 0, n("D-5"), LEAD)
        fx(LEAD_CH, 0, 0, FX_VOLUME, 0x0D)
        fx(LEAD_CH, 0, 1, FX_VIBRATO, 0x52)
        note(LEAD_CH, 16, n("F-5"), LEAD)
        note(LEAD_CH, 24, n("E-5"), LEAD)
        note(LEAD_CH, 32, n("A-5"), LEAD)
        fx(LEAD_CH, 32, 1, FX_VIBRATO, 0x63)
        fx(LEAD_CH, 48, 0, FX_SLIDE_DOWN, 0x08)
        fx(LEAD_CH, 48, 1, FX_VOLUME_SLIDE, 0x02)
        cut(LEAD_CH, 63)
        fx(LEAD_CH, 63, 0, FX_SLIDE_DOWN, 0x00)
        fx(LEAD_CH, 63, 1, FX_VOLUME_SLIDE, 0x00)
        fx(CHORD_CH, 0, 1, FX_VOLUME, 0x0D)
    }.build()

    /** The buzzer bass: root, root, an octave pop (`J01`), root, root, fifth, root, a pop; volume set at the bar's start. */
    private fun PatternBuilder.buzzerBass(bar: Int, chord: Chord) {
        val r = bar * 16
        val root = n(chord.bass)
        for ((row, offset) in listOf(0 to 0, 3 to 0, 8 to 0, 10 to 7, 12 to 0)) note(BASS_CH, r + row, root + offset, BUZZER)
        for (row in listOf(6, 14)) {
            note(BASS_CH, r + row, root, BUZZER)
            fx(BASS_CH, r + row, 0, FX_ENV_AUTO, 0x01)
        }
    }

    /** One chord stab from the arpeggio envelope (it plays until the next note on the channel). */
    private fun PatternBuilder.stab(row: Int, chord: Chord) = note(CHORD_CH, row, n(chord.arp), if (chord.minor) ARP_MINOR else ARP_MAJOR)

    /** Kick and snare on the beats, the chord between them, hats on the off-beat sixteenths; an open hat ends odd bars. */
    private fun PatternBuilder.drums(bar: Int, chord: Chord) {
        val r = bar * 16
        note(CHORD_CH, r, KICK_NOTE, KICK)
        stab(r + 2, chord)
        note(CHORD_CH, r + 4, SNARE_NOTE, SNARE)
        stab(r + 6, chord)
        note(CHORD_CH, r + 8, KICK_NOTE, KICK)
        stab(r + 10, chord)
        note(CHORD_CH, r + 12, SNARE_NOTE, SNARE)
        if (bar % 2 == 1) note(CHORD_CH, r + 14, HAT_NOTE, OPEN_HAT)
        else {
            note(CHORD_CH, r + 14, HAT_NOTE, HAT)
            note(CHORD_CH, r + 15, HAT_NOTE, HAT)
        }
    }

    private val KICK_NOTE = n("C-2")
    private val SNARE_NOTE = n("D-4")
    private val HAT_NOTE = n("C-5")

    private fun PatternBuilder.lead(notes: List<Pair<Int, String>>) {
        for ((row, name) in notes) note(LEAD_CH, row, n(name), LEAD)
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }

    private class PatternBuilder(val title: String) {
        private val tracks = List(AyRules.CHANNELS) { Array(Songs.ROWS) { Cell.BLANK } }

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
