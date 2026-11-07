package org.thortracker.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thortracker.editor.ViewKind
import org.thortracker.model.Phrase
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Songs

class WorkspaceCodecTest {
    /** A workspace as 0.8.6 saved it (org.json, phrase notes counted from C-3). */
    private val v086 = """[{"kind":"SKETCH","pinned":true,"follow":false,"locked":2,"order":1,"row":5,"channel":2,"field":1,""" +
        """"instrument":3,"wave":4,"scroll":0,"octave":5,"bank":1,"followPlayback":true,"entryMode":false,"songItem":5,""" +
        """"songField":0,"instField":0,"waveRow":1,"waveCol":0,"waveLevel":8,"kbRow":1,"kbField":0,"kbKey":7,"helpPage":0,""" +
        """"textPos":0,"sketchRoot":9,"sketchScale":"NATURAL_MINOR","sketchRow":3,"sketchField":0,"sketchChord":2,""" +
        """"sketchTone":1,"sketchCircle":false,"sketchCircleKey":3,"sketchCircleMinor":true,"phraseField":6,"phraseLength":6,""" +
        """"phraseRhythm":"EVEN","phraseMotion":"STEPS","phraseChordTones":true,"phraseSeed":42,"keepRhythm":false,""" +
        """"keepPitches":true,"phrase":[33,-1,0,-1,71,-1]},{"kind":"KEYBOARD","octave":3,"kbKey":24}]"""

    @Test fun oldPhrasesKeepTheirPitchesAndEverythingElseReadsAsBefore() {
        val (sketch, keyboard) = WorkspaceCodec.decode(v086)
        // A-5, rest, C-3, rest, B-8, rest: the same pitches, now counted from C-0.
        assertEquals(Phrase(listOf(69, null, 36, null, 107, null)), sketch.sketchPhrase)
        assertEquals(listOf("A-5", "C-3", "B-8"), sketch.sketchPhrase!!.pitches.map(Songs::noteName))
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
        assertNull(keyboard.sketchPhrase)
    }

    @Test fun savingWritesAbsoluteNotesThatReadBackUnchanged() {
        val states = WorkspaceCodec.decode(v086)
        val text = WorkspaceCodec.encode(states)
        assertEquals(false, text.contains("\"phrase\":"))
        assertEquals(true, text.contains("\"phraseNotes\":[69,-1,36,-1,107,-1]"))
        assertEquals(states, WorkspaceCodec.decode(text))
    }

    @Test fun unusablePhrasesAndJunkFallBackToDefaults() {
        assertNull(WorkspaceCodec.decode("""[{"kind":"SKETCH","phrase":[-1,-1]}]""")[0].sketchPhrase)
        assertNull(WorkspaceCodec.decode("""[{"kind":"SKETCH","phraseNotes":[200]}]""")[0].sketchPhrase)
        assertEquals(listOf(ViewKind.PATTERN, ViewKind.KEYBOARD), WorkspaceCodec.decode("not json").map { it.kind })
    }
}
