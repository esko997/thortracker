package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.EpsmPatch
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.nes
import org.thortracker.system.nes.DpcmMapping
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.DpcmStarterKit

/**
 * Songs whose FamiStudio text export FamiStudio 4.5.3 itself turned into engine data (tools/audio/nes_golden.py):
 * the fixtures in src/jvmTest/resources/nes-golden. Between them they use every NES effect, OFF as release and stop,
 * legato, identical and differing instruments, custom pattern lengths, speed changes on several channels, parked
 * orders and envelope edge cases; the VRC6 ones its pulses' eight duties, the saw's master volumes and slides; the
 * EPSM ones its SSG (mixer, noise, hardware envelopes, Ixx/Jyy), FM (algorithms, pans, the LFO, release, slides) and
 * rhythm channels. The EPSM songs hold no two identical EPSM sounds (FamiStudio's export merges those, and an FM sound
 * it does not reload would not write its patch registers again).
 */
object NesGoldenSongs {
    val ALL: Map<String, () -> Song> = linkedMapOf(
        "demo" to NesSystem::demo,
        "effects" to ::effects,
        "envelopes" to ::envelopes,
        "dpcm" to ::dpcm,
        "vrc6_demo" to NesVrc6System::demo,
        "vrc6_effects" to ::vrc6Effects,
        "epsm_demo" to NesEpsmSystem::demo,
        "epsm_effects" to ::epsmEffects,
        "epsm_rhythm" to ::epsmRhythm,
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

    private val EPSM = NesChip.EPSM
    private const val SSG1 = NesRules.EPSM_SSG1
    private const val SSG2 = NesRules.EPSM_SSG1 + 1
    private const val SSG3 = NesRules.EPSM_SSG1 + 2
    private const val FM1 = NesRules.EPSM_FM1
    private const val EPSM_CHANNELS = NesRules.EPSM_RHYTHM1 + 6

    private fun op(mul: Int, tl: Int, ar: Int = 31, d1r: Int = 0, sl: Int = 0, rr: Int = 7, dt: Int = 0, ks: Int = 0,
                   d2r: Int = 0, am: Boolean = false, ssgEg: Int = 0) =
        YmOperator(dt = dt, mul = mul, tl = tl, ks = ks, ar = ar, am = am, d1r = d1r, d2r = d2r, sl = sl, rr = rr, ssgEg = ssgEg)

    /**
     * EPSM SSG and FM: SSG tone, noise and tone+noise mixers with noise envelopes, hardware envelopes with auto period
     * (saw) and manual period (4000: FamiStudio's text keeps no other), Ixx/Jyy on SSG 2 and 3 (moved to SSG 1), volume on
     * an envelope sound, release and stop; FM algorithms 0, 2, 4, 5 and 7, feedback, left-only pan, SSG-EG, the LFO with
     * AMS/PMS, FM release (key off) and stop, slides up and down, vibrato, fine pitch, 0xy and instrument arpeggios,
     * pitch envelopes, volume and volume slides, delays, cuts, legato, the range ends C-1 and B-7, the speed on FM, and a
     * 2A03 pulse alongside.
     */
    fun epsmEffects(): Song {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("SSG lead", volume = NesEnvelope(listOf(15, 13, 12, 11), loop = 3), chip = EPSM)
        bank[1] = NesInstrument("SSG hat", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)), mixer = NesEnvelope(listOf(1)),
            noise = NesEnvelope(listOf(4, 8, 12)), chip = EPSM)
        bank[2] = NesInstrument("SSG snare", volume = NesEnvelope(listOf(15, 12, 9, 6, 3, 0)), mixer = NesEnvelope(listOf(0, 0, 2), loop = 2),
            noise = NesEnvelope(listOf(20)), chip = EPSM)
        bank[3] = NesInstrument("SSG env auto", chip = EPSM, epsmEnvelope = S5bEnvelope(shape = S5bShape.SAW_DOWN, autoPitch = true, octave = 4, period = 4000))
        bank[4] = NesInstrument("SSG env manual", chip = EPSM, epsmEnvelope = S5bEnvelope(shape = S5bShape.TRIANGLE, autoPitch = false, period = 4000))
        bank[5] = NesInstrument("SSG release", volume = NesEnvelope(listOf(15, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), chip = EPSM)
        bank[6] = NesInstrument("FM bass", chip = EPSM, epsm = EpsmPatch(algorithm = 0, feedback = 5, operators = listOf(
            op(1, 30, d1r = 8, sl = 4), op(2, 38, d1r = 6, sl = 3), op(1, 28, d1r = 5, sl = 2), op(1, 0, d1r = 4, sl = 2, rr = 9))))
        bank[7] = NesInstrument("FM bell", chip = EPSM, volume = NesEnvelope(listOf(15, 14, 13, 12, 11, 10, 6, 3, 0), loop = 4, release = 6),
            epsm = EpsmPatch(algorithm = 4, feedback = 3, operators = listOf(
            op(7, 36, d1r = 10, sl = 8, dt = 3), op(1, 0, d1r = 7, sl = 6, rr = 5), op(14, 40, d1r = 12, sl = 9), op(2, 4, d1r = 9, sl = 7, rr = 6))))
        bank[8] = NesInstrument("FM organ left", chip = EPSM, epsm = EpsmPatch(algorithm = 7, right = false, operators = listOf(
            op(1, 8), op(2, 14), op(4, 20), op(8, 26))))
        bank[9] = NesInstrument("FM vibrato", chip = EPSM, epsm = EpsmPatch(algorithm = 2, feedback = 4, ams = 2, pms = 4, operators = listOf(
            op(1, 34, am = true), op(3, 40), op(1, 30), op(1, 2, am = true, ssgEg = 0x0E))))
        bank[10] = NesInstrument("FM arp", arpeggio = NesEnvelope(listOf(0, 0, 12, 12), loop = 0), chip = EPSM,
            epsm = EpsmPatch(algorithm = 5, feedback = 6, operators = listOf(op(1, 28), op(1, 6), op(2, 8), op(1, 10))))
        bank[11] = NesInstrument("FM pitch", pitch = NesEnvelope(listOf(-2, -1, 0, 1, 2), loop = 0), pitchRelative = true, chip = EPSM,
            epsm = EpsmPatch(algorithm = 4, feedback = 1, left = true, right = true, operators = listOf(op(3, 32), op(1, 2), op(5, 44), op(1, 6))))
        bank[12] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        val fm2 = FM1 + 1
        val fm3 = FM1 + 2
        val fm4 = FM1 + 3
        val fm5 = FM1 + 4
        val fm6 = FM1 + 5
        val a = P("EPSM A", channels = EPSM_CHANNELS)
        // SSG 1: a lead with legato, volume, release and stop.
        a.note(SSG1, 0, "C-4", 1)
        a.note(SSG1, 4, "E-4", 0) // legato
        a.note(SSG1, 8, "G-4", 1, NesRules.FX_VOLUME, 0x09)
        a.note(SSG1, 12, "A-4", 6) // release sound
        a.off(SSG1, 16) // release
        a.off(SSG1, 18) // already released: stop
        a.note(SSG1, 20, "C-5", 1, NesRules.FX_SLIDE_UP, 0x05)
        a.note(SSG1, 24, "B-7", 1) // the SSG's highest
        a.note(SSG1, 28, "C-1", 1) // its lowest
        a.note(SSG1, 32, "D-4", 1, NesRules.FX_FINE_PITCH, 0x90)
        a.note(SSG1, 34, "D-4", 1) // fine pitch reset
        a.note(SSG1, 36, "F-4", 1, NesRules.FX_VIBRATO, 0x46)
        a.fx(SSG1, 40, NesRules.FX_VIBRATO, 0x00)
        a.note(SSG1, 44, "G-4", 1, NesRules.FX_ARPEGGIO, 0x37)
        a.note(SSG1, 48, "A-4", 1, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(SSG1, 52, "A-4", 1, NesRules.FX_NOTE_CUT, 0x03)
        a.note(SSG1, 56, "E-4", 1, NesRules.FX_VOLUME_SLIDE, 0xF4)
        // SSG 2: noise hats with Ixx on some rows (they move to SSG 1); SSG 3: snare, then the envelope sounds.
        for (r in 0 until 64 step 4) a.note(SSG2, r, "C-5", 2)
        a.fx(SSG2, 8, NesRules.FX_ENV_PERIOD_HI, 0x0A)
        a.note(SSG3, 0, "C-4", 3)
        a.note(SSG3, 8, "C-3", 4) // auto-period saw
        a.note(SSG3, 12, "G-3", 4, NesRules.FX_VOLUME, 0x08) // volume on an envelope sound
        a.note(SSG3, 16, "C-3", 5) // manual period 4000
        a.fx(SSG3, 20, NesRules.FX_ENV_PERIOD_LO, 0x40) // a lone Jyy: period 0040
        a.note(SSG3, 24, "E-3", 5) // legato-free reload: the manual period again
        a.fx(SSG3, 28, NesRules.FX_ENV_PERIOD_HI, 0x02)
        a.fx(SSG2, 28, NesRules.FX_ENV_PERIOD_LO, 0x80) // one row, two squares: period 0280
        a.note(SSG3, 32, "G-3", 0) // legato keeps the effect's period
        a.note(SSG3, 40, "C-3", 5) // a new attack reloads 4000
        a.off(SSG3, 48)
        a.note(SSG3, 52, "C-4", 3)
        // FM 1: bass with slides, release (key off), stop, the range ends.
        a.note(FM1, 0, "C-2", 7)
        a.note(FM1, 4, "C-2", 7, NesRules.FX_SLIDE_UP, 0x07)
        a.note(FM1, 8, "G-2", 7, NesRules.FX_SLIDE_DOWN, 0x0C)
        a.off(FM1, 12) // FM release: key off
        a.off(FM1, 14) // already released: stop
        a.note(FM1, 16, "C-1", 7) // FM's lowest
        a.note(FM1, 20, "B-7", 7) // FM's highest
        a.note(FM1, 24, "A-1", 7, NesRules.FX_SPEED, 0x05)
        a.note(FM1, 32, "D-2", 0) // legato
        a.note(FM1, 40, "F-2", 7, NesRules.FX_FINE_PITCH, 0x70)
        a.fx(FM1, 42, NesRules.FX_FINE_PITCH, 0xA0) // bend on an empty row
        a.note(FM1, 44, "F-2", 7)
        a.fx(FM1, 48, NesRules.FX_SPEED, 0x06)
        a.note(FM1, 52, "C-2", 7, NesRules.FX_NOTE_CUT, 0x02)
        // FM 2: bell with a volume-release, vibrato, delays.
        a.note(fm2, 0, "E-5", 8)
        a.off(fm2, 6)
        a.note(fm2, 8, "G-5", 8, NesRules.FX_VIBRATO, 0x3A)
        a.note(fm2, 16, "C-6", 8, NesRules.FX_NOTE_DELAY, 0x03)
        a.fx(fm2, 20, NesRules.FX_VIBRATO, 0x00)
        a.off(fm2, 24, NesRules.FX_NOTE_DELAY, 0x01)
        a.note(fm2, 32, "A-5", 8, NesRules.FX_VOLUME_SLIDE, 0xF2)
        a.off(fm2, 44)
        // FM 3: left-only organ, volume and a cut; FM 4: the LFO sound; FM 5: arpeggios; FM 6: pitch envelope.
        a.note(fm3, 0, "C-4", 9, NesRules.FX_VOLUME, 0x0B)
        a.note(fm3, 16, "E-4", 9, NesRules.FX_NOTE_CUT, 0x04)
        a.note(fm3, 32, "G-4", 9)
        a.fx(fm3, 36, NesRules.FX_VOLUME, 0x05)
        a.off(fm3, 48)
        a.note(fm4, 0, "A-3", 10)
        a.note(fm4, 24, "D-4", 10, NesRules.FX_SLIDE_UP, 0x02)
        a.off(fm4, 40)
        a.note(fm5, 0, "C-4", 11)
        a.note(fm5, 16, "C-4", 11, NesRules.FX_ARPEGGIO, 0x47)
        a.note(fm5, 32, "C-4", 11) // back to the instrument's arpeggio
        a.off(fm5, 48)
        a.note(fm6, 8, "G-3", 12)
        a.note(fm6, 24, "A-3", 12, NesRules.FX_SLIDE_DOWN, 0x03)
        a.off(fm6, 40)
        // 2A03 alongside.
        a.note(0, 0, "C-5", 13)
        a.note(0, 32, "G-4", 13)
        a.off(0, 56)
        val b = P("EPSM B", length = 32, channels = EPSM_CHANNELS)
        b.note(SSG1, 0, "A-4", 0) // the loop enters legato on SSG 1's last sound
        b.note(SSG2, 0, "C-5", 2)
        b.note(SSG3, 0, "A-2", 4)
        b.off(SSG3, 16)
        b.note(FM1, 0, "A-1", 7)
        b.note(FM1, 16, "E-2", 0)
        b.note(fm2, 0, "C-6", 8)
        b.note(fm4, 8, "E-4", 10)
        b.note(0, 0, "A-4", 13)
        return base.copy(
            title = "EPSM effects", artist = "Golden", ticksPerRow = 4, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1, 0),
            sound = base.nes.copy(instruments = bank, epsmLfo = NesSound.EPSM_LFO_ON or 3),
        )
    }

