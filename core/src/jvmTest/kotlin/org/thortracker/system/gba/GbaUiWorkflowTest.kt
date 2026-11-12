package org.thortracker.system.gba

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.GbaExportConstraints
import org.thortracker.backend.GbaXmExporter
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.ProjectCodec
import org.thortracker.system.gba.GbaSoundView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class GbaUiWorkflowTest {
    private fun session() = EditorSession(GbaSystem.blank().copy(sound = GbaSound()))
    private fun sample(name: String = "Original") = GbaSample(name, List(256) { (it % 64) * 3 - 96 })
    private fun controller(session: EditorSession) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, dpcmKey = 48, octave = 4))
    private fun focus(v: ViewController, row: Row) { v.state.instField = GbaSoundView.rows(v.state).indexOf(row) }
    private fun ViewController.tap(action: Action) { press(action); release(action) }

    @Test fun wavImportTransformMappingAndDeleteAreRealUndoableMenuOperations() {
        val s = session()
        val v = controller(s)
        val menu = Menus()
        s.onMenuRequest = { it(menu) }
        val empty = s.song
        GbaSampleMenu.open(v)
        menu.choose("Import WAV; map all")
        menu.file!!("Recorded", ".wav", wav())
        assertEquals("Recorded", s.song.gba.samples.single().name)
        assertTrue(s.song.gba.instrument(1)!!.keymap.all { it == 0 })
        assertFalse(s.song.gba.samples.single().pcm.isEmpty())
        val imported = s.song
        GbaSampleMenu.open(v); menu.choose("Normalise peak")
        assertNotEquals(imported.gba.samples.single().pcm, s.song.gba.samples.single().pcm)
        s.undo(); assertSame(imported, s.song)
        GbaSampleMenu.open(v); menu.choose("Trim to frame")
        menu.accept("1"); menu.accept("7")
        assertEquals(6, s.song.gba.samples.single().pcm.size)
        s.undo(); assertSame(imported, s.song)
        GbaSampleMenu.open(v); menu.choose("Gain…"); menu.accept("50")
        assertTrue(s.song.gba.samples.single().pcm.maxOrNull()!! < imported.gba.samples.single().pcm.maxOrNull()!!)
        s.undo()
        GbaSampleMenu.open(v); menu.choose("Fade in / out")
        menu.accept("2"); menu.accept("2")
        assertNotEquals(imported.gba.samples.single().pcm, s.song.gba.samples.single().pcm)
        s.undo()
        GbaSampleMenu.open(v); menu.choose("Resample")
        menu.accept("16726")
        assertEquals(16726, s.song.gba.samples.single().sampleRate)
        assertEquals(16, s.song.gba.samples.single().pcm.size)
        s.undo()
        GbaSampleMenu.open(v); menu.choose("Rename sample"); menu.accept("New name")
        assertEquals("New name", s.song.gba.samples.single().name)
        s.undo()
        GbaSampleMenu.open(v); menu.choose("Delete sample ("); menu.choose("Delete sample")
        assertTrue(s.song.gba.samples.isEmpty())
        assertTrue(s.song.gba.instrument(1)!!.keymap.all { it == -1 })
        s.undo(); assertSame(imported, s.song)
        s.undo(); assertSame(empty, s.song)
        assertTrue(menu.errors.isEmpty())
    }

    @Test fun originalKitAndSingleKeyWavKeepOtherMappingsAndAuditionTheSelectedKey() {
        val s = session(); val v = controller(s); val menu = Menus()
        s.onMenuRequest = { it(menu) }
        GbaSampleMenu.open(v); menu.choose("Add original drum kit"); menu.choose("Add kit")
        assertEquals(listOf("Kick", "Snare", "Hat"), s.song.gba.samples.map { it.name })
        assertEquals(listOf(0, 1, 2), s.song.gba.instrument(1)!!.keymap.subList(48, 51))
        GbaSoundView.set(v, Row.KEY, 53)
        GbaSampleMenu.open(v); menu.choose("Import WAV; map only")
        menu.file!!("Clap", ".wav", wav())
        assertEquals(listOf(0, 1, 2), s.song.gba.instrument(1)!!.keymap.subList(48, 51))
        assertEquals(3, s.song.gba.instrument(1)!!.keymap[53])
        val notes = mutableListOf<Int>(); var releases = 0
        s.onAudition = { _, note, _, _ -> notes += note }
        s.onReleaseAudition = { releases++ }
        v.press(Action.AUDITION); v.release(Action.AUDITION)
        assertEquals(listOf(53), notes)
        assertTrue(releases > 0)
        assertTrue(GbaExportConstraints.errors(s.song.gba).isEmpty())
        assertEquals(s.song, ProjectCodec.decode(ProjectCodec.encode(s.song)))
        assertTrue(GbaXmExporter.export(s.song).size > 336)
    }

    @Test fun sampleDeletionRepairsEveryInstrumentAndCrossDocumentClipboardCarriesPcm() {
        val s = session()
        s.addGbaSamples(1, listOf(sample("First")))
        s.addGbaSamples(2, listOf(sample("Second")))
        val before = s.song
        s.deleteGbaSample(0)
        assertTrue(s.song.gba.instrument(1)!!.keymap.all { it == -1 })
        assertTrue(s.song.gba.instrument(2)!!.keymap.all { it == 0 })
        assertEquals("Second", s.song.gba.samples.single().name)
        s.undo(); assertSame(before, s.song)
        s.copyGbaInstrument(2)
        val destination = session()
        destination.hold(s.clipboard!!)
        destination.pasteGbaInstrument(4)
        assertEquals("Second", destination.song.gba.samples.single().name)
        assertTrue(destination.song.gba.instrument(4)!!.keymap.all { it == 0 })
        destination.undo(); assertTrue(destination.song.gba.samples.isEmpty())
    }

    @Test fun faceSwitchesPreserveArbitraryPatchAndHistoryAndHeldDirectionsCannotLeak() {
        val s = session()
        s.addGbaSamples(1, listOf(sample()))
        s.editGbaInstrument(1, "Custom") { it.copy(fadeout = 31) }
        val v = controller(s)
        focus(v, Row.VOLUME)
        val before = s.song; val label = s.undoLabel
        v.press(Action.EDIT)
        GbaSoundView.selectMode(v, true)
        v.tap(Action.RIGHT)
        GbaSoundView.selectMode(v, false)
        v.release(Action.EDIT)
        assertSame(before, s.song)
        assertEquals(label, s.undoLabel)
        assertEquals(31, s.song.gba.instrument(1)!!.fadeout)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument)
        assertSame(before, s.song)
    }

    @Test fun envelopePointEditingMaintainsTickOrderAndFadeoutWritesConverterPrecision() {
        val s = session(); s.addGbaSamples(1, listOf(sample()))
        val v = controller(s)
        GbaSoundView.selectMode(v, true); GbaSoundView.selectPage(v, 2)
        GbaSoundView.set(v, Row.ENABLED, 1)
        focus(v, Row.ADD_POINT); GbaSoundView.activate(v)
        assertEquals(3, s.song.gba.instrument(1)!!.volumeEnvelope.points.size)
        GbaSoundView.set(v, Row.TICK, 1)
        assertEquals(13, s.song.gba.instrument(1)!!.volumeEnvelope.points.last().tick)
        GbaSoundView.set(v, Row.VALUE, 17)
        GbaSoundView.set(v, Row.SUSTAIN, 1)
        GbaSoundView.set(v, Row.ENV_LOOP_START, 0)
        GbaSoundView.set(v, Row.ENV_LOOP_END, 2)
        GbaSoundView.set(v, Row.FADEOUT, 16)
        assertEquals(512, s.song.gba.instrument(1)!!.fadeout)
        focus(v, Row.REMOVE_POINT); GbaSoundView.activate(v)
        val env = s.song.gba.instrument(1)!!.volumeEnvelope
        assertEquals(2, env.points.size)
        assertEquals(-1, env.loopStart)
        assertEquals(-1, env.loopEnd)
        assertTrue(GbaExportConstraints.errors(s.song.gba).isEmpty())
    }

    @Test fun staleFileDialogCannotWriteIntoAnotherDocumentOrSlot() {
        val s = session(); val v = controller(s); val menu = Menus()
        s.onMenuRequest = { it(menu) }
        GbaSampleMenu.open(v); menu.choose("Import WAV; map all")
        GbaSoundView.moveSlot(v, 1)
        val before = s.song
        menu.file!!("Late", ".wav", wav())
        assertSame(before, s.song)
        assertTrue(menu.errors.isNotEmpty())
    }

    @Test fun heldEditTouchPreviewsAndWaveDragIsOneUndoStepAtCompactSize() {
        val s = session(); s.addGbaSamples(1, listOf(sample().copy(loopEnd = 256, volume = 32)))
        val panel = Panel(s)
        focus(panel.pane.controller, Row.VOLUME)
        panel.draw()
        var heard = 0
        s.onAudition = { _, _, _, _ -> heard++ }
        panel.pane.controller.press(Action.EDIT)
        val afterPress = heard
        val target = panel.painter.fieldTarget(Row.VOLUME)!!
        panel.painter.down(target.right - 1, target.centerY()); panel.painter.up(target.right - 1, target.centerY())
        assertEquals(33, s.song.gba.samples.single().volume)
        assertTrue(heard > afterPress)
        panel.pane.controller.release(Action.EDIT)
        val before = s.song
        panel.draw()
        val wave = panel.painter.waveformTarget()
        assertTrue(wave.height() > 0)
        panel.painter.down(wave.left + wave.width() * 0.2f, wave.centerY())
        panel.painter.move(wave.left + wave.width() * 0.35f, wave.centerY())
        panel.painter.move(wave.left + wave.width() * 0.4f, wave.centerY())
        panel.painter.up(wave.centerX(), wave.centerY())
        assertTrue(s.song.gba.samples.single().loopStart > 0)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun everyAdvancedControlRemainsReachableOnCompactDisplayWithoutMutatingData() {
        val s = session(); s.addGbaSamples(1, listOf(sample()))
        val panel = Panel(s)
        val before = s.song
        GbaSoundView.selectMode(panel.pane.controller, true)
        for (page in 0..3) {
            GbaSoundView.selectPage(panel.pane.controller, page)
            for (row in GbaSoundView.rows(panel.state)) {
                if (row == Row.MODE) continue
                focus(panel.pane.controller, row); panel.draw()
                val target = panel.painter.fieldTarget(row)!!
                assertTrue(target.width() > 100)
                assertTrue(target.height() > 0)
                assertTrue(target.left >= 0 && target.right <= 320 && target.bottom <= panel.area.bottom)
            }
        }
        panel.draw()
        val mode = panel.painter.modeTarget(false)
        panel.painter.down(mode.centerX(), mode.centerY()); panel.painter.up(mode.centerX(), mode.centerY())
        assertFalse(GbaSoundView.advanced(panel.state))
        assertSame(before, s.song)
    }

    private class Menus : MenuContext {
        var choices: List<Pair<String, () -> Unit>> = emptyList()
        var input: ((String) -> Unit)? = null
        var file: ((String, String, ByteArray) -> Unit)? = null
        val errors = mutableListOf<String>()
        fun choose(prefix: String) = choices.first { it.first.startsWith(prefix) }.second()
        fun accept(value: String) { val callback = input!!; input = null; callback(value) }
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { choices = entries }
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) { input = accepted }
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) { file = picked }
        override fun status(message: String) {}
        override fun error(message: String) { errors += message }
        override fun installRhythmRom(bytes: ByteArray): String? = "Not used"
    }

    private class Panel(session: EditorSession) {
        val state = ViewState(kind = ViewKind.INSTRUMENT, dpcmKey = 48)
        val surface = TestSurface()
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = GbaSoundPainter(pane)
        val area = Rect()
        fun draw() {
            pane.ink.layout(320, 240, state.zoom)
            area.set(0f, 0f, 320f, 240 - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            painter.draw(surface, area)
        }
    }
    private class TestSurface : Surface {
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {}
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {}
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = s.length * size / 2
    }
    private fun wav(): ByteArray = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(44); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8363); putInt(8363); putShort(1); putShort(8)
        put("data".toByteArray()); putInt(8); put(byteArrayOf(128.toByte(), 144.toByte(), 160.toByte(), 144.toByte(), 128.toByte(), 112, 96, 112))
    }.array()
}
