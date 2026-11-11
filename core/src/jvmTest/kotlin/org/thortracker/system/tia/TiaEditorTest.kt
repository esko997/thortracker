package org.thortracker.system.tia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.tia.TiaInstrumentView.Page
import org.thortracker.system.tia.TiaInstrumentView.Row

/** The Atari 2600 instrument editor driven through controller actions, its pitch guide and its document edits. */
class TiaEditorTest {
    private val session = EditorSession(TiaNtscSystem.blank())
    private val auditions = ArrayList<List<Int>>()

    init {
        session.onAudition = { ch, note, ins, detune -> auditions += listOf(ch, note, ins, detune) }
    }

    private fun view(setup: ViewState.() -> Unit = {}) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply {
        soundPanel("tia").advanced = true
    }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = TiaInstrumentView.rows(state).indexOf(row)
        assertTrue("row $row is on the page", state.instField >= 0)
    }

    private fun ViewController.showPage(page: Page) {
        state.envelope = TiaInstrumentView.PAGES.indexOf(page)
    }

    /** The last slot: empty in a new song (the presets fill the first slots). */
    private val slot = TiaSystem.SLOTS

    private fun sound(s: Int = slot) = session.song.tiaInstrument(s)

    private val menus = ArrayList<List<Pair<String, () -> Unit>>>()

    private fun installMenus() {
        session.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = Unit
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("no ROM install expected")
            })
        }
    }

    @Test
    fun emptySlotsAreMadeAndPresetsReplaceTheSound() {
        assertNull(sound())
        val v = view { instrument = slot }
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultTiaInstrument(slot), sound())
        assertEquals(0xC, sound()!!.waveform)
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        val first = TiaPresets.ALL.first()
        assertEquals(first.instrument, sound())
        assertEquals(0, TiaInstrumentView.presetIndex(sound()))
        // Stepping wraps through every preset back to the first.
        v.hold(Action.EDIT, *Array(TiaPresets.ALL.size) { Action.RIGHT })
        assertEquals(0, TiaInstrumentView.presetIndex(sound()))
        // A raw preset keeps its raw pitch mode.
        val raw = TiaPresets.ALL.indexOfFirst { it.category == TiaPresets.Category.RAW }
        v.hold(Action.EDIT, *Array(raw) { Action.RIGHT })
        assertTrue(sound()!!.raw)
        // Edit opens the list; choosing an entry applies it.
        installMenus()
        v.tap(Action.EDIT)
        assertEquals(TiaPresets.ALL.size, menus.single().size)
        menus.single()[1].second()
        assertEquals(1, TiaInstrumentView.presetIndex(sound()))
        // Clear: the default div 6 square, keeping the name; Clear on Slot empties it, undo brings it back.
        v.tap(Action.CLEAR)
        assertEquals(defaultTiaInstrument(slot).copy(name = TiaPresets.ALL[1].name), sound())
        v.goTo(Row.SLOT)
        v.tap(Action.CLEAR)
        assertNull(sound())
        session.undo()
        assertEquals(0xC, sound()!!.waveform)
    }

    @Test
    fun waveformAndPitchModeAreEditedWithTheController() {
        val v = view { instrument = slot }
        v.goTo(Row.WAVEFORM)
        // On an empty slot the first change makes the sound: C + 1 = D.
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0xD, sound()!!.waveform)
        v.hold(Action.EDIT, Action.UP, Action.UP) // +4 each, clamped at F
        assertEquals(0xF, sound()!!.waveform)
        v.hold(Action.EDIT, *Array(20) { Action.LEFT })
        assertEquals(0x0, sound()!!.waveform)
        v.tap(Action.CLEAR)
        assertEquals(TiaInstrument.DEFAULT_WAVEFORM, sound()!!.waveform)
        // Pitch mode: tonal ↔ raw by a tap; Clear makes it tonal.
        v.goTo(Row.MODE)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.raw)
        v.tap(Action.EDIT)
        assertEquals(false, sound()!!.raw)
        v.tap(Action.EDIT)
        v.tap(Action.CLEAR)
        assertEquals(false, sound()!!.raw)
        // Listen plays the slot on the Listen channel.
        v.state.listenChannel = 1
        v.tap(Action.AUDITION)
        assertEquals(listOf(1, v.defaultNote(), slot, 0), auditions.last())
    }

    @Test
    fun envelopeStepsAreDrawnAndStayInRange() {
        session.setTiaInstrument(slot, defaultTiaInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        // An empty volume envelope's first step starts at 15: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13), sound()!!.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13, 13), sound()!!.volume.values)
        // One hold is one undo step.
        session.undo()
        assertEquals(listOf(13), sound()!!.volume.values)
        v.state.envStep = 0
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(15), sound()!!.volume.values)
        // Waveform steps are AUDC 0-F and start from the sound's own waveform.
        v.showPage(Page.WAVEFORMS)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.tap(Action.EDIT)
        assertEquals(listOf(0xC), sound()!!.waveforms.values)
        v.state.envStep = 0
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(0xF), sound()!!.waveforms.values)
        // Pitch bottoms out at -128; Mode toggles the relative pitch envelope.
        v.showPage(Page.PITCH)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, *Array(200) { Action.DOWN })
        assertEquals(listOf(TiaEnvelope.PITCH.first), sound()!!.pitch.values)
        v.goTo(Row.RELATIVE)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.relativePitch)
        // Loop and release stay legal: a loop moves before a later release.
        v.showPage(Page.VOLUME)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.UP) // +8
        assertEquals(9, sound()!!.volume.values.size)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, sound()!!.volume.release)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, *Array(9) { Action.RIGHT })
        assertEquals(2, sound()!!.volume.loop)
        assertNull(sound()!!.volume.problem(TiaEnvelope.VOLUME))
    }

    @Test
    fun copyPasteKeepsSlotsSeparate() {
        session.setTiaInstrument(slot - 1, TiaPresets.ALL.first().instrument, "test")
        val v = view { instrument = slot - 1 }
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        assertEquals(slot, v.state.instrument)
        v.tap(Action.PASTE)
        assertEquals(sound(slot - 1), sound())
        // Editing the pasted sound leaves the original alone (two records, never linked).
        v.goTo(Row.WAVEFORM)
        v.hold(Action.EDIT, Action.LEFT)
        assertNotEquals(sound(slot - 1), sound())
        assertEquals(TiaPresets.ALL.first().instrument, sound(slot - 1))
        // Envelopes move between pages, clamped into the target's range.
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, *Array(40) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(40, 40), sound()!!.arpeggio.values)
        v.tap(Action.COPY)
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        v.tap(Action.PASTE)
        assertEquals(listOf(15, 15), sound()!!.volume.values)
    }

    @Test
    fun thePitchGuidePlaysTheDividerUnderTheCursor() {
        session.setTiaInstrument(slot, defaultTiaInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.GUIDE)
        assertEquals(listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.WAVEFORM, Row.MODE, Row.PRESET, Row.PAGE, Row.LADDER),
            TiaInstrumentView.rows(v.state))
        v.goTo(Row.LADDER)
        v.state.envStep = 11
        v.tap(Action.EDIT)
        // AUDF 11 of div 6 is 436.10 Hz (A-4 15 cents flat): played as G#4 plus the upward fine pitch that lands there.
        val (ch, note, ins, fine) = auditions.last()
        assertEquals(listOf(0, 56, slot), listOf(ch, note, ins))
        assertTrue(fine in 0..63)
        assertEquals(11, TiaRules.audf(TiaRegion.NTSC, 0xC, note * 64 + fine))
        // Every divider of every pitched waveform is reachable this way on both standards.
        for (region in TiaRegion.entries) for (w in 0..15) {
            val tonal = TiaInstrument(waveform = w)
            if (TiaRules.period(w) == null) {
                assertNull(TiaInstrumentView.ladderNote(region, tonal, 0, 57))
                continue
            }
            for (a in 0..31) {
                val (n, f) = TiaInstrumentView.ladderNote(region, tonal, a, 57) ?: continue
                assertEquals("AUDC $w AUDF $a $region", a, TiaRules.audf(region, w, n * 64 + f))
            }
        }
        // Raw: the note 31 - AUDF in the 32-semitone cycle nearest the Listen note.
        val raw = TiaInstrument(waveform = 8, raw = true)
        for (a in 0..31) {
            val (n, f) = TiaInstrumentView.ladderNote(TiaRegion.NTSC, raw, a, 57)!!
            assertEquals(0, f)
            assertEquals(a, TiaRules.rawAudf(n))
        }
        // ←→ moves the cursor along the 32 dividers.
        v.tap(Action.RIGHT)
        assertEquals(12, v.state.envStep)
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP, Action.UP, Action.UP)
        assertEquals(31, v.state.envStep)
    }

    @Test
    fun newSongsOfEachStandardKeepTheirRegion() {
        for (system in listOf(TiaNtscSystem, TiaPalSystem)) {
            val s = EditorSession(system.blank())
            assertEquals(system.region, s.song.tia.region)
            assertEquals(system, s.song.system)
            // Edits keep the region: the song stays the variant's.
            assertTrue(s.setTiaWaveform(1, 0x4))
            assertEquals(system, s.song.system)
            assertEquals(emptyList<String>(), system.errors(s.song))
        }
        // The guide's cursor opens on the divider the Listen note plays: the same AUDF, a lower frequency on PAL.
        val pal = EditorSession(TiaPalSystem.blank())
        pal.setTiaInstrument(1, TiaInstrument(waveform = 0xC), "test")
        val v = ViewController(pal, ViewState(kind = ViewKind.INSTRUMENT).apply {
            soundPanel("tia").advanced = true
            instrument = 1
            envelope = TiaInstrumentView.PAGES.indexOf(Page.GUIDE) - 1
        })
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(Page.GUIDE, TiaInstrumentView.page(v.state))
        val expected = TiaRules.audf(TiaRegion.PAL, 0xC, v.defaultNote() * 64)
        assertEquals(expected, v.state.envStep)
        val hzPal = TiaRules.frequency(TiaRegion.PAL, 0xC, 11)!!
        val hzNtsc = TiaRules.frequency(TiaRegion.NTSC, 0xC, 11)!!
        assertTrue(hzPal < hzNtsc)
    }

}
