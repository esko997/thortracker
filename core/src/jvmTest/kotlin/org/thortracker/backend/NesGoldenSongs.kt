package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.N163Preset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.nes.Vrc7Presets
import org.thortracker.system.nes.Vrc7Sustain
import org.thortracker.system.nes.nes
import org.thortracker.system.nes.DpcmMapping
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.DpcmStarterKit

/**
 * Songs whose FamiStudio text export FamiStudio 4.5.3 itself turned into engine data (tools/audio/nes_golden.py):
 * the fixtures in src/jvmTest/resources/nes-golden. Between them they use every NES effect, OFF as release and stop,
 * legato, identical and differing instruments, custom pattern lengths, speed changes on several channels, parked
 * orders and envelope edge cases; the VRC6 ones its pulses' eight duties, the saw's master volumes and slides; the
 * VRC7 ones built-in and custom patches, two custom sounds taking the chip's one custom patch in turn, key-off releases
 * and stops with the song's sustain overrides, and FM pitch effects; the N163 ones wave sequences (repeats, loop,
 * release), wave sizes and positions, shared waves, the phase reset and 1, 2, 4 and 8 channels (each count has its own
 * note table, pitch shift and engine build). The copies songs (2A03, VRC6, N163) hold identical sounds under different
 * names, which FamiStudio's export merges into one instrument, with a legato loop entry across the copies. Every trace
 * covers the 2A03's registers as well as the expansion's.
 */
