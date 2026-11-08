package org.thortracker.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thortracker.editor.ViewKind
import org.thortracker.model.Phrase
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.system.gb.GbSystem

class WorkspaceCodecTest {
    /** A workspace as 0.8.6 saved it (org.json, phrase notes counted from C-3). */
    private val v086 = """[{"kind":"SKETCH","pinned":true,"follow":false,"locked":2,"order":1,"row":5,"channel":2,"field":1,""" +
        """"instrument":3,"wave":4,"scroll":0,"octave":5,"bank":1,"followPlayback":true,"entryMode":false,"songItem":5,""" +
        """"songField":0,"instField":0,"waveRow":1,"waveCol":0,"waveLevel":8,"kbRow":1,"kbField":0,"kbKey":7,"helpPage":0,""" +
        """"textPos":0,"sketchRoot":9,"sketchScale":"NATURAL_MINOR","sketchRow":3,"sketchField":0,"sketchChord":2,""" +
        """"sketchTone":1,"sketchCircle":false,"sketchCircleKey":3,"sketchCircleMinor":true,"phraseField":6,"phraseLength":6,""" +
        """"phraseRhythm":"EVEN","phraseMotion":"STEPS","phraseChordTones":true,"phraseSeed":42,"keepRhythm":false,""" +
        """"keepPitches":true,"phrase":[33,-1,0,-1,71,-1]},{"kind":"KEYBOARD","octave":3,"kbKey":24,"phraseNotes":[60,-1,62]}]"""

    @Test fun oldPhrasesKeepTheirPitchesAndEverythingElseReadsAsBefore() {
        val (sketch, keyboard) = WorkspaceCodec.decode(v086)
        // hUGETracker's A-5, C-3 and B-8 (A4 = 440 Hz, C2, B7): the same pitches, now in scientific pitch.
        assertEquals(Phrase(listOf(57, null, 24, null, 95, null)), sketch.sketchPhrase)
        assertEquals(listOf("A-5", "C-3", "B-8"), sketch.sketchPhrase!!.pitches.map(GbSystem::noteName))
        // Octaves and keys were never note numbers: unchanged.
        assertEquals(5, sketch.octave)
        assertEquals(7, sketch.kbKey)
        assertEquals(ViewKind.SKETCH, sketch.kind)
        assertEquals(Scale(9, ScaleKind.NATURAL_MINOR), sketch.sketchScale)
        assertEquals(42L, sketch.sketchPhraseSeed)
        assertEquals(true, sketch.sketchKeepPitches)
        assertEquals(2, sketch.lockedSubject)
        assertEquals(ViewKind.KEYBOARD, keyboard.kind)
        assertEquals(3, keyboard.octave)
        assertEquals(24, keyboard.kbKey)
        // Development builds' "phraseNotes" (octave naming of an unknown system) regenerates.
        assertNull(keyboard.sketchPhrase)
    }

    @Test fun savingWritesAbsoluteNotesThatReadBackUnchanged() {
        val states = WorkspaceCodec.decode(v086)
        val text = WorkspaceCodec.encode(states)
        assertEquals(false, text.contains("\"phrase\":"))
        assertEquals(true, text.contains("\"phrasePitches\":[57,-1,24,-1,95,-1]"))
        assertEquals(false, text.contains("\"phraseNotes\""))
        assertEquals(states, WorkspaceCodec.decode(text))
    }

    @Test fun unusablePhrasesAndJunkFallBackToDefaults() {
        assertNull(WorkspaceCodec.decode("""[{"kind":"SKETCH","phrase":[-1,-1]}]""")[0].sketchPhrase)
        assertNull(WorkspaceCodec.decode("""[{"kind":"SKETCH","phrasePitches":[200]}]""")[0].sketchPhrase)
        assertEquals(listOf(ViewKind.PATTERN, ViewKind.KEYBOARD), WorkspaceCodec.decode("not json").map { it.kind })
    }

    @Test fun nesInstrumentEditorCursorPersists() {
        val state = org.thortracker.editor.ViewState(kind = ViewKind.INSTRUMENT, envelope = 2, envStep = 17, listenChannel = 3)
        val back = WorkspaceCodec.decode(WorkspaceCodec.encode(listOf(state, org.thortracker.editor.ViewState())))[0]
        assertEquals(listOf(2, 17, 3), listOf(back.envelope, back.envStep, back.listenChannel))
        // Workspaces from before these fields read the defaults.
        assertEquals(listOf(0, 0, 0), WorkspaceCodec.decode(v086)[0].let { listOf(it.envelope, it.envStep, it.listenChannel) })
    }
}
