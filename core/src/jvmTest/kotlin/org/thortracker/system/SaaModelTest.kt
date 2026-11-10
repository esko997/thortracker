package org.thortracker.system

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.SaaLowering
import org.thortracker.backend.SaaPlayerImage
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.ProjectFormatException
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.CmsSystem
import org.thortracker.system.saa.Saa1099System
import org.thortracker.system.saa.SaaDemoSong
import org.thortracker.system.saa.SaaHardwareEnvelope
import org.thortracker.system.saa.SaaInstrument
import org.thortracker.system.saa.SaaInstrumentView
import org.thortracker.system.saa.SaaPresets
import org.thortracker.system.saa.SaaRules
import org.thortracker.system.saa.SaaSharedState
import org.thortracker.system.saa.SaaSongSettings
import org.thortracker.system.saa.SaaSound
import org.thortracker.system.saa.SaaValidator
import org.thortracker.system.saa.SaaVariant
import org.thortracker.system.saa.SaaVgm
import org.thortracker.system.saa.SaaVgmRenderer
import org.thortracker.system.saa.SaaVgmTags
import org.thortracker.system.saa.SamCoupeSystem
import org.thortracker.system.saa.saa

/**
 * The SAM Coupé and CMS systems' model: registration and shape, project files, the validator's mirror of the loader's
 * checks, the shared-resource warnings (noise generators, hardware envelopes, sync), arrangement, the shared-state
 * walk, fine pitch, the lowering's header, records, envelopes, order table and sharing, the VGM seam, presets and the
 * demos. Slots are the presets' (a new song's): 01 Lead, 02 Square bass, 03 Pluck, 04 Stereo pad, 05 Echo lead,
 * 06 Kick, 07 Snare, 08 Hat, 09 Noise sweep, 0A Buzz bass, 0B Slow swell, 0C Mirror.
 */
class SaaModelTest {
    @After
    fun noRenderer() {
        SaaVgm.renderer = null
    }

    private val lead = slot("Lead")
    private val pluck = slot("Pluck")
    private val snare = slot("Snare")
    private val hat = slot("Hat")
    private val noiseSweep = slot("Noise sweep")
    private val buzz = slot("Buzz bass")
    private val swell = slot("Slow swell")
    private val mirror = slot("Mirror")

    private fun slot(name: String) = SaaPresets.ALL.indexOfFirst { it.name == name } + 1

    /** Held noise at clock 0 and clock 2 (full volume, noise only), for the shared-noise rules. */
    private val noise0 = 20
    private val noise2 = 21
    private fun heldNoise(clock: Int) = SaaInstrument("noise $clock", mixer = NesEnvelope(listOf(SaaInstrument.MIXER_NOISE)), noise = clock)

