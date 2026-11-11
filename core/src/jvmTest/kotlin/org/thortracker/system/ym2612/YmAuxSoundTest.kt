package org.thortracker.system.ym2612

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.PcmData
import org.thortracker.system.panel.EnvelopeContour

class YmAuxSoundTest {
    private fun controller(ins: YmInstrument, lane: Int): ViewController {
        val base = YmSystem.blank()
        val samples = listOf(
            YmPcmSample("Kick", PcmData.of(byteArrayOf(30, -40, 20, 0)), 8000),
            YmPcmSample("Bell", PcmData.of(byteArrayOf(-20, 70, -50, 0)), 16000),
        )
        val session = EditorSession(base.copy(sound = base.ym.withInstrument(1, ins).copy(pcmSamples = samples)))
        return ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 2,
            listenChannel = lane, dpcmKey = 36))
    }
    private fun ViewController.tap(action: Action) { press(action); release(action) }
    private fun custom() = YmPsgInstrument(name = "Keep", volume = NesEnvelope(listOf(3, 12, 5, 9), 1, 3),
        arpeggio = NesEnvelope(listOf(0, 7, 12), 0), pitch = NesEnvelope(listOf(1, -2, 3), 0), pitchRelative = true,
        noise = 7, sweep = YmSweep(9, -3, 11))

    @Test fun modeSwitchesAndListeningSelectionPreserveEveryNativeField() {
        for ((ins, lane) in listOf(custom() to YmRules.PSG_FIRST,
            YmPcmInstrument("Kit", 9, mapOf(36 to YmPcmMapping(0, -7), 40 to YmPcmMapping(1, 4))) to YmRules.PCM_FIRST)) {
            val v = controller(ins, lane)
            val original = v.session.song
            YmSoundFace.selectMode(v, true)
            assertTrue(v.state.ymPanel.advanced)
            YmSoundFace.selectMode(v, false)
            assertFalse(v.state.ymPanel.advanced)
            YmAuxSoundControls.nextLane(v)
            YmAuxSoundControls.set(v, YmAuxSoundControls.count(original, v.state) - 1, 0)
            assertSame(original, v.session.song)
        }
    }

    @Test fun customEnvelopeTimeIsUnavailableAndExplicitContourOwnsOnlyVolume() {
        val original = custom()
        val v = controller(original, YmRules.PSG_FIRST)
        assertEquals(-1, YmAuxSoundControls.value(v.session.song, v.state, 0))
        assertFalse(YmAuxSoundControls.enabled(v.session.song, v.state, 1))
        YmAuxSoundControls.set(v, 1, 4)
        assertSame(original, v.session.song.ymInstrument(1))
        YmAuxSoundControls.set(v, 0, EnvelopeContour.Shape.PLUCK.ordinal)
        val changed = v.session.song.ymInstrument(1) as YmPsgInstrument
        assertEquals(original, changed.copy(volume = original.volume))
        assertEquals(EnvelopeContour.Recognized(EnvelopeContour.Shape.PLUCK, 16, 12), EnvelopeContour.read(changed.volume))
        YmAuxSoundControls.set(v, 1, 3)
        assertEquals(32, EnvelopeContour.read((v.session.song.ymInstrument(1) as YmPsgInstrument).volume)!!.ticks)
    }

    @Test fun noiseControlsAreUnavailableOnToneAndPreserveUnownedBits() {
        val original = custom()
        val v = controller(original, YmRules.PSG_FIRST)
        YmAuxSoundControls.set(v, 3, 0)
        assertSame(original, v.session.song.ymInstrument(1))
        v.state.listenChannel = YmRules.NOISE
        YmAuxSoundControls.set(v, 3, 0)
        assertEquals(original.copy(noise = 3), v.session.song.ymInstrument(1))
        YmAuxSoundControls.set(v, 4, 1)
        assertEquals(original.copy(noise = 1), v.session.song.ymInstrument(1))
    }

    @Test fun selectingCurrentInactiveSweepPreservesRawInactiveFields() {
        val original = custom().copy(sweep = YmSweep(33, -17, 0))
        val v = controller(original, YmRules.PSG_FIRST)
        YmAuxSoundControls.set(v, 2, 0)
        assertSame(original, v.session.song.ymInstrument(1))
        YmAuxSoundControls.set(v, 2, 1)
        assertEquals(original.copy(sweep = YmSweep(12, 0, 24)), v.session.song.ymInstrument(1))
    }

    @Test fun sampleChoiceAndTuneChangeOnlyTheSelectedMappingNeverSourceBytes() {
        val original = YmPcmInstrument("Kit", 9, mapOf(36 to YmPcmMapping(0, -7), 40 to YmPcmMapping(1, 4)))
        val v = controller(original, YmRules.PCM_FIRST)
        val sources = v.session.song.ym.pcmSamples
        YmAuxSoundControls.set(v, 1, 2)
        assertEquals(original.copy(pcm = original.pcm + (36 to YmPcmMapping(1, -7))), v.session.song.ymInstrument(1))
        YmAuxSoundControls.set(v, 2, 999)
        assertEquals(YmPcmMapping(1, 24), (v.session.song.ymInstrument(1) as YmPcmInstrument).pcm[36])
        assertEquals(original.pcm[40], (v.session.song.ymInstrument(1) as YmPcmInstrument).pcm[40])
        assertSame(sources, v.session.song.ym.pcmSamples)
        YmAuxSoundControls.set(v, 1, 0)
        assertEquals(original.pcm - 36, (v.session.song.ymInstrument(1) as YmPcmInstrument).pcm)
        assertFalse(YmAuxSoundControls.enabled(v.session.song, v.state, 2))
    }

    @Test fun keySelectionDoesNotCreateAnInstrumentButMappingDoes() {
        val v = controller(YmPcmInstrument(), YmRules.PCM_FIRST)
        v.session.setYmInstrument(1, null, "Empty slot")
        val before = v.session.song
        YmAuxSoundControls.set(v, 0, 40)
        assertSame(before, v.session.song)
        YmAuxSoundControls.set(v, 1, 1)
        assertEquals(mapOf(40 to YmPcmMapping(0)), (v.session.song.ymInstrument(1) as YmPcmInstrument).pcm)
    }

    @Test fun heldControllerLevelEditsAreOneUndoAndAxesRetainTheirMeaning() {
        val original = YmPcmInstrument("Kit", 8, mapOf(36 to YmPcmMapping(0)))
        val v = controller(original, YmRules.PCM_FIRST)
        val before = v.session.song
        v.state.instField = 5
        v.press(Action.EDIT)
        repeat(3) { v.tap(Action.RIGHT) }
        v.release(Action.EDIT)
        assertEquals(original.copy(level = 11), v.session.song.ymInstrument(1))
        v.session.undo()
        assertSame(before, v.session.song)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument)
        assertEquals(5, v.state.instField)
        v.tap(Action.LEFT)
        assertEquals(4, v.state.instField)
        assertSame(before, v.session.song)
    }

    @Test fun listeningNavigationStartsFromTheEffectiveCompatibleLane() {
        val v = controller(custom(), 0)
        YmAuxSoundControls.nextLane(v)
        assertEquals(YmRules.PSG_FIRST + 1, v.state.listenChannel)
    }

    @Test fun pastedAndLockedTypesCannotInheritAHeldParameterEdit() {
        for (paste in listOf(true, false)) {
            val replacement = custom()
            val v = controller(YmFmInstrument(), 0)
            v.session.setYmInstrument(2, replacement, "Second sound")
            v.session.copyYmInstrument(2)
            v.press(Action.EDIT)
            if (paste) v.tap(Action.PASTE) else {
                YmInstrumentView.applyLock(v.state, 2)
                v.normalise()
            }
            val before = v.session.song
            v.tap(Action.RIGHT)
            assertSame(before, v.session.song)
            v.release(Action.EDIT)
            v.press(Action.EDIT)
            v.tap(Action.RIGHT)
            v.release(Action.EDIT)
            assertNotEquals(replacement, v.session.song.ymInstrument(v.state.instrument))
        }
    }
}
