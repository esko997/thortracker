package org.thortracker.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.PokeyLowering
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.pokey.PokeyDemoSong
import org.thortracker.system.pokey.PokeyDistortion
import org.thortracker.system.pokey.PokeyInstrument
import org.thortracker.system.pokey.PokeyKind
import org.thortracker.system.pokey.PokeyMachine
import org.thortracker.system.pokey.PokeyPitch
import org.thortracker.system.pokey.PokeyPitch.Counter
import org.thortracker.system.pokey.PokeyPresets
import org.thortracker.system.pokey.PokeyRules
import org.thortracker.system.pokey.PokeySample
import org.thortracker.system.pokey.PokeySamples
import org.thortracker.system.pokey.PokeyStraightPlay
import org.thortracker.system.pokey.PokeySystem
import org.thortracker.system.pokey.PokeyValidator
import org.thortracker.system.pokey.pokey
import kotlin.math.abs

class PokeyModelTest {
    private fun with(song: Song, channel: Int, row: Int, cell: Cell, order: Int = 0): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun slot(name: String) = PokeyPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun withInstrument(song: Song, slot: Int, ins: PokeyInstrument?) = song.copy(sound = song.pokey.withInstrument(slot, ins))

    private val a4 = 57 * 64

    // ---- registry, model and project files ---------------------------------------------------------------

    @Test
    fun systemIsRegisteredWithItsShape() {
        assertEquals(PokeySystem, SoundSystems.of("atari-pokey"))
        assertEquals(listOf("POKEY 1", "POKEY 2", "POKEY 3", "POKEY 4"), PokeySystem.channels.map { it.name })
        assertTrue(PokeySystem.channels.all { it.tonal && it.bank == 0 && it.kind == ChannelKind.VOICE })
        assertEquals(0..107, PokeySystem.noteRange)
        assertEquals("A-4", PokeySystem.noteName(57))
        assertEquals(4, PokeySystem.effectColumns(3))
        assertEquals(65536, PokeySystem.engine.imageSize)
        assertEquals(12000, PokeySystem.engine.seekTicks)
        assertEquals("atari-pokey", PokeySystem.engine.id)
        assertEquals(listOf("vgm"), PokeySystem.exports.map { it.extension })
        assertEquals(1, PokeySystem.backendVersion)
        // One tick per video frame times multispeed, on the song's machine.
        val pal = PokeySystem.blank().let { it.copy(sound = it.pokey.copy(machine = PokeyMachine.PAL, multispeed = 3)) }
        assertEquals(1773447.5 / 35568 * 3, PokeySystem.ticksPerSecond(pal), 1e-9)
        assertEquals(315e6 / 176 / 29868, PokeySystem.ticksPerSecond(PokeySystem.blank()), 1e-9)
    }

    @Test
    fun songsRoundTripThroughProjectFiles() {
        val kind = withInstrument(PokeySystem.blank(), 30, PokeyInstrument(name = "switched", kind = PokeyKind.TONE, sample = 3,
            filterOffset = -5, partnerVolume = 4, difference = true, rate = 12000, loop = 3, pitchRelative = true,
            pitch = NesEnvelope(listOf(1, -2, 3), loop = 1, release = 2), distortion = NesEnvelope(listOf(6, 8), release = 1)))
        val songs = listOf(PokeySystem.blank(), PokeySystem.demo(), PokeyDemoSong.shortest(), kind,
            with(PokeySystem.blank(), 2, 5, Cell(60, 2).withEffect(1, PokeyRules.FX_CLOCK, 1).withEffect(3, PokeyRules.FX_FINE, 0x90)))
        for (song in songs) {
            val text = ProjectCodec.encode(song)
            assertTrue(text.contains("\"backend\":\"atari-pokey\",\"backendVersion\":1"))
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
        }
    }

    @Test
    fun presetsAreValidAndBlankSongsHaveThemAndNoSamples() {
        for (p in PokeyPresets.ALL) assertNull(p.name, PokeyValidator.instrumentProblem(p.instrument))
        val kinds = PokeyPresets.ALL.map { it.instrument.kind }.toSet()
        assertEquals(setOf(PokeyKind.TONE, PokeyKind.JOINED, PokeyKind.FILTERED, PokeyKind.JOINED_FILTERED), kinds)
        assertTrue(PokeyPresets.ALL.any { it.instrument.distortion.values == listOf(PokeyDistortion.VOLUME_ONLY) })
        assertTrue(PokeyPresets.ALL.any { it.instrument.kind.filtered && it.instrument.difference })
        val sound = PokeySystem.blank().pokey
        assertEquals(PokeyPresets.ALL.map { it.instrument }, sound.instruments.take(PokeyPresets.ALL.size))
        assertTrue(sound.instruments.drop(PokeyPresets.ALL.size).all { it == null })
        assertEquals(64, sound.instruments.size)
        assertTrue(sound.samples.isEmpty())
        assertEquals(emptyList<String>(), PokeySystem.errors(PokeySystem.blank()))
        val kit = PokeyPresets.drumKit(5)
        assertEquals(listOf(5, 6, 7, 8), kit.map { it.instrument.sample })
        assertTrue(kit.all { it.instrument.kind == PokeyKind.SAMPLE && it.instrument.loop == -1 && it.instrument.rate == PokeySamples.KIT_RATE })
    }

