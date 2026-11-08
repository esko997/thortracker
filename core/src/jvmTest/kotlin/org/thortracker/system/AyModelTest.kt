package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.AyLowering
import org.thortracker.backend.AyPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.ay.AyAtariStSystem
import org.thortracker.system.ay.AyCpcSystem
import org.thortracker.system.ay.AyDemoSong
import org.thortracker.system.ay.AyEnvelopeSettings
import org.thortracker.system.ay.AyInstrument
import org.thortracker.system.ay.AyMsxSystem
import org.thortracker.system.ay.AyPresets
import org.thortracker.system.ay.AyRules
import org.thortracker.system.ay.AySound
import org.thortracker.system.ay.AySweep
import org.thortracker.system.ay.AySystem
import org.thortracker.system.ay.AyVariant
import org.thortracker.system.ay.AyVgm
import org.thortracker.system.ay.AyVgmRenderer
import org.thortracker.system.ay.AyVgmTags
import org.thortracker.system.ay.AyZx128System
import org.thortracker.system.ay.ay
import org.thortracker.system.nes.NesEnvelope
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

class AyModelTest {
    @After
    fun noRenderer() {
        AyVgm.renderer = null
    }

    private val zx = AyZx128System

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun blankWith(channel: Int, row: Int, cell: Cell, system: AySystem = zx) = with(system.blank(), 0, channel, row, cell)

    private fun slot(name: String) = AyPresets.ALL.indexOfFirst { it.name == name } + 1

    private fun withSound(song: Song, slot: Int, ins: AyInstrument) = song.copy(sound = song.ay.withInstrument(slot, ins))

    private fun cents(a: Double, b: Double) = 1200.0 * log2(a / b)

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    // ---- system shape --------------------------------------------------------------------------------------

