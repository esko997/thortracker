package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.N163Preset
import org.thortracker.system.nes.N163Presets
import org.thortracker.system.nes.N163Wave
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesN163System
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.nes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The N163 variants' rules, lowering and exports, against FamiStudio 4.5.3's sources (`FamitoneMusicFile`,
 * `Instrument.BuildWaveformsAndWaveIndexEnvelope`, `Envelope.GetN163Waveform`, `ChannelStateN163`, `NesApu` note tables)
 * and the NESdev "Namco 163 audio" page: what the engine and the chip need, not FamiStudio's byte layout (the golden
 * test compares register traces with FamiStudio's own export).
 */
class NesN163Test {
    private fun n(name: String): Int {
        val index = listOf("C-", "C#", "D-", "D#", "E-", "F-", "F#", "G-", "G#", "A-", "A#", "B-").indexOf(name.substring(0, 2))
        return name.substring(2).toInt() * 12 + index
    }

    /** A [channels]-channel N163 song: [sounds] in slots 1.., one pattern built by [cells] on 5 + [channels] tracks. */
    private fun song(channels: Int, sounds: List<NesInstrument>, cells: (Array<Array<Cell>>) -> Unit): Song {
        val base = NesN163System.of(channels).blank()
        val bank = base.nes.instruments.toMutableList()
        sounds.forEachIndexed { i, s -> bank[i] = s }
        val tracks = Array(NesRules.APU_CHANNELS + channels) { Array(Songs.ROWS) { Cell.BLANK } }
        cells(tracks)
        return base.copy(patterns = listOf(Pattern("N163", tracks.map { it.toList() })), sound = base.nes.copy(instruments = bank))
    }

    private fun sine(position: Int = 0) =
        NesInstrument("Sine", chip = NesChip.N163, wave = N163Wave(waves = listOf(N163Presets.sine(16)), position = position))

    @Test
    fun eachChannelCountIsItsOwnVariantWithItsOwnBuild() {
        assertEquals((1..8).toList(), NesN163System.ALL.map { it.n163Channels })
        for (system in NesN163System.ALL) {
            val n = system.n163Channels
            assertEquals("nes-famistudio-n163-$n", system.id)
            assertEquals(system.id, system.engine.id)
            assertEquals(5 + n, system.channels.size)
            assertEquals((1..n).map { "N163 $it" }, system.channels.drop(5).map { it.name })
            assertEquals("NES", system.family)
            assertEquals("NES + N163", system.familyGroup)
            assertEquals(system, NesExpansion.n163(n).system)
            assertEquals(0x10, NesExpansion.n163(n).player.nsfChips)
            // The chip's RAM: 8 bytes of registers per channel, the rest for waves.
            assertEquals(256 - 16 * n, NesRules.n163WaveRam(n))
        }
        // Only the N163 variants offer the phase reset; the plain NES keeps its effect list.
        assertFalse(NesSystem.effects.any { it.code == NesRules.FX_PHASE_RESET })
        assertTrue(NesN163System.of(4).effects.any { it.code == NesRules.FX_PHASE_RESET })
        val wave = NesN163System.of(4).effectsFor(NesRules.N163_FIRST).map { it.code }
        assertTrue(NesRules.FX_PHASE_RESET in wave && NesRules.FX_DUTY !in wave && NesRules.FX_FINE_PITCH in wave)
        assertFalse(NesRules.FX_PHASE_RESET in NesN163System.of(4).effectsFor(0).map { it.code })
    }

    /** `NesApu.GetOrCreateNoteTableSet`: the N163 tables saturate at $FFFF with 4+ channels, so the top notes are refused. */
    @Test
    fun theNoteTableTopLimitsTheNotes() {
        assertEquals(NesRules.NOTE_COUNT, NesRules.n163HighestFsNote(1))
        assertEquals(NesRules.NOTE_COUNT, NesRules.n163HighestFsNote(3))
        assertEquals(94, NesRules.n163HighestFsNote(4))
        assertEquals(82, NesRules.n163HighestFsNote(8)) // A-7
        for (c in 1..8) {
            val table = N163NoteTables.NTSC[c - 1]
            assertTrue(table[NesRules.n163HighestFsNote(c)] < 0xFFFF)
            // Every playable note is higher than the one below it (no flat spot in the playable range).
            for (i in 2..NesRules.n163HighestFsNote(c)) assertTrue(table[i] > table[i - 1], "$c channels, note $i")
        }
        val s = song(8, listOf(sine())) { t ->
            t[5][0] = Cell(n("A-7"), 1, 0, 0)
            t[5][4] = Cell(n("A#7"), 1, 0, 0)
        }
        val errors = NesSystem.errors(s)
        assertEquals(1, errors.size, errors.toString())
        assertTrue(errors[0].contains("A#7") && errors[0].contains("8 N163 channels"), errors[0])
    }