    // ---- the demo and the shortest song --------------------------------------------------------------------

    @Test
    fun demoAndShortestSongHaveNoErrorsOrWarnings() {
        val demo = PokeySystem.demo()
        assertEquals("Scanline Cruise", demo.title)
        assertEquals(PokeyMachine.NTSC, demo.pokey.machine)
        assertEquals(emptyList<String>(), PokeySystem.errors(demo))
        assertEquals(emptyList<String>(), PokeySystem.warnings(demo))
        // Every kind plays in it, and the chip-wide effects.
        val used = demo.patterns.flatMap { p -> p.tracks.flatten() }
        val kinds = used.filter { it.instrument > 0 }.mapNotNull { demo.pokey.instrument(it.instrument)?.kind }.toSet()
        assertTrue(kinds.containsAll(listOf(PokeyKind.TONE, PokeyKind.JOINED, PokeyKind.FILTERED, PokeyKind.SAMPLE)))
        val codes = used.flatMap { c -> (0..c.more.size).map { c.effectCode(it) to c.effectParam(it) } }.toSet()
        for (fx in listOf(PokeyRules.FX_CLOCK to 1, PokeyRules.FX_POLY to 1, PokeyRules.FX_TWO_TONE to 2, PokeyRules.FX_RESTART to 0)) {
            assertTrue("$fx", fx in codes)
        }
        assertEquals(PokeySamples.starterKit(), demo.pokey.samples)

        val shortest = PokeyDemoSong.shortest()
        assertEquals(emptyList<String>(), PokeySystem.errors(shortest))
        assertEquals(emptyList<String>(), PokeySystem.warnings(shortest))
        assertEquals(1, shortest.patterns.single().length)
        assertEquals(1, shortest.ticksPerRow)
        assertEquals(listOf(0), shortest.orders)
        assertEquals(Cell(57, 1), shortest.patterns[0].tracks[0][0])
    }

    // ---- the pitch search ---------------------------------------------------------------------------------

    @Test
    fun pitchSearchMatchesTheSpecificationsKnownValues() {
        // NTSC, 64 kHz, pure tone: A-4 -> AUDF 72 = 1789772.7 / (28 * 73 * 2) = 437.8 Hz.
        assertEquals(72, PokeyPitch.counter(PokeyMachine.NTSC, false, Counter.BASE_8, a4, PokeyDistortion.PURE))
        val lead = PokeyPresets.instrument("Pure lead")
        assertEquals(437.81, PokeyPitch.hz(PokeyMachine.NTSC, false, lead, 0, a4, PokeyDistortion.PURE), 0.01)
        assertEquals("437.8 Hz", PokeyPitch.hzText(PokeyPitch.hz(PokeyMachine.NTSC, false, lead, 0, a4, PokeyDistortion.PURE)))
        // PAL: 1773447.5 / 2 / 440 / 28 = 71.97 -> N = 71.
        assertEquals(71, PokeyPitch.counter(PokeyMachine.PAL, false, Counter.BASE_8, a4, PokeyDistortion.PURE))
        // 15 kHz: 2033.8 / 114 = 17.84 -> N = 17 (2052 cycles, 436.1 Hz).
        assertEquals(17, PokeyPitch.counter(PokeyMachine.NTSC, true, Counter.BASE_8, a4, PokeyDistortion.PURE))
        // 16-bit at 1.79 MHz: P = N + 7 nearest 2033.8 -> N = 2027.
        assertEquals(2027, PokeyPitch.counter(PokeyMachine.NTSC, false, Counter.FAST_16, a4, PokeyDistortion.PURE))
        // Distortion C (D 15, M 15) on C-2: Pt = 1824.2 cycles; 28 x 65 is not coprime to 15, so N + 1 = 64 (1792).
        assertEquals(63, PokeyPitch.counter(PokeyMachine.NTSC, false, Counter.BASE_8, 24 * 64, 6))
        // Difference at 1.79 MHz, d = 1: C/63 - C/64 = 443.9 Hz is nearest A-4 -> P = 63, N = 59.
        val saw = PokeyPresets.instrument("POKEY saw")
        assertEquals(59, PokeyPitch.differenceCounter(PokeyMachine.NTSC, false, Counter.FAST_8, a4, 1))
        assertEquals(59, PokeyPitch.chosen(PokeyMachine.NTSC, false, saw, 0, a4, PokeyDistortion.PURE))
        // Samples: 7990 levels/s at the root = 224 cycles = 28 x 8 -> N = 7; at 1.79 MHz on POKEY 1, N = 220.
        assertEquals(7, PokeyPitch.sampleCounter(PokeyMachine.NTSC, false, false, 7990, 48, 48 * 64))
        assertEquals(220, PokeyPitch.sampleCounter(PokeyMachine.NTSC, false, true, 7990, 48, 48 * 64))
        // An octave up asks for 112 cycles, below one scan line: the shortest allowed period, 140 cycles (N = 4).
        assertEquals(4, PokeyPitch.sampleCounter(PokeyMachine.NTSC, false, false, 7990, 48, 60 * 64))
    }