object NesGoldenSongs {
    val ALL: Map<String, () -> Song> = linkedMapOf(
        "demo" to NesSystem::demo,
        "effects" to ::effects,
        "envelopes" to ::envelopes,
        "dpcm" to ::dpcm,
        "copies" to ::copies,
        "vrc6_demo" to NesVrc6System::demo,
        "vrc6_effects" to ::vrc6Effects,
        "vrc6_copies" to ::vrc6Copies,
        "vrc7_demo" to NesVrc7System::demo,
        "vrc7_effects" to ::vrc7Effects,
        "n163_demo" to { NesN163System.of(8).demo() },
        "n163_demo_1" to { NesN163System.of(1).demo() },
        "n163_effects" to ::n163Effects,
        "n163_copies" to ::n163Copies,
    )

    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        require(index >= 0)
        return name.substring(2).toInt() * 12 + index
    }

    private class P(val name: String, val length: Int = Songs.ROWS, channels: Int = NesSystem.channels.size) {
        val tracks = List(channels) { Array(Songs.ROWS) { Cell.BLANK } }
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

    /**
     * DPCM: the starter kit plus a looping sample, a twin of the hat (identical data, its own initial value) and
     * mapped but unplayed samples; notes with and without instrument (no attack), several rates, loop, an initial value
     * override, Zxx with and without a note, delays, cuts, OFF, and the speed set on DPCM only.
     */
    fun dpcm(): Song {
        val base = NesSystem.blank()
        val kit = DpcmStarterKit.samples()
        val samples = kit + listOf(
            DpcmSample("Buzz loop", List(33) { if (it % 2 == 0) 0xFF else 0x00 }, dmcInitial = 20),
            DpcmSample("Hat twin", kit[DpcmStarterKit.HAT].data, dmcInitial = 40),
            DpcmSample("Never", List(17) { 0x5A }),
        )
        val bank = base.nes.instruments.toMutableList()
        bank[0] = DpcmStarterKit.instrument(0)
        bank[1] = NesInstrument("Effects", dpcm = mapOf(
            60 to DpcmMapping(5, pitch = 9, loop = true),
            62 to DpcmMapping(5, pitch = 13, loop = true, dmcInitial = 10),
            64 to DpcmMapping(6),
            65 to DpcmMapping(7),
        ))
        bank[2] = NesInstrument("Lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2))
        val d = NesRules.DPCM
        val a = P("Drums A")
        a.note(d, 0, "C-3", 1)
        a.note(d, 4, "F#3", 1)
        a.note(d, 8, "D-3", 1)
        a.note(d, 12, "F#3", 0) // no instrument: no attack, the level is kept
        a.note(d, 16, "C-3", 1, NesRules.FX_DELTA_COUNTER, 0x20) // level for this sample's start
        a.note(d, 20, "G-3", 1)
        a.note(d, 24, "A-3", 1, NesRules.FX_NOTE_DELAY, 0x02)
        a.off(d, 28)
        a.fx(d, 30, NesRules.FX_DELTA_COUNTER, 0x60) // at once
        a.note(d, 32, "C-5", 2)
        a.note(d, 36, "D-5", 2)
        a.note(d, 40, "E-5", 2, NesRules.FX_NOTE_CUT, 0x03)
        a.note(d, 44, "C-3", 1)
        a.fx(d, 48, NesRules.FX_SPEED, 0x04)
        a.note(d, 52, "D-3", 1)
        a.note(d, 56, "C-5", 2)
        a.fx(d, 60, NesRules.FX_DELTA_COUNTER, 0x00)
        a.note(0, 0, "C-4", 3)
        a.note(0, 16, "E-4", 3)
        a.note(0, 32, "G-4", 3)
        a.off(0, 48)
        a.note(2, 0, "C-3", 3)
        a.note(3, 8, "C-4", 3)
        val b = P("Drums B", length = 32)
        b.note(d, 0, "C-3", 1)
        b.note(d, 8, "D-3", 1)
        b.note(d, 16, "C-3", 1, NesRules.FX_DELTA_COUNTER, 0x7F)
        b.note(d, 24, "D-3", 1)
        b.note(0, 0, "A-3", 3)
        return base.copy(title = "DPCM", ticksPerRow = 6, loopOrder = 1, patterns = listOf(a.build(), b.build()),
            orders = listOf(0, 1, 1), sound = NesSound(bank, samples))
    }

    /**
     * VRC6: all eight duties (envelope and Vxx), the three saw master volumes switching between instruments (and across
     * the loop), volume and volume slides on the saw, slides on a VRC6 pulse and the saw, vibrato, fine pitch, delays,
     * cuts, OFF release and stop, legato, the lowest notes the VRC6 reaches, the speed set on the saw, and 2A03
     * channels alongside.
     */
    fun vrc6Effects(): Song {
        val base = NesVrc6System.blank()
        val bank = base.nes.instruments.toMutableList()
        val vrc6 = NesChip.VRC6
        bank[0] = NesInstrument("Duty walk", volume = NesEnvelope(listOf(13, 12, 11, 10), loop = 3), duty = NesEnvelope(listOf(0, 1, 2, 3, 4, 5, 6, 7), loop = 0), chip = vrc6)
        bank[1] = NesInstrument("Release pulse", volume = NesEnvelope(listOf(15, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), duty = NesEnvelope(listOf(5)), chip = vrc6)
        bank[2] = NesInstrument("Saw full", volume = NesEnvelope(listOf(10, 9, 8), loop = 2), chip = vrc6, sawVolume = Vrc6SawVolume.FULL)
        bank[3] = NesInstrument("Saw half", volume = NesEnvelope(listOf(15, 13, 11), loop = 2), chip = vrc6)
        bank[4] = NesInstrument("Saw quarter", volume = NesEnvelope(listOf(15)), arpeggio = NesEnvelope(listOf(0, 12), loop = 0), chip = vrc6,
            sawVolume = Vrc6SawVolume.QUARTER)
        bank[5] = NesInstrument("Saw full twin", volume = NesEnvelope(listOf(10, 9, 8), loop = 2), chip = vrc6, sawVolume = Vrc6SawVolume.FULL)
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        bank[7] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val p1 = NesRules.VRC6_PULSE1
        val p2 = NesRules.VRC6_PULSE2
        val saw = NesRules.VRC6_SAW
        val a = P("VRC6 A", channels = 8)
        // VRC6 Pulse 1: the duty envelope, Vxx over all eight duties, slides, vibrato, fine pitch, the lowest note.
        a.note(p1, 0, "C-4", 1)
        a.note(p1, 4, "E-4", 0) // legato
        a.note(p1, 8, "G-4", 2, NesRules.FX_DUTY, 0x00)
        for (d in 1..7) a.fx(p1, 8 + d, NesRules.FX_DUTY, d)
        a.note(p1, 16, "A-4", 2, NesRules.FX_SLIDE_UP, 0x05)
        a.note(p1, 20, "D-5", 2, NesRules.FX_SLIDE_DOWN, 0x07)
        a.note(p1, 24, "C-5", 1, NesRules.FX_VIBRATO, 0x46)
        a.fx(p1, 28, NesRules.FX_VIBRATO, 0x00)
        a.note(p1, 30, "C-5", 1, NesRules.FX_FINE_PITCH, 0x8C)
        a.off(p1, 34) // release
        a.off(p1, 36) // already released: stop
        a.note(p1, 40, "C-1", 1) // below the 2A03's A-1: the VRC6's 12-bit period reaches it
        a.note(p1, 44, "F-4", 2, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(p1, 48, "F-4", 2, NesRules.FX_NOTE_CUT, 0x03)
        // VRC6 Pulse 2: volume and a volume slide, arpeggio, duty 7 square.
        a.note(p2, 0, "G-3", 2, NesRules.FX_VOLUME, 0x09)
        a.note(p2, 8, "B-3", 2, NesRules.FX_VOLUME_SLIDE, 0xF3)
        a.fx(p2, 16, NesRules.FX_VOLUME, 0x0C)
        a.note(p2, 20, "D-4", 2, NesRules.FX_ARPEGGIO, 0x47)
        a.note(p2, 32, "D-4", 1, NesRules.FX_DUTY, 0x07)
        a.off(p2, 56)
        // Saw: the three master volumes (each instrument change sends $55), twins, volume, slides, speed.
        a.note(saw, 0, "C-3", 3)
        a.note(saw, 4, "C-3", 4) // Half
        a.note(saw, 8, "E-3", 5, NesRules.FX_VOLUME, 0x0A) // Quarter, with its octave arpeggio
        a.note(saw, 12, "G-3", 6) // Full again: identical to instrument 3
        a.note(saw, 16, "G-3", 3, NesRules.FX_SLIDE_DOWN, 0x0C)
        a.fx(saw, 20, NesRules.FX_SPEED, 0x05)
        a.note(saw, 24, "A-2", 4, NesRules.FX_VOLUME_SLIDE, 0x4F)
        a.note(saw, 32, "C-1", 4) // the saw's lowest
        a.note(saw, 36, "C-6", 4, NesRules.FX_VIBRATO, 0x38)
        a.note(saw, 40, "D-5", 0, NesRules.FX_VIBRATO, 0x00) // legato, vibrato off
        a.fx(saw, 44, NesRules.FX_SPEED, 0x06)
        a.note(saw, 48, "F-3", 5, NesRules.FX_FINE_PITCH, 0x74)
        a.off(saw, 56)
        // 2A03 alongside.
        a.note(0, 0, "E-4", 7)
        a.note(0, 32, "G-4", 7, NesRules.FX_DUTY, 0x03)
        a.note(2, 0, "C-3", 7)
        a.off(2, 40)
        for (r in 0 until 64 step 8) a.note(3, r, "C-3", 8)
        val b = P("VRC6 B", length = 32, channels = 8)
        b.note(saw, 0, "A-3", 5) // the loop enters with Quarter after the song left the saw at Full
        b.note(saw, 16, "A-3", 3)
        b.note(p1, 0, "E-5", 0)
        b.note(p2, 0, "C-5", 1)
        b.off(p2, 24)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "VRC6 effects", artist = "Golden", ticksPerRow = 4, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1, 0),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /**
     * VRC7: two custom sounds (and an identical twin of one) loading the chip's one custom patch in turn, also on the
     * same row, and built-in patches beside them; the song's release and stop sustain overrides with OFF as key-off
     * release ($56) and as stop, Sxx cuts and note delays; legato, slides, vibrato, Pxx (converted to frequency steps),
     * volume and volume slides, 0xy, pitch and arpeggio envelopes, the FM channels' lowest and a high note, the speed
     * set on an FM channel, and 2A03 channels alongside. The last custom-sound note before the song's end is FM 1's
     * (FM 1 holds that sound when the loop is entered), so FamiStudio's exporter and the lowering send the same
     * instruments at the loop.
     */
    fun vrc7Effects(): Song {
        val base = NesVrc7System.blank()
        val bank = base.nes.instruments.toMutableList()
        val vrc7 = NesChip.VRC7
        val lead = Vrc7Patch(Vrc7Presets.CUSTOM, listOf(0xF1, 0x61, 0x9B, 0x1F, 0xD2, 0x74, 0x31, 0x57))
        bank[0] = NesInstrument("Custom lead", volume = NesEnvelope(listOf(15, 14, 13, 12, 11, 9, 6, 3, 0), loop = 3, release = 5), chip = vrc7, fm = lead)
        bank[1] = NesInstrument("Custom bass", volume = NesEnvelope(listOf(15, 13, 12), loop = 2), chip = vrc7,
            fm = Vrc7Patch(Vrc7Presets.CUSTOM, listOf(0x11, 0x01, 0x8A, 0x47, 0xF9, 0xF5, 0x13, 0x26)))
        bank[2] = NesInstrument("Piano arp", arpeggio = NesEnvelope(listOf(0, 4, 7), loop = 0), chip = vrc7, fm = Vrc7Patch().withPreset(3))
        bank[3] = NesInstrument("Bass guitar bend", pitch = NesEnvelope(listOf(6, 3, 1, 0)), chip = vrc7, fm = Vrc7Patch().withPreset(13))
        bank[4] = NesInstrument("Bell decay", volume = NesEnvelope(listOf(15, 13, 11, 9, 7, 5, 3, 1)), chip = vrc7, fm = Vrc7Patch().withPreset(Vrc7Presets.BELL))
        bank[5] = NesInstrument("Custom lead twin", volume = NesEnvelope(listOf(15, 14, 13, 12, 11, 9, 6, 3, 0), loop = 3, release = 5), chip = vrc7, fm = lead)
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        bank[7] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val fm1 = NesRules.VRC7_FM1
        val fm2 = fm1 + 1
        val fm3 = fm1 + 2
        val fm4 = fm1 + 3
        val fm5 = fm1 + 4
        val fm6 = fm1 + 5
        val a = P("VRC7 A", channels = 11)
        // FM 1: custom lead, legato, effects, release then stop, the range ends, delays and cuts, its twin.
        a.note(fm1, 0, "C-4", 1)
        a.note(fm1, 4, "E-4", 0) // legato
        a.note(fm1, 8, "G-4", 1, NesRules.FX_VIBRATO, 0x46)
        a.fx(fm1, 12, NesRules.FX_VIBRATO, 0x00)
        a.note(fm1, 14, "A-4", 1, NesRules.FX_SLIDE_UP, 0x05)
        a.note(fm1, 18, "D-5", 1, NesRules.FX_SLIDE_DOWN, 0x07)
        a.note(fm1, 22, "C-5", 1, NesRules.FX_FINE_PITCH, 0x8C)
        a.note(fm1, 24, "C-5", 1) // the next note resets the fine pitch
        a.off(fm1, 26) // key-off release ($56)
        a.off(fm1, 28) // already released: stop
        a.note(fm1, 30, "C-1", 1) // the FM channels' lowest note
        a.note(fm1, 32, "B-7", 1)
        a.note(fm1, 34, "F-4", 1, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(fm1, 36, "F-4", 1, NesRules.FX_NOTE_CUT, 0x03)
        a.note(fm1, 38, "A-4", 1, NesRules.FX_VOLUME, 0x09)
        a.note(fm1, 40, "A-4", 1, NesRules.FX_VOLUME_SLIDE, 0xF3)
        a.note(fm1, 44, "E-4", 1, NesRules.FX_ARPEGGIO, 0x37)
        a.note(fm1, 48, "E-4", 6) // the twin: identical but for its name, so FamiStudio's export merges it with the lead
        a.note(fm1, 50, "G-4", 0) // legato on the twin
        a.off(fm1, 52)
        a.note(fm1, 60, "A-4", 1) // the song's last custom-sound note
        // FM 2: the second custom sound, on FM 1's rows too (both load; the higher channel's patch stays).
        a.note(fm2, 0, "C-3", 2)
        a.off(fm2, 16)
        a.note(fm2, 20, "G-2", 2)
        a.note(fm2, 32, "C-3", 2, NesRules.FX_SLIDE_UP, 0x07)
        a.off(fm2, 44)
        // FM 3: built-in Piano, its arpeggio envelope and 0xy, the speed changes.
        a.note(fm3, 0, "A-3", 3)
        a.note(fm3, 8, "C-4", 3, NesRules.FX_ARPEGGIO, 0x47)
        a.off(fm3, 16)
        a.fx(fm3, 20, NesRules.FX_SPEED, 0x05)
        a.note(fm3, 32, "E-4", 3, NesRules.FX_VOLUME, 0x0C)
        a.fx(fm3, 44, NesRules.FX_SPEED, 0x04)
        a.off(fm3, 48)
        // FM 4: built-in Bass guitar with a pitch envelope, cuts, a slide, vibrato and Pxx.
        a.note(fm4, 0, "A-1", 4)
        a.note(fm4, 8, "A-1", 4, NesRules.FX_NOTE_CUT, 0x02)
        a.note(fm4, 16, "E-2", 4, NesRules.FX_SLIDE_UP, 0x0C)
        a.note(fm4, 32, "C-2", 4, NesRules.FX_VIBRATO, 0x38)
        a.fx(fm4, 40, NesRules.FX_VIBRATO, 0x00)
        a.note(fm4, 48, "C-2", 4, NesRules.FX_FINE_PITCH, 0x70)
        a.off(fm4, 56)
        // FM 5: built-in Bell, a volume slide, notes after the last custom one.
        a.note(fm5, 0, "C-5", 5, NesRules.FX_VOLUME_SLIDE, 0x4F)
        for (r in 8 until 64 step 8) a.note(fm5, r, if (r % 16 == 0) "G-5" else "E-5", 5)
        a.note(fm5, 62, "C-6", 5)
        // FM 6: the twin (one instrument with the lead in FamiStudio's export) while FM 1 holds the lead.
        a.note(fm6, 24, "G-4", 6)
        a.off(fm6, 32)
        a.note(fm6, 46, "B-4", 6, NesRules.FX_NOTE_DELAY, 0x01)
        a.off(fm6, 54)
        // 2A03 alongside.
        a.note(0, 0, "E-4", 7)
        a.note(0, 32, "G-4", 7)
        a.off(0, 56)
        for (r in 0 until 64 step 8) a.note(3, r, "C-3", 8)
        val b = P("VRC7 B", length = 32, channels = 11)
        b.note(fm1, 0, "E-5", 1)
        b.off(fm1, 8)
        b.note(fm1, 16, "E-4", 2) // FM 1 switches to the other custom sound and back
        b.note(fm1, 24, "A-4", 1)
        b.note(fm2, 4, "C-3", 2)
        b.off(fm2, 12)
        b.note(fm3, 0, "A-3", 3)
        b.note(fm5, 16, "E-5", 5)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "VRC7 effects", artist = "Golden", ticksPerRow = 4, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1, 0),
            sound = base.nes.copy(instruments = bank, vrc7Sustain = Vrc7Sustain(release = true, stop = true)),
        )
    }

    /**
     * N163 with 4 channels: a wave sequence with repeats, a loop and a release (OFF releases volume and waves: `$5A`), a
     * sound that releases through its waves only, 32- and 8-sample waves, two sounds sharing one identical wave at one
     * position, legato on the same sound and an instrument change (always an attack on the N163), the phase reset with
     * and without a note, slides both ways, vibrato, fine pitch, volume and a volume slide, arpeggio, delays, a cut, a
     * stop, the speed set on an N163 channel, and 2A03 channels alongside.
     */
    fun n163Effects(): Song {
        val system = NesN163System.of(4)
        val base = system.blank()
        val bank = base.nes.instruments.toMutableList()
        val n163 = NesChip.N163
        fun wave(preset: N163Preset, size: Int) = N163Presets.wave(preset, size, 0, 1)
        bank[0] = NesInstrument("Sequence", volume = NesEnvelope(listOf(14, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), chip = n163,
            wave = N163Wave(waves = N163Presets.waves(N163Preset.PWM2, 16, 3), repeat = listOf(2, 3, 1), loop = 1, release = 2, position = 0))
        bank[1] = NesInstrument("Wave release", volume = NesEnvelope(listOf(11)), chip = n163,
            wave = N163Wave(waves = listOf(wave(N163Preset.SQUARE50, 16), wave(N163Preset.SINE, 16)), repeat = listOf(1, 1),
                loop = 0, release = 1, position = 16))
        bank[2] = NesInstrument("Big 32", volume = NesEnvelope(listOf(12, 11, 10), loop = 2), arpeggio = NesEnvelope(listOf(0, 12), loop = 0),
            chip = n163, wave = N163Wave(size = 32, waves = listOf(wave(N163Preset.TRIANGLE, 32)), position = 32))
        bank[3] = NesInstrument("Tiny 8", volume = NesEnvelope(listOf(10, 8, 6, 4)), pitch = NesEnvelope(listOf(0, 2, 4, 2), loop = 0),
            chip = n163, wave = N163Wave(size = 8, waves = listOf(wave(N163Preset.SAWTOOTH, 8)), position = 64))
        bank[4] = NesInstrument("Shared A", volume = NesEnvelope(listOf(9)), chip = n163,
            wave = N163Wave(waves = listOf(wave(N163Preset.SINE, 16)), position = 80))
        bank[5] = NesInstrument("Shared B", volume = NesEnvelope(listOf(13, 11, 9), loop = 2), chip = n163,
            wave = N163Wave(waves = listOf(wave(N163Preset.SINE, 16)), position = 80))
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        bank[7] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val w1 = NesRules.N163_FIRST
        val w2 = w1 + 1
        val w3 = w1 + 2
        val w4 = w1 + 3
        val a = P("N163 A", channels = 9)
        // N163 1: the sequence, legato, release then stop, slides, vibrato, fine pitch, phase reset, delay, cut.
        a.note(w1, 0, "A-4", 1)
        a.note(w1, 4, "C-5", 0) // legato: the sequence continues
        a.off(w1, 8) // release: volume and waves jump to their release points
        a.off(w1, 12) // already released: stop
        a.note(w1, 16, "E-4", 1, NesRules.FX_SLIDE_UP, 0x07)
        a.note(w1, 20, "B-4", 1, NesRules.FX_SLIDE_DOWN, 0x05)
        a.note(w1, 24, "D-5", 1, NesRules.FX_VIBRATO, 0x46)
        a.fx(w1, 28, NesRules.FX_VIBRATO, 0x00)
        a.note(w1, 30, "D-5", 1, NesRules.FX_FINE_PITCH, 0x8A)
        a.fx(w1, 33, NesRules.FX_PHASE_RESET, 0x00) // without a note
        a.note(w1, 36, "F-4", 1, NesRules.FX_PHASE_RESET, 0x00) // with a note
        a.note(w1, 40, "G-4", 1, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(w1, 44, "G-4", 1, NesRules.FX_NOTE_CUT, 0x03)
        a.note(w1, 48, "C-1", 1) // the N163 reaches every note down to C-1
        a.note(w1, 52, "F-8", 1) // and high up with 4 channels
        a.off(w1, 56)
        // N163 2: release through the waves only, then an instrument change (no legato across sounds on the N163).
        a.note(w2, 0, "E-4", 2)
        a.off(w2, 6)
        a.note(w2, 8, "G-4", 2, NesRules.FX_VOLUME, 0x09)
        a.note(w2, 12, "G-4", 6) // another sound: an attack even without one written
        a.note(w2, 16, "B-4", 0, NesRules.FX_VOLUME_SLIDE, 0xF4)
        a.fx(w2, 24, NesRules.FX_VOLUME, 0x0C)
        a.note(w2, 28, "C-5", 2, NesRules.FX_ARPEGGIO, 0x47)
        a.fx(w2, 32, NesRules.FX_SPEED, 0x05)
        a.fx(w2, 40, NesRules.FX_SPEED, 0x04)
        a.off(w2, 48)
        // N163 3: the 32-sample triangle (an octave below the note) with its octave arpeggio, then the shared sine.
        a.note(w3, 0, "C-4", 3)
        a.note(w3, 16, "G-4", 3, NesRules.FX_SLIDE_DOWN, 0x0C)
        a.note(w3, 32, "A-3", 5)
        a.off(w3, 60)
        // N163 4: the 8-sample saw (an octave above) with its pitch envelope, then the other shared sine alongside N163 3.
        a.note(w4, 0, "C-3", 4)
        a.note(w4, 8, "E-3", 4, NesRules.FX_FINE_PITCH, 0x70)
        a.note(w4, 32, "E-4", 6)
        a.note(w4, 40, "E-4", 0, NesRules.FX_PHASE_RESET, 0x00) // legato with a phase reset
        a.off(w4, 60)
        // 2A03 alongside.
        a.note(0, 0, "E-4", 7)
        a.note(2, 0, "C-3", 7)
        a.off(2, 40)
        for (r in 0 until 64 step 8) a.note(3, r, "C-3", 8)
        val b = P("N163 B", length = 32, channels = 9)
        b.note(w1, 0, "A-4", 1) // the loop enters with the sequence restarted
        b.note(w2, 0, "C-5", 2)
        b.off(w2, 16)
        b.note(w3, 0, "E-4", 5)
        b.note(w4, 0, "E-4", 6)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "N163 effects", artist = "Golden", ticksPerRow = 4, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1, 0),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /**
     * N163 with 2 channels: two identical sounds under different names (FamiStudio's export merges them into one
     * instrument, `Project.MergeIdenticalInstruments`, which ignores names) and a third that differs only in its volume.
     * N163 1 switches between the copies with attacks and legato, and the loop's first note is legato on one copy while
     * the loop's last instrument is the other: one instrument after the merge, so the loop keeps that note without an
     * attack (unmerged copies would force one: the N163 allows no attack only on the same instrument). N163 2 plays the
     * other copy at the same RAM position alongside, and the differing sound (always an attack). 2A03 pulse and noise
     * alongside, so their registers are traced too.
     */
    fun n163Copies(): Song {
        val system = NesN163System.of(2)
        val base = system.blank()
        val bank = base.nes.instruments.toMutableList()
        val n163 = NesChip.N163
        val copy = NesInstrument("Copy", volume = NesEnvelope(listOf(13, 11, 9, 8), loop = 3), chip = n163,
            wave = N163Wave(waves = listOf(N163Presets.wave(N163Preset.SINE, 16, 0, 1)), position = 0))
        bank[0] = copy
        bank[1] = copy.copy(name = "Renamed twin")
        bank[2] = copy.copy(name = "Quieter", volume = NesEnvelope(listOf(10, 9, 8, 7), loop = 3),
            wave = copy.wave.copy(position = 32))
        bank[3] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        bank[4] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val w1 = NesRules.N163_FIRST
        val w2 = w1 + 1
        val a = P("Copies A", length = 16, channels = 7)
        a.note(w1, 0, "C-4", 1)
        a.note(w1, 4, "E-4", 2) // the twin with an attack: no instrument change after the merge
        a.note(w1, 8, "G-4", 0) // legato on the twin
        a.note(w1, 12, "A-4", 1)
        a.note(w2, 2, "C-3", 2)
        a.note(w2, 10, "C-3", 3) // the differing sound: an attack even without one written
        a.note(w2, 14, "D-3", 0)
        a.note(0, 0, "E-4", 4)
        a.note(3, 0, "C-3", 5)
        a.note(3, 8, "C-3", 5)
        val b = P("Copies B", length = 16, channels = 7)
        b.note(w1, 0, "C-5", 0) // legato: on Copy the first time, on the twin each time the loop comes round
        b.note(w1, 8, "E-5", 2)
        b.note(w1, 12, "D-5", 0) // the loop's last instrument: the twin
        b.note(w2, 4, "G-3", 1)
        b.off(w2, 12)
        b.note(0, 0, "A-4", 4)
        return base.copy(
            title = "N163 copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /**
     * Identical sounds under different names on the [legato] channels (FamiStudio's export merges them into one
     * instrument, `Project.MergeIdenticalInstruments`, which ignores names) and one that differs only slightly: on each
     * channel the copies take turns with attacks and legato, and the loop's first note is legato on one copy while the
     * loop's last instrument is the other. After the merge that is one instrument, so the loop keeps the note without an
     * attack (comparing the copies by slot would force one). Pattern A is the intro, B the loop.
     */
    private fun copyPatterns(channels: Int, legato: List<Int>, copy: Int, twin: Int, other: Int): Pair<P, P> {
        val a = P("Copies A", length = 16, channels = channels)
        val b = P("Copies B", length = 16, channels = channels)
        for ((k, ch) in legato.withIndex()) {
            // Every other channel runs the copies the other way round.
            val (first, second) = if (k % 2 == 0) copy to twin else twin to copy
            a.note(ch, 0, "C-4", first)
            a.note(ch, 4, "E-4", second) // the other copy with an attack: the same instrument after the merge
            a.note(ch, 8, "G-4", 0) // legato on it
            a.note(ch, 10, "G-4", other) // the differing sound: an instrument change
            a.note(ch, 12, "A-4", first)
            b.note(ch, 0, "C-5", 0) // legato: on the first copy the first time, on the second when the loop comes round
            b.note(ch, 8, "E-5", second)
            b.note(ch, 12, "D-5", 0) // the loop's last instrument: the second copy
        }
        return a to b
    }

    /** 2A03: renamed identical copies on both pulses ([copyPatterns]), the triangle and noise alongside. */
    fun copies(): Song {
        val base = NesSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val lead = NesInstrument("Lead", volume = NesEnvelope(listOf(13, 11, 9, 8), loop = 3), duty = NesEnvelope(listOf(2, 1), loop = 1))
        bank[0] = lead
        bank[1] = lead.copy(name = "Lead, renamed")
        bank[2] = lead.copy(name = "Lead, quieter", volume = NesEnvelope(listOf(10, 9, 8, 7), loop = 3))
        bank[3] = NesInstrument("Bass")
        bank[4] = NesInstrument("Noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val (a, b) = copyPatterns(NesSystem.channels.size, listOf(0, 1), 1, 2, 3)
        a.note(2, 0, "C-3", 4)
        b.note(2, 0, "G-2", 4)
        for (r in 0 until 16 step 8) a.note(3, r, "C-3", 5)
        return base.copy(
            title = "Copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /** VRC6: renamed identical copies on VRC6 Pulse 1 and the saw ([copyPatterns]), 2A03 Pulse 1 alongside. */
    fun vrc6Copies(): Song {
        val base = NesVrc6System.blank()
        val bank = base.nes.instruments.toMutableList()
        val vrc6 = NesChip.VRC6
        val pulse = NesInstrument("VRC6 lead", volume = NesEnvelope(listOf(13, 11, 9, 8), loop = 3),
            duty = NesEnvelope(listOf(6, 3), loop = 1), chip = vrc6)
        bank[0] = pulse
        bank[1] = pulse.copy(name = "VRC6 lead, renamed")
        bank[2] = pulse.copy(name = "VRC6 lead, quieter", volume = NesEnvelope(listOf(10, 9, 8, 7), loop = 3))
        val saw = NesInstrument("Saw", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), chip = vrc6, sawVolume = Vrc6SawVolume.FULL)
        bank[3] = saw
        bank[4] = saw.copy(name = "Saw, renamed")
        bank[5] = saw.copy(name = "Saw, half", sawVolume = Vrc6SawVolume.HALF)
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val channels = NesVrc6System.channels.size
        val (a, b) = copyPatterns(channels, listOf(NesRules.VRC6_PULSE1), 1, 2, 3)
        val (sa, sb) = copyPatterns(channels, listOf(NesRules.VRC6_SAW), 4, 5, 6)
        for (r in 0 until 16) {
            a.tracks[NesRules.VRC6_SAW][r] = sa.tracks[NesRules.VRC6_SAW][r]
            b.tracks[NesRules.VRC6_SAW][r] = sb.tracks[NesRules.VRC6_SAW][r]
        }
        a.note(0, 0, "E-4", 7)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "VRC6 copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }
}