    @Test
    fun fourComputersAreRegisteredAsOneFamily() {
        assertEquals(AyZx128System, SoundSystems.of("ay-zx128"))
        assertEquals(AyMsxSystem, SoundSystems.of("ay-msx"))
        assertEquals(AyAtariStSystem, SoundSystems.of("ay-atari-st"))
        assertEquals(AyCpcSystem, SoundSystems.of("ay-cpc"))
        assertEquals(listOf(AyZx128System, AyMsxSystem, AyAtariStSystem, AyCpcSystem), SoundSystems.ALL.takeLast(4))
        assertEquals(listOf("ZX Spectrum 128 (AY-3-8912)", "MSX (AY-3-8910)", "Atari ST (YM2149F)", "Amstrad CPC (AY-3-8912)"),
            AyVariant.entries.map { it.system.name })
        assertEquals(listOf(false, false, true, false), AyVariant.entries.map { it.ym })
        assertEquals(listOf(false, false, false, true), AyVariant.entries.map { it.stereo })
        assertEquals(listOf(0, 1, 2, 3), AyVariant.entries.map { it.index })
        assertEquals(listOf("zx128", "msx", "atari-st", "cpc"), AyVariant.entries.map { it.fileKey })
        assertTrue(AyCpcSystem.help.effectsIntro[0].contains("Channel A left, B on both sides, C right"))
        assertTrue(AyZx128System.help.effectsIntro[0].contains("mono"))
        for (variant in AyVariant.entries) {
            val system = variant.system
            assertEquals(variant, system.variant)
            assertEquals("AY-3-8910 / YM2149", system.family)
            assertEquals(system.id, variant.engineId)
            assertEquals(listOf("Channel A", "Channel B", "Channel C"), system.channels.map { it.name })
            assertTrue(system.channels.all { it.tonal && it.bank == 0 && it.kind == ChannelKind.PULSE })
            assertEquals(listOf(BankSpec("Sounds", 64)), system.banks)
            for (ch in 0 until 3) {
                assertEquals(4, system.effectColumns(ch))
                assertEquals("01234ABCDEFGHIJPSVW", system.effectsFor(ch).joinToString("") { it.char.toString() })
            }
            assertEquals(95, system.noteRange.last)
            assertEquals(listOf("vgm"), system.exports.map { it.extension })
            assertEquals("A-4", system.noteName(57))
            assertEquals(256, system.maxOrders)
            assertEquals(system, system.blank().system)
            assertEquals(AySound.serializer(), system.soundSerializer)
        }
        assertEquals(50.02, AyZx128System.tickHz, 0.005)
        assertEquals(59.92, AyMsxSystem.tickHz, 0.005)
        assertEquals(50.05, AyAtariStSystem.tickHz, 0.005)
        assertEquals(50.08, AyCpcSystem.tickHz, 0.005)
        assertEquals(1773450.0, AyVariant.ZX128.clockHz, 1e-6)
        assertEquals(1789772.5, AyVariant.MSX.clockHz, 1e-6)
        assertEquals(2005311.75, AyVariant.ATARI_ST.clockHz, 1e-6)
        assertEquals(1000000.0, AyVariant.CPC.clockHz, 1e-6)
        val song = zx.blank()
        assertEquals("Square lead", zx.instrumentName(song, 0, 1))
        assertEquals(AySystem.EMPTY_NAME, zx.instrumentName(song, 0, 60))
        assertNull(zx.instrumentName(song, 1, 1))
        assertTrue(zx.instrumentSummary(song, 0, slot("Buzzer bass"))!!.contains("envelope 8"))
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun songsRoundTripThroughProjectFilesOnEveryVariant() {
        for (variant in AyVariant.entries) {
            for (song in listOf(variant.system.blank(), variant.system.demo())) {
                val text = ProjectCodec.encode(song)
                assertTrue(text.contains("\"backend\":\"${variant.engineId}\",\"backendVersion\":1"))
                assertTrue(text.contains("\"variant\":\"${variant.fileKey}\""))
                val back = ProjectCodec.decode(text)
                assertEquals(song, back)
                assertEquals(variant.system, back.system)
                assertEquals(text, ProjectCodec.encode(back))
            }
        }
    }

    @Test
    fun aSoundOfAnotherVariantIsAStructuralError() {
        val zxSong = zx.blank()
        val problems = AyMsxSystem.structuralErrors(zxSong)
        assertEquals(listOf("The song is for ZX Spectrum 128 (AY-3-8912), not MSX (AY-3-8910)"), problems)
        assertEquals(problems, AyMsxSystem.errors(zxSong))
        assertEquals(emptyList<String>(), AyMsxSystem.warnings(zxSong))
        // A project whose backend names another computer than its sound is rejected.
        val text = ProjectCodec.encode(zxSong).replace("\"ay-zx128\"", "\"ay-atari-st\"")
        val e = assertThrows(org.thortracker.model.ProjectFormatException::class.java) { ProjectCodec.decode(text) }
        assertTrue(e.message!!, e.message!!.contains("ZX Spectrum 128"))
    }

    // ---- presets ---------------------------------------------------------------------------------------------

    @Test
    fun presetsAreValidAndFillNewSongs() {
        val names = listOf("Square lead", "Pluck", "Soft pad", "Arp major", "Arp minor", "Buzzer bass", "Sync buzzer", "Bell",
            "Envelope tremolo", "Kick", "Snare", "Closed hat", "Open hat", "Tom", "Noise sweep")
        assertEquals(names, AyPresets.ALL.map { it.name })
        for (p in AyPresets.ALL) {
            assertNull(p.name, p.instrument.problem())
            assertEquals(p.name, p.instrument.name)
            assertTrue(p.name, p.note in AyRules.NOTES)
        }
        for (variant in AyVariant.entries) {
            val sound = variant.system.blank().ay
            assertEquals(variant, sound.variant)
            assertEquals(64, sound.instruments.size)
            assertEquals(AyPresets.ALL.map { it.instrument }, sound.instruments.take(AyPresets.ALL.size))
            assertTrue(sound.instruments.drop(AyPresets.ALL.size).all { it == null })
            assertEquals(emptyList<String>(), variant.system.errors(variant.system.blank()))
        }
        // The buzzer bass is the bare saw envelope; the sync buzzer locks its tone; the bell has a manual period.
        val buzzer = AyPresets.instrument("Buzzer bass")
        assertEquals(listOf(0), buzzer.mixer.values)
        assertEquals(AyEnvelopeSettings(shape = 8, auto = true, octave = 0), buzzer.envelope)
        assertTrue(AyPresets.instrument("Sync buzzer").envelope.sync)
        assertFalse(AyPresets.instrument("Bell").envelope.auto)
        assertTrue(AyPresets.instrument("Kick").sweep.active)
        assertTrue(AyPresets.instrument("Noise sweep").noise.values.let { it.first() == 31 && it.last() == 1 })
        assertThrows(IllegalArgumentException::class.java) { AyPresets.instrument("Cowbell") }
    }

    // ---- periods ---------------------------------------------------------------------------------------------

    @Test
    fun periodFunctionsFollowTheGeneratedTables() {
        val c0 = 440.0 * 2.0.pow(-57 / 12.0)
        val tables = mapOf(AyVariant.ZX128 to AyPlayerImage.DIV_ZX128, AyVariant.MSX to AyPlayerImage.DIV_MSX,
            AyVariant.ATARI_ST to AyPlayerImage.DIV_ATARI_ST, AyVariant.CPC to AyPlayerImage.DIV_CPC)
        assertEquals(AyVariant.entries.toSet(), tables.keys)
        // A whole period is at most half a step off: its error in cents, plus the table's own rounding.
        fun bound(period: Int) = 1200.0 * log2((period + 0.5) / period) + 1.5
        for ((variant, table) in tables) {
            assertEquals(768, table.size)
            val clock = AyPlayerImage.MASTER_HZ[variant.index].toDouble() / AyPlayerImage.AY_DIV[variant.index]
            for (f in 0 until 768) {
                val expected = floor(256.0 * clock / (16.0 * c0 * 2.0.pow(f / 768.0)) + 0.5).toLong()
                assertTrue("${variant.label} DIV[$f]", abs(table[f] - expected) <= 1)
            }
            for (pitch in 0..AyPlayerImage.PITCH_MAX) {
                val o = pitch / 768
                val raw = (table[pitch % 768].toLong() + (128L shl o)) shr (8 + o)
                assertEquals("${variant.label} pitch $pitch", raw.coerceIn(1L, 4095L).toInt(), AyRules.tonePeriod(variant, pitch))
            }
            for (note in 24..48) {
                // J00 on a saw: the envelope's cycle is the note itself; J01 an octave up.
                val hz = 440.0 * 2.0.pow((note - 57) / 12.0)
                val ep = AyRules.autoEnvelopePeriod(variant, note * 64, 0, 8)
                assertTrue("${variant.label} note $note", abs(cents(AyRules.envelopeHz(variant, ep, 8), hz)) < bound(ep))
                // Triangles take two ramps a cycle: half the period for the same note.
                val tri = AyRules.autoEnvelopePeriod(variant, note * 64, 0, 10)
                assertTrue("${variant.label} triangle $note", abs(cents(AyRules.envelopeHz(variant, tri, 10), hz)) < bound(tri))
                // Synced, the tone sits on the note within the envelope period's step.
                val tp = AyRules.syncedTonePeriod(variant, ep, 0, 8)
                assertEquals(ep * 16, tp)
                assertTrue(abs(cents(AyRules.toneHz(variant, tp), hz)) < bound(ep))
            }
            val a2 = 33 * 64
            val up = AyRules.envelopeHz(variant, AyRules.autoEnvelopePeriod(variant, a2, 1, 8), 8)
            val own = AyRules.envelopeHz(variant, AyRules.autoEnvelopePeriod(variant, a2, 0, 8), 8)
            assertEquals(2.0, up / own, 0.1)
            // Negative octaves: the tone is the envelope period times 2^(4 + t + J), rounded when that is a division.
            assertEquals(40 * 8, AyRules.syncedTonePeriod(variant, 40, -2, 10))
            assertEquals(5, AyRules.syncedTonePeriod(variant, 40, -7, 8))
            assertEquals(4095, AyRules.syncedTonePeriod(variant, 4000, 0, 8))
            assertEquals(AyRules.tonePeriod(variant, AyPlayerImage.PITCH_MAX), AyRules.tonePeriod(variant, AyPlayerImage.PITCH_MAX + 100))
        }
        assertEquals(9, AyRules.lowestNote(AyVariant.ZX128))
        assertEquals(9, AyRules.lowestNote(AyVariant.MSX))
        assertEquals(11, AyRules.lowestNote(AyVariant.ATARI_ST))
        assertEquals(0, AyRules.lowestNote(AyVariant.CPC))
        assertEquals(3822, AyRules.tonePeriod(AyVariant.CPC, 0))
        assertEquals(142, AyRules.tonePeriod(AyVariant.CPC, 57 * 64))
        assertEquals(16, AyRules.tonePeriod(AyVariant.CPC, 95 * 64))
        assertEquals(4095, AyRules.tonePeriod(AyVariant.ZX128, 0))
        assertEquals(setOf(8, 9, 10, 11, 12, 13, 14, 15), AyRules.SHAPE_NAMES.keys)
    }

    // ---- tuning realisation --------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = zx.fine
        assertEquals("Pxx", fine.name)
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, AyRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(AyRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, AyRules.FX_VOLUME, 0x0C)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, AyRules.FX_FINE, 0x70)))
        assertEquals(16, zx.engine.fine(0, 57, 16))
        assertEquals(57, zx.engine.note(zx.blank(), 0, 57, 1))
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun validationExplainsWhatTheLoaderRejects() {
        fun error(cell: Cell) = zx.errors(blankWith(0, 0, cell)).joinToString("\n")
        assertTrue(error(Cell(57, 1, AyRules.FX_ENV_SHAPE, 0x05)).contains("shapes are E00 (off) and E08-E0F"))
        assertTrue(error(Cell(57, 1, AyRules.FX_ENV_AUTO, 0x08)).contains("octaves are J00-J07 (up) and JF8-JFF (down)"))
        assertTrue(error(Cell(57, 1, AyRules.FX_MIXER, 4)).contains("the mixer is V00-V03"))
        assertTrue(error(Cell(57, 1, AyRules.FX_NOISE, 0x20)).contains("noise periods are W00-W1F"))
        assertTrue(error(Cell(57, 1).withEffect(3, AyRules.FX_VOLUME, 0x10)).contains("volumes are C00-C0F"))
        assertTrue(error(Cell(57, 1, AyRules.FX_SPEED, 0)).contains("F00"))
        assertTrue(error(Cell(57, 1, AyRules.FX_JUMP, 1)).contains("jumps past the last order"))
        assertTrue(error(Cell(57, 60)).contains("sound 3C is empty"))
        assertTrue(error(Cell(96, 1)).contains("outside C-0..B-7"))
        assertTrue(error(Cell(57, 1, 20, 1)).contains("does not exist on the AY"))
        // Every legal extreme loads.
        for ((code, param) in listOf(AyRules.FX_ENV_SHAPE to 0, AyRules.FX_ENV_SHAPE to 0x0F, AyRules.FX_ENV_AUTO to 0x07,
            AyRules.FX_ENV_AUTO to 0xF8, AyRules.FX_ENV_HIGH to 0xFF, AyRules.FX_ENV_LOW to 0xFF, AyRules.FX_MIXER to 0,
            AyRules.FX_MIXER to 3, AyRules.FX_NOISE to 0x1F, AyRules.FX_VOLUME to 0x0F, AyRules.FX_FINE to 0xFF)) {
            assertEquals("${AyRules.effectText(code, param)}", "", error(Cell(57, 1, code, param)))
        }
        fun soundError(ins: AyInstrument) = zx.errors(withSound(zx.blank(), 1, ins)).joinToString("\n")
        assertTrue(soundError(AyInstrument("x", volume = NesEnvelope(listOf(15, 16)))).contains("volume envelope values must be 0..15"))
        assertTrue(soundError(AyInstrument("x", mixer = NesEnvelope(listOf(4)))).contains("mixer envelope values must be 0..3"))
        assertTrue(soundError(AyInstrument("x", noise = NesEnvelope(listOf(32)))).contains("noise envelope values must be 0..31"))
        assertTrue(soundError(AyInstrument("x", noise = NesEnvelope(listOf(3, 4), loop = 0, release = 1)))
            .contains("only the volume envelope has a release point"))
        assertTrue(soundError(AyInstrument("x", envelope = AyEnvelopeSettings(shape = 3))).contains("envelope shape 3 must be 0 (off) or 8-15"))
        assertTrue(soundError(AyInstrument("x", envelope = AyEnvelopeSettings(octave = 8))).contains("envelope octave 8 is outside -8..7"))
        assertTrue(soundError(AyInstrument("x", envelope = AyEnvelopeSettings(period = 65536))).contains("envelope period 65536"))
        assertTrue(soundError(AyInstrument("x", sweep = AySweep(97, 0, 1))).contains("sweep start and end must be -96..96"))
        // Sync, octave and period are kept in either mode.
        assertEquals("", soundError(AyInstrument("x", envelope = AyEnvelopeSettings(shape = 9, auto = false, octave = 5, period = 777, sync = true))))
        assertTrue(zx.structuralErrors(zx.blank().let { it.copy(sound = it.ay.copy(instruments = it.ay.instruments.take(10))) })
            .joinToString().contains("exactly 64"))
    }

    @Test
    fun warningsCoverTheSpecificationsShoulds() {
        val lead = slot("Square lead")
        fun warnings(song: Song) = song.system.warnings(song).joinToString("\n")
        assertTrue(warnings(blankWith(0, 4, Cell(48))).contains("has no sound"))
        assertTrue(warnings(blankWith(0, 0, Cell(5, lead))).contains("below the ZX Spectrum 128's lowest note (A-0)"))
        assertTrue(warnings(blankWith(0, 0, Cell(10, lead), AyAtariStSystem)).contains("lowest note (B-0)"))
        assertFalse(warnings(blankWith(0, 0, Cell(10, lead))).contains("lowest note"))
        assertTrue(warnings(blankWith(0, 0, Cell(48, lead, AyRules.FX_DELAY, 6))).contains("the row never plays"))
        assertFalse(warnings(blankWith(0, 0, Cell(48, lead, AyRules.FX_DELAY, 5))).contains("never plays"))
        assertTrue(warnings(blankWith(0, 1, Cell(effect = AyRules.FX_MIXER, param = 2))).contains("Vxx is a note setting, but nothing plays"))
        assertTrue(warnings(blankWith(0, 0, Cell(57, lead, AyRules.FX_ENV_AUTO, 1))).contains("Jxx sets the envelope period, but the note has no envelope shape"))
        assertFalse(warnings(blankWith(0, 0, Cell(57, lead, AyRules.FX_ENV_AUTO, 1).withEffect(1, AyRules.FX_ENV_SHAPE, 8))).contains("no envelope shape"))
        assertEquals("", warnings(blankWith(0, 0, Cell(33, slot("Buzzer bass"), AyRules.FX_ENV_AUTO, 1))))
    }

    /** A buzzer on A, a bell on B taking the envelope from it, hats on C; then a snare on B taking the noise from C. */
    private fun sharedSong(): Song {
        var song = zx.blank()
        song = with(song, 0, 0, 0, Cell(33, slot("Buzzer bass")))
        song = with(song, 0, 1, 4, Cell(57, slot("Bell")))
        song = with(song, 0, 2, 4, Cell(60, slot("Closed hat")))
        song = with(song, 0, 2, 8, Cell(60, slot("Open hat")))
        song = with(song, 0, 1, 9, Cell(50, slot("Snare")))
        return song
    }

    @Test
    fun sharedStateTagsTheOwnersByTheMostRecentKeyOn() {
        val song = sharedSong()
        val start = zx.sharedState(song, 0, 0)!!
        assertEquals(listOf("ENV", null, null), start.tags)
        assertTrue(start.lines[0], start.lines[0].startsWith("Envelope: Channel A owns it (shape 8, auto J00 on A-2: period 003F"))
        assertEquals("Noise: free (no sounding note uses it)", start.lines[1])
        val taken = zx.sharedState(song, 0, 4)!!
        assertEquals(listOf("env", "ENV", "NZ"), taken.tags)
        assertTrue(taken.lines[0], taken.lines[0].startsWith("Envelope: Channel B owns it (shape 9, manual 1000: period 1000"))
        assertTrue(taken.lines[0], taken.lines[0].endsWith("; Channel A plays it"))
        assertEquals("Noise: Channel C owns it (period 1)", taken.lines[1])
        // B's snare ends its bell: A's buzzer owns the envelope again; the snare takes the noise from the open hat.
        val noise = zx.sharedState(song, 0, 9)!!
        assertEquals(listOf("ENV", "NZ", "nz"), noise.tags)
        assertEquals("Noise: Channel B owns it (period 5); Channel C plays it", noise.lines[1])
        assertEquals(noise, zx.sharedState(song, 0, 9))
        val warnings = zx.warnings(song)
        assertTrue(warnings.toString(), warnings.contains("Channel B's note at 00:04 takes the envelope (shape 9, manual 1000) from Channel A's " +
            "held note (shape 8, auto J00 on A-2); A plays B's envelope until B's note ends"))
        assertTrue(warnings.toString(), warnings.contains("Channel B's note at 00:09 takes the noise generator (period 5) from Channel C's " +
            "held note (period 2); C plays noise period 5 until B's note ends"))
        assertEquals(2, warnings.size)
        // An OFF on A before the bell: a clean handover, no warning.
        val clean = with(song, 0, 0, 3, Cell(Cell.CUT))
        assertFalse(zx.warnings(clean).any { it.contains("takes the envelope") })
        assertEquals(listOf(null, "ENV", "NZ"), zx.sharedState(clean, 0, 4)!!.tags)
        // A parked order is read on its own.
        val parked = song.copy(patterns = song.patterns + Songs.blankPattern(song), orders = listOf(0, 1), parkedOrders = listOf(1))
        assertEquals(listOf(null, null, null), zx.sharedState(parked, 1, 0)!!.tags)
        assertNull(zx.sharedState(song, 5, 0))
    }

    @Test
    fun noteSettingsStartFromTheImagesFieldsNotTheKeptOnes() {
        // An auto sound keeps a manual period 0309 in the model; the image (and so the player) holds 0, so I40 makes 0040.
        val kept = AyInstrument("kept", envelope = AyEnvelopeSettings(shape = 9, auto = true, octave = 0, period = 0x0309))
        val song = with(withSound(zx.blank(), 21, kept), 0, 0, 0, Cell(57, 21, AyRules.FX_ENV_LOW, 0x40))
        val line = zx.sharedState(song, 0, 0)!!.lines[0]
        assertTrue(line, line.contains("shape 9, manual 0040: period 0040"))
        val detail = zx.effectDetail(song, 0, 0, 0, 0)!!
        assertTrue(detail, detail.contains("manual: period 0040"))
        // The same on a row without a note: the sounding note's settings, from its key-on row on.
        val later = with(with(song, 0, 0, 0, Cell(57, 21)), 0, 0, 2, Cell(effect = AyRules.FX_ENV_LOW, param = 0x40))
        assertTrue(zx.effectDetail(later, 0, 0, 2, 0)!!.contains("manual: period 0040"))
        assertTrue(zx.sharedState(later, 0, 2)!!.lines[0].contains("manual 0040"))
        assertEquals(AyEnvelopeSettings(9, true, 0, 0x0309), kept.envelope)
        assertEquals(0, kept.envelope.imagePeriod)
        assertEquals(0, AyEnvelopeSettings(9, false, 5, 0x0100).imageOctave)
    }

    /** A sound on the envelope (saw, auto) whose release holds level 10 for good, so a released note keeps sounding. */
    private val held = AyInstrument("held", volume = NesEnvelope(listOf(15, 15, 10), loop = 0, release = 1),
        envelope = AyEnvelopeSettings(shape = 8, auto = true))

    @Test
    fun portamentoGlidesOnlyOnAHeldNoteAndSlidesEndIt() {
        val buzzer = slot("Buzzer bass")
        // B keys on with 320; A's buzzer takes the envelope; B's next note only glides (no key-on), so A keeps it.
        var song = withSound(zx.blank(), 20, held)
        song = with(song, 0, 1, 0, Cell(57, 20, AyRules.FX_PORTAMENTO, 0x20))
        song = with(song, 0, 0, 2, Cell(33, buzzer))
        song = with(song, 0, 1, 4, Cell(60))
        assertEquals("ENV", zx.sharedState(song, 0, 4)!!.tags[0])
        // 1xx (or 2xx) ends the glide: the next note keys on and takes the envelope.
        for (code in listOf(AyRules.FX_SLIDE_UP, AyRules.FX_SLIDE_DOWN)) {
            val slid = with(song, 0, 1, 3, Cell(effect = code, param = 0))
            assertEquals(AyRules.effectText(code, 0), "ENV", zx.sharedState(slid, 0, 4)!!.tags[1])
        }
        // A released note does not glide either: the next note keys on.
        val released = with(song, 0, 1, 3, Cell(Cell.CUT))
        assertEquals("env", zx.sharedState(released, 0, 3)!!.tags[1])
        assertEquals("ENV", zx.sharedState(released, 0, 4)!!.tags[1])
    }

    @Test
    fun aSecondKeyOffLeavesAReleasedNoteAlone() {
        var song = withSound(zx.blank(), 20, held)
        song = with(song, 0, 1, 0, Cell(57, 20))
        song = with(song, 0, 1, 2, Cell(Cell.CUT))
        song = with(song, 0, 1, 4, Cell(Cell.CUT, effect = AyRules.FX_CUT, param = 2))
        // Released at row 2, it keeps sounding on its release level through the second OFF and the Sxx.
        for (row in 2..6) assertEquals("row $row", "ENV", zx.sharedState(song, 0, row)!!.tags[1])
    }

    @Test
    fun effectDetailReadsArpeggiosAndNoteSettings() {
        var song = blankWith(0, 0, Cell(57, 1, AyRules.FX_ARPEGGIO, 0x47))
        song = with(song, 0, 0, 2, Cell(effect = AyRules.FX_ARPEGGIO, param = 0x37))
        song = with(song, 0, 1, 0, Cell(33, slot("Buzzer bass"), AyRules.FX_ENV_AUTO, 1))
        song = with(song, 0, 2, 5, Cell(effect = AyRules.FX_NOISE, param = 3))
        assertEquals("A-4 → C#5 → E-5 every tick", zx.effectDetail(song, 0, 0, 0, 0))
        assertEquals("A-4 → C-5 → E-5 every tick", zx.effectDetail(song, 0, 0, 2, 0))
        val buzz = zx.effectDetail(song, 0, 1, 0, 0)!!
        assertTrue(buzz, buzz.contains("A-2 on this row") && buzz.contains("envelope 8") && buzz.contains("auto J01"))
        assertTrue(buzz, buzz.contains("envelope owner Channel B"))
        assertTrue(zx.effectDetail(song, 0, 2, 5, 0)!!.contains("ignored"))
        assertNull(zx.effectDetail(song, 0, 0, 1, 0))
    }

    // ---- arrangement, audition, lowering and exports -------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val base = zx.blank()
        var song = base.copy(patterns = List(3) { Songs.blankPattern(base) }, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 2, 5, Cell(48, 1).withEffect(2, AyRules.FX_JUMP, 2))
        val arranged = zx.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, zx.jumpTarget(arranged.patterns[2].tracks[2][5]))
        val loop = zx.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, zx.jumpTarget(loop.patterns[2].tracks[2][5]))
    }

    @Test
    fun auditionChecksTheSlotAndArguments() {
        val song = zx.blank()
        val engine = zx.engine
        assertNull(engine.auditionProblem(song, 0, 57, 1, 0))
        assertNull(engine.auditionProblem(song, 2, 33, slot("Buzzer bass"), -128))
        assertTrue(engine.auditionProblem(song, 0, 57, 60, 0)!!.contains("is empty"))
        assertTrue(engine.auditionProblem(song, 0, 57, 0, 0)!!.contains("Choose a sound"))
        assertTrue(engine.auditionProblem(song, 3, 57, 1, 0)!!.contains("Cannot audition"))
        assertTrue(engine.auditionProblem(song, 0, 57, 1, 128)!!.contains("Fine pitch"))
        for (variant in AyVariant.entries) {
            assertEquals(variant.engineId, variant.system.engine.id)
            assertEquals(65536, variant.system.engine.imageSize)
            assertEquals(AyPlayerImage.SEEK_TICKS, variant.system.engine.seekTicks)
        }
    }

    @Test
    fun loweringWritesTheVariantAndTheActiveEnvelopeMode() {
        for (variant in AyVariant.entries) {
            val image = AyLowering.lower(variant.system.blank())
            assertEquals("TTAY", String(image.copyOfRange(0, 4).map { it.toInt().toChar() }.toCharArray()))
            assertEquals(variant.index, image[AyPlayerImage.H_VARIANT].toInt())
            assertEquals(AyPresets.ALL.size, image[AyPlayerImage.H_INSTRUMENTS].toInt())
        }
        fun record(name: String): ByteArray {
            val image = AyLowering.lower(zx.blank())
            val at = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot(name) - 1)
            return image.copyOfRange(at, at + AyPlayerImage.INSTRUMENT_SIZE)
        }
        val sync = record("Sync buzzer")
        assertEquals(AyPlayerImage.FLAG_DEFINED or AyPlayerImage.FLAG_AUTO_PERIOD or AyPlayerImage.FLAG_SYNC, sync[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(10, sync[AyPlayerImage.I_SHAPE].toInt())
        assertEquals(-2, sync[AyPlayerImage.I_OCTAVE].toInt())
        assertEquals(0, u16(sync, AyPlayerImage.I_PERIOD))
        val bell = record("Bell")
        assertEquals(AyPlayerImage.FLAG_DEFINED, bell[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(0x1000, u16(bell, AyPlayerImage.I_PERIOD))
        assertEquals(AyPlayerImage.NO_ENVELOPE, u16(bell, AyPlayerImage.I_ENV_VOLUME))
        // A manual sound keeps its octave and sync in the model; the image gets neither.
        val kept = AyInstrument("x", envelope = AyEnvelopeSettings(shape = 9, auto = false, octave = 5, period = 777, sync = true))
        val r = AyLowering.record(kept) { 0 }
        assertEquals(AyPlayerImage.FLAG_DEFINED, r[AyPlayerImage.I_FLAGS].toInt() and 0xFF)
        assertEquals(0, r[AyPlayerImage.I_OCTAVE].toInt())
        assertEquals(777, u16(r, AyPlayerImage.I_PERIOD))
        // Identical envelopes are stored once: Arp major and Arp minor share their volume envelope.
        val arps = AyLowering.lower(zx.blank())
        val major = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot("Arp major") - 1)
        val minor = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot("Arp minor") - 1)
        assertEquals(u16(arps, major + AyPlayerImage.I_ENV_VOLUME), u16(arps, minor + AyPlayerImage.I_ENV_VOLUME))
        assertFalse(u16(arps, major + AyPlayerImage.I_ENV_ARPEGGIO) == u16(arps, minor + AyPlayerImage.I_ENV_ARPEGGIO))
    }

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        for (variant in AyVariant.entries) {
            val demo = variant.system.demo()
            assertThrows(IllegalStateException::class.java) { variant.system.exports[0].export(demo) }
            var seen: Triple<Int, Int, AyVgmTags>? = null
            var image: ByteArray? = null
            AyVgm.renderer = AyVgmRenderer { v, img, maxTicks, tags -> seen = Triple(v, maxTicks, tags); image = img; byteArrayOf(v.toByte(), 7) }
            assertArrayEquals(byteArrayOf(variant.index.toByte(), 7), variant.system.exports[0].export(demo))
            assertEquals(variant.index, seen!!.first)
            assertEquals((variant.tickHz * 600).toInt(), seen!!.second)
            assertTrue(seen!!.second in 30000..36000)
            assertEquals(AyVgmTags("Copper Lane", "Thor Tracker"), seen!!.third)
            assertArrayEquals(AyLowering.lower(demo), image)
            AyVgm.renderer = null
        }
    }

    @Test
    fun demoIsCleanOnEveryComputerAtTheSameTempo() {
        for (variant in AyVariant.entries) {
            val system = variant.system
            val demo = system.demo()
            assertEquals("Copper Lane", demo.title)
            assertEquals(system, demo.system)
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            // 10 rows a second: 150 BPM in sixteenths.
            assertEquals(AyDemoSong.speed(variant), demo.ticksPerRow)
            assertEquals(10.0, system.tickHz / demo.ticksPerRow, 0.05)
            val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
            val names = cells.filter { it.instrument != 0 }.map { demo.ay.instrument(it.instrument)!!.name }.toSet()
            assertTrue(names.toString(), names.containsAll(listOf("Buzzer bass", "Sync buzzer", "Arp major", "Arp minor", "Kick", "Snare", "Noise sweep")))
            fun uses(code: Int) = cells.any { c -> (0..c.more.size).any { c.effectCode(it) == code } }
            for (code in listOf(AyRules.FX_VIBRATO, AyRules.FX_PORTAMENTO, AyRules.FX_VOLUME_SLIDE, AyRules.FX_ENV_AUTO, AyRules.FX_ENV_SHAPE)) {
                assertTrue(AyRules.effectText(code, 0), uses(code))
            }
            for (ch in 0 until 3) assertTrue("channel $ch", demo.patterns.all { p -> p.tracks[ch].any { it.note >= 0 } })
            val image = AyLowering.lower(demo)
            assertEquals(AyLowering.dataSize(demo), u16(image, AyPlayerImage.H_DATA_SIZE))
            // The bridge hands the envelope to the synced lead on Channel A; the theme gives it back to the buzzer bass.
            val bridge = demo.orders.indexOf(2)
            assertEquals("ENV", system.sharedState(demo, bridge, 0)!!.tags[0])
            assertEquals("ENV", system.sharedState(demo, 1, 0)!!.tags[1])
        }
    }
}