    /**
     * EPSM rhythm: all six drums with both, left-only and right-only pans, first volumes of 15 down to 6, Cxx and Axy,
     * delays, cuts, OFF (a stop: drums have no release), notes without an instrument and with another drum sound, the
     * speed set on a drum channel, and FM and SSG notes around them.
     */
    fun epsmRhythm(): Song {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Kick", chip = EPSM)
        bank[1] = NesInstrument("Snare left", volume = NesEnvelope(listOf(13)), chip = EPSM, epsm = EpsmPatch(right = false))
        bank[2] = NesInstrument("Hat right", volume = NesEnvelope(listOf(10)), chip = EPSM, epsm = EpsmPatch(left = false))
        bank[3] = NesInstrument("Tom soft", volume = NesEnvelope(listOf(6)), chip = EPSM)
        bank[4] = NesInstrument("Rim", volume = NesEnvelope(listOf(12)), chip = EPSM)
        bank[5] = NesInstrument("Cymbal both", volume = NesEnvelope(listOf(14)), chip = EPSM)
        bank[6] = NesInstrument("FM bass", chip = EPSM, epsm = EpsmPatch(algorithm = 1, feedback = 6, operators = listOf(
            op(1, 34, d1r = 6, sl = 3), op(1, 36, d1r = 5, sl = 2), op(2, 30, d1r = 7, sl = 4), op(1, 0, d1r = 3, sl = 2, rr = 8))))
        bank[7] = NesInstrument("SSG chord", volume = NesEnvelope(listOf(11, 10, 9, 8), loop = 3), chip = EPSM)
        val kick = NesRules.EPSM_RHYTHM1
        val snare = kick + 1
        val cymbal = kick + 2
        val hat = kick + 3
        val tom = kick + 4
        val rim = kick + 5
        val a = P("Groove", channels = EPSM_CHANNELS)
        for (r in 0 until 64 step 8) a.note(kick, r, "C-4", 1)
        a.note(kick, 12, "C-4", 0) // no instrument: the same drum, no attack in FamiStudio's sense
        for (r in 4 until 64 step 8) a.note(snare, r, "D-4", 2)
        a.fx(snare, 20, NesRules.FX_VOLUME, 0x08)
        a.note(snare, 28, "D-4", 2, NesRules.FX_NOTE_DELAY, 0x02)
        for (r in 0 until 64 step 2) a.note(hat, r, "F#4", 3)
        a.fx(hat, 16, NesRules.FX_VOLUME_SLIDE, 0xF4)
        a.note(hat, 33, "F#4", 3, NesRules.FX_NOTE_CUT, 0x01)
        a.note(tom, 30, "A-3", 4)
        a.note(tom, 31, "E-3", 4, NesRules.FX_VOLUME, 0x0F)
        a.off(tom, 40)
        a.note(rim, 10, "C#4", 5)
        a.note(rim, 26, "C#4", 4) // another drum sound on the rim channel
        a.fx(rim, 32, NesRules.FX_SPEED, 0x05)
        a.note(cymbal, 0, "C-5", 6)
        a.note(cymbal, 48, "C-5", 6, NesRules.FX_VOLUME, 0x0A)
        a.note(FM1, 0, "A-1", 7)
        a.note(FM1, 16, "F-1", 7)
        a.note(FM1, 32, "C-2", 7)
        a.off(FM1, 60)
        a.note(SSG1, 0, "A-4", 8)
        a.note(SSG2, 0, "C-5", 8)
        a.note(SSG3, 0, "E-5", 8)
        a.off(SSG3, 32)
        val b = P("Fill", length = 16, channels = EPSM_CHANNELS)
        for (r in 0 until 16 step 2) b.note(tom, r, "A-3", 4, NesRules.FX_VOLUME, 15 - r / 2)
        b.note(kick, 0, "C-4", 1)
        b.note(cymbal, 15, "C-5", 6)
        return base.copy(
            title = "EPSM rhythm", artist = "Golden", ticksPerRow = 6, loopOrder = 0,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }
}
