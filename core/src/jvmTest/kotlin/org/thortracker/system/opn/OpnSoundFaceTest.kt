package org.thortracker.system.opn

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmSweep

class OpnSoundFaceTest {
    private fun fm(algorithm: Int = 4) = OpnFmSound(name = "PC tone", algorithm = algorithm, feedback = 3, ams = 2, fms = 4,
        operators = List(4) { i -> YmOperator(dt = i, mul = i + 2, tl = 30 + i * 9, ks = i, ar = 18 + i,
            am = true, d1r = 4 + i, d2r = 3 + i, sl = 5 + i, rr = 6 + i) },
        ch3 = listOf(64, -128, 17, 256), sweep = YmSweep(8, -2, 11))

    @Test fun everyFmRoutingPreservesOffsetsRatiosAndUnownedOperatorFields() {
        for (algorithm in 0..7) for (control in 0..4) {
            val before = fm(algorithm)
            val after = OpnSoundMacros.set(before, control, OpnSoundMacros.maximum(before, control)) as OpnFmSound
            val restored = when (control) {
                3 -> after.copy(feedback = before.feedback)
                4 -> after.copy(sweep = before.sweep)
                else -> after.copy(operators = after.operators.mapIndexed { i, op ->
                    val old = before.operators[i]
                    if ((control == 2) != before.isCarrier(i)) { assertSame(old, op); op }
                    else when (control) {
                        0 -> op.copy(tl = old.tl)
                        1 -> op.copy(mul = old.mul)
                        else -> op.copy(ar = old.ar, d1r = old.d1r, d2r = old.d2r, sl = old.sl, rr = old.rr)
                    }
                })
            }
            assertEquals(before, restored)
            OpnSoundMacros.value(after, control)?.let { assertSame(after, OpnSoundMacros.set(after, control, it)) }
        }
    }

    @Test fun onlyActiveCarrierSsgBlocksContourAndCustomOffsetsNeverChange() {
        val before = fm()
        for (i in 0..3) for (bits in 0..15) {
            val special = before.copy(operators = before.operators.mapIndexed { k, op -> if (i == k) op.copy(ssgEg = bits) else op })
            val blocked = before.isCarrier(i) && bits and 8 != 0
            assertEquals(!blocked, OpnSoundMacros.enabled(special, 2))
            val after = OpnSoundMacros.set(special, 2, 1) as OpnFmSound
            if (blocked) assertSame(special, after)
            assertEquals(bits, after.operators[i].ssgEg)
            assertEquals(special.ch3, after.ch3)
        }
    }

    @Test fun ssgRecipesOwnOnlyTheirProgramsAndRespectTheSharedHardwareEnvelope() {
        val custom = NesEnvelope(listOf(15, 9, 12, 4), loop = 1, release = 3)
        val before = OpnSsgSound(name = "SSG", volume = custom, arpeggio = NesEnvelope(listOf(0, 7, 12), loop = 0),
            pitch = NesEnvelope(listOf(0, 2, -1), loop = 1), pitchRelative = true,
            mixer = NesEnvelope(listOf(2, 0, 1), loop = 1), noise = NesEnvelope(listOf(3, 9, 7), loop = 0), sweep = YmSweep(12, 0, 20))
        assertNull(OpnSoundMacros.value(before, 0))
        assertNull(OpnSoundMacros.value(before, 1))
        assertNull(OpnSoundMacros.value(before, 3))
        assertFalse(OpnSoundMacros.enabled(before, 2))
        assertSame(before, OpnSoundMacros.set(before, 2, 4))
        val pluck = OpnSoundMacros.set(before, 1, 1) as OpnSsgSound
        assertEquals(before, pluck.copy(volume = before.volume))
        assertEquals(EnvelopeContour.Shape.PLUCK, EnvelopeContour.read(pluck.volume)!!.shape)
        assertSame(pluck, OpnSoundMacros.set(pluck, 1, 1))
        val long = OpnSoundMacros.set(pluck, 2, 4) as OpnSsgSound
        assertEquals(64, EnvelopeContour.read(long.volume)!!.ticks)
        assertEquals(pluck, long.copy(volume = pluck.volume))
        val hardware = before.copy(envelope = before.envelope.copy(shape = S5bShape.SAW_DOWN, period = 1234))
        for (i in 1..2) { assertFalse(OpnSoundMacros.enabled(hardware, i)); assertSame(hardware, OpnSoundMacros.set(hardware, i, 0)) }
        val shape = OpnSoundMacros.set(hardware, 4, 0) as OpnSsgSound
        assertEquals(hardware, shape.copy(envelope = hardware.envelope))
        assertEquals(1234, shape.envelope.period)
        assertEquals(before.copy(mixer = NesEnvelope(listOf(1))), OpnSoundMacros.set(before, 0, 1))
        assertEquals(before.copy(noise = NesEnvelope(listOf(31))), OpnSoundMacros.set(before, 3, 31))
    }