    /** A wave of L samples plays 16 / L times the note's frequency (NESdev: f = CPU × freq / (15 × 65536 × L × c)). */
    @Test
    fun waveSizesTransposeByWholeOctavesOnlyAtPowersOfTwo() {
        assertEquals(0.0, NesRules.n163Transpose(16))
        assertEquals(-12.0, NesRules.n163Transpose(32))
        assertEquals(12.0, NesRules.n163Transpose(8))
        assertTrue(NesRules.n163InTune(4) && NesRules.n163InTune(64) && !NesRules.n163InTune(12) && !NesRules.n163InTune(24))
        // A4 with one channel: freq18 = table × 4; f = 1789773 × freq18 / (15 × 65536 × 16 × 1) is 440 Hz within the
        // table's truncation (2 cents at this size).
        val freq18 = N163NoteTables.NTSC[0][n("A-4") - NesRules.NOTE_OFFSET] * 4.0
        val hz = 1789773.0 * freq18 / (15.0 * 65536 * 16)
        assertTrue(kotlin.math.abs(1200 * kotlin.math.log2(hz / 440.0)) < 2.0, "A-4 plays $hz Hz")
    }

    /** `Envelope.GetN163Waveform`: two samples a byte, the even one in the low nibble (NESdev: little-endian nibbles). */
    @Test
    fun wavesPackLowNibbleFirst() {
        assertContentEquals(byteArrayOf(0x21, 0x43, 0xF0.toByte(), 0x0F), FamiStudioEnvelopes.n163WaveBytes(listOf(1, 2, 3, 4, 0, 15, 15, 0)))
    }



    /** Slides use the channel count's note table and drop its pitch shift (`GetShiftsForType`). */
    @Test
    fun slidesUseTheN163TableAndShift() {
        for (c in listOf(1, 2, 4, 8)) {
            val s = song(c, listOf(sine())) { t -> t[5][0] = Cell(n("C-4"), 1, NesRules.FX_SLIDE_UP, 12); t[5][16] = Cell(Cell.CUT, 0, 0, 0) }
            val lowered = FamiStudioLowering.lower(s)
            val bytes = lowered.data.map { it.toInt() and 0xff }
            val from = n("C-4") - NesRules.NOTE_OFFSET
            val i = (0 until bytes.size - 3).first { bytes[it] == 0x50 && bytes[it + 2] == from && bytes[it + 3] == from + 12 }
            val step = bytes[i + 1].toByte().toInt()
            val shift = NesRules.n163PitchShift(c)
            val frames = 16 * s.ticksPerRow
            fun delta(table: IntArray) = (table[from] - table[from + 12]) shr shift
            val expected = maxOf(
                kotlin.math.ceil(kotlin.math.abs(delta(N163NoteTables.NTSC[c - 1])).toFloat() / frames.toFloat()).toInt(),
                kotlin.math.ceil(kotlin.math.abs(delta(N163NoteTables.PAL[c - 1])).toFloat() / (frames * 125f / 150f)).toInt(),
            )
            assertEquals(expected.coerceAtMost(127), step, "$c channels")
        }
    }

