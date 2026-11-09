package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.FdsMasterVolume
import org.thortracker.system.nes.FdsPresets
import org.thortracker.system.nes.FdsSound
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesFdsSystem
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.WavePreset
import org.thortracker.system.nes.nes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The NES + FDS variant's rules, lowering and exports, against FamiStudio 4.5.3's sources (`FamitoneMusicFile`,
 * `Envelope.BuildFdsModulationTable`, `Envelope.SetFromPreset`, `Instrument.BuildWaveformsAndWaveIndexEnvelope`,
 * `Channel.CanDisableAttack`, `NesApu` note tables, `NsfFile`), its engine (`famistudio_set_fds_instrument`,
 * `famistudio_update_fds_channel_sound`) and the NESdev "FDS audio" page.
 */
class NesFdsTest {
    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        return name.substring(2).toInt() * 12 + index
    }

    private val fdsChannel = NesRules.FDS

    /** An FDS song: [sounds] in slots 1.., patterns built by [cells] (one per entry) on 6 tracks, played in order. */
    private fun song(sounds: List<NesInstrument>, vararg cells: (Array<Array<Cell>>) -> Unit): Song {
        val base = NesFdsSystem.blank()
        val bank = base.nes.instruments.toMutableList()
        sounds.forEachIndexed { i, s -> bank[i] = s }
        val patterns = cells.mapIndexed { i, fill ->
            val tracks = Array(NesExpansion.FDS.channels) { Array(Songs.ROWS) { Cell.BLANK } }
            fill(tracks)
            Pattern("FDS $i", tracks.map { it.toList() })
        }
        return base.copy(patterns = patterns, orders = patterns.indices.toList(), sound = base.nes.copy(instruments = bank))
    }

    private fun fds(name: String = "FDS", sound: FdsSound = FdsSound(), volume: NesEnvelope = NesEnvelope(listOf(12))) =
        NesInstrument(name, volume = volume, chip = NesChip.FDS, fds = sound)

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)


    @Test
    fun theVariantHasItsOwnChannelBuildAndEffects() {
        // H, I, J and the FDS's phase reset exist only on the FDS variant, and only on its FDS channel.
        val fdsCodes = listOf(NesRules.FX_FDS_MOD_DEPTH, NesRules.FX_FDS_MOD_SPEED_HI, NesRules.FX_FDS_MOD_SPEED_LO, NesRules.FX_PHASE_RESET)
        assertTrue(NesFdsSystem.effects.map { it.code }.containsAll(fdsCodes))
        assertEquals(NesFdsSystem.effects.map { it.code }, NesFdsSystem.effects.map { it.code }.sorted())
        val onFds = NesFdsSystem.effectsFor(fdsChannel).map { it.code }
        assertTrue(onFds.containsAll(fdsCodes) && NesRules.FX_DUTY !in onFds && NesRules.FX_FINE_PITCH in onFds && NesRules.FX_DELTA_COUNTER !in onFds)
        assertTrue(NesFdsSystem.effectsFor(0).none { it.code in fdsCodes })
        assertTrue(NesSystem.effects.none { it.code in fdsCodes })
        assertNull(NesRules.effect(NesExpansion.NONE, NesRules.FX_FDS_MOD_DEPTH))
        // I and J are expansion-specific: the FDS's mod speed in FDS songs, the 5B's envelope period elsewhere.
        assertTrue(NesRules.effect(NesExpansion.n163(4), NesRules.FX_FDS_MOD_SPEED_HI) != NesRules.effect(NesExpansion.FDS, NesRules.FX_FDS_MOD_SPEED_HI))
        assertNotNull(NesRules.effect(NesExpansion.FDS, NesRules.FX_FDS_MOD_SPEED_LO))
        // The N163's list is unchanged: its X is its own.
        assertTrue(NesN163System.of(4).effects.none { it.code == NesRules.FX_FDS_MOD_DEPTH })
    }

    /**
     * True pitch: FamiStudio's FDS table is f × 2^20 / CPU and the chip plays CPU × frequency / 2^22 per 64-step wave
     * (NESdev "FDS audio"), so the FDS channel plays FamiStudio's note 24 higher: A-4 is engine note 70, 439.9 Hz.
     */
    @Test
    fun notesAreTruePitchUpToGSharp6() {
        assertEquals(70, NesRules.engineNote(NesExpansion.FDS, fdsChannel, n("A-4")))
        assertEquals(46, NesRules.engineNote(NesExpansion.FDS, 0, n("A-4")))
        assertEquals(0, NesRules.engineNoteShift(NesExpansion.n163(4), NesRules.N163_FIRST))
        val hz = 1789773.0 * NesRules.fdsFrequency(70) / 4194304.0
        assertTrue(kotlin.math.abs(1200 * kotlin.math.log2(hz / 440.0)) < 1.0, "A-4 plays $hz Hz")
        // The table is FamiStudio's: round(f × 65536 / (CPU / 16)) at FamiStudio's note pitch.
        for (note in 1..96) {
            val f = 440.0 / 13.454340859610068739450573644169 * Math.pow(2.0, (note - 1) / 12.0)
            assertEquals((f * 65536.0 / (1789773 / 16.0) + 0.5).toInt(), FamiStudioLowering.NOTE_TABLE_FDS[note], "note $note")
            assertEquals((f * 65536.0 / (1662607 / 16.0) + 0.5).toInt(), FamiStudioLowering.NOTE_TABLE_FDS_PAL[note], "PAL note $note")
        }
        // Engine note 93 is the last within 12 bits: G#6.
        assertTrue(FamiStudioLowering.NOTE_TABLE_FDS[93] <= 0xFFF && FamiStudioLowering.NOTE_TABLE_FDS[94] > 0xFFF)
        assertEquals(n("G#6"), NesRules.highestNote(NesExpansion.FDS, fdsChannel))
        assertEquals(n("C-1"), NesRules.lowestNote(NesExpansion.FDS, fdsChannel))
        assertEquals(n("B-8"), NesRules.highestNote(NesExpansion.FDS, 0))
        val ok = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("G#6"), 1); t[fdsChannel][4] = Cell(n("C-1"), 1) })
        assertEquals(emptyList(), NesFdsSystem.errors(ok))
        assertEquals(93, FsSong.of(ok).notes[fdsChannel][0]!!.value)
        val high = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("A-6"), 1) })
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(high) }
        // Slides and arpeggios may not end above it either.
        val slide = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("E-6"), 1, NesRules.FX_SLIDE_UP, 5) })
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(slide) }
        val arp = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("E-6"), 1, NesRules.FX_ARPEGGIO, 0x37) })
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(arp) }
    }

    /** Pxx means one pitch on every channel: on the FDS the nearest frequency step at the engine note, up for an upward Pxx. */
    @Test
    fun finePitchConvertsToTheNearestFdsStep() {
        val engine = NesRules.engineNote(NesExpansion.FDS, fdsChannel, n("A-4"))
        val pulse = n("A-4") - NesRules.NOTE_OFFSET
        val up = NesRules.fdsFinePitch(engine, 4)
        val down = NesRules.fdsFinePitch(engine, -4)
        assertTrue(up > 0 && down < 0, "$up $down")
        val p = FamiStudioLowering.NOTE_TABLE[pulse] + 1.0
        val want = p / (p - 4)
        val f = NesRules.fdsFrequency(engine).toDouble()
        val stepCents = 1200 * kotlin.math.log2((f + 1) / f)
        assertTrue(kotlin.math.abs(1200 * kotlin.math.log2((f + up) / f / want)) <= stepCents / 2 + 1e-9)
        assertEquals(0, NesRules.fdsFinePitch(engine, 0))
        // The cell's Pxx and the audition agree.
        val s = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("A-4"), 1, NesRules.FX_FINE_PITCH, 0x84) })
        assertEquals(up, FsSong.of(s).notes[fdsChannel][0]!!.finePitch)
        assertEquals(up, NesFdsSystem.engine.fine(fdsChannel, n("A-4"), 4))
    }

    /**
     * `Envelope.BuildFdsModulationTable` worked by hand: entry 0 resets; 2 from 0 takes +1 (×2 = 2); 2 again stays (0, the
     * first of the equals); 5 from 2 ties +1 (4) and +2 (6) and keeps the first; −64 from 4 takes −4 (−8 → −4); 0 resets.
     */
    @Test
    fun modulationEncodingIsFamiStudios() {
        val targets = listOf(0, 2, 2, 5, -64, 0) + List(26) { 0 }
        val table = FdsSound.encodeModulation(targets)
        assertEquals(listOf(4, 1, 0, 1, 5, 4) + List(26) { 4 }, table)
        // Each entry applies twice (NESdev: the table is clocked twice per entry), the counter wrapping in 7 bits.
        assertEquals(listOf(0, 2, 2, 4, -4, 0), FdsSound.modTableCurve(table).take(6))
        // Steps that would leave −64..63 are skipped: 63 from 60 takes +1 (62), not +2 (64).
        val edge = FdsSound.encodeModulation(listOf(0, 60, 60, 60, 60, 60, 60, 60, 60, 63))
        assertEquals(1, edge.last())
        // FamiStudio's flat table: every entry a reset.
        assertEquals(List(32) { 4 }, FdsSound().modTable)
        assertContentEquals(ByteArray(32) { 4 }, FamiStudioEnvelopes.fdsModBytes(FdsSound()))
    }

    /** `Envelope.SetFromPreset` with FamiStudio's float lerp and round-half-to-even, at 64 samples 0..63 and 32 targets −64..63. */
    @Test
    fun presetsAreFamiStudios() {
        val sine = FdsPresets.SINE
        assertEquals(64, sine.size)
        assertEquals(32, sine[0]) // 31.5 rounds to even
        assertEquals(0, sine[16])
        assertEquals(63, sine[48])
        assertEquals(List(32) { 0 } + List(32) { 63 }, FdsPresets.wave(WavePreset.SQUARE50, 0, 1))
        val mod = FdsPresets.modulation(WavePreset.SINE)
        assertEquals(listOf(0, -64, 63), listOf(mod[0], mod[8], mod[24]))
        assertEquals(List(32) { 0 }, FdsPresets.FLAT_MOD)
        assertEquals(WavePreset.SINE, FdsPresets.presetOf(listOf(sine)))
        assertNull(FdsPresets.presetOf(listOf(List(64) { (it * 7) % 64 })))
    }

    /** Ixx/Jxx set one half of the 12-bit speed; the other is the channel's at that row along the played order. */
    @Test
    fun modSpeedHalvesResolveAlongThePlayedOrder() {
        val manual = fds("Manual", FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modSpeed = 0x123, modDepth = 8))
        val auto = fds("Auto", FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modSpeed = 0x050, modDepth = 8, autoMod = true))
        val s = song(listOf(manual, auto), { t ->
            t[fdsChannel][0] = Cell(n("C-4"), 1)
            t[fdsChannel][2] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_LO, 0x45)
            t[fdsChannel][4] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_HI, 0x07)
            t[fdsChannel][8] = Cell(n("C-4"), 1) // the attack loads the sound's $123 again
            t[fdsChannel][10] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_HI, 0x02)
            t[fdsChannel][12] = Cell(n("C-4"), 2) // auto-mod leaves the engine's speed as it was
        }, { t ->
            t[fdsChannel][0] = Cell(Cell.EMPTY, 0, NesRules.FX_FDS_MOD_SPEED_LO, 0x11) // across the order
            t[fdsChannel][4] = Cell(n("D-4"), 1, NesRules.FX_FDS_MOD_SPEED_HI, 0x0F) // on the attack: the sound's low byte
        })
        assertEquals(emptyList(), NesFdsSystem.errors(s))
        val notes = FsSong.of(s).notes[fdsChannel]
        assertEquals(0x145, notes[2]!!.fdsModSpeed)
        assertEquals(0x745, notes[4]!!.fdsModSpeed)
        assertEquals(0x223, notes[10]!!.fdsModSpeed)
        assertEquals(0x211, notes[64]!!.fdsModSpeed)
        assertEquals(0xF23, notes[68]!!.fdsModSpeed)
        // On the auto-mod sound's own row it is an error.
        val own = song(listOf(auto), { t -> t[fdsChannel][0] = Cell(n("C-4"), 1, NesRules.FX_FDS_MOD_SPEED_HI, 0x01) })
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(own) }
    }


    /** OFF releases (`$59`: volume and wave-index envelopes) a sound with a volume or wave release point, else stops. */
    @Test
    fun offReleasesOrStops() {
        val waves = fds("Waves", FdsSound(waves = listOf(FdsPresets.SINE, FdsPresets.wave(WavePreset.SQUARE50, 0, 1)), repeat = listOf(1, 1),
            loop = 0, release = 1))
        val plain = fds("Plain")
        val s = song(listOf(waves, plain), { t ->
            t[fdsChannel][0] = Cell(n("C-4"), 1, NesRules.FX_PHASE_RESET, 0)
            t[fdsChannel][4] = Cell(Cell.CUT, 0)
            t[fdsChannel][8] = Cell(n("C-4"), 2)
            t[fdsChannel][12] = Cell(Cell.CUT, 0)
        })
        assertEquals(emptyList(), NesFdsSystem.errors(s))
        val fs = FsSong.of(s)
        assertEquals(FsNote.RELEASE, fs.notes[fdsChannel][4]!!.value)
        assertEquals(FsNote.STOP, fs.notes[fdsChannel][12]!!.value)
        assertTrue(fs.notes[fdsChannel][0]!!.phaseReset)
    }

    /** `CanDisableAttack`: FDS sounds continue without an attack when all envelopes match, waves and mod table included. */
    @Test
    fun legatoNeedsIdenticalEnvelopes() {
        val a = fds("A", FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modDepth = 8, modSpeed = 20))
        val deeper = a.copy(name = "B", fds = a.fds.copy(modDepth = 30, masterVolume = FdsMasterVolume.HALF))
        assertTrue(FamiStudioEnvelopes.allIdentical(a, deeper)) // depth, speed and master volume are not envelopes
        assertFalse(FamiStudioEnvelopes.allIdentical(a, a.copy(fds = a.fds.copy(modulation = FdsPresets.FLAT_MOD))))
        assertFalse(FamiStudioEnvelopes.allIdentical(a, a.copy(fds = a.fds.copy(waves = listOf(FdsPresets.wave(WavePreset.SAWTOOTH, 0, 1))))))
        assertFalse(FamiStudioEnvelopes.allIdentical(a, a.copy(fds = a.fds.copy(repeat = listOf(2)))))
    }

    /** `OutputInstruments` for an FDS instrument, byte by byte, and the wave-index envelope, waves and table it points at. */
    @Test
    fun expansionEntryIsFamiStudios() {
        val manual = fds("Manual", FdsSound(waves = listOf(FdsPresets.SINE, FdsPresets.SINE, FdsPresets.wave(WavePreset.TRIANGLE, 0, 1)),
            repeat = listOf(2, 1, 3), loop = 1, release = 2, modulation = FdsPresets.modulation(WavePreset.SINE), modSpeed = 0x345,
            modDepth = 0x21, modDelay = 7, masterVolume = FdsMasterVolume.HALF), volume = NesEnvelope(listOf(12, 10, 8), loop = 1, release = 2))
        val auto = fds("Auto", FdsSound(modulation = FdsPresets.modulation(WavePreset.SINE), modDepth = 1, autoMod = true, autoModNumer = 6,
            autoModDenom = 4))
        val s = song(listOf(manual, auto), { t -> t[fdsChannel][0] = Cell(n("C-4"), 1); t[fdsChannel][8] = Cell(n("C-4"), 2) })
        assertEquals(emptyList(), NesFdsSystem.errors(s))
        val lowered = FamiStudioLowering.lower(s)
        val data = lowered.data
        val base = lowered.player.songBase
        val exp = u16(data, 3) - base
        fun b(at: Int) = data[at].toInt() and 0xff
        assertEquals(0x86, b(exp + 8)) // depth $21 << 2 | master volume 2 (50%)
        assertEquals(listOf(0x45, 0x03, 7), listOf(b(exp + 13), b(exp + 14), b(exp + 15)))
        val second = exp + FamiStudioLowering.EXP_INSTRUMENT_SIZE
        assertEquals(listOf(0x80 or 3, 2, 0), listOf(b(second + 13), b(second + 14), b(second + 15))) // 6/4 reduced to 3/2
        // Two distinct waves (the second sine is the first), the wave-index envelope 0 0 0 1 1 1 with loop and release.
        val table = u16(data, exp + 9) - base
        val w0 = u16(data, table) - base
        val w1 = u16(data, table + 2) - base
        assertEquals(FdsPresets.SINE, data.copyOfRange(w0, w0 + 64).map { it.toInt() })
        assertEquals(FdsPresets.wave(WavePreset.TRIANGLE, 0, 1), data.copyOfRange(w1, w1 + 64).map { it.toInt() })
        assertContentEquals(intArrayOf(0, 0, 1), FamiStudioEnvelopes.fdsWaves(manual.fds).index)
        val env = u16(data, exp + 6) - base
        val want = FamiStudioEnvelopes.waveIndex(manual.fds)
        assertContentEquals(want, data.copyOfRange(env, env + want.size))
        // values 0 0 0 1 1 1, loop at 2, release at 3: release header 6, C0 C0 C0 (the loop at the third), $00 3 (back to
        // it until released), then C1 C1 C1 and $00 8 (the last value held).
        assertContentEquals(byteArrayOf(0x06, 0xC0.toByte(), 0xC0.toByte(), 0xC0.toByte(), 0x00, 0x03, 0xC1.toByte(), 0xC1.toByte(),
            0xC1.toByte(), 0x00, 0x08), want)
        // Both sounds share the sine modulation table; the 2A03 slots carry well-formed default data.
        assertEquals(u16(data, exp + 11), u16(data, second + 11))
        val mod = u16(data, exp + 11) - base
        assertEquals(manual.fds.modTable, data.copyOfRange(mod, mod + 32).map { it.toInt() })
        val slot3 = exp + 2 * FamiStudioLowering.EXP_INSTRUMENT_SIZE
        assertEquals(0, b(slot3 + 8))
        // The wave-index envelopes sit at 256 + slot, effect envelopes from 320.
        val dir = lowered.envelopes.associateBy { it.index }
        assertEquals(FamiStudioEnvelopes.Kind.WAVE, dir.getValue(256).kind)
        assertTrue(lowered.envelopes.filter { it.kind != FamiStudioEnvelopes.Kind.WAVE && it.index >= 256 }.all { it.index >= 320 })
    }

    @Test
    fun exportRejectsInvalidFdsData() {
        val two = listOf(FdsPresets.SINE, FdsPresets.SINE)
        val invalid = listOf(
            FdsSound(waves = two, repeat = listOf(1, 1), release = 1),
            FdsSound(waves = two, repeat = listOf(1, 1), loop = 1, release = 1),
            FdsSound(waves = List(17) { FdsPresets.SINE }, repeat = List(17) { 1 }),
            FdsSound(waves = listOf(List(64) { 64 })),
            FdsSound(waves = listOf(List(32) { 0 })),
            FdsSound(repeat = listOf(16)),
            FdsSound(modulation = List(31) { 0 }),
            FdsSound(modulation = List(32) { 64 }),
            FdsSound(modSpeed = 4096),
            FdsSound(modDepth = 64),
            FdsSound(modDelay = 256),
            FdsSound(autoModNumer = 33),
        )
        for (sound in invalid) {
            val s = song(listOf(fds(sound = sound)), { t -> t[fdsChannel][0] = Cell(n("C-4"), 1) })
            assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(s) }
        }
        val stray = song(listOf(NesInstrument("2A03", fds = FdsSound(modDepth = 3))), { _ -> })
        assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(stray) }
        val invalidCells = listOf(
            0 to Cell(n("C-4"), 1),
            fdsChannel to Cell(n("C-4"), 2),
            fdsChannel to Cell(n("C-4"), 1, NesRules.FX_FDS_MOD_DEPTH, 0x40),
            fdsChannel to Cell(n("C-4"), 1, NesRules.FX_FDS_MOD_SPEED_HI, 0x10),
            fdsChannel to Cell(n("C-4"), 1, NesRules.FX_DUTY, 1),
            0 to Cell(n("C-4"), 2, NesRules.FX_FDS_MOD_DEPTH, 0x10),
            0 to Cell(n("C-4"), 2, NesRules.FX_PHASE_RESET, 0),
        )
        for ((channel, cell) in invalidCells) {
            val s = song(listOf(fds(), NesInstrument("Pulse")), { t -> t[channel][0] = cell })
            assertFailsWith<IllegalArgumentException> { FamiStudioLowering.lower(s) }
        }
    }

    @Test
    fun autoModOverflowStartsAtTheHardwareBoundary() {
        // G#5: 1947 × 32 = 62304; A5: 2062 × 32 = 65984, beyond the 16-bit multiply.
        assertFalse(NesRules.fdsAutoModOverflows(81, 32))
        assertTrue(NesRules.fdsAutoModOverflows(82, 32))
        assertEquals(n("A-5"), NesRules.fdsLowestOverflowingNote(32))
        assertNull(NesRules.fdsLowestOverflowingNote(1))
    }


    /** The `.nsf` names the FDS (header `$7B` bit 2, FamiStudio's `NsfFile`) and carries the FDS player build. */
    @Test
    fun nsfNamesTheFds() {
        val s = song(listOf(fds()), { t -> t[fdsChannel][0] = Cell(n("C-4"), 1) })
        val nsf = NsfExporter.export(s)
        assertEquals(0x04, nsf[0x7b].toInt())
        assertEquals(NesPlayer.FDS.load, u16(nsf, 8))
    }

}
