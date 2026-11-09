package org.thortracker.system.sms

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.sms.SmsRules.FX_CUT
import org.thortracker.system.sms.SmsRules.FX_DELAY
import org.thortracker.system.sms.SmsRules.FX_PAN
import org.thortracker.system.sms.SmsRules.FX_PORTAMENTO
import org.thortracker.system.sms.SmsRules.FX_SLIDE_DOWN
import org.thortracker.system.sms.SmsRules.FX_VIBRATO
import org.thortracker.system.sms.SmsRules.FX_VOLUME

/**
 * The bundled Master System demo, "Harbour Lights" (A minor, Am-F-C-G with a Dm-G-C-E bridge, 6 ticks per row =
 * 150 BPM in sixteenths), arranged for each layout: intro, theme, bridge, theme, fill, looping to the theme.
 * PSG: Square 1 sings the melody (vibrato on long notes, a portamento in the bridge, a falling slide in the fill),
 * Square 2 plays the chords from arpeggio envelopes, Square 3 the bass, Noise the kit (fixed-rate noise). Game Gear:
 * the same spread across the speakers. FM unit: an FM lead on the custom voice, the built-in synth bass, organ pads, a
 * vibraphone echo a few ticks late, PSG arpeggios and noise drums; in rhythm mode the YM2413's drums play the kit.
 */
internal object SmsDemoSong {
    private const val SPEED = 6

    private class Chord(val bass: Int, val arp: Int, val minor: Boolean)

    private val VERSE = listOf(Chord(n("A-3"), n("A-4"), true), Chord(n("F-3"), n("F-4"), false), Chord(n("C-3"), n("C-5"), false), Chord(n("G-3"), n("G-4"), false))
    private val BRIDGE = listOf(Chord(n("D-3"), n("D-4"), true), Chord(n("G-3"), n("G-4"), false), Chord(n("C-3"), n("C-5"), false), Chord(n("E-3"), n("E-4"), false))

    private val THEME = listOf(
        0 to "E-5", 4 to "A-5", 6 to "C-6", 8 to "B-5", 12 to "A-5", 16 to "F-5", 20 to "A-5", 22 to "C-6", 24 to "A-5", 28 to "F-5",
        32 to "G-5", 36 to "C-6", 38 to "E-6", 40 to "D-6", 44 to "C-6", 48 to "B-5", 52 to "D-6", 56 to "G-5", 60 to "B-5",
    )
    private val BRIDGE_LINE = listOf(0 to "F-5", 8 to "A-5", 16 to "B-5", 24 to "D-6", 32 to "E-6", 40 to "C-6", 48 to "G#5", 56 to "B-5")

    /** Channel roles in [layout]. */
    private class Roles(layout: SmsLayout) {
        val fm = layout.fm
        val lead = if (fm) SmsLayout.FIRST_FM else 0
        val bass = if (fm) SmsLayout.FIRST_FM + 1 else SmsLayout.SQUARE_3
        val padLow = if (fm) SmsLayout.FIRST_FM + 2 else -1
        val padHigh = if (fm) SmsLayout.FIRST_FM + 3 else -1
        val echo = if (fm) SmsLayout.FIRST_FM + 4 else -1
        val arp = if (fm) 0 else 1
        val noise = if (layout.drums) -1 else SmsLayout.NOISE
        val kick = if (layout.drums) 4 + layout.fmChannels else -1
        val snare = kick + 1
        val tom = kick + 2
        val cymbal = kick + 3
        val hat = kick + 4
    }

    fun build(layout: SmsLayout): Song {
        val system = SmsSystem.of(layout)
        val blank = system.blank()
        val slots = SmsPresets.forLayout(layout).mapIndexed { i, p -> p.name to i + 1 }.toMap()
        fun slot(name: String) = slots[name] ?: throw IllegalStateException("Demo needs preset $name")
        val b = Builder(layout, Roles(layout), ::slot)
        return blank.copy(
            title = "Harbour Lights",
            artist = "Thor Tracker",
            comment = "${layout.displayName} demo for the Banjo driver. Loops to order 1.",
            ticksPerRow = SPEED,
            loopOrder = 1,
            patterns = listOf(b.intro(), b.theme(), b.bridge(), b.fill()),
            orders = listOf(0, 1, 2, 1, 3),
        )
    }

    private class Builder(val layout: SmsLayout, val r: Roles, val slot: (String) -> Int) {
        private val lead = slot(if (r.fm) "FM square lead" else "Square lead")
        private val bass = slot(if (r.fm) "Synth bass" else "PSG bass")
        private val organ = if (r.fm) slot("Organ") else 0
        private val vibes = if (r.fm) slot("Vibraphone") else 0
        private val major = slot("Arp major")
        private val minor = slot("Arp minor")
        private val kick = slot("Noise kick")
        private val snare = slot("Noise snare")
        private val hat = slot("Noise hat")
        private val openHat = slot("Noise open hat")
        private val kit = if (layout.drums) slot("Standard kit") else 0

        private fun pattern(name: String, body: Tracks.() -> Unit): Pattern = Tracks(layout.channelCount).apply(body).build(name)

        fun intro() = pattern("Intro") {
            for (bar in 0 until 4) {
                bass(this, bar, VERSE[bar])
                chords(this, bar, VERSE[bar])
                if (bar >= 2) drums(this, bar, fill = false) else hats(this, bar)
            }
            fx(r.arp, 0, 0, FX_VOLUME, 0x0B)
            if (layout.stereo) {
                fx(r.arp, 0, 1, FX_PAN, 0x10)
                fx(SmsLayout.NOISE, 0, 1, FX_PAN, 0x01)
            }
        }

