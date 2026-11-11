package org.thortracker.system.pokey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.model.Songs
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.pokey.PokeyInstrumentView.Page
import org.thortracker.system.pokey.PokeyInstrumentView.Row

/**
 * The POKEY instrument editor driven through semantic controller actions: every edit changes the sound as intended,
 * is one undo step, stays in its legal range, and the kinds' channel constraints show in where Listen plays.
 */
class PokeyEditorTest {
    /** A blank song with slots 49-64 empty, so tests have empty slots whatever the preset count. */
    private val session = EditorSession(PokeySystem.blank().let { s ->
        s.copy(sound = s.pokey.copy(instruments = s.pokey.instruments.mapIndexed { i, ins -> if (i >= 48) null else ins }))
    })
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(slot: Int, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = slot).apply {
            soundPanel("pokey").advanced = true
        }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        val i = PokeyInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("$row is not on page ${PokeyInstrumentView.page(session.song, state)}", i >= 0)
        state.instField = i
    }

    private fun ViewController.showPage(page: Page) {
        val i = PokeyInstrumentView.pages(session.song, state).indexOf(page)
        assertTrue("$page is not a page of this sound", i >= 0)
        state.envelope = i
    }

    private fun ins(slot: Int) = session.song.pokeyInstrument(slot)!!

    /** Runs [edit] and checks it changed the song as one undo step: undo brings back exactly the song before. */
    private fun oneStep(edit: () -> Unit) {
        val before = session.song
        edit()
        assertTrue("the edit changed nothing", session.song != before)
        session.undo()
        assertEquals(before, session.song)
        session.redo()
    }

    private fun sample(name: String, length: Int) = PokeySample(name, List(length) { (it * 7 + name.length) % 16 })

    @Test
    fun replacingTheDocumentDoesNotReinterpretItsEntrySlotAsAPokeySound() {
        // GB 06 -> joined-only POKEY bass; GB 0F -> silent sync clock; old POKEY 10 -> empty.
        // These are real remembered slots, selected by pressing an existing note, not manufactured pens.
        val previous = listOf(
            Songs.blank() to 6,
            Songs.blank() to 15,
            PokeySystem.blank().let { it.copy(sound = it.pokey.withInstrument(16, PokeyInstrument(name = "Old lead"))) } to 16,
        )
        for ((old, slot) in previous) {
            val s = EditorSession(old)
            s.step = 0
            s.setCell(0, 0, 0, Cell(note = 60, instrument = slot))
            val v = ViewController(s, ViewState(kind = ViewKind.PATTERN))
            v.tap(Action.EDIT)
            assertEquals(slot, v.pen(0))
            // The instrument editor's displayed slot is independent of the remembered Pattern pen.
            v.state.instrument = 1
            s.replaceSong(PokeySystem.blank())
            val dormantDisplay = v.state.snapshot()
            v.tap(Action.EDIT)
            val cell = s.song.patterns[0].tracks[0][0]
            assertEquals("old ${old.system.id} slot $slot leaked into the new document", 1, cell.instrument)
            assertNull(PokeySystem.cellProblem(s.song, 0, cell))
            assertEquals(PokeyPresets.instrument("Pure lead"), s.song.pokey.instrument(cell.instrument))
            assertEquals("a display snapshot made before normalization also loses the old slot", 1,
                ViewController(s, dormantDisplay).pen(0))
        }
    }

    @Test
    fun entryMemorySurvivesEditsHistoryAndDisplaySwapsButNotReplacement() {
        val s = EditorSession(PokeySystem.blank())
        s.step = 0
        s.setCell(0, 1, 0, Cell(note = 48, instrument = 6))
        val v = ViewController(s, ViewState(kind = ViewKind.PATTERN, channel = 1))
        v.tap(Action.EDIT)
        v.state.entry.effects[1] = PokeyRules.FX_VOLUME to 7
        v.state.entry.tuned[1] = 48 to 2
        val remembered = v.state.entry.deepCopy()
        s.setTitle("Edited")
        v.normalise()
        assertEquals(remembered, v.state.entry)
        s.undo()
        v.normalise()
        assertEquals(remembered, v.state.entry)
        s.redo()
        val swapped = ViewController(s, v.state.snapshot())
        swapped.normalise()
        assertEquals(remembered, swapped.state.entry)
        assertEquals(6, swapped.pen(0))
        s.replaceSong(PokeySystem.blank())
        swapped.normalise()
        assertEquals(1, swapped.pen(0))
        assertTrue(swapped.state.entry.notes.isEmpty())
        assertTrue(swapped.state.entry.effects.isEmpty())
        assertTrue(swapped.state.entry.pens.isEmpty())
        assertTrue(swapped.state.entry.tuned.isEmpty())
        assertEquals("the original display cannot revive its old entry memory", 1, v.pen(0))
    }

    @Test
    fun anEmptySlotGetsANewSoundAndClearEmptiesIt() {
        val v = view(50)
        assertNull(session.song.pokeyInstrument(50))
        assertEquals(PokeyInstrumentView.TONE_PAGES, PokeyInstrumentView.pages(session.song, v.state))
        v.goTo(Row.SLOT)
        oneStep { v.tap(Action.EDIT) }
        assertEquals(PokeyKind.TONE, ins(50).kind)
        assertEquals("Sound 32", ins(50).name)
        v.tap(Action.CLEAR)
        assertNull(session.song.pokeyInstrument(50))
    }

    @Test
    fun kindSwitchesKeepTheOtherSettingsAndMoveListenWhereTheKindPlays() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50).copy(partnerVolume = 3, filterOffset = -5, rate = 5000), "test")
        val v = view(50) { listenChannel = 0 }
        v.goTo(Row.KIND)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals(PokeyKind.JOINED, ins(50).kind)
        assertEquals(1, v.state.listenChannel) // 16-bit plays on POKEY 2 or 4
        assertEquals(3, ins(50).partnerVolume)
        assertEquals(-5, ins(50).filterOffset)
        assertEquals(5000, ins(50).rate)
        // a tap steps on: high-pass, 16-bit high-pass, samples
        v.tap(Action.EDIT)
        assertEquals(PokeyKind.FILTERED, ins(50).kind)
        v.tap(Action.EDIT)
        assertEquals(PokeyKind.JOINED_FILTERED, ins(50).kind)
        v.tap(Action.EDIT)
        assertEquals(PokeyKind.SAMPLE, ins(50).kind)
        assertEquals(PokeyInstrumentView.SAMPLE_PAGES, PokeyInstrumentView.pages(session.song, v.state))
        assertTrue(v.state.listenChannel in PokeyKind.SAMPLE.lanes)
        // Clear on Kind: back to 8-bit, settings still there
        v.goTo(Row.KIND)
        v.tap(Action.CLEAR)
        assertEquals(PokeyKind.TONE, ins(50).kind)
        assertEquals(3, ins(50).partnerVolume)
    }

    @Test
    fun aDifferenceSoundSwitchedToHighPassBringsItsOffsetIntoRange() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50).copy(difference = true, filterOffset = -40), "test")
        assertTrue(session.setPokeyKind(50, PokeyKind.FILTERED))
        assertEquals(PokeyInstrument.DIFFERENCE_OFFSET.first, ins(50).filterOffset)
    }

    @Test
    fun highPassSettingsClampToTheirRanges() {
        session.setPokeyKind(50, PokeyKind.FILTERED)
        val v = view(50)
        val rows = PokeyInstrumentView.rows(session.song, v.state)
        assertTrue(rows.containsAll(listOf(Row.FILTER, Row.DIFFERENCE, Row.PARTNER_DIST, Row.PARTNER_VOL)))
        v.goTo(Row.FILTER)
        oneStep { v.hold(Action.EDIT, *Array(20) { Action.DOWN }) }
        assertEquals(PokeyInstrument.FILTER_OFFSET.first, ins(50).filterOffset)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(PokeyInstrument.FILTER_OFFSET.first + 1, ins(50).filterOffset)
        // Difference on: the offset moves into 1..127 and stays there
        v.goTo(Row.DIFFERENCE)
        oneStep { v.tap(Action.EDIT) }
        assertTrue(ins(50).difference)
        assertEquals(PokeyInstrument.DIFFERENCE_OFFSET.first, ins(50).filterOffset)
        v.goTo(Row.FILTER)
        v.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(PokeyInstrument.DIFFERENCE_OFFSET.last, ins(50).filterOffset)
        v.hold(Action.EDIT, *Array(20) { Action.DOWN })
        assertEquals(PokeyInstrument.DIFFERENCE_OFFSET.first, ins(50).filterOffset)
        v.tap(Action.CLEAR)
        assertEquals(PokeyInstrument().filterOffset, ins(50).filterOffset)
        // partner channels: volume 0..15, distortion steps 0..8 and wraps
        v.goTo(Row.PARTNER_VOL)
        oneStep { v.hold(Action.EDIT, *Array(6) { Action.UP }) }
        assertEquals(PokeyInstrument.VOLUME.last, ins(50).partnerVolume)
        v.tap(Action.CLEAR)
        assertEquals(0, ins(50).partnerVolume)
        v.goTo(Row.PARTNER_DIST)
        session.setPokeyPartnerDistortion(50, PokeyDistortion.VOLUME_ONLY)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, ins(50).partnerDistortion)
        v.tap(Action.CLEAR)
        assertEquals(PokeyDistortion.PURE, ins(50).partnerDistortion)
        session.setPokeyPartnerDistortion(50, 99) // out of range: clamped
        assertEquals(PokeyDistortion.VOLUME_ONLY, ins(50).partnerDistortion)
    }

    @Test
    fun theClockAndPitchModeAreToggles() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50), "test")
        val v = view(50) { listenChannel = 1 }
        v.goTo(Row.CLOCK)
        oneStep { v.tap(Action.EDIT) }
        assertTrue(ins(50).fast)
        // 8-bit sounds get 1.79 MHz on POKEY 1 and 3 only: on POKEY 2 the flag is kept but does not apply
        assertFalse(PokeyInstrumentView.fastOn(ins(50), 1))
        assertTrue(PokeyInstrumentView.fastOn(ins(50), 0))
        v.tap(Action.CLEAR)
        assertFalse(ins(50).fast)
        v.goTo(Row.MODE)
        oneStep { v.tap(Action.EDIT) }
        assertTrue(ins(50).pitchRelative)
        v.tap(Action.CLEAR)
        assertFalse(ins(50).pitchRelative)
    }

    @Test
    fun volumeStepsLoopAndAReleaseWithoutALoop() {
        session.editPokeyInstrument(50, "test") { it.copy(volume = NesEnvelope(listOf(15, 12))) }
        val v = view(50)
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        oneStep { v.hold(Action.EDIT, Action.DOWN) }
        assertEquals(listOf(14, 12), ins(50).volume.values)
        v.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(listOf(15, 12), ins(50).volume.values)
        v.state.envStep = 2
        oneStep { v.tap(Action.EDIT) }
        assertEquals(listOf(15, 12, 12), ins(50).volume.values)
        v.state.envStep = 1
        v.tap(Action.CLEAR)
        assertEquals(listOf(15, 12), ins(50).volume.values)
        // POKEY envelopes may release without a loop (the step before holds until OFF)
        v.goTo(Row.ENV_RELEASE)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals(NesEnvelope(listOf(15, 12), loop = -1, release = 1), ins(50).volume)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(0, ins(50).volume.loop) // kept before the release
        v.goTo(Row.LENGTH)
        oneStep { v.hold(Action.EDIT, Action.UP) }
        assertEquals(10, ins(50).volume.values.size)
        assertEquals(12, ins(50).volume.values.last())
        v.tap(Action.CLEAR)
        assertEquals(PokeyEnvelopeKind.VOLUME.default, ins(50).volume)
    }

    @Test
    fun releasePointsWorkOnEveryEnvelope() {
        session.editPokeyInstrument(50, "test") { it.copy(arpeggio = NesEnvelope(listOf(0, 4, 7))) }
        val v = view(50)
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.ENV_RELEASE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, ins(50).arpeggio.release)
        v.hold(Action.EDIT, *Array(5) { Action.LEFT })
        assertEquals(-1, ins(50).arpeggio.release)
    }

    @Test
    fun distortionStepsStayWithinZeroToEight() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50), "test")
        val v = view(50)
        v.showPage(Page.DISTORTION)
        v.goTo(Row.STEPS)
        // an empty envelope plays pure tone: the first step starts from A (5)
        oneStep { v.hold(Action.EDIT, Action.UP) }
        assertEquals(listOf(PokeyDistortion.PURE + 1), ins(50).distortion.values)
        v.hold(Action.EDIT, *Array(12) { Action.UP })
        assertEquals(listOf(PokeyDistortion.VOLUME_ONLY), ins(50).distortion.values)
        assertEquals("V", PokeyEnvelopeKind.DISTORTION.valueText(ins(50).distortion.values[0]))
        v.hold(Action.EDIT, *Array(12) { Action.DOWN })
        assertEquals(listOf(0), ins(50).distortion.values)
        // drawing along carries the value; Clear deletes the step under the cursor
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(0, 0, 0), ins(50).distortion.values)
        assertEquals(NesEnvelope(listOf(8, 0)), PokeyEnvelopeKind.DISTORTION.fixed(NesEnvelope(listOf(12, -1))))
        // a pasted volume envelope is clamped into the distortion range
        session.editPokeyInstrument(50, "test") { it.copy(volume = NesEnvelope(listOf(15, 3))) }
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        v.tap(Action.COPY)
        v.showPage(Page.DISTORTION)
        v.goTo(Row.STEPS)
        oneStep { v.tap(Action.PASTE) }
        assertEquals(listOf(8, 3), ins(50).distortion.values)
    }

    @Test
    fun soundsCanOnlyBeHeardWhereTheirKindPlays() {
        session.setPokeyKind(50, PokeyKind.JOINED)
        val v = view(50) { listenChannel = 0 }
        v.tap(Action.AUDITION)
        assertEquals(Triple(1, v.defaultNote(), 50), auditions.last())
        // what each POKEY channel does for a 16-bit note on POKEY 4: 3 is its low byte, 1 and 2 are left alone
        assertEquals(listOf(false, false, true, true), PokeyInstrumentView.roles(PokeyKind.JOINED, 3).map { it != null })
        assertEquals(listOf(false, false, false, false), PokeyInstrumentView.roles(PokeyKind.JOINED, 0).map { it != null })
        assertEquals(listOf(true, true, true, true), PokeyInstrumentView.roles(PokeyKind.JOINED_FILTERED, 1).map { it != null })
        assertEquals(listOf(true, false, true, false), PokeyInstrumentView.roles(PokeyKind.FILTERED, 0).map { it != null })
        // samples are heard on their root note, never on POKEY 3 (timer 3 has no interrupt)
        session.importPokeySample(51, sample("Ping", 64), 7990)
        session.setPokeyRoot(51, 40)
        val w = view(51) { listenChannel = 2 }
        w.tap(Action.AUDITION)
        val heard = auditions.last()
        assertTrue(heard.first in PokeyKind.SAMPLE.lanes)
        assertEquals(40, heard.second)
    }

    @Test
    fun aPresetFillsAnEmptySlotInPlace() {
        val before = session.song.pokey.instruments
        val v = view(50) { listenChannel = 2 }
        v.goTo(Row.PRESET)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        val first = PokeyInstrumentView.presets(session.song).first()
        assertEquals(first.instrument, ins(50))
        assertTrue(v.state.listenChannel in first.instrument.kind.lanes)
        // only slot 50 changed
        assertEquals(before.filterIndexed { i, _ -> i != 49 }, session.song.pokey.instruments.filterIndexed { i, _ -> i != 49 })
        v.tap(Action.CLEAR)
        assertEquals(defaultPokeyInstrument(50), ins(50))
    }

    @Test
    fun sampleSettingsClampToTheSampleAndTheRateRange() {
        assertTrue(session.importPokeySample(50, sample("Ping", 100), 7990))
        assertEquals(PokeyKind.SAMPLE, ins(50).kind)
        assertEquals(0, ins(50).sample)
        val v = view(50)
        v.showPage(Page.SAMPLE)
        assertEquals(Page.SAMPLE, PokeyInstrumentView.page(session.song, v.state))
        // rate: ±10 Hz, ↑↓ to the import rates, clamped to 1000..32000
        v.goTo(Row.RATE)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals(8000, ins(50).rate)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(PokeySamples.IMPORT_RATES.sorted().firstOrNull { it > 8000 } ?: PokeyInstrument.RATES.last, ins(50).rate)
        v.hold(Action.EDIT, *Array(PokeySamples.IMPORT_RATES.size + 2) { Action.UP })
        assertEquals(PokeyInstrument.RATES.last, ins(50).rate)
        v.hold(Action.EDIT, *Array(PokeySamples.IMPORT_RATES.size + 2) { Action.DOWN })
        assertEquals(PokeyInstrument.RATES.first, ins(50).rate)
        v.tap(Action.CLEAR)
        assertEquals(PokeyInstrument.DEFAULT_RATE, ins(50).rate)
        // root note: semitones and octaves within the note range
        v.goTo(Row.ROOT)
        oneStep { v.hold(Action.EDIT, Action.UP) }
        assertEquals(PokeyInstrument.DEFAULT_ROOT + 12, ins(50).root)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(PokeySystem.noteRange.last, ins(50).root)
        v.tap(Action.CLEAR)
        assertEquals(PokeyInstrument.DEFAULT_ROOT, ins(50).root)
        // loop: a tap loops from the start, ←→ one level, ↑↓ a sixteenth, never past the last level
        v.goTo(Row.SAMPLE_LOOP)
        oneStep { v.tap(Action.EDIT) }
        assertEquals(0, ins(50).loop)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(1 + 100 / 16, ins(50).loop)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(99, ins(50).loop)
        v.tap(Action.CLEAR)
        assertEquals(-1, ins(50).loop)
    }

    @Test
    fun theSampleListChoosesRenamesAndDeletesUnusedSamplesOnly() {
        session.importPokeySample(50, sample("Kick", 40), 7990)
        session.importPokeySample(51, sample("Snare", 60), 7990)
        session.setPokeyInstrument(52, defaultPokeyInstrument(52).copy(sample = 1), "test") // an 8-bit sound with a stale sample number
        assertEquals(listOf("Kick", "Snare"), session.song.pokey.samples.map { it.name })
        val v = view(50)
        v.showPage(Page.SAMPLE)
        // the Sample row steps through the song's samples
        v.goTo(Row.SAMPLE)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals(1, ins(50).sample)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, ins(50).sample)
        // the list: choose sample 2, Edit makes this sound play it
        v.goTo(Row.SAMPLES)
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.dpcmSample)
        oneStep { v.tap(Action.EDIT) }
        assertEquals(1, ins(50).sample)
        // rename the listed sample
        v.goTo(Row.SAMPLE_NAME)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals("Tnare", session.song.pokey.samples[1].name)
        // a sample a sound plays is not deleted
        v.goTo(Row.SAMPLES)
        v.state.dpcmSample = 1
        val before = session.song
        v.tap(Action.CLEAR)
        assertEquals(before, session.song)
        // Kick is unused now: deleting it moves Snare down and every sound follows
        v.state.dpcmSample = 0
        oneStep { v.tap(Action.CLEAR) }
        assertEquals(listOf("Tnare"), session.song.pokey.samples.map { it.name })
        assertEquals(0, ins(50).sample)
        assertEquals(0, ins(51).sample)
        assertEquals(0, ins(52).sample)
        assertEquals(sample("Snare", 60).levels, session.song.pokey.samples[ins(51).sample].levels)
    }

    @Test
    fun theStarterKitFillsOnlyEmptySlotsAsOneStep() {
        val menus = ArrayList<List<Pair<String, () -> Unit>>>()
        session.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = Unit
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("no ROM install expected")
            })
        }
        session.setPokeyKind(50, PokeyKind.SAMPLE) // a sample sound with no sample yet
        session.setPokeyInstrument(52, defaultPokeyInstrument(52).copy(name = "Keep me"), "test")
        val before = session.song
        val v = view(50)
        v.showPage(Page.SAMPLE)
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        assertEquals(2, menus.single().size)
        menus.single()[0].second()
        val kit = PokeySamples.starterKit()
        assertEquals(kit, session.song.pokey.samples)
        assertEquals(0, pokeyKitStart(session.song))
        val presets = PokeyPresets.drumKit(0)
        val slots = listOf(50) + (51..PokeySystem.SLOTS).filter { before.pokeyInstrument(it) == null }.take(presets.size - 1)
        for ((i, slot) in slots.withIndex()) assertEquals(presets[i].instrument, ins(slot))
        assertEquals("Keep me", ins(52).name)
        // no other slot changed
        for (slot in 1..PokeySystem.SLOTS) if (slot !in slots) assertEquals(before.pokeyInstrument(slot), session.song.pokeyInstrument(slot))
        // the kit's sounds are offered on the Preset row now
        assertTrue(PokeyInstrumentView.presets(session.song).map { it.instrument }.containsAll(presets.map { it.instrument }))
        // one undo step
        session.undo()
        assertEquals(before, session.song)
        session.redo()
        // adding it again reuses the samples and fills further empty slots only
        val again = session.addPokeyKit(50)
        assertEquals(kit.size, session.song.pokey.samples.size)
        assertTrue(again.none { it in slots })
    }

    @Test
    fun copyPasteAndDuplicateNeverReplaceAnotherSound() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50).copy(name = "Lead", kind = PokeyKind.JOINED), "test")
        session.setPokeyInstrument(51, defaultPokeyInstrument(51).copy(name = "Taken"), "test")
        val v = view(50)
        v.goTo(Row.SLOT)
        oneStep { v.tap(Action.DUPLICATE) }
        assertEquals("Taken", ins(51).name)
        assertEquals(52, v.state.instrument) // the first empty slot after 50: 51 is taken
        assertEquals("Lead+", ins(52).name)
        assertEquals(PokeyKind.JOINED, ins(v.state.instrument).kind)
        v.state.instrument = 50
        v.tap(Action.COPY)
        val w = view(60)
        oneStep { w.tap(Action.PASTE) }
        assertEquals(ins(50), ins(60))
        // a pitch envelope pastes with its mode
        session.editPokeyInstrument(50, "test") { it.copy(pitch = NesEnvelope(listOf(4, -4), loop = 0), pitchRelative = true) }
        v.showPage(Page.PITCH)
        v.goTo(Row.STEPS)
        v.tap(Action.COPY)
        w.showPage(Page.PITCH)
        w.goTo(Row.STEPS)
        w.tap(Action.PASTE)
        assertEquals(ins(50).pitch, ins(60).pitch)
        assertTrue(ins(60).pitchRelative)
    }

    @Test
    fun namesEditLetterByLetter() {
        session.setPokeyInstrument(50, defaultPokeyInstrument(50).copy(name = "Bass"), "test")
        val v = view(50)
        v.goTo(Row.NAME)
        oneStep { v.hold(Action.EDIT, Action.RIGHT) }
        assertEquals("Cass", ins(50).name)
        v.tap(Action.CLEAR)
        assertEquals("ass", ins(50).name)
    }

    @Test
    fun pagesFollowTheKindAndTheCursorKeepsItsRow() {
        session.importPokeySample(50, sample("Ping", 32), 7990)
        session.setPokeyInstrument(51, defaultPokeyInstrument(51), "test")
        val v = view(50)
        v.goTo(Row.PAGE)
        v.tap(Action.RIGHT)
        assertEquals(Page.SAMPLE, PokeyInstrumentView.page(session.song, v.state))
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(Page.ARPEGGIO, PokeyInstrumentView.page(session.song, v.state))
        // moving to an 8-bit sound keeps the Arpeggio page
        v.goTo(Row.LENGTH)
        v.tap(Action.RIGHT)
        assertEquals(51, v.state.instrument)
        assertEquals(Page.ARPEGGIO, PokeyInstrumentView.page(session.song, v.state))
        assertEquals(Row.LENGTH, PokeyInstrumentView.row(session.song, v.state))
        // its Distortion page has no counterpart on a sample sound: back there, the Sound page shows
        v.showPage(Page.DISTORTION)
        v.goTo(Row.LENGTH)
        v.tap(Action.LEFT)
        assertEquals(50, v.state.instrument)
        assertEquals(Page.SOUND, PokeyInstrumentView.page(session.song, v.state))
    }
}
