package org.thortracker.system.gb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** The Game Boy Sound panel: its hardware timing, held-Edit listening, touch editing with Undo, and layout. */
class GbSoundPanelTest {
    private val session = EditorSession(GbSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()
    private var releases = 0

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        session.onReleaseAudition = { releases++ }
    }

    private fun pulse(f: GbInstrument.() -> GbInstrument) = GbInstrument(kind = GbBank.PULSE).f()

    private fun note(index: Int) = HugeRules.NOTE_OFFSET + index

    // ---- the APU model behind the timeline ----------------------------------------------------------------------

    @Test
    fun envelopeLengthAndDacFollowTheHardware() {
        val fade = pulse { copy(volume = 12, envelopeDirection = 0, envelopePace = 2) }
        assertEquals(2 / 64.0, SoundTimeline.stepSeconds(fade)!!, 0.0)
        assertEquals(12, SoundTimeline.level(fade, 0.0))
        assertEquals(12, SoundTimeline.level(fade, 2 / 64.0 - 1e-6))
        assertEquals(11, SoundTimeline.level(fade, 2 / 64.0))
        assertEquals(0, SoundTimeline.level(fade, 24 / 64.0))
        assertEquals(0, SoundTimeline.level(fade, 1.9))
        assertEquals(24 / 64.0, SoundTimeline.envelopeEndSeconds(fade)!!, 1e-12)
        // A swell stops at 15; pace 0 holds.
        val swell = pulse { copy(volume = 13, envelopeDirection = 1, envelopePace = 1) }
        assertEquals(15, SoundTimeline.level(swell, 1.0))
        assertEquals(2, SoundTimeline.envelopeSteps(swell))
        assertEquals(9, SoundTimeline.level(pulse { copy(volume = 9, envelopePace = 0) }, 1.5))
        assertNull(SoundTimeline.envelopeEndSeconds(pulse { copy(volume = 15, envelopeDirection = 1, envelopePace = 3) }))
        // NRx2 with volume 0 and Fade switches the DAC off; volume 0 with Swell keeps it on and rises.
        assertTrue(SoundTimeline.dacOff(pulse { copy(volume = 0, envelopeDirection = 0) }))
        val rise = pulse { copy(volume = 0, envelopeDirection = 1, envelopePace = 1) }
        assertFalse(SoundTimeline.dacOff(rise))
        assertEquals(3, SoundTimeline.level(rise, 3 / 64.0))
        assertTrue(SoundTimeline.describe(pulse { copy(volume = 0, envelopeDirection = 0) }).startsWith("Silent"))
        // Length: (64 - L) / 256 s on pulse and noise (6 bits), (256 - L) / 256 s on wave.
        assertNull(SoundTimeline.cutSeconds(pulse { copy(length = 20) }))
        assertEquals(44 / 256.0, SoundTimeline.cutSeconds(pulse { copy(length = 20, lengthEnabled = true) })!!, 0.0)
        assertEquals(1.0, SoundTimeline.cutSeconds(GbInstrument(kind = GbBank.WAVE, length = 0, lengthEnabled = true))!!, 0.0)
        assertEquals(1 / 256.0, SoundTimeline.cutSeconds(GbInstrument(kind = GbBank.WAVE, length = 255, lengthEnabled = true))!!, 0.0)
        // Wave output: NR32 1 = 100%, 2 = 50%, 3 = 25%, 0 = mute, as right shifts of the 4-bit samples.
        assertEquals(listOf(0, 15, 7, 3), (0..3).map { SoundTimeline.level(GbInstrument(kind = GbBank.WAVE, waveVolume = it), 0.0) })
        val described = SoundTimeline.describe(fade.copy(length = 20, lengthEnabled = true))
        assertTrue(described, described.startsWith("Fades 12 → 0 in 375.0 ms, a step every 31.3 ms"))
        assertTrue(described, described.endsWith("length cut at 171.9 ms"))
    }