    /** Pxx means one pitch on every channel: on the N163 the nearest frequency step, up for an upward Pxx. */
    @Test
    fun finePitchConvertsToTheNearestN163Step() {
        val note = n("A-4") - NesRules.NOTE_OFFSET
        for (c in 1..8) {
            val up = NesRules.n163FinePitch(note, 4, c)
            val down = NesRules.n163FinePitch(note, -4, c)
            assertTrue(up > 0 && down < 0, "$c channels: $up $down")
            val p = FamiStudioLowering.NOTE_TABLE[note] + 1.0
            val want = p / (p - 4)
            val f = N163NoteTables.NTSC[c - 1][note].toDouble()
            val got = (f + (up shl NesRules.n163PitchShift(c))) / f
            val stepCents = 1200 * kotlin.math.log2((f + (1 shl NesRules.n163PitchShift(c))) / f)
            assertTrue(kotlin.math.abs(1200 * kotlin.math.log2(got / want)) <= stepCents / 2 + 1e-9, "$c channels")
        }
        assertEquals(0, NesRules.n163FinePitch(note, 0, 4))
    }

    /** RAM the channel registers use is refused; overlapping sounds playing at once warn; identical shared waves do not. */
    @Test
    fun ramChecksExplainTheSharedMemory() {
        // With 8 channels waves end at sample 127: a 16-sample wave at 120 runs into N163 8's registers.
        val high = song(8, listOf(sine(120))) { t -> t[5][0] = Cell(n("C-4"), 1, 0, 0) }
        assertTrue(NesSystem.errors(high).any { it.contains("channel registers") && it.contains("0-127") }, NesSystem.errors(high).toString())
        assertEquals(emptyList(), NesSystem.errors(song(4, listOf(sine(120))) { t -> t[5][0] = Cell(n("C-4"), 1, 0, 0) }))
        // Two different sounds overlapping (0-15 and 8-23) on two channels at once.
        val other = NesInstrument("Saw", chip = NesChip.N163, wave = N163Wave(waves = listOf(N163Presets.wave(N163Preset.SAWTOOTH, 16, 0, 1)), position = 8))
        val clash = song(2, listOf(sine(0), other)) { t -> t[5][0] = Cell(n("C-4"), 1, 0, 0); t[6][2] = Cell(n("E-4"), 2, 0, 0) }
        assertEquals(emptyList(), NesSystem.errors(clash))
        val warning = NesSystem.warnings(clash).firstOrNull { it.contains("overwrites") }
        assertNotNull(warning)
        assertTrue(warning.contains("8-15"), warning)
        // Played one after the other (each stopped before the other starts, also across the loop) they do not clash; a
        // sound still sounding when the song loops back clashes with the loop's first note.
        val apart = song(2, listOf(sine(0), other)) { t ->
            t[5][0] = Cell(n("C-4"), 1, 0, 0); t[5][1] = Cell(Cell.CUT, 0, 0, 0)
            t[6][2] = Cell(n("E-4"), 2, 0, 0); t[6][3] = Cell(Cell.CUT, 0, 0, 0)
        }
        assertNull(NesSystem.warnings(apart).firstOrNull { it.contains("overwrites") })
        val held = song(2, listOf(sine(0), other)) { t ->
            t[5][0] = Cell(n("C-4"), 1, 0, 0); t[5][1] = Cell(Cell.CUT, 0, 0, 0); t[6][2] = Cell(n("E-4"), 2, 0, 0)
        }
        assertNotNull(NesSystem.warnings(held).firstOrNull { it.contains("overwrites") })
        // Identical single waves at one place share safely.
        val twin = song(2, listOf(sine(0), sine(0).copy(name = "Twin", volume = NesEnvelope(listOf(9))))) { t ->
            t[5][0] = Cell(n("C-4"), 1, 0, 0); t[6][2] = Cell(n("E-4"), 2, 0, 0)
        }
        assertNull(NesSystem.warnings(twin).firstOrNull { it.contains("overwrites") })
        // Off-key sizes warn; the help counts the RAM in use.
        val odd = NesInstrument("Odd", chip = NesChip.N163, wave = N163Wave(size = 24, waves = listOf(N163Presets.sine(24))))
        assertTrue(NesSystem.warnings(song(1, listOf(odd)) { t -> t[5][0] = Cell(n("C-4"), 1, 0, 0) }).any { it.contains("24-sample") })
        assertEquals(24, org.thortracker.system.nes.NesValidator.n163RamUsed(clash))
    }