        fun theme() = pattern("Theme") {
            for (bar in 0 until 4) {
                bass(this, bar, VERSE[bar])
                chords(this, bar, VERSE[bar])
                drums(this, bar, fill = false)
            }
            melody(this, THEME)
            fx(r.lead, 0, 0, FX_VOLUME, 0x0F)
            for (row in listOf(8, 16, 32, 48)) fx(r.lead, row, 0, FX_VIBRATO, 0x63)
            if (r.echo >= 0) for ((row, name) in THEME) if (row + 2 < Songs.ROWS) {
                note(r.echo, row + 2, n(name) - 12, vibes)
                fx(r.echo, row + 2, 0, FX_DELAY, 3)
            }
            if (layout.drums) note(r.cymbal, 0, n("C-4"), kit)
            if (layout.stereo) {
                fx(r.arp, 0, 1, FX_PAN, 0x10)
                fx(SmsLayout.NOISE, 0, 1, FX_PAN, 0x11)
            }
        }

        fun bridge() = pattern("Bridge") {
            for (bar in 0 until 4) {
                bass(this, bar, BRIDGE[bar])
                chords(this, bar, BRIDGE[bar])
                drums(this, bar, fill = false)
            }
            melody(this, BRIDGE_LINE)
            fx(r.lead, 0, 0, FX_VIBRATO, 0x54)
            // A glide without a new attack from D-6 up to E-6.
            note(r.lead, 30, n("E-6"), 0)
            fx(r.lead, 30, 0, FX_PORTAMENTO, 0x10)
            if (layout.stereo) {
                fx(r.arp, 0, 1, FX_PAN, 0x01)
                fx(r.lead, 0, 1, FX_PAN, 0x10)
                fx(r.lead, 32, 1, FX_PAN, 0x11)
            }
        }

        fun fill() = pattern("Fill") {
            for (bar in 0 until 4) {
                bass(this, bar, VERSE[bar])
                if (bar < 2) chords(this, bar, VERSE[bar])
                drums(this, bar, fill = bar >= 2)
            }
            note(r.lead, 32, n("A-5"), lead)
            fx(r.lead, 32, 0, FX_VIBRATO, 0x64)
            fx(r.lead, 48, 0, FX_SLIDE_DOWN, 0x04)
            fx(r.lead, 62, 0, FX_CUT, 3)
        }

        private fun bass(t: Tracks, bar: Int, chord: Chord) {
            val row = bar * 16
            val root = chord.bass - (if (r.fm) 12 else 0)
            for ((k, offset) in listOf(0 to 0, 4 to 0, 6 to 12, 8 to 0, 12 to 7, 14 to 12)) t.note(r.bass, row + k, root + offset, bass)
            if (bar == 0) t.fx(r.bass, row, 0, FX_VOLUME, 0x0E)
        }

        private fun chords(t: Tracks, bar: Int, chord: Chord) {
            val row = bar * 16
            for (k in listOf(0, 8)) t.note(r.arp, row + k, chord.arp, if (chord.minor) minor else major)
            if (r.padLow >= 0) {
                val third = chord.arp + (if (chord.minor) 3 else 4) - 12
                t.note(r.padLow, row, third, organ)
                t.note(r.padHigh, row, chord.arp + 7 - 12, organ)
                if (bar == 0) {
                    t.fx(r.padLow, row, 0, FX_VOLUME, 0x0A)
                    t.fx(r.padHigh, row, 0, FX_VOLUME, 0x0A)
                }
            }
        }

        private fun hats(t: Tracks, bar: Int) {
            val row = bar * 16
            if (layout.drums) {
                for (k in 0 until 16 step 2) t.note(r.hat, row + k, n("C-4"), kit)
            } else {
                for (k in 0 until 16 step 2) t.note(r.noise, row + k, n("D-4"), if (k == 14) openHat else hat)
            }
        }

        private fun drums(t: Tracks, bar: Int, fill: Boolean) {
            val row = bar * 16
            if (layout.drums) {
                for (k in listOf(0, 8)) t.note(r.kick, row + k, n("C-3"), kit)
                for (k in listOf(4, 12)) t.note(r.snare, row + k, n("C-4"), kit)
                for (k in 0 until 16 step 2) t.note(r.hat, row + k, n("C-4"), kit)
                if (fill) for (k in listOf(10, 13, 14)) t.note(r.tom, row + k, n("C-4"), kit)
                if (bar == 0) t.fx(r.hat, row, 0, FX_VOLUME, 0x0A)
                return
            }
            val steps = listOf(0 to kick, 2 to hat, 4 to snare, 6 to hat, 8 to kick, 10 to hat, 12 to snare, 14 to openHat)
            for ((k, sound) in steps) {
                val note = when (sound) { kick -> n("C-4"); snare -> n("C#4"); else -> n("D-4") }
                t.note(r.noise, row + k, note, sound)
            }
            if (fill) for (k in listOf(13, 15)) t.note(r.noise, row + k, n("C#4"), snare)
        }

        private fun melody(t: Tracks, line: List<Pair<Int, String>>) {
            for ((row, name) in line) t.note(r.lead, row, n(name), lead)
        }
    }

    private class Tracks(channels: Int) {
        private val tracks = List(channels) { Array(Songs.ROWS) { Cell.BLANK } }

        fun note(channel: Int, row: Int, note: Int, instrument: Int) {
            tracks[channel][row] = tracks[channel][row].copy(note = note, instrument = instrument)
        }

        fun fx(channel: Int, row: Int, column: Int, effect: Int, param: Int) {
            tracks[channel][row] = tracks[channel][row].withEffect(column, effect, param)
        }

        fun build(name: String) = Pattern(name, tracks.map { it.toList() })
    }

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0) { "bad note $name" }
        return name.substring(2).toInt() * 12 + index
    }
}