    @Test
    fun pulseOneSweepUsesBothOverflowChecks() {
        // Period 1046 (driver note 12): shift 0 never changes the pitch, but its upward check (2 × 1046 > 2047) still
        // switches Pulse 1 off at the first clock; period 986 (note 11) passes it for ever.
        val flat = pulse { copy(sweepTime = 1, sweepDirection = 1, sweepShift = 0) }
        assertEquals(1046, SoundTimeline.notePeriod(note(12)))
        assertEquals(1 / 128.0, SoundTimeline.sweepOffSeconds(flat, note(12))!!, 0.0)
        assertNull(SoundTimeline.sweepOffSeconds(flat, note(11)))
        assertEquals(986, SoundTimeline.sweepClock(flat, 986))
        // Shift 1 upward: the trigger check alone silences 1379 + 689 > 2047; 1339 passes it, its first clock writes
        // 2008, and the immediate second check (2008 + 1004) switches the channel off at that clock.
        val up = pulse { copy(sweepTime = 3, sweepDirection = 1, sweepShift = 1) }
        assertTrue(SoundTimeline.sweepSilencesAtTrigger(up, SoundTimeline.notePeriod(note(19))))
        assertEquals(0.0, SoundTimeline.sweepOffSeconds(up, note(19))!!, 0.0)
        assertFalse(SoundTimeline.sweepSilencesAtTrigger(up, 1339))
        assertEquals(-1, SoundTimeline.sweepClock(up, 1339))
        assertEquals(3 / 128.0, SoundTimeline.sweepOffSeconds(up, note(18))!!, 0.0)
        // Time 0 never clocks, even with a shift; the trigger check still applies.
        assertNull(SoundTimeline.sweepClockSeconds(pulse { copy(sweepShift = 3) }))
        assertNull(SoundTimeline.sweepOffSeconds(pulse { copy(sweepShift = 3, sweepDirection = 1) }, note(0)))
        // Downward never overflows; the pitch falls.
        val down = pulse { copy(sweepTime = 2, sweepDirection = 0, sweepShift = 2) }
        assertNull(SoundTimeline.sweepOffSeconds(down, note(60)))
        val after = SoundTimeline.sweepClock(down, 1046)
        assertEquals(1046 - (1046 shr 2), after)
        assertTrue(SoundTimeline.semitones(1046, after) < 0)
        assertTrue(SoundTimeline.describeSweep(down, note(12), "C-4")!!.contains("down"))
        assertNull(SoundTimeline.describeSweep(pulse { this }, note(12), "C-4"))
        assertNull(SoundTimeline.describeSweep(GbInstrument(kind = GbBank.NOISE, sweepTime = 3, sweepShift = 2), note(12), "C-4"))
    }

    @Test
    fun noiseAndDutyMatchTheDriverAndTheEmulator() {
        val long = GbInstrument(kind = GbBank.NOISE, noiseWidth = 0)
        val short = long.copy(noiseWidth = 1)
        val poly = HugeRules.noisePoly(12)
        assertEquals(poly, SoundTimeline.noiseNr43(long, note(12)))
        assertEquals(poly or 0x08, SoundTimeline.noiseNr43(short, note(12)))
        assertEquals(524288.0, SoundTimeline.noiseClockHz(0x00)!!, 0.0)
        assertEquals(4194304.0 / (48 shl 2), SoundTimeline.noiseClockHz(0x23)!!, 0.0)
        assertNull(SoundTimeline.noiseClockHz(0xE0))
        // The 7-bit LFSR repeats every 127 output bits; the 15-bit one does not within that span.
        val seven = BooleanArray(400).also { SoundTimeline.lfsrBits(1, it) }
        for (i in 16 until 250) assertEquals("bit $i", seven[i], seven[i + 127])
        val fifteen = BooleanArray(400).also { SoundTimeline.lfsrBits(0, it) }
        assertTrue((16 until 250).any { fifteen[it] != fifteen[it + 127] })
        // Duty patterns are high for 1, 2, 4 and 6 of their 8 steps.
        assertEquals(listOf(1, 2, 4, 6), SoundTimeline.DUTY_STEPS.map { steps -> steps.count { it } })
    }

    // ---- controller: held Edit listens, one Undo per hold ---------------------------------------------------------