    @Test
    fun nsfRejectsInvalidWaveSequences() {
        val two = listOf(N163Presets.sine(16), N163Presets.sine(16))
        val invalid = listOf(
            N163Wave(waves = two, repeat = listOf(1, 1), release = 1),
            N163Wave(waves = two, repeat = listOf(1, 1), loop = 1, release = 1),
            N163Wave(waves = two, repeat = listOf(1, 16)),
            N163Wave(size = 14, waves = listOf(List(14) { 8 })),
            N163Wave(position = 3),
            N163Wave(size = 32, waves = List(33) { N163Presets.sine(32) }, repeat = List(33) { 1 }),
        )
        for (wave in invalid) {
            val s = song(4, listOf(NesInstrument("W", chip = NesChip.N163, wave = wave))) { t ->
                t[5][0] = Cell(n("C-4"), 1, 0, 0)
            }
            assertFailsWith<IllegalArgumentException> { NsfExporter.export(s) }
        }
        val stray = song(4, listOf(NesInstrument("2A03", wave = N163Wave(position = 16)))) { _ -> }
        assertFailsWith<IllegalArgumentException> { NsfExporter.export(stray) }
    }

    /** FamiStudio's text names the channel count, the N163 instruments, both wave envelopes and the phase reset. */
    @Test
    fun textExportDescribesTheN163() {
        val seq = NesInstrument("Seq", chip = NesChip.N163,
            wave = N163Wave(size = 8, position = 8, waves = listOf(List(8) { it }, List(8) { 15 - it }), repeat = listOf(2, 3), loop = 0, release = 1))
        val text = FamiStudioText.text(song(3, listOf(seq)) { t -> t[5][0] = Cell(n("C-4"), 1, NesRules.FX_PHASE_RESET, 0) })
        assertTrue(text.lines()[0].contains("Expansions=\"N163\" NumN163Channels=\"3\""), text.lines()[0])
        assertTrue(text.contains("Expansion=\"N163\" N163WavePreset=\"Custom\" N163WaveSize=\"8\" N163WavePos=\"8\" N163WaveCount=\"2\""), text)
        assertTrue(text.contains("Envelope Type=\"N163Wave\" Length=\"16\" Loop=\"0\" Release=\"8\" Values=\"0,1,2,3,4,5,6,7,15,14,13,12,11,10,9,8\""), text)
        assertTrue(text.contains("Envelope Type=\"Repeat\" Length=\"2\" Loop=\"0\" Release=\"1\" Values=\"2,3\""), text)
        assertTrue(text.contains("Channel Type=\"N163Wave1\"") && text.contains("Channel Type=\"N163Wave3\""), text)
        assertTrue(text.contains("PhaseReset=\"1\""), text)
        assertFalse(text.contains("Envelope Type=\"DutyCycle\""), text)
    }

    /** The `.nsf` names the Namco 163 (header `$7B` bit 4) and carries the song's N163 player build. */
    @Test
    fun nsfNamesTheNamco163() {
        for (c in listOf(1, 8)) {
            val s = song(c, listOf(sine())) { t -> t[5][0] = Cell(n("C-4"), 1, 0, 0) }
            val nsf = NsfExporter.export(s)
            assertEquals(0x10, nsf[0x7b].toInt())
            val player = NesPlayer.N163[c - 1]
            assertContentEquals(player.bytes, nsf.copyOfRange(128, 128 + player.bytes.size))
        }
    }

    /** The engine's fine-pitch argument on an N163 channel is the N163 step (+ raises), on the 2A03 the period offset. */
    @Test
    fun auditionFineFollowsTheChannel() {
        val engine = NesN163System.of(4).engine
        val a4 = n("A-4")
        assertEquals(NesRules.n163FinePitch(a4 - NesRules.NOTE_OFFSET, 6, 4), engine.fine(NesRules.N163_FIRST, a4, 6))
        assertEquals(-6, engine.fine(0, a4, 6))
        val s = song(8, listOf(sine())) { _ -> }
        assertNotNull(NesN163System.of(8).engine.auditionProblem(s, NesRules.N163_FIRST, n("B-7"), 1, 0))
        assertNull(NesN163System.of(8).engine.auditionProblem(s, NesRules.N163_FIRST, n("A-7"), 1, 0))
        assertNotNull(NesN163System.of(8).engine.auditionProblem(s, 0, n("C-4"), 1, 0)) // an N163 sound on a 2A03 pulse
    }
}
