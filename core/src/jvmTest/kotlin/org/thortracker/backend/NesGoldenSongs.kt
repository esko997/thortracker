package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.FdsMasterVolume
import org.thortracker.system.nes.FdsPresets
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.WavePreset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.EpsmPatch
import org.thortracker.system.nes.NesEpsmSystem
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesSound
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.NesS5bSystem
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesVrc7System
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.Vrc7Patch
import org.thortracker.system.nes.Vrc7Presets
import org.thortracker.system.nes.Vrc7Sustain
import org.thortracker.system.ym2612.YmOperator
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
 * note table, pitch shift and engine build); the FDS ones wave sequences, manual and auto-mod modulation, mod delay,
 * master volumes, H/I/J and FamiStudio's re-bind after them, the phase reset and the FDS's true-pitch range; the 5B ones
 * its mixer and noise envelopes, every hardware envelope mode and the envelope period effects; the EPSM ones its SSG
 * (mixer, noise, hardware envelopes, Ixx/Jyy), FM (algorithms, pans, the LFO, release, slides) and rhythm channels. The
 * copies songs (2A03, VRC6, N163, 5B, EPSM) hold identical sounds under different names, which FamiStudio's export
 * merges into one instrument, with a legato loop entry across the copies. Every trace covers the 2A03's registers as
 * well as the expansion's.
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
        "fds_demo" to NesFdsSystem::demo,
        "fds_effects" to ::fdsEffects,
        "fds_copies" to { fdsCopies(false) },
        "fds_auto_copies" to { fdsCopies(true) },
        "s5b_demo" to NesS5bSystem::demo,
        "s5b_effects" to ::s5bEffects,
        "s5b_copies" to ::s5bCopies,
        "s5b_manual_copies" to ::s5bManualCopies,
        "epsm_demo" to NesEpsmSystem::demo,
        "epsm_effects" to ::epsmEffects,
        "epsm_rhythm" to ::epsmRhythm,
        "epsm_copies" to ::epsmCopies,
    )
    /**
     * Independently exported witnesses for intentional corrections, not trace-equality fixtures. The FDS one is
     * [fdsManualSpeedWitness]: FamiStudio's export of it is compared with our lowering of [fdsManualSpeeds].
     */
    val CORRECTIONS: Map<String, () -> Song> = mapOf(
        "vrc7_patch_restore" to ::vrc7PatchRestore,
        "fds_manual_speed_witness" to ::fdsManualSpeedWitness,
    )

    fun vrc7PatchRestore(): Song {
        val base = NesVrc7System.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Held lead", volume = NesEnvelope(listOf(15, 12, 9, 6), loop = 3),
            chip = NesChip.VRC7, fm = Vrc7Patch(Vrc7Presets.CUSTOM,
                listOf(0x21, 0x61, 0x1A, 0x07, 0xF0, 0x80, 0x0F, 0x26)))
        bank[1] = NesInstrument("Other patch", chip = NesChip.VRC7,
            fm = Vrc7Patch(Vrc7Presets.CUSTOM, listOf(0x11, 0x01, 0x8A, 0x47, 0xF9, 0xF5, 0x13, 0x26)))
        val fm1 = NesRules.VRC7_FM1
        val first = P("Mid-song legato", length = 32, channels = base.system.channels.size)
        first.note(fm1, 0, "A-4", 1)
        first.note(fm1 + 1, 8, "C-3", 2)
        first.note(fm1, 16, "C-5") // Restore lead without restarting its held envelope.
        val loop = P("Loop restoration", length = 16, channels = base.system.channels.size)
        loop.note(fm1, 0, "E-5") // The previous loop ended with the other channel's patch.
        loop.note(fm1 + 1, 8, "C-3", 2)
        return base.copy(patterns = listOf(first.build(), loop.build()), orders = listOf(0, 1),
            loopOrder = 1, sound = base.nes.copy(instruments = bank))
    }

    /**
     * FDS sounds whose manual mod speeds have a low byte of $80-$FF ($080, $181, $FFF) beside ones FamiStudio 4.5.3's
     * engine reads correctly from an instrument ($07F, auto-mod 3/2), with mod delays, note delays, legato and a loop.
     * FamiStudio's own export plays those three instruments as auto-mod (its engine takes bit 7 of the speed's low byte
     * for the auto-mod flag); Thor Tracker's FDS player plays every manual speed as itself. Not a golden fixture: its
     * upstream-correct equivalent is [fdsManualSpeedWitness].
     */
    fun fdsManualSpeeds(): Song = fdsManualSpeedSong(witness = false)

    /**
     * [fdsManualSpeeds] in a form FamiStudio's export plays correctly: the three sounds keep a speed whose low byte is
     * below $80 ($000, $101, $F00) and every note with an attack on them sets the intended speed with `Jxx` (`$57`, the
     * whole 12-bit `FdsModSpeed`, which the engine keeps over the sound's), so both play the same speeds frame by frame.
     */
    fun fdsManualSpeedWitness(): Song = fdsManualSpeedSong(witness = true)

    private fun fdsManualSpeedSong(witness: Boolean): Song {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val fds = NesChip.FDS
        val sine = FdsPresets.modulation(WavePreset.SINE)
        val triangle = FdsPresets.modulation(WavePreset.TRIANGLE)
        // The intended speed of slot s (1..3), the speed the witness's sound keeps, and the Jxx that makes up the rest.
        val speeds = listOf(0x080, 0x181, 0xFFF)
        val kept = listOf(0x000, 0x101, 0xF00)
        fun manual(s: Int) = if (witness) kept[s - 1] else speeds[s - 1]
        bank[0] = NesInstrument("Speed 080", volume = NesEnvelope(listOf(13, 12, 11), loop = 2), chip = fds,
            fds = FdsSound(modulation = sine, modSpeed = manual(1), modDepth = 0x20))
        bank[1] = NesInstrument("Speed 181", volume = NesEnvelope(listOf(12, 11), loop = 1), chip = fds,
            fds = FdsSound(waves = listOf(FdsPresets.wave(WavePreset.SAWTOOTH, 0, 1)), modulation = triangle,
                modSpeed = manual(2), modDepth = 0x18, modDelay = 3))
        bank[2] = NesInstrument("Speed FFF", volume = NesEnvelope(listOf(11)), chip = fds,
            fds = FdsSound(modulation = sine, modSpeed = manual(3), modDepth = 0x3F, modDelay = 1))
        bank[3] = NesInstrument("Speed 07F", volume = NesEnvelope(listOf(12, 10), loop = 1), chip = fds,
            fds = FdsSound(modulation = sine, modSpeed = 0x07F, modDepth = 0x10))
        bank[4] = NesInstrument("Auto 3/2", volume = NesEnvelope(listOf(12)), chip = fds,
            fds = FdsSound(modulation = triangle, modDepth = 0x14, autoMod = true, autoModNumer = 3, autoModDenom = 2))
        bank[5] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val f = NesRules.FDS
        val channels = NesFdsSystem.channels.size
        val a = P("Manual speeds A", length = 32, channels = channels)
        val b = P("Manual speeds B", length = 16, channels = channels)
        // An attacked note of slot s (1..3): in the witness with the Jxx that completes its intended speed.
        fun P.attack(row: Int, pitch: String, s: Int) =
            if (witness) note(f, row, pitch, s, NesRules.FX_FDS_MOD_SPEED_LO, speeds[s - 1] and 0xFF) else note(f, row, pitch, s)
        a.attack(0, "C-4", 1)
        a.note(f, 2, "D-4") // legato: the speed holds
        a.note(f, 4, "E-4", 4, NesRules.FX_NOTE_DELAY, 0x02) // the neighbouring speed $07F, delayed
        a.attack(8, "G-4", 2) // $181 with a 3-frame mod delay
        a.note(f, 12, "A-4", 5) // auto-mod between manual sounds
        a.attack(14, "A-4", 3) // $FFF, mod delay 1
        a.note(f, 18, "B-4") // legato
        a.note(f, 20, "C-5", 4, NesRules.FX_NOTE_DELAY, 0x01)
        a.attack(22, "E-4", 1)
        a.attack(24, "E-4", 2) // adjacent tagged sounds, attack after attack
        a.attack(26, "G-4", 3)
        a.off(f, 30)
        a.note(0, 0, "E-4", 6)
        a.note(0, 16, "G-4", 6)
        b.attack(0, "C-5", 2) // the loop order starts on $181; the song's end comes back here after $FFF
        b.note(f, 4, "D-5") // legato
        b.attack(8, "F-4", 1)
        b.attack(12, "A-4", 3) // the loop's last sound
        b.note(0, 0, "A-4", 6)
        return base.copy(
            title = if (witness) "FDS manual speeds (witness)" else "FDS manual speeds", artist = "Golden", ticksPerRow = 3,
            loopOrder = 1, patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }

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

    /** A loop's first legato note uses the original slot, but inherits the previous pass's modulation overrides. */
    fun fdsCopies(autoMod: Boolean): Song {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        bank[0] = NesInstrument("Original", chip = NesChip.FDS, volume = NesEnvelope(listOf(14)),
            fds = FdsSound(modulation = FdsPresets.modulation(WavePreset.TRIANGLE),
                modSpeed = 0x100, modDepth = 12, modDelay = 3, autoMod = autoMod, autoModNumer = 1, autoModDenom = 2))
        bank[1] = bank[0].copy(name = "Renamed copy")
        val f = NesRules.FDS
        val a = P("Intro", length = 8, channels = NesFdsSystem.channels.size)
        a.note(f, 0, "C-4", 1)
        if (!autoMod) {
            a.fx(f, 1, NesRules.FX_FDS_MOD_SPEED_HI, 1)
            a.fx(f, 2, NesRules.FX_FDS_MOD_SPEED_LO, 0x20)
        }
        a.fx(f, 3, NesRules.FX_FDS_MOD_DEPTH, 8)
        val b = P("Loop", length = 8, channels = NesFdsSystem.channels.size)
        b.note(f, 0, "C-5")
        b.note(f, 4, "E-5", 2)
        if (!autoMod) {
            b.fx(f, 5, NesRules.FX_FDS_MOD_SPEED_HI, 2)
            b.fx(f, 6, NesRules.FX_FDS_MOD_SPEED_LO, 0x60)
        }
        b.fx(f, 7, NesRules.FX_FDS_MOD_DEPTH, 24)
        return base.copy(title = if (autoMod) "FDS auto copies" else "FDS copies", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1), sound = base.nes.copy(instruments = bank))
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
     * 5B: mixer envelopes over all four settings with loops, noise envelopes, the hardware envelope with saw, triangle
     * and one-shot shapes at positive, zero and negative auto octaves and manual periods, `Jyy` alone on Square 2 (moved to
     * Square 1) and the reload of the manual period at Square 1's next note with attack, `Ixx` and `Jyy` combined from
     * Squares 3 and 2 on a note row (the note keeps it), volume and volume slides, vibrato, slides, arpeggio, fine pitch,
     * delays, cuts, OFF release and stop, legato between identical sounds, the 5B's lowest and highest notes (C-1, B-7:
     * FamiStudio's notes 13 and 96), the speed set on a 5B square, and 2A03 channels alongside. Orders C-E (the loop
     * from C) start with manual-period sounds the square already holds, which the lowering does not re-send (a load would
     * set the shared period FamiStudio's export leaves): order D's first note, delayed past its row, while Square 2's
     * manual sound holds the period; order E's delayed legato note after a `J55` (Square 2's next attack there reloads
     * its saw, FamiStudio's override rule); order C's first note, whose period is 900 on the first pass and 2000 looping.
     */
    fun s5bEffects(): Song {
        val base = NesS5bSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val s5b = NesChip.S5B
        bank[0] = NesInstrument("Tone lead", volume = NesEnvelope(listOf(13, 12, 11, 10), loop = 3), chip = s5b)
        bank[1] = NesInstrument("Release tone", volume = NesEnvelope(listOf(15, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), chip = s5b)
        bank[2] = NesInstrument("Noise snare", volume = NesEnvelope(listOf(14, 11, 9, 7, 5, 3, 1, 0)), chip = s5b,
            mixer = NesEnvelope(listOf(1, 1, 0, 0, 2)), noise = NesEnvelope(listOf(6, 9, 12, 15, 18)))
        bank[3] = NesInstrument("Mixer walk", volume = NesEnvelope(listOf(12)), chip = s5b,
            mixer = NesEnvelope(listOf(0, 0, 1, 1, 2, 2, 3, 3), loop = 0), noise = NesEnvelope(listOf(3, 5, 7), loop = 1))
        bank[4] = NesInstrument("Buzz saw", chip = s5b, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = true, octave = 4))
        bank[5] = NesInstrument("Buzz tri neg", chip = s5b, s5b = S5bEnvelope(S5bShape.TRIANGLE_UP, autoPitch = true, octave = -1))
        bank[6] = NesInstrument("Decay once", chip = s5b, s5b = S5bEnvelope(S5bShape.DECAY, autoPitch = true, octave = 0))
        bank[7] = NesInstrument("Manual tremolo", chip = s5b, s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 900))
        bank[8] = NesInstrument("Attack hold", chip = s5b, mixer = NesEnvelope(listOf(2, 0), loop = 1), noise = NesEnvelope(listOf(20)),
            s5b = S5bEnvelope(S5bShape.ATTACK, autoPitch = true, octave = 2))
        bank[9] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        bank[10] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        bank[11] = NesInstrument("Tone twin", volume = NesEnvelope(listOf(13, 12, 11, 10), loop = 3), chip = s5b)
        bank[12] = NesInstrument("Manual saw", chip = s5b, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = false, period = 2000))
        val sq1 = NesRules.S5B_SQUARE1
        val sq2 = sq1 + 1
        val sq3 = sq1 + 2
        val a = P("5B A", channels = 8)
        // Square 1: the manual period sound alone in the envelope generator (Squares 2 and 3 play none until row 32).
        a.note(sq1, 0, "C-4", 8)
        a.fx(sq2, 8, NesRules.FX_ENV_PERIOD_LO, 0x40) // period $0040 from Square 2, moved to Square 1
        a.note(sq1, 16, "E-4", 8) // attack after the effect: the sound's own 900 again
        a.note(sq1, 24, "G-4", 8)
        a.fx(sq3, 24, NesRules.FX_ENV_PERIOD_HI, 0x03) // with Square 2's J20 below: $0320, kept by this note
        a.fx(sq2, 24, NesRules.FX_ENV_PERIOD_LO, 0x20)
        a.note(sq1, 32, "C-5", 1, NesRules.FX_VIBRATO, 0x46)
        a.note(sq1, 36, "D-5", 12) // identical sound: legato allowed by FamiStudio's rule, attack kept here
        a.note(sq1, 38, "E-5", 0) // legato
        a.fx(sq1, 40, NesRules.FX_VIBRATO, 0x00)
        a.note(sq1, 42, "C-5", 1, NesRules.FX_SLIDE_UP, 0x07)
        a.note(sq1, 46, "G-5", 1, NesRules.FX_FINE_PITCH, 0x8C)
        a.note(sq1, 48, "A-4", 2, NesRules.FX_ARPEGGIO, 0x47)
        a.off(sq1, 52) // release
        a.off(sq1, 54) // already released: stop
        a.note(sq1, 56, "C-1", 1) // the lowest note
        a.note(sq1, 60, "B-7", 1, NesRules.FX_NOTE_CUT, 0x03) // the 5B's highest (FamiStudio's B-8 an octave down)
        // Square 2: the mixer walk with volume and a volume slide, then auto-period envelopes.
        a.note(sq2, 0, "G-3", 4, NesRules.FX_VOLUME, 0x09)
        a.note(sq2, 12, "B-3", 4, NesRules.FX_VOLUME_SLIDE, 0xF3)
        a.fx(sq2, 20, NesRules.FX_VOLUME, 0x0C)
        a.note(sq2, 32, "E-2", 5) // saw buzz, octave 4
        a.note(sq2, 40, "E-2", 5, NesRules.FX_SLIDE_DOWN, 0x05)
        a.note(sq2, 48, "A-2", 6) // triangle up, octave -1
        a.note(sq2, 56, "A-2", 6, NesRules.FX_NOTE_DELAY, 0x02)
        // Square 3: noise snare, the speed, a one-shot envelope, the attack-and-hold sound with noise.
        for (r in listOf(4, 12, 20, 28)) a.note(sq3, r, "D-5", 3)
        a.fx(sq3, 16, NesRules.FX_SPEED, 0x05)
        a.note(sq3, 36, "C-4", 7) // decay once, octave 0
        a.note(sq3, 44, "F-4", 9) // attack, hold, octave 2, tone then tone+noise
        a.fx(sq3, 50, NesRules.FX_SPEED, 0x04)
        a.note(sq3, 52, "F-4", 9, NesRules.FX_NOTE_CUT, 0x02)
        // 2A03 alongside.
        a.note(0, 0, "E-4", 10)
        a.note(0, 32, "G-4", 10, NesRules.FX_DUTY, 0x03)
        a.note(2, 0, "C-3", 10)
        a.off(2, 40)
        for (r in 0 until 64 step 8) a.note(3, r, "C-3", 11)
        val b = P("5B B", length = 32, channels = 8)
        b.note(sq1, 0, "E-5", 1)
        b.note(sq1, 16, "C-5", 1)
        b.off(sq1, 24)
        b.note(sq1, 28, "C-4", 8) // FamiStudio loads the manual sound: period 900 into order C
        b.note(sq2, 0, "A-2", 5)
        b.note(sq2, 16, "D-3", 4)
        b.note(sq3, 0, "D-5", 3)
        b.note(sq3, 8, "D-5", 3)
        b.note(0, 0, "A-4", 10)
        val c = P("5B C", length = 16, channels = 8)
        c.note(sq1, 0, "D-4", 8) // held sound: no load (FamiStudio's export has none); 900 first pass, 2000 looping
        c.note(sq2, 8, "E-3", 13) // the manual saw: period 2000
        c.note(sq3, 4, "D-5", 3) // a held sound without a manual period: re-sent, as on every channel
        c.note(0, 0, "D-4", 10)
        val d = P("5B D", length = 16, channels = 8)
        d.note(sq1, 0, "E-4", 8, NesRules.FX_NOTE_DELAY, 0x05) // held: Square 2's 2000 stays; delayed past the 4-frame row
        d.fx(sq1, 12, NesRules.FX_ENV_PERIOD_LO, 0x55) // period $0055, Square 1's override pending
        d.note(sq3, 0, "D-5", 3, NesRules.FX_NOTE_DELAY, 0x03) // held, re-sent, delayed
        val e = P("5B E", length = 16, channels = 8)
        e.note(sq1, 0, "G-4", 0, NesRules.FX_NOTE_DELAY, 0x02) // delayed legato: no reload, $0055 stays
        e.note(sq2, 8, "A-2", 13) // same sound, but the J55 is pending on Square 2 too: FamiStudio reloads it (2000)
        e.note(0, 0, "G-4", 10)
        return base.copy(
            title = "5B effects", artist = "Golden", ticksPerRow = 4, loopOrder = 2,
            patterns = listOf(a.build(), b.build(), c.build(), d.build(), e.build()), orders = listOf(0, 1, 2, 3, 4),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /**
     * VRC7: two custom sounds (and an identical twin of one) loading the chip's one custom patch in turn, also on the
     * same row, and built-in patches beside them; the song's release and stop sustain overrides with OFF as key-off
     * release ($56) and as stop, Sxx cuts and note delays; legato, slides, vibrato, Pxx (converted to frequency steps),
     * volume and volume slides, 0xy, pitch and arpeggio envelopes, the FM channels' lowest and a high note, the speed
     * set on an FM channel, and 2A03 channels alongside. The last custom-sound note before the song's end is FM 1's
     * (FM 1 holds that sound when the loop is entered), so FamiStudio's exporter and the lowering have the same
     * patch state at the loop. The two intentional export corrections have their own adversarial fixture.
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
        bank[3] = NesInstrument("SSG env auto", chip = EPSM, s5b = S5bEnvelope(shape = S5bShape.SAW_DOWN, autoPitch = true, octave = 4, period = 4000))
        bank[4] = NesInstrument("SSG env manual", chip = EPSM, s5b = S5bEnvelope(shape = S5bShape.TRIANGLE, autoPitch = false, period = 4000))
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
        fun wave(preset: WavePreset, size: Int) = N163Presets.wave(preset, size, 0, 1)
        bank[0] = NesInstrument("Sequence", volume = NesEnvelope(listOf(14, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), chip = n163,
            wave = N163Wave(waves = N163Presets.waves(WavePreset.PWM2, 16, 3), repeat = listOf(2, 3, 1), loop = 1, release = 2, position = 0))
        bank[1] = NesInstrument("Wave release", volume = NesEnvelope(listOf(11)), chip = n163,
            wave = N163Wave(waves = listOf(wave(WavePreset.SQUARE50, 16), wave(WavePreset.SINE, 16)), repeat = listOf(1, 1),
                loop = 0, release = 1, position = 16))
        bank[2] = NesInstrument("Big 32", volume = NesEnvelope(listOf(12, 11, 10), loop = 2), arpeggio = NesEnvelope(listOf(0, 12), loop = 0),
            chip = n163, wave = N163Wave(size = 32, waves = listOf(wave(WavePreset.TRIANGLE, 32)), position = 32))
        bank[3] = NesInstrument("Tiny 8", volume = NesEnvelope(listOf(10, 8, 6, 4)), pitch = NesEnvelope(listOf(0, 2, 4, 2), loop = 0),
            chip = n163, wave = N163Wave(size = 8, waves = listOf(wave(WavePreset.SAWTOOTH, 8)), position = 64))
        bank[4] = NesInstrument("Shared A", volume = NesEnvelope(listOf(9)), chip = n163,
            wave = N163Wave(waves = listOf(wave(WavePreset.SINE, 16)), position = 80))
        bank[5] = NesInstrument("Shared B", volume = NesEnvelope(listOf(13, 11, 9), loop = 2), chip = n163,
            wave = N163Wave(waves = listOf(wave(WavePreset.SINE, 16)), position = 80))
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
     * FDS: a wave sequence with repeats, a loop and a release (OFF: `$59`, the volume and wave-index envelopes release),
     * a sound that releases through its waves only, a stop, legato, manual modulation with a mod delay (and the delay of
     * 1, which enables modulation at once), auto-mod with a reduced ratio (6/4 = 3/2), all four master volumes, waves and
     * mod tables shared between sounds, Hxx/Ixx/Jxx with and without notes and FamiStudio's re-bind on the next attack
     * without them, an H override held into the next order's legato first note (the order's re-send repeats it), Jxx
     * while an auto-mod sound plays (no effect), the phase reset with and without a note, slides both ways (FDS table),
     * vibrato, fine pitch, volume and a volume slide, arpeggio, delays, a cut, the speed set on the FDS channel, the FDS's
     * lowest and highest notes (C-1, G#6), and 2A03 channels alongside.
     */
    fun fdsEffects(): Song {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val fds = NesChip.FDS
        val sine = FdsPresets.modulation(WavePreset.SINE)
        val triangle = FdsPresets.modulation(WavePreset.TRIANGLE)
        fun wave(preset: WavePreset) = FdsPresets.wave(preset, 0, 1)
        bank[0] = NesInstrument("Sequence", volume = NesEnvelope(listOf(14, 12, 10, 9, 8, 6, 4, 2, 0), loop = 3, release = 5), chip = fds,
            fds = FdsSound(waves = FdsPresets.waves(WavePreset.PWM2, 3), repeat = listOf(2, 3, 1), loop = 1, release = 2,
                modulation = sine, modSpeed = 0x020, modDepth = 0x08, modDelay = 10))
        bank[1] = NesInstrument("Wave release", volume = NesEnvelope(listOf(11)), chip = fds,
            fds = FdsSound(waves = listOf(wave(WavePreset.SQUARE50), FdsPresets.SINE), repeat = listOf(1, 1), loop = 0, release = 1,
                masterVolume = FdsMasterVolume.TWO_THIRDS))
        bank[2] = NesInstrument("Auto FM", volume = NesEnvelope(listOf(12, 11, 10), loop = 2), chip = fds,
            fds = FdsSound(modulation = triangle, modDepth = 0x14, autoMod = true, autoModNumer = 3, autoModDenom = 2,
                masterVolume = FdsMasterVolume.HALF))
        bank[3] = NesInstrument("Auto FM 6/4", volume = NesEnvelope(listOf(12, 11, 10), loop = 2), chip = fds,
            fds = FdsSound(modulation = triangle, modDepth = 0x24, autoMod = true, autoModNumer = 6, autoModDenom = 4,
                masterVolume = FdsMasterVolume.TWO_FIFTHS))
        bank[4] = NesInstrument("Manual high", volume = NesEnvelope(listOf(13, 12, 11), loop = 2), chip = fds,
            fds = FdsSound(waves = listOf(wave(WavePreset.SAWTOOTH)), modulation = FdsPresets.modulation(WavePreset.SQUARE50),
                modSpeed = 0x37F, modDepth = 0x3F, modDelay = 1))
        bank[5] = NesInstrument("Shared table", volume = NesEnvelope(listOf(10, 9, 8, 7), loop = 3), chip = fds,
            fds = FdsSound(modulation = sine, modSpeed = 0x100, modDepth = 0x02))
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(2)))
        bank[7] = NesInstrument("2A03 noise", volume = NesEnvelope(listOf(14, 9, 5, 2, 0)))
        val f = NesRules.FDS
        val channels = NesFdsSystem.channels.size
        val a = P("FDS A", channels = channels)
        a.note(f, 0, "C-4", 1) // the mod delay: 10 frames without modulation
        a.note(f, 4, "E-4", 0) // legato: the sequence continues
        a.off(f, 8) // release: volume and waves jump to their release points ($59)
        a.off(f, 12) // already released: stop
        a.note(f, 16, "E-4", 1, NesRules.FX_SLIDE_UP, 0x07)
        a.note(f, 20, "B-4", 1, NesRules.FX_SLIDE_DOWN, 0x05)
        a.note(f, 24, "D-5", 1, NesRules.FX_VIBRATO, 0x46)
        a.fx(f, 28, NesRules.FX_VIBRATO, 0x00)
        a.note(f, 30, "D-5", 1, NesRules.FX_FINE_PITCH, 0x8A)
        a.fx(f, 32, NesRules.FX_PHASE_RESET, 0x00) // without a note
        a.note(f, 33, "F-4", 2, NesRules.FX_PHASE_RESET, 0x00) // with a note: releases through its waves later
        a.off(f, 35)
        a.note(f, 36, "G-4", 5, NesRules.FX_FDS_MOD_DEPTH, 0x20) // the depth overridden on the attack
        a.fx(f, 37, NesRules.FX_FDS_MOD_SPEED_HI, 0x02) // $27F: the sound's low byte kept
        a.fx(f, 38, NesRules.FX_FDS_MOD_SPEED_LO, 0x10) // $210
        a.note(f, 40, "A-4", 5) // an attack without H/I/J: FamiStudio re-binds the sound (its depth and speed again)
        a.note(f, 42, "B-4", 0) // legato
        a.note(f, 44, "C-5", 3) // auto-mod 3/2
        a.note(f, 46, "C-5", 4) // auto-mod 6/4 = 3/2 written reduced, another depth and master volume
        a.note(f, 48, "C-1", 2) // the FDS's lowest note
        a.note(f, 50, "G#6", 2) // and its highest: FamiStudio's note 93
        a.note(f, 52, "E-4", 6, NesRules.FX_NOTE_DELAY, 0x02)
        a.note(f, 56, "E-4", 6, NesRules.FX_NOTE_CUT, 0x03)
        a.note(f, 58, "A-4", 3, NesRules.FX_VOLUME, 0x09)
        a.fx(f, 60, NesRules.FX_SPEED, 0x05) // the speed set on the FDS channel
        a.fx(f, 61, NesRules.FX_VOLUME_SLIDE, 0x3C)
        a.fx(f, 62, NesRules.FX_FDS_MOD_DEPTH, 0x10) // held into the next order's legato first note
        a.note(0, 0, "E-4", 7)
        a.note(0, 32, "G-4", 7)
        a.note(2, 0, "C-3", 7)
        a.off(2, 40)
        for (r in 0 until 64 step 8) a.note(3, r, "C-3", 8)
        val b = P("FDS B", length = 32, channels = channels)
        b.note(f, 0, "A-4", 0) // legato keeps the runtime H10 depth across the order boundary
        b.off(f, 4) // no release point anywhere: a stop
        b.note(f, 8, "E-5", 1, NesRules.FX_ARPEGGIO, 0x47)
        b.note(f, 12, "G-4", 5, NesRules.FX_FDS_MOD_SPEED_HI, 0x01) // $17F on the attack
        b.note(f, 16, "G-4", 5) // re-bind: $37F again
        b.note(f, 20, "B-4", 4)
        b.fx(f, 24, NesRules.FX_FDS_MOD_SPEED_LO, 0x40) // no effect: auto-mod computes the speed
        b.off(f, 28)
        b.note(0, 0, "A-4", 7)
        b.note(3, 0, "C-3", 8)
        return base.copy(
            title = "FDS effects", artist = "Golden", ticksPerRow = 4, loopOrder = 1,
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
            wave = N163Wave(waves = listOf(N163Presets.wave(WavePreset.SINE, 16, 0, 1)), position = 0))
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

    /**
     * 5B: renamed identical copies on 5B Square 1 (volume and a tone/off mixer envelope) and 5B Square 2 (an auto-period
     * saw buzz) ([copyPatterns]); the buzz's differing sound changes only its hardware envelope octave, which FamiStudio's
     * merge compares but its legato rule does not. 2A03 Pulse 1 alongside.
     */
    fun s5bCopies(): Song {
        val base = NesS5bSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val s5b = NesChip.S5B
        val lead = NesInstrument("5B lead", volume = NesEnvelope(listOf(13, 11, 9, 8), loop = 3), chip = s5b,
            mixer = NesEnvelope(listOf(2, 2, 3, 2), loop = 1))
        bank[0] = lead
        bank[1] = lead.copy(name = "5B lead, renamed")
        bank[2] = lead.copy(name = "5B lead, quieter", volume = NesEnvelope(listOf(10, 9, 8, 7), loop = 3))
        val buzz = NesInstrument("Buzz", chip = s5b, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = true, octave = 4))
        bank[3] = buzz
        bank[4] = buzz.copy(name = "Buzz, renamed")
        bank[5] = buzz.copy(name = "Buzz, octave 3", s5b = buzz.s5b.copy(octave = 3))
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val channels = NesS5bSystem.channels.size
        val sq1 = NesRules.S5B_SQUARE1
        val sq2 = sq1 + 1
        val (a, b) = copyPatterns(channels, listOf(sq1), 1, 2, 3)
        val (sa, sb) = copyPatterns(channels, listOf(sq2), 4, 5, 6)
        for (r in 0 until 16) {
            a.tracks[sq2][r] = sa.tracks[sq2][r]
            b.tracks[sq2][r] = sb.tracks[sq2][r]
        }
        a.note(0, 0, "E-4", 7)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "5B copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }

    /**
     * 5B: two manual-period sounds identical but for their names (one instrument in FamiStudio's merged export) on Square
     * 1 while another square's manual sound or an `Jyy` changes the shared envelope period. Order A: Square 2's manual saw
     * sets 2000, then Square 1 switches to the renamed copy with an attack (FamiStudio's export loads nothing there: the
     * same instrument after the merge, so 2000 stays), a `J40` from Square 3, a legato note (no reload). Order B (the
     * loop): the saw again, then the switch back to the first copy, and the loop's first note is legato on the other copy
     * (merged: no attack, no load). Each switch loads the copy's own slot in our streams (live edits follow the played
     * slot) with `$5F`, which keeps the shared period, so every frame must match FamiStudio's export, where the other
     * square's period stays.
     */
    fun s5bManualCopies(): Song {
        val base = NesS5bSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val s5b = NesChip.S5B
        val tremolo = NesInstrument("Manual tremolo", volume = NesEnvelope(listOf(13)), chip = s5b,
            s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 900))
        bank[0] = tremolo
        bank[1] = tremolo.copy(name = "Manual tremolo, renamed")
        bank[2] = NesInstrument("Manual saw", chip = s5b, s5b = S5bEnvelope(S5bShape.SAW_DOWN, autoPitch = false, period = 2000))
        bank[3] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val sq1 = NesRules.S5B_SQUARE1
        val sq2 = sq1 + 1
        val sq3 = sq1 + 2
        val a = P("Manual copies A", length = 16, channels = 8)
        a.note(sq1, 0, "C-4", 1)
        a.note(sq2, 4, "E-3", 3) // Square 2's manual saw: the shared period is 2000
        a.note(sq1, 8, "E-4", 2) // the renamed copy with an attack: FamiStudio loads nothing (one instrument)
        a.fx(sq3, 10, NesRules.FX_ENV_PERIOD_LO, 0x40) // period $0040, Square 1's override pending
        a.note(sq1, 12, "G-4") // legato: no reload, $0040 stays
        a.note(0, 0, "E-4", 4)
        val b = P("Manual copies B", length = 16, channels = 8)
        b.note(sq1, 0, "C-5") // legato on the renamed copy; looping, the first copy is held (merged: still no attack)
        b.note(sq2, 4, "D-3", 3) // the saw reloads 2000
        b.note(sq1, 8, "E-5", 1) // the pending J40 override forces this attack to reload the manual period, 900
        b.note(sq1, 12, "D-5") // legato; the loop's last instrument is the first copy
        b.note(0, 0, "A-4", 4)
        return base.copy(
            title = "5B manual copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
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

    /**
     * EPSM copies: an FM sound, a manual-period SSG sound (period 4000, the only one FamiStudio's text keeps) and a drum
     * sound, each with an identical copy under another name (one instrument each in FamiStudio's merged export). Order A
     * switches to each copy with an attack (FamiStudio's export loads nothing there) and plays a legato note; order B (the
     * loop) enters legato on the first copies while the second ones are held from the song's end (merged: no attack, no
     * load in FamiStudio's export). Our streams load each note's own slot: the FM copy rewrites its channel's patch
     * registers with the same values, the SSG copy goes through `$5F` with the keep-period bit. The trace comparison
     * drops unchanged EPSM patch-register writes on both sides, so every other write must match FamiStudio's export.
     */
    fun epsmCopies(): Song {
        val base = NesEpsmSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        val lead = NesInstrument("FM lead", volume = NesEnvelope(listOf(15, 13, 12), loop = 2), chip = EPSM,
            epsm = EpsmPatch(algorithm = 4, feedback = 5, operators = listOf(op(2, 30, d1r = 6, sl = 3), op(1, 4, d1r = 4, sl = 2),
                op(3, 36, d1r = 8, sl = 5), op(1, 6, d1r = 5, sl = 3, rr = 8))))
        val tremolo = NesInstrument("SSG tremolo", volume = NesEnvelope(listOf(13)), chip = EPSM,
            s5b = S5bEnvelope(S5bShape.TRIANGLE, autoPitch = false, period = 4000))
        val kick = NesInstrument("Kick", volume = NesEnvelope(listOf(14)), chip = EPSM)
        bank[0] = lead
        bank[1] = lead.copy(name = "FM lead, renamed")
        bank[2] = tremolo
        bank[3] = tremolo.copy(name = "SSG tremolo, renamed")
        bank[4] = kick
        bank[5] = kick.copy(name = "Kick, renamed")
        bank[6] = NesInstrument("2A03 lead", volume = NesEnvelope(listOf(12, 10, 8), loop = 2), duty = NesEnvelope(listOf(1)))
        val drum = NesRules.EPSM_RHYTHM1
        val a = P("EPSM copies A", length = 16, channels = EPSM_CHANNELS)
        a.note(FM1, 0, "C-4", 1)
        a.note(FM1, 4, "E-4", 2) // the renamed FM copy with an attack: FamiStudio loads nothing
        a.note(FM1, 8, "G-4") // legato
        a.note(SSG1, 0, "C-4", 3)
        a.note(SSG1, 4, "E-4", 4) // the renamed manual copy with an attack: FamiStudio loads nothing, the period stays
        a.note(SSG1, 8, "G-4") // legato
        a.note(drum, 0, "C-4", 5)
        a.note(drum, 8, "C-4", 6) // the renamed drum copy
        a.note(0, 0, "E-4", 7)
        val b = P("EPSM copies B", length = 16, channels = EPSM_CHANNELS)
        b.note(FM1, 0, "C-5") // legato; looping, the renamed copy is held (merged: still no attack)
        b.note(FM1, 8, "E-5", 1) // back to the first copy
        b.note(FM1, 12, "D-5") // legato; the loop's last instrument is the first copy
        b.note(SSG1, 0, "C-5")
        b.note(SSG1, 8, "E-5", 3)
        b.note(SSG1, 12, "D-5")
        b.note(drum, 4, "C-4", 5)
        b.note(0, 0, "A-4", 7)
        return base.copy(
            title = "EPSM copies", artist = "Golden", ticksPerRow = 3, loopOrder = 1,
            patterns = listOf(a.build(), b.build()), orders = listOf(0, 1),
            sound = base.nes.copy(instruments = bank),
        )
    }
}