    @Test fun sampleAndRhythmControlsNeverReplaceNativeSampleReferences() {
        val sample = OpnAdpcmSound(name = "Native sample", sample = 7, root = 42, level = 171, sweep = YmSweep(3, -1, 5))
        for (i in 0..4) {
            val changed = OpnSoundMacros.set(sample, i, OpnSoundMacros.maximum(sample, i)) as OpnAdpcmSound
            val restored = when (i) {
                0 -> changed.copy(level = sample.level)
                1 -> changed.copy(root = sample.root)
                2 -> changed.copy(loop = sample.loop)
                3 -> changed.copy(left = sample.left, right = sample.right)
                else -> changed.copy(sweep = sample.sweep)
            }
            assertEquals(sample, restored)
            assertSame(changed, OpnSoundMacros.set(changed, i, OpnSoundMacros.value(changed, i)!!))
        }
        val drum = OpnRhythmSound(name = "Kick", level = 17)
        assertEquals(drum.copy(level = 0), OpnSoundMacros.set(drum, 0, -1))
        assertEquals(drum.copy(left = false, right = true), OpnSoundMacros.set(drum, 1, 2))
    }

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun bothChipsKeepAllNativeTypesReachableAndOpeningNeverChangesTheirState() {
        for (chip in OpnChip.entries) {
            val session = EditorSession(if (chip.opna) OpnaSystem.blank() else OpnSystem.blank())
            val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 60))
            for (part in chip.parts) {
                OpnEdits.set(session, 60, OpnEdits.newSound(chip, part, 60), "Fixture")
                val before = session.song
                v.normalise()
                repeat(2) { OpnSoundFace.selectMode(v, true); v.normalise(); OpnSoundFace.selectMode(v, false); v.normalise() }
                assertSame(before, session.song)
                assertEquals(part, OpnSoundFace.current(v)!!.part)
                OpnSoundFace.selectMode(v, true)
                v.state.instField = OpnInstrumentView.rows(session.song, v.state).indexOf(OpnInstrumentView.Row.TYPE)
                v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
                assertNotEquals(part, OpnSoundFace.current(v)!!.part)
                session.undo(); assertSame(before, session.song)
                OpnSoundFace.selectMode(v, false)
            }
        }
    }

    @Test fun emptySlotsCreateTheListenChannelsNativeTypeAndUndoRestoresAbsence() {
        val session = EditorSession(OpnaSystem.blank())
        for (part in OpnPart.entries) {
            OpnEdits.set(session, 60, null, "Empty")
            val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 60,
                listenChannel = OpnEdits.firstChannel(OpnChip.YM2608, part), instField = 2))
            val before = session.song
            v.press(Action.EDIT); v.tap(Action.LEFT); v.release(Action.EDIT)
            assertEquals(part, session.song.opn.instrument(60)!!.part)
            session.undo(); assertSame(before, session.song)
        }
    }

    @Test fun soundCopyIgnoresParkedSsgEnvelopePageAndCopiesTheWholePatch() {
        val session = EditorSession(OpnaSystem.blank())
        val source = OpnEdits.defaultSsg(OpnChip.YM2608, 50)
        OpnEdits.set(session, 50, source, "Fixture")
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 50, instField = 4))
        v.tap(Action.COPY); v.tap(Action.DOWN); v.tap(Action.PASTE)
        assertEquals(source, session.song.opn.instrument(51))
    }

    @Test fun crossingNativeTypesClampsTheControlAndPlaybackEditsKeepSharedDataAndSampleBytes() {
        val blank = OpnaSystem.blank()
        val sample = OpnaSample("Recorded", 16000, AdpcmBytes(ByteArray(32) { it.toByte() }))
        val bank = blank.opn.copy(samples = listOf(sample), lfo = 11, fm3Mode = OpnFm3Mode.SPECIAL, timerA = 345, rhythmLevel = 41)
            .withInstrument(50, fm())
            .withInstrument(51, OpnRhythmSound("Kit", level = 23))
            .withInstrument(52, OpnAdpcmSound("Recording", level = 180))
        val session = EditorSession(blank.copy(sound = bank))
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 50, instField = 6))
        v.tap(Action.DOWN)
        assertEquals(51, v.state.instrument)
        assertEquals(3, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(52, v.state.instrument)
        val before = session.song
        v.state.instField = 2
        v.press(Action.EDIT); v.tap(Action.LEFT); v.release(Action.EDIT)
        assertEquals(179, (session.song.opn.instrument(52) as OpnAdpcmSound).level)
        assertSame(sample, session.song.opn.samples.single())
        assertEquals(before, session.song.copy(sound = session.song.opn.withInstrument(52, before.opn.instrument(52))))
        session.undo(); assertSame(before, session.song)
    }

    @Test fun heldTypeSwitchCannotContinueIntoTheNewNativeKindUntilReleased() {
        val session = EditorSession(OpnaSystem.blank())
        OpnEdits.set(session, 60, fm(), "Fixture")
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 60))
        OpnSoundFace.selectMode(v, true)
        v.state.instField = OpnInstrumentView.rows(session.song, v.state).indexOf(OpnInstrumentView.Row.TYPE)
        v.press(Action.EDIT); v.tap(Action.RIGHT)
        val ssg = session.song
        assertTrue(ssg.opn.instrument(60) is OpnSsgSound)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        assertSame(ssg, session.song)
        v.release(Action.EDIT)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(session.song.opn.instrument(60) is OpnRhythmSound)
    }
}