    @Test
    fun soundsPlayTruePitchAcrossKinds() {
        val machine = PokeyMachine.NTSC
        // Each sound over the notes its counter covers well: 8-bit 64 kHz counters reach down to ~125 Hz (B-2).
        for ((sound, notes) in listOf(
            (PokeyPresets.instrument("16-bit bass") to 1) to listOf(33, 45, 57, 69),
            (PokeyPresets.instrument("16-bit bass") to 3) to listOf(33, 45, 57, 69),
            (PokeyPresets.instrument("16-bit saw") to 1) to listOf(33, 45, 57),
            (PokeyPresets.instrument("Pure lead") to 2) to listOf(45, 57, 69),
            (PokeyPresets.instrument("Phasing lead") to 0) to listOf(45, 57, 69),
        )) {
            val (ins, lane) = sound
            for (note in notes) {
                val cents = PokeyPitch.cents(machine, false, ins, lane, note * 64, PokeyDistortion.PURE)
                assertTrue("${ins.name} ${PokeySystem.noteName(note)} on $lane: $cents", abs(cents) < 20)
            }
        }
        // 16-bit at 1.79 MHz is within a cent through the bass range.
        for (note in 24..60) {
            val c = PokeyPitch.cents(machine, false, PokeyPresets.instrument("16-bit bass"), 1, note * 64, PokeyDistortion.PURE)
            assertTrue("$note: $c", abs(c) < 1)
        }
        // A kind cannot play on a lane it does not use.
        assertTrue(PokeyPitch.hz(machine, false, PokeyPresets.instrument("16-bit bass"), 0, a4, PokeyDistortion.PURE).isNaN())
        // Fine pitch is the player's unit: 1/64 semitone.
        assertEquals(100.0 / 64, PokeySystem.fine.cents(57, 1), 1e-12)
    }

    // ---- validation ---------------------------------------------------------------------------------------

    @Test
    fun soundsOnChannelsTheirKindCannotUseAreErrors() {
        val blank = PokeySystem.blank()
        val bass16 = slot("16-bit bass")
        val e1 = PokeySystem.errors(with(blank, 0, 0, Cell(36, bass16)))
        assertTrue(e1.toString(), e1.single().contains("16-bit") && e1.single().contains("POKEY 2, POKEY 4"))
        // Fast 8-bit exists on POKEY 1 and 3 only.
        val e2 = PokeySystem.errors(with(blank, 1, 0, Cell(100, slot("Sync lead"))))
        assertTrue(e2.toString(), e2.single().contains("1.79 MHz"))
        // Samples never on POKEY 3; a note without a sound number inherits the lane's last one.
        val kit = blank.copy(sound = blank.pokey.copy(samples = PokeySamples.starterKit()).withInstrument(40, PokeyPresets.drumKit(0)[0].instrument))
        val e3 = PokeySystem.errors(with(with(kit, 2, 0, Cell(48, 40)), 2, 4, Cell(48, 0)))
        assertEquals(e3.toString(), 2, e3.size)
        assertTrue(e3[0].contains("timer 3"))
        assertTrue(e3[1].contains("last sound"))
        assertTrue(PokeySystem.errors(with(kit, 3, 0, Cell(48, 40))).isEmpty())
        // An empty slot, a missing sample, a loop past the sample's end.
        assertTrue(PokeySystem.errors(with(blank, 0, 0, Cell(48, 50))).single().contains("empty"))
        val missing = withInstrument(blank, 40, PokeyPresets.drumKit(2)[0].instrument)
        assertTrue(PokeySystem.errors(missing).single().contains("the song has 0 samples"))
        val pastLoop = kit.copy(sound = kit.pokey.withInstrument(40, kit.pokey.instrument(40)!!.copy(loop = 30000)))
        assertTrue(PokeySystem.errors(pastLoop).single().contains("past the end"))
    }