    private fun view(setup: ViewState.() -> Unit) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply(setup))

    @Test
    fun heldEditSoundsTheSoundAndEveryChangeAgainAsOneUndo() {
        val v = view { bank = GbBank.PULSE.ordinal; instrument = 1; instField = 3 } // Volume
        val before = session.song
        v.press(Action.EDIT)
        assertEquals(listOf(Triple(0, v.defaultNote(), 1)), auditions)
        v.press(Action.LEFT); v.release(Action.LEFT)
        v.press(Action.LEFT); v.release(Action.LEFT)
        assertEquals(13, session.song.gb.pulse[0].volume)
        assertEquals(3, auditions.size)
        v.release(Action.EDIT)
        assertTrue(releases >= 1)
        session.undo()
        assertSame(before, session.song)

        // At the top of the range nothing changes, so nothing sounds again.
        auditions.clear()
        session.setInstrument(GbBank.PULSE, 1, session.song.gb.pulse[0].copy(volume = 15))
        v.press(Action.EDIT)
        v.press(Action.UP); v.release(Action.UP)
        v.release(Action.EDIT)
        assertEquals(1, auditions.size)

        // Names and slots are not sound settings: holding Edit there stays silent.
        auditions.clear()
        view { instField = 1 }.apply { press(Action.EDIT); press(Action.RIGHT); release(Action.RIGHT); release(Action.EDIT) }
        view { instField = 0 }.apply { press(Action.EDIT); release(Action.EDIT) }
        assertTrue(auditions.isEmpty())
    }

    @Test
    fun heldListenHearsClearsAndWaveChangesOnTheirChannels() {
        session.setInstrument(GbBank.PULSE, 2, session.song.gb.pulse[1].copy(volume = 4))
        val v = view { bank = GbBank.PULSE.ordinal; instrument = 2; instField = 3 }
        v.press(Action.AUDITION)
        v.press(Action.CLEAR); v.release(Action.CLEAR)
        assertEquals(12, session.song.gb.pulse[1].volume) // the default sound's volume
        assertEquals(2, auditions.size)
        v.release(Action.AUDITION)

        auditions.clear()
        val w = view { bank = GbBank.WAVE.ordinal; instrument = 1; instField = 2 } // Wave
        w.press(Action.EDIT)
        w.press(Action.RIGHT); w.release(Action.RIGHT)
        w.release(Action.EDIT)
        assertEquals(1, session.song.gb.wave[0].wave)
        assertTrue(auditions.isNotEmpty() && auditions.all { it.first == 2 }) // the Wave channel
        assertEquals(ViewKind.WAVE, session.selection.context)
    }

    // ---- touch ----------------------------------------------------------------------------------------------------

    private class RecordingSurface : Surface {
        data class Text(val value: String, val x: Float, val top: Float, val h: Float, val size: Float, val align: Align)
        val texts = ArrayList<Text>()
        val rects = ArrayList<FloatArray>()
        fun clear() { texts.clear(); rects.clear() }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { rects += floatArrayOf(l, t, r, b) }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { rects += floatArrayOf(l, t, r, b) }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += Text(s, x, top, h, size, align)
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }

    private val surface = RecordingSurface()

    private class Panel(val pane: PaneView, val painter: SoundPainter, val area: Rect)

    private fun panel(state: ViewState, width: Int = 1240, height: Int = 1080, density: Float = 2.3f, zoom: PaneZoom = PaneZoom.NORMAL): Panel {
        val pane = PaneView(session, state, density, surface, { 0L }, {})
        val painter = SoundPainter(pane)
        state.zoom = zoom
        return Panel(pane, painter, Rect()).also { draw(it, width, height) }
    }

    private fun draw(p: Panel, width: Int, height: Int) {
        p.pane.ink.layout(width, height, p.pane.state.zoom)
        p.area.set(0f, 0f, width.toFloat(), height - p.pane.ink.unit * 1.9f)
        surface.clear()
        p.pane.controller.normalise()
        p.painter.draw(surface, p.area)
    }

    private fun Panel.tap(r: Rect) {
        assertTrue(painter.down(r.centerX(), r.centerY()))
        painter.up(r.centerX(), r.centerY())
    }

    @Test
    fun aSliderDragIsOneUndoAndUndoDuringItEndsIt() {
        val state = ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 1)
        val p = panel(state)
        val row = p.painter.rowRect(3)!! // Volume
        val y = row.centerY()
        val before = session.song
        assertTrue(p.painter.down(p.painter.railX(3, 0f)!!, y))
        assertEquals(3, state.instField)
        assertEquals(0, session.song.gb.pulse[0].volume)
        p.painter.move(p.painter.railX(3, 0.5f)!!, y)
        p.painter.move(p.painter.railX(3, 1f)!! + 500f, y) // past the end clamps
        p.painter.move(p.painter.railX(3, 0.5f)!!, y)
        p.painter.up(0f, 0f)
        assertEquals(8, session.song.gb.pulse[0].volume)
        assertEquals("Set volume", session.undoLabel)
        session.undo()
        assertSame(before, session.song)

        // Undo in the middle of a drag closes its group; later moves write nothing.
        draw(p, 1240, 1080)
        assertTrue(p.painter.down(p.painter.railX(3, 0f)!!, y))
        session.undo()
        assertSame(before, session.song)
        p.painter.move(p.painter.railX(3, 1f)!!, y)
        p.painter.up(0f, 0f)
        assertSame(before, session.song)
        assertTrue("the undone drag can be redone", session.canRedo)
    }

    @Test
    fun choicesAndTheWaveStripEditByTapAndDragWithListenHeard() {
        val state = ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 1)
        val p = panel(state)
        p.tap(p.painter.cellRect(2, 3)!!) // Duty 75%
        assertEquals(3, session.song.gb.pulse[0].duty)
        assertEquals("Set duty", session.undoLabel)
        // A held Listen hears each touch edit; tapping the chosen value again changes and sounds nothing.
        p.pane.controller.press(Action.AUDITION)
        assertEquals(1, auditions.size)
        draw(p, 1240, 1080)
        p.tap(p.painter.cellRect(2, 1)!!)
        assertEquals(1, session.song.gb.pulse[0].duty)
        assertEquals(2, auditions.size)
        draw(p, 1240, 1080)
        p.tap(p.painter.cellRect(2, 1)!!)
        assertEquals(2, auditions.size)
        p.pane.controller.release(Action.AUDITION)

        // The wave strip: tap wave 2, drag to wave 9 — one undo step, then a following Wave view shows it.
        state.bank = GbBank.WAVE.ordinal
        draw(p, 1240, 1080)
        val cell2 = p.painter.cellRect(2, 2)!!
        val cell9 = p.painter.cellRect(2, 9)!!
        val wavesBefore = session.song
        assertTrue(p.painter.down(cell2.centerX(), cell2.centerY()))
        assertEquals(2, session.song.gb.wave[0].wave)
        p.painter.move(cell9.centerX(), cell2.centerY())
        p.painter.up(cell9.centerX(), cell2.centerY())
        assertEquals(9, session.song.gb.wave[0].wave)
        assertEquals(9, session.selection.wave)
        assertEquals(ViewKind.WAVE, session.selection.context)
        session.undo()
        assertSame(wavesBefore, session.song)
    }

    @Test
    fun tabsSlotsAndTheNameSelectWithoutEditing() {
        val state = ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 1, instField = 4)
        val p = panel(state)
        val before = session.song
        p.tap(p.painter.tabRect(GbBank.NOISE))
        assertEquals(GbBank.NOISE.ordinal, state.bank)
        draw(p, 1240, 1080)
        p.tap(p.painter.slotRect(7))
        assertEquals(7, state.instrument)
        assertEquals(0, state.instField)
        draw(p, 1240, 1080)
        p.tap(p.painter.rowRect(1)!!)
        assertEquals(1, state.instField)
        assertSame(before, session.song)
    }

    @Test
    fun touchEditsExportTheSameBytesAsControllerEdits() {
        val state = ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 3)
        val p = panel(state)
        val row = p.painter.rowRect(5)!! // Env pace 0..7
        p.painter.down(p.painter.railX(5, 3 / 7f)!!, row.centerY())
        p.painter.up(0f, 0f)
        draw(p, 1240, 1080)
        p.tap(p.painter.cellRect(4, 1)!!) // Envelope: Swell
        val touched = session.song

        val other = EditorSession(GbSystem.blank())
        other.editInstrument(GbBank.PULSE, 3, "Set") { it.copy(envelopePace = 3, envelopeDirection = 1) }
        assertEquals(other.song, touched)
        val uge = GbSystem.exports.first { it.extension == "uge" }
        assertArrayEquals(uge.export(other.song), uge.export(touched))
    }

    // ---- layout on the target displays --------------------------------------------------------------------------

    @Test
    fun everyRowStaysVisibleInsideThePaneOnEveryDisplayZoomAndPalette() {
        // Brick (1024×768 at 2), Thor top and bottom (369 dpi), a phone in portrait and landscape, a short 4:3 pane.
        val displays = listOf(Triple(1024, 768, 2f), Triple(1920, 1080, 2.306f), Triple(1240, 1080, 2.306f),
            Triple(1080, 2340, 2.625f), Triple(2340, 1080, 2.625f), Triple(640, 480, 1f))
        session.setInstrument(GbBank.PULSE, 1, session.song.gb.pulse[0].copy(name = "A very long sound", sweepTime = 2,
            sweepShift = 1, sweepDirection = 1, lengthEnabled = true, length = 10, envelopePace = 3))
        for ((w, h, density) in displays) for (zoom in PaneZoom.entries) for (kind in GbBank.entries) {
            val state = ViewState(kind = ViewKind.INSTRUMENT, bank = kind.ordinal, instrument = 1)
            val p = panel(state, w, h, density, zoom)
            for (palette in Palette.all) {
                p.pane.palette = palette
                for (field in 0..1 + GbSoundView.params(state).size) {
                    state.instField = field
                    draw(p, w, h)
                    val where = "${w}x$h@$density $zoom $kind ${palette.id} field $field"
                    if (field > 0) {
                        val row = assertNotNullRow(p.painter.rowRect(field), where)
                        assertTrue("$where row inside", row.top >= p.area.top - 0.5f && row.bottom <= p.area.bottom + 0.5f)
                    }
                    for (t in surface.texts) {
                        val tw = t.value.length * t.size / 2
                        val left = when (t.align) { Align.LEFT -> t.x; Align.CENTER -> t.x - tw / 2; Align.RIGHT -> t.x - tw }
                        assertTrue("$where text '${t.value}' at $left..${left + tw}", left >= -0.5f && left + tw <= w + 0.5f)
                        assertTrue("$where text '${t.value}' vertical", t.top >= -0.5f && t.top + t.h <= p.area.bottom + 0.5f)
                    }
                    for (r in surface.rects) assertTrue("$where rect ${r.toList()}", r[0] >= -0.5f && r[2] <= w + 0.5f && r[1] >= -0.5f && r[3] <= p.area.bottom + 0.5f)
                }
            }
        }
    }

    @Test
    fun tallTouchPanesGrowRowsAndSlotsToTouchSize() {
        val density = 2.625f
        val state = ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 1)
        val p = panel(state, 1080, 2340, density)
        assertTrue(p.painter.timelineShown)
        for (field in 1..1 + GbSoundView.params(state).size) {
            assertTrue("row $field", p.painter.rowRect(field)!!.height() >= 48 * density - 0.5f)
        }
        for (slot in 1..HugeRules.INSTRUMENT_SLOTS) assertTrue("slot $slot", p.painter.slotRect(slot).height() >= 44 * density - 0.5f)
        // The Brick keeps its dense rows and still shows the timeline at 100%.
        val brick = panel(ViewState(kind = ViewKind.INSTRUMENT, bank = GbBank.PULSE.ordinal, instrument = 1), 1024, 768, 2f)
        assertTrue(brick.painter.timelineShown)
        assertTrue(brick.painter.rowRect(1)!!.height() < 48 * 2f)
    }

    private fun assertNotNullRow(r: Rect?, where: String): Rect {
        assertNotNull("$where: the cursor's row must be in view", r)
        return r!!
    }
}
