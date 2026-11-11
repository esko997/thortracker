package org.thortracker.system.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.sms.SmsInstrumentView.Page
import org.thortracker.system.sms.SmsInstrumentView.Row

/** The Master System editor driven through controller actions: entry sounds per kind, FM voice grid, PSG envelopes, kits. */
class SmsEditorTest {
    private fun session(system: SmsSystem) = EditorSession(system.blank()).also { s ->
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    private fun EditorSession.controller(kind: ViewKind, setup: ViewState.() -> Unit = {}) = ViewController(this,
        ViewState(kind = kind).apply { soundPanel("sms").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) { state.instField = SmsInstrumentView.rows(session.song, state).indexOf(row) }

    private fun slot(layout: SmsLayout, name: String) = SmsPresets.forLayout(layout).indexOfFirst { it.name == name } + 1

    @Test
    fun notesOnPsgAndFmLanesGetTheirKindsSound() {
        val session = session(SmsSystem.FM)
        session.step = 0
        for (ch in listOf(0, SmsLayout.FIRST_FM)) {
            val v = session.controller(ViewKind.PATTERN) { channel = ch }
            v.tap(Action.EDIT)
            val ins = session.song.smsInstrument(session.song.patterns[0].tracks[ch][0].instrument)
            assertEquals(if (ch == 0) SmsInstrumentKind.PSG else SmsInstrumentKind.FM, ins?.kind)
        }
        assertTrue(SmsSystem.FM.errors(session.song).toString(), SmsSystem.FM.errors(session.song).isEmpty())
    }

    @Test
    fun fmVoiceGridEditsTheCustomVoice() {
        val session = session(SmsSystem.FM)
        val violin = slot(SmsLayout.FM, "Violin")
        val v = session.controller(ViewKind.INSTRUMENT) { instrument = violin; listenChannel = SmsLayout.FIRST_FM }
        assertEquals(SmsInstrumentView.FM_PAGES, SmsInstrumentView.pages(session.song, v.state))
        // Instrument 1 → custom (0) starts from Violin's registers.
        v.goTo(Row.PATCH)
        v.hold(Action.EDIT, Action.LEFT)
        val fm = session.song.smsInstrument(violin) as SmsFmInstrument
        assertEquals(0, fm.patch)
        assertEquals(OpllPatch.rom(1), fm.custom)
        assertTrue(auditions.isNotEmpty() && auditions.last().first == SmsLayout.FIRST_FM)
        // The carrier's multiplier: R1 moves to the carrier column, Edit+→ raises it.
        v.goTo(Row.OP_MULT)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(1, v.state.envStep)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(OpllPatch.rom(1).carrier.mult + 1, (session.song.smsInstrument(violin) as SmsFmInstrument).custom.carrier.mult)
        // A switch toggles on tap.
        v.goTo(Row.OP_WAVE)
        v.tap(Action.EDIT)
        assertEquals(!OpllPatch.rom(1).carrier.rectified, (session.song.smsInstrument(violin) as SmsFmInstrument).custom.carrier.rectified)
        // Editing a built-in voice's register makes it custom from that instrument.
        val piano = slot(SmsLayout.FM, "Piano")
        v.state.instrument = piano
        v.goTo(Row.OP_AR)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.LEFT)
        val edited = session.song.smsInstrument(piano) as SmsFmInstrument
        assertEquals(0, edited.patch)
        assertEquals(OpllPatch.rom(3).modulator.ar - 1, edited.custom.modulator.ar)
    }

    @Test
    fun psgEnvelopesKeepOneExtraEnvelope() {
        val session = session(SmsSystem.PSG)
        val snare = slot(SmsLayout.PSG, "Noise snare")
        val v = session.controller(ViewKind.INSTRUMENT) { instrument = snare; listenChannel = 0 }
        assertEquals(SmsInstrumentView.PSG_PAGES, SmsInstrumentView.pages(session.song, v.state))
        v.tap(Action.AUDITION)
        assertEquals(SmsLayout.NOISE, auditions.last().first) // a noise sound listens on Noise
        // Pitch: refused while the noise envelope has steps.
        v.state.envelope = SmsInstrumentView.PSG_PAGES.indexOf(Page.PITCH)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP)
        assertTrue((session.song.smsInstrument(snare) as SmsPsgInstrument).pitch.values.isEmpty())
        // Noise page: values are modes 0-3.
        v.state.envelope = SmsInstrumentView.PSG_PAGES.indexOf(Page.NOISE)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP)
        assertEquals(listOf(3), (session.song.smsInstrument(snare) as SmsPsgInstrument).noise.values)
        // Empty the noise envelope; now the pitch envelope takes steps.
        v.goTo(Row.LENGTH)
        v.tap(Action.CLEAR)
        assertEquals(NesEnvelope(), (session.song.smsInstrument(snare) as SmsPsgInstrument).noise)
        v.state.envelope = SmsInstrumentView.PSG_PAGES.indexOf(Page.PITCH)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP)
        assertEquals(listOf(1), (session.song.smsInstrument(snare) as SmsPsgInstrument).pitch.values)
    }

    @Test
    fun kitsAndTypesFollowTheLayout() {
        val session = session(SmsSystem.FM_DRUMS)
        val kit = slot(SmsLayout.FM_DRUMS, "Pitched kit")
        val v = session.controller(ViewKind.INSTRUMENT) { instrument = kit; listenChannel = 10 }
        assertEquals(SmsInstrumentView.KIT_PAGES, SmsInstrumentView.pages(session.song, v.state))
        v.goTo(Row.KIT_MODE)
        v.tap(Action.EDIT)
        assertTrue((session.song.smsInstrument(kit) as SmsDrumKit).fixed)
        v.goTo(Row.KIT_KICK)
        val kick = (session.song.smsInstrument(kit) as SmsDrumKit).kick
        v.hold(Action.EDIT, Action.UP)
        assertEquals(kick + 12, (session.song.smsInstrument(kit) as SmsDrumKit).kick)
        // An empty slot takes the Listen channel's kind; Type steps through PSG sound, FM voice, drum kit.
        v.state.instrument = 60
        v.state.listenChannel = 0
        v.state.instField = 0
        v.tap(Action.EDIT)
        assertEquals(SmsInstrumentKind.PSG, session.song.smsInstrument(60)?.kind)
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT)
        assertEquals(SmsInstrumentKind.FM, session.song.smsInstrument(60)?.kind)
        assertEquals("Sound 3C", session.song.smsInstrument(60)?.name)
        assertEquals(SmsLayout.FIRST_FM, v.state.listenChannel)
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT)
        assertEquals(SmsInstrumentKind.KIT, session.song.smsInstrument(60)?.kind)
        v.state.instField = 0
        v.tap(Action.CLEAR)
        assertNull(session.song.smsInstrument(60))
        // The PSG-only layouts have one kind.
        val psg = session(SmsSystem.GG)
        val w = psg.controller(ViewKind.INSTRUMENT) { instrument = 1 }
        w.goTo(Row.TYPE)
        w.tap(Action.EDIT)
        assertEquals(SmsInstrumentKind.PSG, psg.song.smsInstrument(1)?.kind)
    }

    @Test
    fun copyPasteAndHelp() {
        val session = session(SmsSystem.FM)
        val v = session.controller(ViewKind.INSTRUMENT) { instrument = 1 }
        v.state.instField = 0
        v.tap(Action.COPY)
        v.state.instrument = 61
        v.tap(Action.PASTE)
        assertEquals(session.song.smsInstrument(1), session.song.smsInstrument(61))
        val sections = SmsSystem.FM.help.viewSections(Keymap.DEFAULT).map { it.first }
        assertTrue(sections.containsAll(listOf("Instruments", "PSG sounds", "FM voices and kits")))
        assertEquals(SmsInstrumentView, SmsSystem.FM.view(ViewKind.INSTRUMENT))
        assertNull(SmsSystem.FM.view(ViewKind.WAVE))
        assertEquals(Cell.BLANK, session.song.patterns[0].tracks[0][0])
    }
}