    @Test
    fun effectParametersMirrorTheLoader() {
        val blank = PokeySystem.blank()
        for ((fx, param) in listOf(
            PokeyRules.FX_CLOCK to 2, PokeyRules.FX_POLY to 2, PokeyRules.FX_TWO_TONE to 3, PokeyRules.FX_DISTORTION to 9,
            PokeyRules.FX_VOLUME to 0x10, PokeyRules.FX_SPEED to 0, PokeyRules.FX_RESTART to 1, PokeyRules.FX_TIMERS to 1,
            PokeyRules.FX_JUMP to 1,
        )) {
            val song = with(blank, 0, 3, Cell().withEffect(2, fx, param))
            assertEquals("${EffectSpec.charOf(fx)}$param", 1, PokeySystem.errors(song).size)
        }
        for ((fx, param) in listOf(PokeyRules.FX_CLOCK to 1, PokeyRules.FX_TWO_TONE to 2, PokeyRules.FX_DISTORTION to 8,
                PokeyRules.FX_FILTER to 0xFF, PokeyRules.FX_FINE to 0x00, PokeyRules.FX_RESTART to 0, PokeyRules.FX_TIMERS to 0)) {
            assertEquals(emptyList<String>(), PokeySystem.errors(with(blank, 0, 3, Cell().withEffect(0, fx, param))))
        }
        // Effects the POKEY does not have.
        assertNotNull(PokeySystem.cellProblem(blank, 0, Cell().withEffect(0, 8, 0x11)))
        assertNotNull(PokeySystem.cellProblem(blank, 0, Cell(108, 1)))
    }

    @Test
    fun fieldsTheKindDoesNotUseAreNotChecked() {
        val tone = PokeyInstrument(name = "kept", sample = 40, rate = 5, root = 200, loop = 99999, filterOffset = 900,
            partnerVolume = 99, partnerDistortion = 42, difference = true)
        assertNull(PokeyValidator.instrumentProblem(tone))
        val sample = PokeyInstrument(kind = PokeyKind.SAMPLE, volume = NesEnvelope(listOf(99)), distortion = NesEnvelope(listOf(77), loop = 9))
        assertNull(PokeyValidator.instrumentProblem(sample))
        // Release without a loop is the image's rule; on any envelope.
        assertNull(PokeyValidator.instrumentProblem(PokeyInstrument(arpeggio = NesEnvelope(listOf(0, 4, 7), release = 2))))
        assertNotNull(PokeyValidator.instrumentProblem(PokeyInstrument(pitch = NesEnvelope(listOf(0, 4, 7), loop = 2, release = 2))))
        assertNotNull(PokeyValidator.instrumentProblem(PokeyInstrument(kind = PokeyKind.FILTERED, difference = true, filterOffset = 0)))
        assertNotNull(PokeyValidator.instrumentProblem(PokeyInstrument(volume = NesEnvelope(List(257) { 1 }))))
        assertNotNull(PokeyValidator.instrumentProblem(PokeyInstrument(distortion = NesEnvelope(listOf(9)))))
        val song = withInstrument(PokeySystem.blank(), 60, tone)
        assertEquals(emptyList<String>(), PokeySystem.structuralErrors(song))
    }

    @Test
    fun warningsCoverCutsTheFifteenKilohertzClockAndUnreachableNotes() {
        val blank = PokeySystem.blank()
        // A held lead on POKEY 1, then a 16-bit bass on POKEY 2 takes POKEY 1.
        val cut = with(with(blank, 0, 0, Cell(57, slot("Pure lead"))), 1, 4, Cell(36, slot("16-bit bass")))
        assertTrue(PokeySystem.warnings(cut).toString(), PokeySystem.warnings(cut).any { it.contains("takes POKEY 1 and cuts POKEY 1") })
        // Released first: no warning.
        val released = with(cut, 0, 2, Cell(Cell.CUT))
        assertFalse(PokeySystem.warnings(released).any { it.contains("cuts") })
        // Distortion C on the 15 kHz clock.
        val buzz15 = with(with(blank, 3, 0, Cell(36, slot("Dist C bass"))), 2, 0, Cell().withEffect(0, PokeyRules.FX_CLOCK, 1))
        assertTrue(PokeySystem.warnings(buzz15).toString(), PokeySystem.warnings(buzz15).any { it.contains("15 kHz") })
        // C-0 on an 8-bit 64 kHz counter is far out of its range.
        val low = with(blank, 0, 0, Cell(0, slot("Pure lead")))
        assertTrue(PokeySystem.warnings(low).toString(), PokeySystem.warnings(low).any { it.contains("out of the counter's range") })
        // Hxx on a sound without high-pass.
        val h = with(blank, 0, 0, Cell(57, slot("Pure lead")).withEffect(0, PokeyRules.FX_FILTER, 2))
        assertTrue(PokeySystem.warnings(h).any { it.contains("high-pass sounds only") })
    }

    // ---- audition, shared state, samples, VGM -------------------------------------------------------------

