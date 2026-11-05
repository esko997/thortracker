package org.thortracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.TonalGuide.Chord

class TonalGuideTest {
    private fun guide(root: Int, kind: ScaleKind) = TonalGuide(Scale(root, kind))

    /** Pitch-class names of a chord's tones, from offsets above the scale root. */
    private fun spelled(guide: TonalGuide, chord: Chord) = chord.intervals.map { Scale.ROOT_NAMES[(guide.scale.root + it) % 12] }

    @Test
    fun cMajorHasItsSevenDiatonicTriads() {
        assertEquals(
            listOf(
                Chord("C", "I", listOf(0, 4, 7)),
                Chord("Dm", "ii", listOf(2, 5, 9)),
                Chord("Em", "iii", listOf(4, 7, 11)),
                Chord("F", "IV", listOf(5, 9, 12)),
                Chord("G", "V", listOf(7, 11, 14)),
                Chord("Am", "vi", listOf(9, 12, 16)),
                Chord("Bdim", "vii°", listOf(11, 14, 17)),
            ),
            guide(0, ScaleKind.MAJOR).chords,
        )
    }

    @Test
    fun aNaturalMinorTriadsUseMinorQualitiesAndFlatDegrees() {
        val g = guide(9, ScaleKind.NATURAL_MINOR)
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), g.tones.map { it.name })
        assertEquals(listOf("1", "2", "b3", "4", "5", "b6", "b7"), g.tones.map { it.degree })
        assertEquals(listOf(0, 2, 3, 5, 7, 8, 10), g.tones.map { it.interval })
        assertEquals(
            listOf(
                Chord("Am", "i", listOf(0, 3, 7)),
                Chord("Bdim", "ii°", listOf(2, 5, 8)),
                Chord("C", "bIII", listOf(3, 7, 10)),
                Chord("Dm", "iv", listOf(5, 8, 12)),
                Chord("Em", "v", listOf(7, 10, 14)),
                Chord("F", "bVI", listOf(8, 12, 15)),
                Chord("G", "bVII", listOf(10, 14, 17)),
            ),
            g.chords,
        )
        val byName = g.chords.associateBy { it.name }
        assertEquals(listOf("E", "G", "B"), spelled(g, byName.getValue("Em")))
        assertEquals(listOf("G", "B", "D"), spelled(g, byName.getValue("G")))
    }

    @Test
    fun majorScaleDegreeLabels() {
        val g = guide(0, ScaleKind.MAJOR)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), g.tones.map { it.degree })
        assertEquals(listOf("C", "D", "E", "F", "G", "A", "B"), g.tones.map { it.name })
    }

    @Test
    fun transposedRootSpellsCorrectPitchesAcrossTheBToCBoundary() {
        // B major: every chord past the root wraps through C, and intervals stay relative to the scale root.
        val g = guide(11, ScaleKind.MAJOR)
        assertEquals(listOf("B", "C#", "D#", "E", "F#", "G#", "A#"), g.tones.map { it.name })
        assertEquals(listOf("B", "C#m", "D#m", "E", "F#", "G#m", "A#dim"), g.chords.map { it.name })
        assertEquals(guide(0, ScaleKind.MAJOR).chords.map { it.intervals }, g.chords.map { it.intervals })
        val gSharpMinor = g.chords.single { it.name == "G#m" }
        assertEquals(listOf(9, 12, 16), gSharpMinor.intervals)
        assertEquals(listOf("G#", "B", "D#"), spelled(g, gSharpMinor))
        assertEquals(listOf("A#", "C#", "E"), spelled(g, g.chords.single { it.name == "A#dim" }))
        // D major's vii° spans the octave: C# E G.
        val d = guide(2, ScaleKind.MAJOR)
        assertEquals(listOf("C#", "E", "G"), spelled(d, d.chords.single { it.degree == "vii°" }))
    }

    @Test
    fun pentatonicChordsAreOnlyTheTriadsTheScaleContains() {
        assertEquals(
            listOf(Chord("C", "I", listOf(0, 4, 7)), Chord("Am", "vi", listOf(9, 12, 16))),
            guide(0, ScaleKind.MAJOR_PENTATONIC).chords,
        )
        val aMinor = guide(9, ScaleKind.MINOR_PENTATONIC)
        assertEquals(listOf("1", "b3", "4", "5", "b7"), aMinor.tones.map { it.degree })
        assertEquals(listOf("A", "C", "D", "E", "G"), aMinor.tones.map { it.name })
        assertEquals(
            listOf(Chord("Am", "i", listOf(0, 3, 7)), Chord("C", "bIII", listOf(3, 7, 10))),
            aMinor.chords,
        )
    }

    @Test
    fun everyChordIsAWellFormedTriadOfScaleTonesForEveryRoot() {
        val shapes = mapOf("" to listOf(4, 7), "m" to listOf(3, 7), "dim" to listOf(3, 6), "aug" to listOf(4, 8))
        for (kind in ScaleKind.entries.filter { it != ScaleKind.CHROMATIC }) {
            for (root in 0 until 12) {
                val g = guide(root, kind)
                val scaleNames = g.tones.map { it.name }.toSet()
                for (chord in g.chords) {
                    val where = "$kind root $root ${chord.name}"
                    assertTrue(where, chord.intervals[0] in 0..11)
                    assertTrue(where, chord.intervals.zipWithNext().all { (a, b) -> a < b })
                    assertTrue(where, chord.intervals.all { kind.hasInterval(it) })
                    assertTrue(where, spelled(g, chord).all { it in scaleNames })
                    val rootName = spelled(g, chord)[0]
                    val suffix = chord.name.removePrefix(rootName)
                    assertEquals(where, shapes.getValue(suffix), chord.intervals.drop(1).map { it - chord.intervals[0] })
                    assertEquals(where, g.tones.single { it.interval == chord.intervals[0] }.degree.first() == 'b', chord.degree.startsWith("b"))
                }
            }
        }
    }

    @Test
    fun modesAndMinorsSpellDegreesAndChordsConventionally() {
        val lydian = guide(5, ScaleKind.LYDIAN)
        assertEquals(listOf("1", "2", "3", "#4", "5", "6", "7"), lydian.tones.map { it.degree })
        assertEquals(listOf("F", "G", "Am", "Bdim", "C", "Dm", "Em"), lydian.chords.map { it.name })
        assertEquals("#iv°", lydian.chords[3].degree)
        val harmonic = guide(9, ScaleKind.HARMONIC_MINOR)
        assertEquals(listOf("i", "ii°", "bIII+", "iv", "V", "bVI", "vii°"), harmonic.chords.map { it.degree })
        assertEquals(listOf("Am", "Bdim", "Caug", "Dm", "E", "F", "G#dim"), harmonic.chords.map { it.name })
        assertEquals(listOf("i", "ii", "bIII+", "IV", "V", "vi°", "vii°"), guide(0, ScaleKind.MELODIC_MINOR).chords.map { it.degree })
        assertEquals(listOf("I", "bII", "iii°", "iv", "v°", "bVI+", "bvii"), guide(4, ScaleKind.PHRYGIAN_DOMINANT).chords.map { it.degree })
    }

    @Test
    fun irregularScalesKeepOnlyStandardTriads() {
        // Double harmonic's V (b5) and vii (bb3) stacks are not standard triads and are left out.
        assertEquals(listOf("I", "bII", "iii", "iv", "bVI+"), guide(0, ScaleKind.DOUBLE_HARMONIC).chords.map { it.degree })
        assertEquals(listOf("i", "bIII+", "V", "bVI", "vii"), guide(0, ScaleKind.HUNGARIAN_MINOR).chords.map { it.degree })
        assertEquals(listOf("1", "2", "b3", "#4", "5", "b6", "7"), guide(0, ScaleKind.HUNGARIAN_MINOR).tones.map { it.degree })
        val whole = guide(0, ScaleKind.WHOLE_TONE)
        assertEquals(listOf("1", "2", "3", "#4", "#5", "b7"), whole.tones.map { it.degree })
        assertEquals(listOf("Caug", "Daug", "Eaug", "F#aug", "G#aug", "A#aug"), whole.chords.map { it.name })
        assertEquals(listOf("Cm", "Cdim", "D#", "D#m"), guide(0, ScaleKind.BLUES).chords.map { it.name })
        assertEquals(listOf("1", "b3", "4", "b5", "5", "b7"), guide(0, ScaleKind.BLUES).tones.map { it.degree })
        assertEquals(listOf("Am", "F"), guide(9, ScaleKind.HIRAJOSHI).chords.map { it.name })
        assertEquals(listOf("F", "Am"), guide(4, ScaleKind.MIYAKO_BUSHI).chords.map { it.name }) // E F A B C
    }

    @Test
    fun chromaticListsTwelveTonesAndNoChords() {
        val g = guide(2, ScaleKind.CHROMATIC)
        assertEquals((0 until 12).toList(), g.tones.map { it.interval })
        assertEquals(listOf("D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C", "C#"), g.tones.map { it.name })
        assertEquals(listOf("1", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7"), g.tones.map { it.degree })
        assertTrue(g.chords.isEmpty())
        assertTrue(TonalGuide(Scale.CHROMATIC).chords.isEmpty())
    }

    @Test
    fun outputsCannotBeMutated() {
        val g = guide(0, ScaleKind.MAJOR)
        assertThrows(UnsupportedOperationException::class.java) { (g.tones as MutableList<TonalGuide.Tone>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (g.chords as MutableList<Chord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (g.chords[0].intervals as MutableList<Int>)[0] = 1 }
    }
}