    private fun with(song: Song, order: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.orders[order]
        val pattern = song.patterns[p]
        val tracks = pattern.tracks.mapIndexed { c, t -> if (c != channel) t else t.toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[p] = pattern.copy(tracks = tracks) })
    }

    private fun Song.at(channel: Int, row: Int, cell: Cell) = with(this, 0, channel, row, cell)

    private fun withSound(song: Song, f: (SaaSound) -> SaaSound) = song.copy(sound = f(song.saa))

    /** A blank song of [system] with the held noise instruments in slots 20 and 21. */
    private fun noisy(system: Saa1099System = SamCoupeSystem) =
        withSound(system.blank()) { it.withInstrument(noise0, heldNoise(0)).withInstrument(noise2, heldNoise(2)) }

    private fun warnings(song: Song) = song.system.warnings(song)

    private fun errors(song: Song) = song.system.errors(song)

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    private fun byte(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    // ---- system shape ------------------------------------------------------------------------------------------

    @Test
    fun bothMachinesAreRegisteredWithTheirLanes() {
        assertSame(SamCoupeSystem, SoundSystems.of("sam-saa1099"))
        assertSame(CmsSystem, SoundSystems.of("cms-saa1099"))
        assertEquals((1..6).map { "SAA $it" }, SamCoupeSystem.channels.map { it.name })
        assertEquals((1..6).map { "A$it" } + (1..6).map { "B$it" }, CmsSystem.channels.map { it.name })
        assertEquals(11..106, SamCoupeSystem.noteRange)
        assertEquals(9..104, CmsSystem.noteRange)
        assertEquals("B-0", SamCoupeSystem.noteName(11))
        assertEquals("A#8", SamCoupeSystem.noteName(106))
        assertEquals("A-0", CmsSystem.noteName(9))
        assertEquals("G#8", CmsSystem.noteName(104))
        assertEquals(50.0, SamCoupeSystem.ticksPerSecond(SamCoupeSystem.blank()), 1e-9)
        assertEquals(180.0, CmsSystem.ticksPerSecond(withSound(CmsSystem.blank()) { it.copy(multispeed = 3) }), 1e-9)
        for (system in listOf(SamCoupeSystem, CmsSystem)) {
            assertEquals(SamCoupeSystem.family, system.family)
            assertTrue(system.channels.all { it.tonal && it.bank == 0 })
            assertEquals(listOf(BankSpec("Instruments", 64)), system.banks)
            assertEquals(4, system.effectColumns(system.channels.size - 1))
            assertEquals("012348ABCDEFGMNPSY".map { it.toString() }, system.effectsFor(0).map { it.char.toString() })
            assertEquals(ViewKind.entries.toSet() - ViewKind.WAVE, system.views)
            assertSame(SaaInstrumentView, system.view(ViewKind.INSTRUMENT))
            assertNull(system.view(ViewKind.WAVE))
            assertNull(system.offEffect)
            assertEquals(256, system.maxOrders)
            assertEquals(1, system.backendVersion)
            assertEquals(listOf("vgm"), system.exports.map { it.extension })
            val engine = system.engine
            assertEquals(system.id, engine.id)
            assertEquals(65536, engine.imageSize)
            assertEquals(12000, engine.seekTicks)
            assertEquals(57, engine.note(system.blank(), 0, 57, 1))
            assertEquals(-20, engine.fine(1, 57, -20))
            assertEquals(system, system.blank().sound.system)
            assertEquals(SaaSound.serializer(), system.soundSerializer)
        }
        assertEquals(SamCoupeSystem, SaaVariant.SAM.system)
        assertEquals(CmsSystem, SaaVariant.CMS.system)
        assertEquals(listOf(1, 2), SaaVariant.entries.map { it.chips })
    }

    @Test
    fun blankSongsStartWithTheTwelvePresets() {
        for (system in listOf(SamCoupeSystem, CmsSystem)) {
            val blank = system.blank()
            assertEquals(emptyList<String>(), system.errors(blank))
            assertEquals(emptyList<String>(), system.warnings(blank))
            val sound = blank.saa
            assertEquals(system.variant, sound.variant)
            assertEquals(64, sound.instruments.size)
            assertEquals(SaaPresets.ALL.map { it.instrument }, sound.instruments.take(12))
            assertTrue(sound.instruments.drop(12).all { it == null })
            assertEquals(1, sound.multispeed)
            assertEquals(system.channels.size, blank.patterns.single().tracks.size)
        }
        val blank = SamCoupeSystem.blank()
        assertEquals("Lead", SamCoupeSystem.instrumentName(blank, 0, lead))
        assertNull(SamCoupeSystem.instrumentName(blank, 1, 1))
        assertNull(SamCoupeSystem.instrumentShape(blank, 0, 40))
        assertNull(SamCoupeSystem.instrumentShape(blank, 0, hat))
        // A tone sound's thumbnail is a square; a hardware envelope's is its shape (the buzz bass: a falling ramp).
        assertEquals(List(16) { 1f } + List(16) { 0f }, SamCoupeSystem.instrumentShape(blank, 0, lead))
        val ramp = SamCoupeSystem.instrumentShape(blank, 0, buzz)!!
        assertEquals(16, ramp.size)
        assertEquals(1f, ramp.first(), 1e-6f)
        assertEquals(0f, ramp.last(), 1e-6f)
    }

    // ---- project files -------------------------------------------------------------------------------------

    @Test
    fun projectFilesRoundTripBothVariants() {
        val custom = SaaInstrument(
            "custom", volume = NesEnvelope(listOf(15, 12, 9, 6, 3, 0), loop = 1, release = 4), arpeggio = NesEnvelope(listOf(0, 7, -12), loop = 0),
            pitch = NesEnvelope(listOf(-64, 63)), mixer = NesEnvelope(listOf(3, 2, 0, 1), loop = 2), pitchRelative = true, noise = 3,
            panLeft = 4, panRight = 13,
            envelope = SaaHardwareEnvelope(shape = 6, threeBit = true, invertRight = true, external = true, rate = 200, retrigger = false),
        )
        val cell = Cell(57, 40, SaaRules.FX_PAN, 0x3C).withEffect(1, SaaRules.FX_ENVELOPE, 0xA3).withEffect(3, SaaRules.FX_FINE, 0x90)
        for (system in listOf(SamCoupeSystem, CmsSystem)) {
            val song = withSound(system.blank().at(2, 0, cell)) {
                it.copy(multispeed = 2).withInstrument(40, custom).withInstrument(2, null)
            }
            assertEquals(emptyList<String>(), system.errors(song))
            val text = ProjectCodec.encode(song)
            assertTrue(text.contains("\"backend\":\"${system.id}\",\"backendVersion\":1"))
            val back = ProjectCodec.decode(text)
            assertEquals(song, back)
            assertEquals(text, ProjectCodec.encode(back))
            assertNull(back.saa.instrument(2))
            assertEquals(custom, back.saa.instrument(40))
            assertEquals(system.variant, back.saa.variant)
        }
        for (s in listOf(SamCoupeSystem.demo(), CmsSystem.demo(), SaaDemoSong.shortest(SaaVariant.SAM), SaaDemoSong.shortest(SaaVariant.CMS))) {
            assertEquals(s, ProjectCodec.decode(ProjectCodec.encode(s)))
        }
        // A project whose backend and variant disagree is refused.
        val sam = ProjectCodec.encode(SamCoupeSystem.blank())
        assertTrue(sam.contains("\"variant\":\"sam\""))
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(sam.replace("\"variant\":\"sam\"", "\"variant\":\"cms\"")) }
        val cmsOnSam = SamCoupeSystem.blank().let { it.copy(sound = it.saa.copy(variant = SaaVariant.CMS)) }
        assertEquals(1, SamCoupeSystem.errors(cmsOnSam).size)
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    fun notesAreCheckedAgainstEachMachinesRange() {
        fun ok(system: Saa1099System, note: Int) = system.errors(system.blank().at(0, 0, Cell(note, lead))).isEmpty()
        assertFalse(ok(SamCoupeSystem, 10))
        assertTrue(ok(SamCoupeSystem, 11))
        assertTrue(ok(SamCoupeSystem, 106))
        assertFalse(ok(SamCoupeSystem, 107))
        assertFalse(ok(CmsSystem, 8))
        assertTrue(ok(CmsSystem, 9))
        assertTrue(ok(CmsSystem, 104))
        assertFalse(ok(CmsSystem, 105))
        assertTrue(errors(SamCoupeSystem.blank().at(0, 0, Cell(Cell.CUT))).isEmpty())
        assertFalse(errors(SamCoupeSystem.blank().at(0, 0, Cell(57, 40))).isEmpty())
        assertFalse(errors(SamCoupeSystem.blank().at(0, 0, Cell(57, 65))).isEmpty())
    }

    @Test
    fun effectParametersAreCheckedAsTheLoaderDoes() {
        fun ok(code: Int, param: Int, column: Int = 0) =
            errors(SamCoupeSystem.blank().at(2, 0, Cell(57, lead).withEffect(column, code, param))).isEmpty()
        assertTrue(ok(SaaRules.FX_VOLUME, 0x0F))
        assertFalse(ok(SaaRules.FX_VOLUME, 0x10, column = 2))
        assertFalse(ok(SaaRules.FX_SPEED, 0))
        assertTrue(ok(SaaRules.FX_SPEED, 0xFF))
        assertTrue(ok(SaaRules.FX_ENVELOPE, 0x00))
        assertTrue(ok(SaaRules.FX_ENVELOPE, 0x80))
        assertTrue(ok(SaaRules.FX_ENVELOPE, 0xBF))
        assertFalse(ok(SaaRules.FX_ENVELOPE, 0x7F))
        assertFalse(ok(SaaRules.FX_ENVELOPE, 0xC0))
        for (code in listOf(SaaRules.FX_MIXER, SaaRules.FX_NOISE)) {
            assertTrue(ok(code, 3))
            assertFalse(ok(code, 4, column = 3))
        }
        assertTrue(ok(SaaRules.FX_SYNC, 0))
        assertFalse(ok(SaaRules.FX_SYNC, 4))
        assertFalse(ok(SaaRules.FX_JUMP, 1))
        assertTrue(ok(SaaRules.FX_JUMP, 0))
        for (code in listOf(SaaRules.FX_DELAY, SaaRules.FX_FINE, SaaRules.FX_ARPEGGIO, SaaRules.FX_PAN, SaaRules.FX_CUT)) assertTrue(ok(code, 0xFF))
        // Codes outside the SAA1099's set.
        for (code in listOf(5, 6, 7, 9, 0x11, 0x1D, 0x20)) assertFalse(EffectSpec.charOf(code).toString(), ok(code, 1))
    }

    @Test
    fun instrumentsAreCheckedAsTheLoaderDoes() {
        fun ok(ins: SaaInstrument) = errors(withSound(SamCoupeSystem.blank()) { it.withInstrument(20, ins) }).isEmpty()
        assertTrue(ok(SaaInstrument(noise = 3, panLeft = 0, panRight = 15)))
        assertFalse(ok(SaaInstrument(noise = 4)))
        assertFalse(ok(SaaInstrument(panLeft = 16)))
        assertFalse(ok(SaaInstrument(panRight = -1)))
        assertTrue(ok(SaaInstrument(envelope = SaaHardwareEnvelope(shape = 7, external = true, rate = 255))))
        assertFalse(ok(SaaInstrument(envelope = SaaHardwareEnvelope(shape = 8))))
        assertFalse(ok(SaaInstrument(envelope = SaaHardwareEnvelope(rate = 0))))
        assertFalse(ok(SaaInstrument(envelope = SaaHardwareEnvelope(rate = 256))))
        assertTrue(ok(SaaInstrument(mixer = NesEnvelope(listOf(0, 1, 2, 3), loop = 3))))
        assertFalse(ok(SaaInstrument(mixer = NesEnvelope(listOf(4)))))
        assertFalse(ok(SaaInstrument(mixer = NesEnvelope(listOf(1, 2), release = 1))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(listOf(16)))))
        assertFalse(ok(SaaInstrument(arpeggio = NesEnvelope(listOf(64)))))
        assertFalse(ok(SaaInstrument(pitch = NesEnvelope(listOf(-65)))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(listOf(15, 0), release = 0))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(listOf(15), release = 1))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(listOf(15, 9, 0), loop = 2, release = 2))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(listOf(15, 9), loop = 2))))
        // An empty envelope that still carries a loop or release point.
        assertFalse(ok(SaaInstrument(mixer = NesEnvelope(loop = 0))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(release = 1))))
        assertFalse(ok(SaaInstrument(volume = NesEnvelope(List(257) { 15 }))))
        assertTrue(ok(SaaInstrument(volume = NesEnvelope(List(256) { 15 }, loop = 3, release = 255))))
        assertFalse(errors(withSound(SamCoupeSystem.blank()) { it.copy(multispeed = 9) }).isEmpty())
        assertFalse(errors(withSound(SamCoupeSystem.blank()) { it.copy(instruments = it.instruments.drop(1)) }).isEmpty())
        // The lowering refuses what the validator rejects.
        val bad = withSound(SamCoupeSystem.blank()) { it.withInstrument(20, SaaInstrument(noise = 4)) }
        assertThrows(IllegalArgumentException::class.java) { SaaLowering.lower(bad) }
    }

    @Test
    fun dataBeyondTheImageIsAnErrorAndLoweringRefusesIt() {
        // 20 distinct full patterns of twelve lanes with four effect columns on every row overflow the data region.
        val base = CmsSystem.blank()
        val patterns = List(20) { p ->
            Pattern("P$p", List(12) { ch -> List(Songs.ROWS) { row ->
                Cell(12 + (p + ch + row) % 80, 1, SaaRules.FX_VOLUME, row % 16)
                    .withEffect(1, SaaRules.FX_DELAY, p).withEffect(2, SaaRules.FX_FINE, 0x80 + ch).withEffect(3, SaaRules.FX_CUT, row)
            } })
        }
        val song = base.copy(patterns = patterns, orders = patterns.indices.toList())
        assertTrue(SaaLowering.dataSize(song) > SaaPlayerImage.DATA_CAPACITY)
        assertFalse(CmsSystem.errors(song).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SaaLowering.lower(song) }
    }

    @Test
    fun auditionChecksSlotLaneNoteAndFine() {
        val sam = SamCoupeSystem.engine
        val cms = CmsSystem.engine
        val samSong = SamCoupeSystem.blank()
        val cmsSong = CmsSystem.blank()
        assertNull(sam.auditionProblem(samSong, 0, 57, lead, 0))
        assertNull(sam.auditionProblem(samSong, 5, 11, buzz, -128))
        assertNull(sam.auditionProblem(samSong, 2, 106, lead, 127))
        assertNotNull(sam.auditionProblem(samSong, 0, 10, lead, 0))
        assertNotNull(sam.auditionProblem(samSong, 0, 107, lead, 0))
        assertNotNull(sam.auditionProblem(samSong, 6, 57, lead, 0))
        assertNull(cms.auditionProblem(cmsSong, 11, 9, lead, 0))
        assertNotNull(cms.auditionProblem(cmsSong, 12, 57, lead, 0))
        assertNotNull(cms.auditionProblem(cmsSong, 0, 105, lead, 0))
        assertNotNull(sam.auditionProblem(samSong, 0, 57, 40, 0))
        assertNotNull(sam.auditionProblem(samSong, 0, 57, 0, 0))
        assertNotNull(sam.auditionProblem(samSong, 0, 57, lead, 128))
        val broken = withSound(samSong) { it.withInstrument(20, SaaInstrument(panLeft = 16)) }
        assertNotNull(sam.auditionProblem(broken, 0, 57, 20, 0))
    }

    // ---- warnings: shared noise ------------------------------------------------------------------------------

    @Test
    fun lanesOfOneTripleSoundingNoiseWithDifferentClocksWarnOncePerStretch() {
        // Hat (clock 0) on SAA 4 and the noise sweep (clock 3) on SAA 5 at once.
        assertEquals(1, warnings(SamCoupeSystem.blank().at(3, 0, Cell(48, hat)).at(4, 0, Cell(48, noiseSweep))).size)
        // Held notes clash on every row: still one warning.
        val held = noisy().at(3, 0, Cell(48, noise0)).at(4, 0, Cell(48, noise2))
        assertEquals(1, warnings(held).size)
        // The same clock on both: no clash. Snare and hat share clock 0.
        assertEquals(0, warnings(noisy().at(3, 0, Cell(48, noise0)).at(5, 0, Cell(48, noise0))).size)
        assertEquals(0, warnings(SamCoupeSystem.blank().at(3, 0, Cell(48, hat)).at(4, 0, Cell(48, snare))).size)
        // Different triples, or different chips on the CMS, have their own generators.
        assertEquals(0, warnings(noisy().at(0, 0, Cell(48, noise0)).at(3, 0, Cell(48, noise2))).size)
        assertEquals(0, warnings(noisy(CmsSystem).at(3, 0, Cell(48, noise0)).at(9, 0, Cell(48, noise2))).size)
        assertEquals(1, warnings(noisy(CmsSystem).at(9, 0, Cell(48, noise0)).at(11, 0, Cell(48, noise2))).size)
        // One after the other: the hat has faded (six ticks) before the sweep starts.
        assertEquals(0, warnings(SamCoupeSystem.blank().at(3, 0, Cell(48, hat)).at(4, 4, Cell(48, noiseSweep))).size)
        // Nxx on a sounding note changes its clock from that row on; Mxx 01 plays tone only.
        assertEquals(1, warnings(noisy().at(3, 0, Cell(48, noise0)).at(4, 0, Cell(48, noise0)).at(4, 8, Cell(effect = SaaRules.FX_NOISE, param = 2))).size)
        assertEquals(0, warnings(noisy().at(3, 0, Cell(48, noise0)).at(4, 0, Cell(48, noise2, SaaRules.FX_MIXER, 1))).size)
        // Channel volume 0 or a pan of 0/0 does not sound.
        assertEquals(0, warnings(noisy().at(3, 0, Cell(48, noise0)).at(4, 0, Cell(48, noise2, SaaRules.FX_VOLUME, 0))).size)
        assertEquals(0, warnings(noisy().at(3, 0, Cell(48, noise0)).at(4, 0, Cell(48, noise2, SaaRules.FX_PAN, 0))).size)
        // OFF on a note without a release point ends it.
        assertEquals(0, warnings(noisy().at(3, 0, Cell(48, noise0)).at(3, 3, Cell(Cell.CUT)).at(4, 4, Cell(48, noise2))).size)
    }

    // ---- warnings: hardware envelopes, sync and the rest ------------------------------------------------------

    @Test
    fun hardwareEnvelopesWarnOnLanesWithoutOneAndWithoutAClock() {
        val blank = SamCoupeSystem.blank()
        // Lanes 1, 2, 4 and 5 have no envelope.
        for (ch in listOf(0, 1, 3, 4)) assertEquals("lane ${ch + 1}", 1, warnings(blank.at(ch, 0, Cell(48, mirror))).size)
        assertEquals(1, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_ENVELOPE, 0x86))).size)
        assertEquals(0, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_ENVELOPE, 0x00))).size)
        // Lanes 3 and 6 (and B3, B6 on the CMS) have one: external clocks need nothing else.
        for (ch in listOf(2, 5)) assertEquals(0, warnings(blank.at(ch, 0, Cell(48, swell))).size)
        for (ch in listOf(2, 5, 8, 11)) assertEquals(0, warnings(CmsSystem.blank().at(ch, 0, Cell(48, mirror))).size)
        // An internal clock needs a note on lane 2 (5) first: the same row's earlier lane counts.
        assertEquals(1, warnings(blank.at(2, 0, Cell(28, buzz))).size)
        assertEquals(0, warnings(blank.at(1, 0, Cell(64, pluck)).at(2, 0, Cell(28, buzz))).size)
        assertEquals(1, warnings(blank.at(2, 0, Cell(28, buzz)).at(1, 4, Cell(64, pluck))).size)
        assertEquals(1, warnings(blank.at(5, 0, Cell(28, lead, SaaRules.FX_ENVELOPE, 0x86))).size)
        assertEquals(0, warnings(blank.at(5, 0, Cell(28, lead, SaaRules.FX_ENVELOPE, 0xA6))).size)
        assertEquals(0, warnings(blank.at(4, 0, Cell(64, pluck)).at(5, 0, Cell(28, buzz))).size)
    }

    @Test
    fun syncHeldAtTheLoopPointWarnsUnlessTheLoopRestoresIt() {
        val blank = SamCoupeSystem.blank()
        assertEquals(1, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_SYNC, 2))).size)
        assertEquals(1, warnings(blank.at(4, 20, Cell(effect = SaaRules.FX_SYNC, param = 3))).size)
        assertEquals(0, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_SYNC, 2)).at(1, 8, Cell(effect = SaaRules.FX_SYNC, param = 0))).size)
        assertEquals(0, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_SYNC, 1))).size)
        // The loop order's first row restores it.
        val twoOrders = blank.copy(patterns = blank.patterns + Songs.blankPattern(blank), orders = listOf(0, 1), loopOrder = 0)
        assertEquals(0, warnings(with(with(twoOrders, 1, 4, 3, Cell(effect = SaaRules.FX_SYNC, param = 3)), 0, 2, 0, Cell(effect = SaaRules.FX_SYNC, param = 0))).size)
        // On the CMS each chip has its own: chip A's Y00 does not restore chip B.
        val cms = CmsSystem.blank()
        assertEquals(1, warnings(cms.at(7, 4, Cell(effect = SaaRules.FX_SYNC, param = 2)).at(0, 0, Cell(effect = SaaRules.FX_SYNC, param = 0))).size)
        assertEquals(0, warnings(cms.at(7, 4, Cell(effect = SaaRules.FX_SYNC, param = 2)).at(6, 0, Cell(effect = SaaRules.FX_SYNC, param = 0))).size)
    }

    @Test
    fun otherWarningsCoverOverridesDelaysAndFirstNotes() {
        val blank = SamCoupeSystem.blank()
        // A note-scoped override where no note sounds; on a sounding note it is fine.
        for (code in listOf(SaaRules.FX_PAN, SaaRules.FX_MIXER, SaaRules.FX_NOISE)) {
            assertEquals(1, warnings(blank.at(0, 3, Cell(effect = code, param = 1))).size)
            assertEquals(0, warnings(blank.at(0, 0, Cell(57, lead)).at(0, 3, Cell(effect = code, param = 1))).size)
        }
        assertEquals(1, warnings(blank.at(2, 3, Cell(effect = SaaRules.FX_ENVELOPE, param = 0xA6))).size)
        // After OFF on a note without a release point nothing sounds.
        assertEquals(1, warnings(blank.at(0, 0, Cell(57, pluck)).at(0, 2, Cell(Cell.CUT)).at(0, 3, Cell(effect = SaaRules.FX_MIXER, param = 2))).size)
        // A released note still sounds.
        assertEquals(0, warnings(blank.at(0, 0, Cell(57, lead)).at(0, 2, Cell(Cell.CUT)).at(0, 3, Cell(effect = SaaRules.FX_PAN, param = 0xF0))).size)
        assertEquals(1, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_DELAY, 6))).size)
        assertEquals(0, warnings(blank.at(0, 0, Cell(48, lead, SaaRules.FX_DELAY, 5))).size)
        assertEquals(1, warnings(blank.at(0, 4, Cell(48))).size)
    }

    // ---- arrangement, settings and shared state -------------------------------------------------------------

    @Test
    fun arrangementRenumbersJumpsInEveryColumn() {
        val start = CmsSystem.blank()
        val patterns = List(3) { Songs.blankPattern(start) }
        var song = start.copy(patterns = patterns, orders = listOf(0, 1, 2), parkedOrders = listOf(1))
        song = with(song, 2, 9, 5, Cell(48, 1).withEffect(2, SaaRules.FX_JUMP, 2))
        val arranged = CmsSystem.arranged(song)
        assertEquals(listOf(0, 2), arranged.orders)
        assertEquals(1, CmsSystem.jumpTarget(arranged.patterns[2].tracks[9][5]))
        val loop = CmsSystem.patternLoop(song, 2)
        assertEquals(listOf(2), loop.orders)
        assertEquals(0, CmsSystem.jumpTarget(loop.patterns[2].tracks[9][5]))
        assertEquals(2, u16(SaaLowering.lower(song), SaaPlayerImage.H_ORDERS))
    }

    @Test
    fun multispeedSettingAdjustsAndResets() {
        val song = SamCoupeSystem.blank()
        assertEquals(listOf("Multispeed"), SamCoupeSystem.songSettings.map { it.label })
        val m = SaaSongSettings.ALL.single()
        assertEquals(2, m.adjust(song, 1, 0).saa.multispeed)
        assertEquals(5, m.adjust(song, 0, 1).saa.multispeed)
        assertEquals(8, m.adjust(song, 0, 5).saa.multispeed)
        assertEquals(1, m.adjust(song, -3, 0).saa.multispeed)
        assertEquals(song, m.reset(m.adjust(song, 2, 0)))
    }

    @Test
    fun sharedStateTagsNoiseOwnersEnvelopesAndHeldChips() {
        var song = SamCoupeSystem.blank()
        song = song.at(4, 0, Cell(48, hat))
        song = song.at(1, 0, Cell(76, pluck))
        song = song.at(2, 0, Cell(40, buzz))
        song = song.at(3, 2, Cell(48, noiseSweep))
        song = song.at(5, 4, Cell(48, swell))
        song = song.at(0, 6, Cell(effect = SaaRules.FX_SYNC, param = 2))
        song = song.at(0, 7, Cell(effect = SaaRules.FX_SYNC, param = 0))

        val start = SamCoupeSystem.sharedState(SamCoupeSystem.blank(), 0, 0)!!
        assertEquals(List<String?>(6) { null }, start.tags)
        assertTrue(start.lines.isNotEmpty())

        // The hat on SAA 5 set generator 1 to clock 0; SAA 3's envelope is clocked by SAA 2.
        val first = SamCoupeSystem.sharedState(song, 0, 0)!!
        assertEquals(listOf(null, null, "E←2", null, "N1:31k", null), first.tags)
        assertEquals(0, SaaSharedState.noiseClock(song, 0, 0, generator = 1))
        assertEquals(-1, SaaSharedState.noiseClock(song, 0, 0, generator = 0))

        // The sweep on SAA 4 claims it at clock 3 (twice SAA 4's tone).
        val second = SamCoupeSystem.sharedState(song, 0, 2)!!
        assertEquals("N1:T4", second.tags[3])
        assertNull(second.tags[4])
        assertEquals(3, SaaSharedState.noiseClock(song, 0, 2, generator = 1))

        // The slow swell on SAA 6 runs on the external clock.
        assertEquals("E ext", SamCoupeSystem.sharedState(song, 0, 4)!!.tags[5])

        // Y02 holds the chip; Y00 releases it.
        assertEquals(List(6) { "SYNC" }, SamCoupeSystem.sharedState(song, 0, 6)!!.tags)
        assertEquals("E←2", SamCoupeSystem.sharedState(song, 0, 7)!!.tags[2])

        // CMS: chip B's generators and envelopes are its own (B4 at 7159090 / 256 ≈ 28 kHz; B3 clocked by B2).
        var cms = CmsSystem.blank()
        cms = cms.at(9, 0, Cell(48, hat))
        cms = cms.at(7, 0, Cell(76, pluck))
        cms = cms.at(8, 0, Cell(40, buzz))
        cms = cms.at(6, 0, Cell(effect = SaaRules.FX_SYNC, param = 3))
        val chipB = CmsSystem.sharedState(cms, 0, 0)!!
        assertEquals(List(6) { null } + List(6) { "OFF" }, chipB.tags)
        val after = CmsSystem.sharedState(cms.at(6, 1, Cell(effect = SaaRules.FX_SYNC, param = 0)), 0, 1)!!
        assertEquals(List(6) { null } + listOf(null, null, "E←2", "N1:28k", null, null), after.tags)
        assertEquals(0, SaaSharedState.noiseClock(cms, 0, 0, chip = 1, generator = 1))
    }

    // ---- tuning realisation ----------------------------------------------------------------------------------

    @Test
    fun fineWritesPxxInAFreeColumn() {
        val fine = CmsSystem.fine
        assertEquals("Pxx", fine.name)
        assertEquals(25.0, fine.cents(57, 16), 1e-9)
        val busy = Cell(57, 1, SaaRules.FX_VIBRATO, 0x23)
        val tuned = fine.write(busy, 57, 16)!!
        assertEquals(SaaRules.FX_FINE, tuned.effectCode(1))
        assertEquals(0x90, tuned.effectParam(1))
        assertEquals(16, fine.of(tuned))
        assertEquals(busy, fine.write(tuned, 57, 0))
        var full = Cell(57, 1)
        for (c in 0 until 4) full = full.withEffect(c, SaaRules.FX_VOLUME, 0x0C)
        assertNull(fine.write(full, 57, 8))
        assertNull(fine.of(Cell(57, 1, SaaRules.FX_FINE, 0x70)))
        // Every note of both machines within 3.4 cents of 12-TET (the dividers' resolution).
        for (variant in SaaVariant.entries) for (note in variant.noteRange) {
            assertTrue("${variant.label} note $note", kotlin.math.abs(SaaRules.cents(note, variant)) <= 3.4)
        }
    }

    // ---- lowering ----------------------------------------------------------------------------------------------

    @Test
    fun hardwareEnvelopeControlValuesAndLevels() {
        assertEquals(0x86, SaaHardwareEnvelope(shape = 3).control)
        assertEquals(0xBB, SaaHardwareEnvelope(shape = 5, threeBit = true, invertRight = true, external = true).control)
        assertEquals(0x80, SaaHardwareEnvelope(shape = 0).control)
        assertEquals(SaaHardwareEnvelope(shape = 5, threeBit = true, invertRight = true, external = true, rate = 4),
            SaaHardwareEnvelope.ofControl(0xBB, 4))
        assertNull(SaaHardwareEnvelope.ofControl(0))
        assertEquals((15 downTo 0).toList(), SaaHardwareEnvelope.levels(2, false).toList())
        assertEquals(listOf(14, 12, 10, 8, 6, 4, 2, 0), SaaHardwareEnvelope.levels(3, true).toList())
        assertEquals((0..15).toList() + (15 downTo 0).toList(), SaaHardwareEnvelope.levels(5, false).toList())
        assertEquals(List(16) { 15 }, SaaHardwareEnvelope.levels(1, false).toList())
        assertEquals(15, SaaHardwareEnvelope.right(0, false, true))
        assertEquals(14, SaaHardwareEnvelope.right(0, true, true))
        assertEquals(36, SaaRules.envelopeInterval(SaaHardwareEnvelope(shape = 3)))
        assertEquals(48, SaaRules.envelopeInterval(SaaHardwareEnvelope(shape = 5)))
        assertEquals(24, SaaRules.envelopeInterval(SaaHardwareEnvelope(shape = 7, threeBit = true)))
        assertNull(SaaRules.envelopeInterval(SaaHardwareEnvelope(shape = 3, external = true)))
        assertNull(SaaRules.envelopeInterval(SaaHardwareEnvelope(shape = 2)))
    }

    @Test
    fun headerTablesAndRecordsAreTheCanonicalBytes() {
        val a = SaaInstrument("a", volume = NesEnvelope(listOf(15, 12, 8, 0), release = 2), noise = 2, panLeft = 15, panRight = 3)
        val b = SaaInstrument("b", volume = NesEnvelope(), arpeggio = NesEnvelope(listOf(0, -12), loop = 0),
            pitch = NesEnvelope(listOf(-40, 8)), mixer = NesEnvelope(listOf(3, 2)), pitchRelative = true, noise = 3, panLeft = 4, panRight = 9,
            envelope = SaaHardwareEnvelope(shape = 5, threeBit = true, invertRight = true, external = true, rate = 7, retrigger = true))
        val c = SaaInstrument("c", mixer = NesEnvelope(listOf(1)), envelope = SaaHardwareEnvelope(shape = 3, external = false, rate = 9, retrigger = false))
        val sound = SaaSound(SaaVariant.SAM, List(SaaSound.SLOTS) { when (it) { 0 -> a; 4 -> b; 5 -> c; else -> null } }, multispeed = 2)
        val song = SamCoupeSystem.blank().copy(ticksPerRow = 5, sound = sound)
        val image = SaaLowering.lower(song)
        assertEquals("TTSA", image.copyOfRange(0, 4).decodeToString())
        assertEquals(1, byte(image, 4))
        assertEquals(0, byte(image, 5))
        assertEquals(2, byte(image, 6))
        assertEquals(5, byte(image, 7))
        assertEquals(1, u16(image, 8))
        assertEquals(0, u16(image, 10))
        assertEquals(6, byte(image, 12))
        assertEquals(6, byte(image, 13))
        for (i in 16 until 64) assertEquals("header byte $i", 0, image[i].toInt())
        assertEquals(64, byte(image, 64))
        assertEquals(0, byte(image, 65))
        // Six empty tracks share one run byte at offset 0 (64 empty rows = 0x80 | 63).
        for (ch in 0 until 12) assertEquals(0, u16(image, 320 + 2 * ch))
        val data = 7488
        assertEquals(0xBF, byte(image, data))
        // Slot 1: flags, noise, pan, no envelope, its volume envelope at 1.
        val ra = 6464
        assertArrayEquals(byteArrayOf(0x80.toByte(), 2, 0x3F, 0, 0, 0, 1, 0, -1, -1, -1, -1, -1, -1, 0, 0), image.copyOfRange(ra, ra + 16))
        assertArrayEquals(byteArrayOf(4, 0, -1, -1, 2, 0, 15, 12, 8, 0), image.copyOfRange(data + 1, data + 11))
        // Slot 5: pitch-relative and retrigger, clock 3, pan 4/9, control 0xBB with rate 7, no volume envelope, then
        // its arpeggio, pitch and mixer envelopes in that order.
        val rb = 6464 + 4 * 16
        assertArrayEquals(byteArrayOf(0x83.toByte(), 3, 0x94.toByte(), 0xBB.toByte(), 7, 0, -1, -1, 11, 0, 19, 0, 27, 0, 0, 0),
            image.copyOfRange(rb, rb + 16))
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, -1, -1, 0, -12), image.copyOfRange(data + 11, data + 19))
        assertArrayEquals(byteArrayOf(2, 0, -1, -1, -1, -1, -40, 8), image.copyOfRange(data + 19, data + 27))
        assertArrayEquals(byteArrayOf(2, 0, -1, -1, -1, -1, 3, 2), image.copyOfRange(data + 27, data + 35))
        // Slot 6: an internal envelope stores no rate; no retrigger flag; its volume (the default 15) and mixer.
        val rc = 6464 + 5 * 16
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, -1, 0x86.toByte(), 0, 0, 35, 0, -1, -1, -1, -1, 42, 0, 0, 0), image.copyOfRange(rc, rc + 16))
        assertArrayEquals(byteArrayOf(1, 0, -1, -1, -1, -1, 15, 1, 0, -1, -1, -1, -1, 1), image.copyOfRange(data + 35, data + 49))
        assertEquals(49, u16(image, 14))
        assertEquals(49, SaaLowering.dataSize(song))
        // Empty slots between and above are zero records, and nothing follows the data.
        assertTrue(image.copyOfRange(ra + 16, rb).all { it == 0.toByte() })
        assertTrue(image.copyOfRange(rc + 16, data).all { it == 0.toByte() })
        assertTrue(image.copyOfRange(data + 49, 65536).all { it == 0.toByte() })
        // The engine's lower overwrites a dirty buffer completely.
        val dirty = ByteArray(65536) { 0x55 }
        SamCoupeSystem.engine.lower(song, dirty)
        assertArrayEquals(image, dirty)
        // The CMS header: variant 1, twelve channels.
        val cms = SaaLowering.lower(CmsSystem.blank())
        assertEquals(1, byte(cms, 5))
        assertEquals(12, byte(cms, 13))
        assertEquals(12, byte(cms, 12))
    }

    @Test
    fun orderTableHasTwelveEntriesPerOrderOnBothMachines() {
        val base = SamCoupeSystem.blank()
        val second = Songs.blankPattern(base).let { p -> p.copy(tracks = p.tracks.mapIndexed { c, t -> if (c == 1) t.toMutableList().also { it[0] = Cell(57, 1) } else t }) }
        val song = base.copy(patterns = base.patterns + second, orders = listOf(0, 1))
        val image = SaaLowering.lower(song)
        fun at(o: Int, c: Int) = u16(image, 320 + 2 * (12 * o + c))
        assertEquals(List(12) { 0 }, (0 until 12).map { at(0, it) })
        assertEquals(listOf(0, 1, 0, 0, 0, 0) + List(6) { 0 }, (0 until 12).map { at(1, it) })
        assertArrayEquals(byteArrayOf(0xBF.toByte(), 3, 57, 1, 0xBE.toByte()), image.copyOfRange(7488, 7493))
        // An identical track in another order is stored once.
        val cms = CmsSystem.blank().at(11, 0, Cell(57, 1))
        val twice = cms.copy(patterns = cms.patterns + cms.patterns[0].copy(name = "copy"), orders = listOf(0, 1))
        val cmsImage = SaaLowering.lower(twice)
        for (c in 0 until 12) assertEquals(u16(cmsImage, 320 + 2 * c), u16(cmsImage, 320 + 2 * (12 + c)))
        assertEquals(1, u16(cmsImage, 320 + 2 * 11))
    }

    @Test
    fun rowsEncodeAsThePlayerReadsThem() {
        val cell = Cell(57, 2, SaaRules.FX_VOLUME, 0x0C).withEffect(2, SaaRules.FX_SYNC, 0x01).withEffect(3, SaaRules.FX_ENVELOPE, 0x86)
        val track = List(Songs.ROWS) { when (it) { 3 -> cell; 4 -> Cell(Cell.CUT); else -> Cell.BLANK } }
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x0F, 57, 2, 12, 0x0C, 34, 0x01, 14, 0x86.toByte(), 1, 0xFE.toByte(), (0x80 or 58).toByte()),
            SaaLowering.encode(track, Songs.ROWS),
        )
        val short = List(Songs.ROWS) { if (it == 1) Cell(effect = SaaRules.FX_SPEED, param = 3) else Cell.BLANK }
        assertArrayEquals(byteArrayOf(0x80.toByte(), (1 shl 2).toByte(), 15, 3, 0x81.toByte()), SaaLowering.encode(short, 4))
        // A whole empty pattern is one run byte (64 rows = 0x80 | 63).
        assertArrayEquals(byteArrayOf(0xBF.toByte()), SaaLowering.encode(List(Songs.ROWS) { Cell.BLANK }, Songs.ROWS))
    }

    // ---- exports and demos ---------------------------------------------------------------------------------

    @Test
    fun vgmExportCallsTheNativeRenderer() {
        val demo = SamCoupeSystem.demo()
        val vgm = SamCoupeSystem.exports.single()
        assertThrows(IllegalStateException::class.java) { vgm.export(demo) }
        var seen: Triple<ByteArray, Int, SaaVgmTags>? = null
        SaaVgm.renderer = SaaVgmRenderer { image, maxTicks, tags -> seen = Triple(image, maxTicks, tags); byteArrayOf(1, 2, 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), vgm.export(demo))
        val (image, maxTicks, tags) = seen!!
        assertArrayEquals(SaaLowering.lower(demo), image)
        assertEquals(30000, maxTicks)
        assertEquals(SaaVgmTags(demo.title, demo.artist), tags)
        CmsSystem.exports.single().export(withSound(CmsSystem.demo()) { it.copy(multispeed = 2) })
        assertEquals(72000, seen!!.second)
        assertEquals(1, byte(seen!!.first, 5))
    }

    @Test
    fun demosAreCleanAndShowTheirChips() {
        val coupe = SamCoupeSystem.demo()
        val skyline = CmsSystem.demo()
        assertEquals("Coupé Lights", coupe.title)
        assertEquals("Blaster Skyline", skyline.title)
        for (demo in listOf(coupe, skyline)) {
            val system = demo.system
            assertEquals(emptyList<String>(), system.errors(demo))
            assertEquals(emptyList<String>(), system.warnings(demo))
            assertTrue(demo.loopOrder > 0)
            for (ch in system.channels.indices) assertTrue("${demo.title} lane $ch", demo.patterns.any { p -> p.tracks[ch].any { it.note >= 0 } })
            val cells = demo.orders.toSet().flatMap { demo.patterns[it].tracks.flatten() }
            assertTrue(cells.any { it.more.size >= 2 })
            assertTrue(cells.any { it.note == Cell.CUT })
            val image = SaaLowering.lower(demo)
            assertEquals(SaaLowering.dataSize(demo), u16(image, 14))
            assertTrue(SaaLowering.dataSize(demo) < SaaPlayerImage.DATA_CAPACITY)
        }
        fun envelopeLanes(demo: Song) = demo.system.channels.indices.filter { ch ->
            demo.patterns.any { p -> p.tracks[ch].any { it.instrument > 0 && demo.saa.instrument(it.instrument)!!.envelope != null } }
        }
        // The buzz bass on SAA 3 (internal clock, mixer off); the CMS uses both chips' envelopes.
        assertEquals(listOf(2), envelopeLanes(coupe))
        assertTrue(coupe.patterns.any { p -> p.tracks[2].any { it.instrument == buzz } })
        assertEquals(listOf(2, 8, 11), envelopeLanes(skyline))
        // Drums on SAA 4-6 share noise clock 0.
        val drumClocks = (3..5).flatMap { ch -> coupe.patterns.flatMap { p -> p.tracks[ch].filter { it.instrument > 0 } } }
            .map { coupe.saa.instrument(it.instrument)!! }.filter { it.usesNoise }.map { it.noise }.toSet()
        assertEquals(setOf(0), drumClocks)
        // Stereo: 8xy in the SAM demo; Y01 phase-aligns the CMS unison.
        fun uses(demo: Song, code: Int, param: Int? = null) = demo.patterns.any { p -> p.tracks.any { t -> t.any { c ->
            (0..c.more.size).any { c.effectCode(it) == code && (param == null || c.effectParam(it) == param) }
        } } }
        assertTrue(uses(coupe, SaaRules.FX_PAN))
        assertTrue(uses(skyline, SaaRules.FX_SYNC, SaaRules.SYNC_RESTART))
        // The shortest songs: one row at speed 1 holding A-4 on lane 1 with slot 01.
        for (variant in SaaVariant.entries) {
            val shortest = SaaDemoSong.shortest(variant)
            assertEquals(variant.system, shortest.system)
            assertEquals(emptyList<String>(), variant.system.errors(shortest))
            assertEquals(emptyList<String>(), variant.system.warnings(shortest))
            assertEquals(1, shortest.ticksPerRow)
            assertEquals(1, shortest.patterns.single().length)
            assertEquals(listOf(0), shortest.orders)
            assertEquals(Cell(57, 1), shortest.patterns.single().tracks[0][0])
            assertEquals(1, SaaLowering.lower(shortest)[64].toInt())
            assertEquals(1, SaaLowering.lower(shortest)[12].toInt())
        }
    }

    @Test
    fun effectDetailAndDescriptionsFollowTheLanes() {
        var song = SamCoupeSystem.blank().at(2, 0, Cell(57, lead, SaaRules.FX_ARPEGGIO, 0x47).withEffect(1, SaaRules.FX_ENVELOPE, 0x86))
        song = song.at(0, 0, Cell(effect = SaaRules.FX_ENVELOPE, param = 0x86))
        song = song.at(4, 0, Cell(57, hat, SaaRules.FX_NOISE, 2).withEffect(1, SaaRules.FX_SYNC, 1))
        assertNotNull(SamCoupeSystem.effectDetail(song, 0, 2, 0, 0))
        assertNotNull(SamCoupeSystem.effectDetail(song, 0, 2, 0, 1))
        assertNotNull(SamCoupeSystem.effectDetail(song, 0, 0, 0, 0))
        assertNotNull(SamCoupeSystem.effectDetail(song, 0, 4, 0, 0))
        assertNotNull(SamCoupeSystem.effectDetail(song, 0, 4, 0, 1))
        assertNull(SamCoupeSystem.effectDetail(song, 0, 2, 1, 0))
        for (fx in SaaRules.EFFECTS) assertTrue(SaaRules.describe(song, 0, fx.code, 0x01).isNotEmpty())
    }

    // ---- presets -------------------------------------------------------------------------------------------

    @Test
    fun presetsAreValidAndUseTheirLanes() {
        assertEquals(listOf("Lead", "Square bass", "Pluck", "Stereo pad", "Echo lead", "Kick", "Snare", "Hat", "Noise sweep",
            "Buzz bass", "Slow swell", "Mirror"), SaaPresets.ALL.map { it.name })
        for (p in SaaPresets.ALL) {
            assertEquals(p.name, p.instrument.name)
            assertNull(p.name, SaaValidator.instrumentProblem(p.instrument))
        }
        val buzzBass = SaaPresets.named("Buzz bass")!!.instrument
        assertEquals(SaaHardwareEnvelope.REPETITIVE_DECAY, buzzBass.envelope!!.shape)
        assertFalse(buzzBass.envelope!!.external)
        assertEquals(listOf(SaaInstrument.MIXER_OFF), buzzBass.mixer.values)
        val slowSwell = SaaPresets.named("Slow swell")!!.instrument.envelope!!
        assertTrue(slowSwell.external)
        assertEquals(SaaHardwareEnvelope.REPETITIVE_TRIANGLE, slowSwell.shape)
        assertTrue(SaaPresets.named("Mirror")!!.instrument.envelope!!.invertRight)
        assertEquals(3, SaaPresets.named("Noise sweep")!!.instrument.noise)
        assertTrue(SaaPresets.named("Kick")!!.instrument.pitchRelative)
        assertTrue(SaaPresets.named("Hat")!!.instrument.usesNoise)
        assertNull(SaaPresets.named("Nope"))
    }
}
