package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.nes

/**
 * Songs whose FamiStudio text export FamiStudio 4.5.3 itself turned into engine data (tools/audio/nes_golden.py):
 * the fixtures in src/jvmTest/resources/nes-golden. Between them they use every NES effect, OFF as release and stop,
 * legato, identical and differing instruments, custom pattern lengths, speed changes on several channels, parked
 * orders and envelope edge cases.
 */
object NesGoldenSongs {
    val ALL: Map<String, () -> Song> = linkedMapOf(
        "demo" to NesSystem::demo,
        "effects" to ::effects,
        "envelopes" to ::envelopes,
    )

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0)
        return name.substring(2).toInt() * 12 + index
    }

    private class P(val name: String, val length: Int = Songs.ROWS) {
        val tracks = List(NesSystem.channels.size) { Array(Songs.ROWS) { Cell.BLANK } }
        fun note(ch: Int, row: Int, note: String, ins: Int = 0, fx: Int = 0, param: Int = 0) {
            tracks[ch][row] = Cell(n(note), ins, fx, param)
        }
        fun off(ch: Int, row: Int, fx: Int = 0, param: Int = 0) { tracks[ch][row] = Cell(Cell.CUT, 0, fx, param) }
        fun fx(ch: Int, row: Int, fx: Int, param: Int) { tracks[ch][row] = tracks[ch][row].copy(effect = fx, param = param) }
        fun build() = Pattern(name, tracks.map { it.toList() }, length = length)
    }

    fun effects(): Song {
        val base = NesSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Release lead", volume = NesEnvelope(listOf(15, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), duty = NesEnvelope(listOf(2)))
        bank[1] = NesInstrument("Plain", volume = NesEnvelope(listOf(12)))
        bank[2] = NesInstrument("Plain twin", volume = NesEnvelope(listOf(12)))
        bank[3] = NesInstrument("Decay", volume = NesEnvelope(listOf(15, 13, 11, 9, 7, 5, 3, 1, 0)), duty = NesEnvelope(listOf(1)))
        bank[4] = NesInstrument("Noise", volume = NesEnvelope(listOf(14, 10, 6, 3, 0)))
        bank[5] = NesInstrument("Arp inst", volume = NesEnvelope(listOf(11)), arpeggio = NesEnvelope(listOf(0, 12), loop = 0))
        val a = P("Effects A")
        a.note(0, 0, "C-4", 1, NesRules.FX_VIBRATO, 0x45)
        a.note(0, 4, "E-4", 0) // legato on the same instrument
        a.off(0, 8) // release (instrument 1 has a release point)
        a.off(0, 10) // already released: stop
        a.note(0, 12, "G-4", 2, NesRules.FX_ARPEGGIO, 0x37)
        a.note(0, 14, "G-4", 3) // identical instrument: attack kept by FamiStudio's rule
        a.note(0, 16, "A-4", 4, NesRules.FX_SLIDE_UP, 0x03)
        a.note(0, 20, "D-5", 4, NesRules.FX_SLIDE_DOWN, 0x05)
        a.fx(0, 22, NesRules.FX_VIBRATO, 0x00)
        a.note(0, 24, "C-5", 2, NesRules.FX_FINE_PITCH, 0x90)
        a.note(0, 26, "C-5", 2) // fine pitch reset by the next note
        a.fx(0, 27, NesRules.FX_FINE_PITCH, 0x70) // bend on an empty row
        a.note(0, 28, "E-5", 6) // instrument arpeggio
        a.note(0, 30, "E-5", 6, NesRules.FX_ARPEGGIO, 0x47) // effect arpeggio overrides it
        a.note(0, 32, "E-5", 6) // back to the instrument's
        a.note(0, 34, "B-4", 2, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(0, 36, "B-4", 2, NesRules.FX_NOTE_CUT, 0x03)
        a.off(0, 38, NesRules.FX_NOTE_DELAY, 0x01)
        a.note(0, 40, "F-4", 2, NesRules.FX_DUTY, 0x01)
        a.note(0, 44, "F-4", 4) // decay with its own duty envelope
        a.note(0, 48, "C-3", 2)
        a.note(0, 52, "B-2", 0) // legato after a different instrument? no: same instrument, plain
        // Pulse 2: volume, volume slides, speed.
        a.note(1, 0, "C-3", 2, NesRules.FX_VOLUME, 0x08)
        a.note(1, 8, "E-3", 2, NesRules.FX_VOLUME_SLIDE, 0x3E)
        a.note(1, 16, "G-3", 4, NesRules.FX_VOLUME, 0x0F)
        a.fx(1, 20, NesRules.FX_SPEED, 0x04)
        a.note(1, 24, "A-3", 2, NesRules.FX_VOLUME_SLIDE, 0xF2)
        a.fx(1, 32, NesRules.FX_VOLUME, 0x0A)
        a.fx(1, 40, NesRules.FX_SPEED, 0x06)
        a.note(1, 44, "A-2", 1)
        a.off(1, 50)
        // Triangle: notes, cuts, slides, a low note, legato across instruments.
        a.note(2, 0, "C-3", 2)
        a.note(2, 4, "C-3", 2, NesRules.FX_NOTE_CUT, 0x02)
        a.note(2, 8, "A-1", 2)
        a.note(2, 12, "G-3", 2, NesRules.FX_SLIDE_DOWN, 0x07)
        a.note(2, 20, "D-4", 2, NesRules.FX_NOTE_DELAY, 0x04)
        a.off(2, 30)
        a.note(2, 32, "E-3", 4)
        a.note(2, 34, "F-3", 0)
        a.note(2, 40, "G-3", 1)
        a.off(2, 48)
        // Noise: rates, a noise slide, duty (mode) and a speed effect on another channel.
        for (r in 0 until 64 step 4) a.note(3, r, if (r % 8 == 0) "C-3" else "A#4", 5)
        a.fx(3, 4, NesRules.FX_DUTY, 0x01)
        a.fx(3, 12, NesRules.FX_DUTY, 0x00)
        a.note(3, 18, "C-4", 5, NesRules.FX_SLIDE_UP, 0x08)
        a.fx(3, 40, NesRules.FX_SPEED, 0x05)
        a.fx(3, 41, NesRules.FX_VOLUME, 0x07)
        val b = P("Short B", length = 24)
        b.note(0, 0, "C-4", 1)
        b.note(1, 0, "E-4", 3, NesRules.FX_VOLUME_SLIDE, 0x0F)
        b.note(2, 0, "G-2", 2)
        b.note(3, 0, "C-3", 5)
        b.fx(0, 6, NesRules.FX_VIBRATO, 0x2A)
        b.off(0, 16)
        b.note(1, 12, "F-4", 0)
        val c = P("Parked", length = 16)
        c.note(0, 0, "C-6", 4)
        val d = P("Loop end", length = 40)
        d.note(0, 0, "D-4", 4, NesRules.FX_ARPEGGIO, 0x58)
        d.note(1, 8, "D-4", 3)
        d.note(2, 16, "D-2", 1)
        d.fx(3, 20, NesRules.FX_SPEED, 0x08)
        d.note(3, 24, "F-4", 5, NesRules.FX_NOTE_CUT, 0x00)
        return base.copy(
            title = "Effects", artist = "Golden", ticksPerRow = 5, loopOrder = 1,
            patterns = listOf(a.build(), b.build(), c.build(), d.build()),
            orders = listOf(0, 1, 2, 3, 1, 0), parkedOrders = listOf(2),
            sound = NesSound(bank),
        )
    }

    fun envelopes(): Song {
        val base = NesSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Long hold", volume = NesEnvelope(List(200) { 9 } + listOf(5, 5, 5) + List(40) { 2 }, loop = -1))
        bank[1] = NesInstrument("Relative pitch", pitch = NesEnvelope(listOf(-2, -1, 0, 1, 2), loop = 0), pitchRelative = true)
        bank[2] = NesInstrument("Absolute pitch", pitch = NesEnvelope(listOf(0, 4, 8, 4, 0, -4, -8, -4), loop = 0))
        bank[3] = NesInstrument("Trailing", volume = NesEnvelope(listOf(15, 10, 6, 6, 6, 6)), arpeggio = NesEnvelope(listOf(12, 7, 0, 0, 0)))
        bank[4] = NesInstrument("All equal loop", volume = NesEnvelope(listOf(7, 7, 7), loop = 1), duty = NesEnvelope(listOf(3, 3)))
        bank[5] = NesInstrument("Duty loop", duty = NesEnvelope(listOf(0, 1, 2, 3), loop = 1), volume = NesEnvelope(listOf(13, 12, 11, 10), loop = 2, release = 3))
        bank[6] = NesInstrument("Negative arp", arpeggio = NesEnvelope(listOf(0, -12, -24, -5), loop = 2))
        bank[7] = NesInstrument("Zero volume", volume = NesEnvelope(listOf(0, 0)))
        bank[8] = NesInstrument("Relative zero", pitch = NesEnvelope(listOf(0, 0, 0), loop = 0), pitchRelative = true)
        bank[9] = NesInstrument("Long relative", pitch = NesEnvelope(List(140) { if (it % 2 == 0) 1 else 0 }), pitchRelative = true,
            volume = NesEnvelope(listOf(14, 12), loop = 0, release = 1))
        val a = P("Envelopes")
        for (i in 0 until 10) {
            a.note(0, i * 6, "C-4", i + 1)
            a.note(1, i * 6 + 3, "G-3", 10 - i)
            a.note(2, i * 6, "C-3", i + 1)
        }
        a.off(0, 61)
        a.off(1, 62)
        a.note(3, 0, "A-3", 7)
        a.note(3, 30, "C-4", 8, NesRules.FX_DUTY, 0x01)
        return base.copy(title = "Envelopes", ticksPerRow = 3, patterns = listOf(a.build()), orders = listOf(0, 0), sound = NesSound(bank))
    }
}