    @Test
    fun auditionAcceptsWhatThePlayerKeysOn() {
        val blank = PokeySystem.blank()
        val engine = PokeySystem.engine
        assertNull(engine.auditionProblem(blank, 1, 36, slot("16-bit bass"), 0))
        assertNotNull(engine.auditionProblem(blank, 0, 36, slot("16-bit bass"), 0))
        assertNotNull(engine.auditionProblem(blank, 1, 100, slot("Sync lead"), 0))
        assertNotNull(engine.auditionProblem(blank, 0, 57, 50, 0))
        assertNotNull(engine.auditionProblem(blank, 0, 57, 1, 200))
        val sampled = blank.copy(sound = blank.pokey.copy(samples = listOf(PokeySample("s", List(64) { it % 16 })))
            .withInstrument(40, PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0)))
        assertNull(engine.auditionProblem(sampled, 0, 48, 40, 0))
        assertNull(engine.auditionProblem(sampled, 3, 48, 40, 0))
        assertNotNull(engine.auditionProblem(sampled, 2, 48, 40, 0))
        assertEquals(57, engine.note(blank, 0, 57, 1))
        assertEquals(-12, engine.fine(0, 57, -12))
    }

    @Test
    fun sharedStateShowsWhichLaneHoldsWhichChannel() {
        val blank = PokeySystem.blank()
        val song = with(with(blank, 1, 0, Cell(36, slot("16-bit bass"))), 2, 4, Cell(48, slot("Pure lead")).withEffect(0, PokeyRules.FX_CLOCK, 1))
        val atStart = PokeySystem.sharedState(song, 0, 0)!!
        assertEquals(listOf("held by 2", "16-bit 1+2", null, null), atStart.tags)
        assertTrue(atStart.lines[0].contains("64 kHz"))
        val later = PokeySystem.sharedState(song, 0, 4)!!
        assertTrue(later.lines[0].contains("15 kHz"))
        val hp = with(blank, 0, 0, Cell(57, slot("Phasing lead")))
        assertEquals(listOf("HP 1 by 3", null, "HP clock of 1", null), PokeySystem.sharedState(hp, 0, 0)!!.tags)
        // A note on POKEY 3 takes its channel back.
        val back = with(hp, 2, 8, Cell(60, slot("Pure lead")))
        assertEquals(listOf(null, null, null, null), PokeySystem.sharedState(back, 0, 8)!!.tags)
    }

    @Test
    fun stickyPortamentoKeysOnAfreshAfterAOneShotSampleEnds() {
        // As the native player and pokey_ref.py decide (pokey_machine_test.c test_porta_samples plays the same song):
        // 200 levels at 7990 Hz end ~25 ms after their note, 8000 levels last ~1 s, slot 62 loops, slot 63 is a tone
        // whose one-shot volume envelope ends at 0 (a synthesized voice stays held: portamento only retargets it).
        val levels = { n: Int -> List(n) { if (it % 2 == 0) 3 else 12 } }
        val samples = listOf(PokeySample("short", levels(200)), PokeySample("long", levels(8000)))
        var song = PokeySystem.blank().let { it.copy(sound = it.pokey.copy(samples = samples)) }
        song = withInstrument(song, 60, PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0))
        song = withInstrument(song, 61, PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 1))
        song = withInstrument(song, 62, PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0, loop = 0))
        song = withInstrument(song, 63, PokeyInstrument(volume = NesEnvelope(listOf(15, 8, 0))))
        for ((lane, slot) in listOf(0 to 60, 1 to 61, 2 to 63, 3 to 62)) {
            song = with(song, lane, 0, Cell(48, slot, PokeyRules.FX_PORTAMENTO, 0x10))
            song = with(song, lane, 8, Cell(52))
            song = with(song, lane, 16, Cell(55))
        }
        assertEquals(emptyList<String>(), PokeySystem.errors(song))
        // Ended one-shots key on afresh; a looping sample and an ended synthesized tone remain held.
        assertArrayEquals(intArrayOf(8, 0, 0, 0), PokeyStraightPlay.upTo(song, 0, 8).voiceRow)
        assertArrayEquals(intArrayOf(16, 16, 0, 0), PokeyStraightPlay.upTo(song, 0, 16).voiceRow)
    }

    private fun ownershipSong(length: Int = 8000, speed: Int = 6, note: Int = 48,
                              ins: PokeyInstrument = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0)): Song {
        var song = PokeySystem.blank().let {
            it.copy(ticksPerRow = speed, sound = it.pokey.copy(samples = listOf(PokeySample("ownership", List(length) { 12 }))))
        }
        song = withInstrument(song, 60, ins)
        song = withInstrument(song, 61, PokeyInstrument(kind = PokeyKind.JOINED))
        return with(song, 1, 0, Cell(note, 60))
    }

    @Test
    fun roundedSampleRateKeepsTheOldVoiceUntilItsActualEnd() {
        var song = ownershipSong(note = 96)
        song = with(song, 0, 0, Cell(48, 1))
        song = with(song, 1, 1, Cell(96, 61, PokeyRules.FX_PORTAMENTO, 0x10))
        song = with(song, 1, 7, Cell(96))
        // The requested 127840 levels/s is clamped to N=4, period 140 cycles. Row 1 still owns the
        // sample, so selecting the joined sound must NOT steal channel 1 until the row-7 key-on.
        val early = PokeyStraightPlay.upTo(song, 0, 1)
        assertEquals(60, early.voiceSlot[1])
        assertEquals(0, early.voiceRow[1])
        assertEquals(0, early.holder[0])
        val ended = PokeyStraightPlay.upTo(song, 0, 7)
        assertEquals(61, ended.voiceSlot[1])
        assertEquals(7, ended.voiceRow[1])
        assertEquals(1, ended.holder[0])
    }

    @Test
    fun heldPortamentoChangesTheTargetNotTheSampleKeyOrElapsedPhase() {
        var song = ownershipSong(length = 2000)
        song = with(song, 1, 1, Cell(96, 61, PokeyRules.FX_PORTAMENTO, 1))
        song = with(song, 1, 2, Cell(96))
        song = with(song, 1, 3, Cell(96))
        assertEquals(60, PokeyStraightPlay.upTo(song, 0, 2).voiceSlot[1])
        assertEquals(0, PokeyStraightPlay.upTo(song, 0, 2).voiceRow[1])
        assertEquals(61, PokeyStraightPlay.upTo(song, 0, 3).voiceSlot[1])
        assertEquals(3, PokeyStraightPlay.upTo(song, 0, 3).voiceRow[1])
    }

    @Test
    fun sampleOwnershipIntegratesPitchEffectsAndEnvelopesEachTick() {
        val sample = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0)
        val cases = listOf(
            Triple(sample, PokeyRules.FX_SLIDE_UP to 255, 8),
            Triple(sample, PokeyRules.FX_ARPEGGIO to 0xcc, 8),
            Triple(sample, PokeyRules.FX_FINE to 255, 9),
            Triple(sample.copy(arpeggio = NesEnvelope(listOf(12))), -1 to 0, 8),
            Triple(sample.copy(pitch = NesEnvelope(listOf(127))), -1 to 0, 8),
            Triple(sample.copy(pitch = NesEnvelope(listOf(16)), pitchRelative = true), -1 to 0, 8),
        )
        for ((ins, fx, row) in cases) {
            var song = ownershipSong(ins = ins)
            if (fx.first >= 0) song = with(song, 1, 0, Cell(48, 60, fx.first, fx.second))
            song = with(song, 1, row, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
            val walk = PokeyStraightPlay.upTo(song, 0, row)
            assertEquals("$ins / $fx", 61, walk.voiceSlot[1])
            assertEquals("$ins / $fx", row, walk.voiceRow[1])
        }
        var down = ownershipSong()
        down = with(down, 1, 0, Cell(48, 60, PokeyRules.FX_SLIDE_DOWN, 255))
        down = with(down, 1, 12, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
        assertEquals(60, PokeyStraightPlay.upTo(down, 0, 12).voiceSlot[1])
    }

    @Test
    fun sampleEndUsesInheritedTimerPhaseIncludingTheFirstShortPeriod() {
        // Power-on counts are 1, not AUDF+1. At N=7 timer 2 fires at cycle 22, then every 224:
        // the 134th interrupt is 29814, before the next NTSC tick at 29868.
        for ((length, slot) in listOf(134 to 61, 135 to 60)) {
            var song = ownershipSong(length = length, speed = 1)
            song = with(song, 1, 1, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
            assertEquals("length $length", slot, PokeyStraightPlay.upTo(song, 0, 1).voiceSlot[1])
        }
        var retrigger = ownershipSong(length = 134, speed = 1)
        retrigger = with(retrigger, 1, 1, Cell(48, 60, PokeyRules.FX_PORTAMENTO, 1))
        retrigger = with(retrigger, 1, 2, Cell(48, 61))
        // Row 1 keys on but does not STIMER: only 133 interrupts fit in its inherited phase.
        assertEquals(60, PokeyStraightPlay.upTo(retrigger, 0, 2).voiceSlot[1])
        assertEquals(1, PokeyStraightPlay.upTo(retrigger, 0, 2).voiceRow[1])
        val reset = with(ownershipSong(length = 134, speed = 1), 1, 0,
            Cell(48, 60, PokeyRules.FX_TIMERS, 0))
        val resetTarget = with(reset, 1, 1, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
        // T00 reloads 8 counts at cycle 0; first interrupt 218, only 133 in the first tick.
        assertEquals(60, PokeyStraightPlay.upTo(resetTarget, 0, 1).voiceSlot[1])
    }

    @Test
    fun clockChangesReleaseLoopsAndTwoToneAffectSampleOwnership() {
        for ((base15, slot) in listOf(false to 60, true to 61)) {
            var song = ownershipSong(length = 1400, note = 96)
            if (base15) song = with(song, 1, 0, Cell(96, 60, PokeyRules.FX_CLOCK, 1))
            song = with(song, 1, 1, Cell(96, 61, PokeyRules.FX_PORTAMENTO, 1))
            assertEquals("base15 $base15", slot, PokeyStraightPlay.upTo(song, 0, 1).voiceSlot[1])
        }
        for (cut in listOf(0, 3, 6)) {
            var song = ownershipSong(length = 200, ins = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0, loop = 17))
            song = with(song, 1, 0, Cell(48, 60, PokeyRules.FX_CUT, cut))
            song = with(song, 1, 1, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
            assertEquals("S$cut", if (cut < 6) 61 else 60, PokeyStraightPlay.upTo(song, 0, 1).voiceSlot[1])
        }
        var twoTone = ownershipSong()
        twoTone = with(twoTone, 1, 0, Cell())
        twoTone = with(twoTone, 0, 0, Cell(48, 60, PokeyRules.FX_TWO_TONE, 2))
        twoTone = withInstrument(twoTone, 61, PokeyInstrument(kind = PokeyKind.FILTERED))
        twoTone = with(twoTone, 0, 15, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
        // Idle timer 2 (N=0) resyncs timer 1 every 28 cycles. A 224-cycle sample timer never finishes.
        val walk = PokeyStraightPlay.upTo(twoTone, 0, 15)
        assertEquals(60, walk.voiceSlot[0])
        assertEquals(-1, walk.holder[2])
    }

    @Test
    fun vibratoAndReplacingPortamentoChangeSampleRestartOwnership() {
        // 4FF's first six pitches select N=7,6,7,8,7,6: over 810 IRQs, versus ~800 without vibrato.
        var vibrato = ownershipSong(length = 810)
        vibrato = with(vibrato, 1, 0, Cell(48, 60, PokeyRules.FX_VIBRATO, 255))
        vibrato = with(vibrato, 1, 1, Cell(48, 61, PokeyRules.FX_PORTAMENTO, 1))
        assertEquals(61, PokeyStraightPlay.upTo(vibrato, 0, 1).voiceSlot[1])

        var replaced = ownershipSong()
        replaced = with(replaced, 1, 0, Cell(48, 60, PokeyRules.FX_PORTAMENTO, 1))
        replaced = with(replaced, 1, 1, Cell(48, 61, PokeyRules.FX_SLIDE_UP, 1))
        assertEquals(61, PokeyStraightPlay.upTo(replaced, 0, 1).voiceSlot[1])
    }

    @Test
    fun sampleOwnershipUsesMachineCyclesMultispeedAndFastCounters() {
        for (machine in PokeyMachine.entries) for (multispeed in 1..4) for (fast in listOf(false, true)) {
            var song = ownershipSong(length = 1400, note = 96,
                ins = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0, fast = fast))
            song = song.copy(sound = song.pokey.copy(machine = machine, multispeed = multispeed))
            song = with(song, 1, 0, Cell())
            song = with(song, 0, 0, Cell(96, 60))
            song = withInstrument(song, 61, PokeyInstrument(kind = PokeyKind.FILTERED))
            song = with(song, 0, 1, Cell(96, 61, PokeyRules.FX_PORTAMENTO, 1))
            val ended = multispeed == 1 && (fast || machine == PokeyMachine.PAL)
            assertEquals("$machine x$multispeed fast=$fast", if (ended) 61 else 60,
                PokeyStraightPlay.upTo(song, 0, 1).voiceSlot[0])
        }
    }

    @Test
    fun longSongSampleOwnershipBulkAdvancesSteadyRows() {
        // 16,711,680 player ticks: ordinary UI queries must not replay them one by one, much less their IRQs.
        var song = ownershipSong(speed = 255, ins = PokeyInstrument(kind = PokeyKind.SAMPLE, sample = 0, loop = 17))
        val pattern = song.patterns[0].copy(length = 256, tracks = List(4) { lane ->
            List(256) { row -> if (lane == 1 && row == 0) Cell(48, 60) else Cell() }
        })
        song = song.copy(patterns = listOf(pattern), orders = List(256) { 0 })
        val walk = PokeyStraightPlay.upTo(song, 255, 255)
        assertEquals(255L * 256 * 256, walk.tick)
        assertEquals(60, walk.voiceSlot[1])
        assertEquals(255, walk.voiceOrder[1])
        assertFalse(walk.silent(1))
    }

    @Test
    fun starterKitIsDeterministicAndFitsTheLevels() {
        val kit = PokeySamples.starterKit()
        assertEquals(kit, PokeySamples.starterKit())
        for (s in kit) {
            assertTrue(s.name, s.levels.all { it in 0..15 })
            assertEquals(s.name, 0, s.levels.last())
            assertTrue(s.name, s.levels.max() >= 8)
        }
        val song = PokeySystem.blank().let { it.copy(sound = it.pokey.copy(samples = kit + PokeySample("odd", listOf(1, 2, 3)))) }
        assertEquals(kit.sumOf { it.levels.size + it.levels.size % 2 } + 4, PokeySamples.used(song))
        assertEquals(PokeySample.MEMORY - PokeySamples.used(song), PokeySamples.free(song))
        assertEquals(PokeyLowering.sampleBytes(song.pokey.samples) * 2, PokeySamples.used(song))
    }

    @Test
    fun wavImportMakesFourBitLevelsAtTheChosenRate() {
        val wav = javaClass.getResource("/dpcm-reference/tone_16m44k.wav")!!.readBytes()
        val levels = PokeySamples.fromWav(wav, 7990)
        assertTrue(levels.isNotEmpty() && levels.size <= PokeySample.MAX_LENGTH)
        assertTrue(levels.all { it in 0..15 })
        assertTrue(levels.max() >= 14)
        assertEquals(levels, PokeySamples.fromWav(wav, 7990))
        val half = PokeySamples.fromWav(wav, 3995)
        if (levels.size < PokeySample.MAX_LENGTH) assertTrue("${half.size} vs ${levels.size}", abs(half.size * 2 - levels.size) <= 2)
        assertThrows(IllegalArgumentException::class.java) { PokeySamples.fromWav(wav, 500) }
        assertThrows(IllegalArgumentException::class.java) { PokeySamples.fromWav(ByteArray(10), 7990) }
        assertTrue(PokeySamples.IMPORT_RATES.all { it in PokeyInstrument.RATES })
    }

    @Test
    fun parkedOrdersAreLeftOutAndJumpsRenumbered() {
        val blank = PokeySystem.blank()
        val two = blank.copy(patterns = blank.patterns + blank.patterns[0].copy(name = "B"), orders = listOf(0, 1, 0), loopOrder = 2,
            parkedOrders = listOf(1))
        val jumping = with(two, 0, 0, Cell().withEffect(1, PokeyRules.FX_JUMP, 2), order = 0)
        val arranged = PokeySystem.arranged(jumping)
        assertEquals(listOf(0, 0), arranged.orders)
        assertEquals(1, arranged.loopOrder)
        assertEquals(1, PokeySystem.jumpTarget(arranged.patterns[0].tracks[0][0]))
    }

    @Test
    fun patternLoopStartsWithTheOrdersStraightPlayState() {
        val blank = PokeySystem.blank()
        val two = blank.copy(patterns = blank.patterns + blank.patterns[0].copy(name = "B"), orders = listOf(0, 1))
        val globals = Cell().withEffect(0, PokeyRules.FX_SPEED, 4).withEffect(1, PokeyRules.FX_CLOCK, 1)
            .withEffect(2, PokeyRules.FX_POLY, 1).withEffect(3, PokeyRules.FX_TWO_TONE, 2)
        val song = with(with(two, 2, 5, globals, order = 0), 0, 0, Cell(57, 1, PokeyRules.FX_VOLUME, 0x0F), order = 1)
        assertEquals(PokeySystem.StartState(4, poly9 = true, base15 = true, twoTone = 2), PokeySystem.startState(song, 1))

        val loop = PokeySystem.patternLoop(song, 1)
        assertEquals(listOf(1), loop.orders)
        assertEquals(4, loop.ticksPerRow)
        assertTrue(loop.pokey.poly9 && loop.pokey.base15)
        // Two-tone goes into the first free column of row 0: POKEY 1's column 1 (column 0 holds C0F).
        val row0 = loop.patterns[1].tracks[0][0]
        assertEquals(PokeyRules.FX_VOLUME, row0.effectCode(0))
        assertEquals(PokeyRules.FX_TWO_TONE to 2, row0.effectCode(1) to row0.effectParam(1))
        assertEquals(emptyList<String>(), PokeySystem.errors(loop))

        // The first order starts with the song's own state; nothing is added.
        val first = PokeySystem.patternLoop(song, 0)
        assertEquals(song.ticksPerRow, first.ticksPerRow)
        assertFalse(first.pokey.poly9 || first.pokey.base15)
        assertEquals(song.patterns[0], first.patterns[0])

        // Row 0's own W stands; a parked earlier order does not count.
        val own = with(song, 3, 0, Cell().withEffect(0, PokeyRules.FX_TWO_TONE, 0), order = 1)
        assertEquals(own.patterns[1].tracks.map { it[0] }, PokeySystem.patternLoop(own, 1).patterns[1].tracks.map { it[0] })
        val parked = song.copy(parkedOrders = listOf(0))
        assertEquals(PokeySystem.StartState(song.ticksPerRow, poly9 = false, base15 = false, twoTone = 0), PokeySystem.startState(parked, 1))

        // No free column in row 0: the loop plays with two-tone off, row 0 unchanged.
        var full = song
        for (lane in 0 until 4) {
            var c = Cell(48, 1)
            for (col in 0 until 4) c = c.withEffect(col, PokeyRules.FX_FINE, 0x80 + col)
            full = with(full, lane, 0, c, order = 1)
        }
        assertEquals(full.patterns[1], PokeySystem.patternLoop(full, 1).patterns[1])
    }
}
