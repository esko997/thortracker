package org.thortracker.model

import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhraseTest {
    private val cMajor = TonalGuide(Scale(0, ScaleKind.MAJOR))

    private fun settings(length: Int = 8, rhythm: PhraseRhythm = PhraseRhythm.EVEN, motion: PhraseMotion = PhraseMotion.STEPS, chordTones: Boolean = true) =
        PhraseSettings(length, rhythm, motion, chordTones)

    @Test fun generatedNotesStayInTheScaleTheRegisterAndTheHardwareRange() {
        for (kind in ScaleKind.entries) for (root in listOf(0, 7, 11)) {
            val guide = TonalGuide(Scale(root, kind))
            for (base in listOf(24 + root, 48 + root, 72 + root)) for (motion in PhraseMotion.entries) for (rhythm in PhraseRhythm.entries) {
                for (chord in listOf(null, guide.chords.firstOrNull())) for (seed in 0L until 4L) {
                    val phrase = Phrase.generate(guide, base, chord, settings(16, rhythm, motion), Random(seed), GbSystem.noteRange)
                    for (note in phrase.pitches) {
                        val where = "$kind root $root base $base $motion $rhythm seed $seed: $note"
                        assertTrue(where, note in GbSystem.noteRange)
                        assertTrue(where, note in base..base + Phrase.REGISTER)
                        assertTrue(where, guide.scale.contains(note))
                    }
                }
            }
        }
    }

    @Test fun lengthAndDownbeatAreAlwaysHonoured() {
        for (length in PhraseSettings.MIN_LENGTH..PhraseSettings.MAX_LENGTH) for (rhythm in PhraseRhythm.entries) for (seed in 0L until 10L) {
            val phrase = Phrase.generate(cMajor, 48, null, settings(length, rhythm), Random(seed), GbSystem.noteRange)
            assertEquals(length, phrase.length)
            assertNotNull("$length $rhythm $seed", phrase.steps[0])
            assertEquals(0, phrase.onsets.first())
        }
    }

    @Test fun sameSeedSameSettingsSamePhrase() {
        val a = Phrase.generate(cMajor, 48, cMajor.chords[0], settings(12, PhraseRhythm.SYNCOPATED, PhraseMotion.LEAPS), Random(42), GbSystem.noteRange)
        val b = Phrase.generate(cMajor, 48, cMajor.chords[0], settings(12, PhraseRhythm.SYNCOPATED, PhraseMotion.LEAPS), Random(42), GbSystem.noteRange)
        assertEquals(a, b)
    }

    @Test fun sparsePhrasesHaveFewerOnsetsThanEvenOnes() {
        var sparseTotal = 0
        var evenTotal = 0
        var syncopatedTotal = 0
        for (length in PhraseSettings.MIN_LENGTH..PhraseSettings.MAX_LENGTH) for (seed in 0L until 20L) {
            val sparse = Phrase.generate(cMajor, 48, null, settings(length, PhraseRhythm.SPARSE), Random(seed), GbSystem.noteRange).onsets.size
            val even = Phrase.generate(cMajor, 48, null, settings(length, PhraseRhythm.EVEN), Random(seed), GbSystem.noteRange).onsets.size
            assertTrue("length $length seed $seed: sparse $sparse, even $even", sparse < even)
            // Sparse: row 0 plus at most one onset in each later four-row block; even: at least every other row.
            assertTrue(sparse <= (length + 3) / 4)
            assertTrue(even >= (length + 1) / 2)
            sparseTotal += sparse
            evenTotal += even
            syncopatedTotal += Phrase.generate(cMajor, 48, null, settings(length, PhraseRhythm.SYNCOPATED), Random(seed), GbSystem.noteRange).onsets.size
        }
        assertTrue(sparseTotal < syncopatedTotal)
        assertTrue(syncopatedTotal < evenTotal)
    }

    @Test fun risingPhrasesClimbAndFallingPhrasesDescend() {
        for (seed in 0L until 50L) for (chord in listOf(null, cMajor.chords[4])) {
            val rising = Phrase.generate(cMajor, 48, chord, settings(16, PhraseRhythm.EVEN, PhraseMotion.RISING), Random(seed), GbSystem.noteRange).pitches
            assertTrue("seed $seed: $rising", rising.last() > rising.first())
            val falling = Phrase.generate(cMajor, 48, chord, settings(16, PhraseRhythm.EVEN, PhraseMotion.FALLING), Random(seed), GbSystem.noteRange).pitches
            assertTrue("seed $seed: $falling", falling.last() < falling.first())
        }
    }

    @Test fun stepsMoveMostlyByAdjacentScaleTonesAndLeapsByThirdsOrMore() {
        fun scaleDistances(pitches: List<Int>): List<Int> {
            val pool = (48..67).filter { cMajor.scale.contains(it) }
            return pitches.zipWithNext { a, b -> Math.abs(pool.indexOf(b) - pool.indexOf(a)) }
        }
        val steps = ArrayList<Int>()
        val leaps = ArrayList<Int>()
        for (seed in 0L until 40L) {
            steps += scaleDistances(Phrase.generate(cMajor, 48, null, settings(16, motion = PhraseMotion.STEPS), Random(seed), GbSystem.noteRange).pitches)
            leaps += scaleDistances(Phrase.generate(cMajor, 48, null, settings(16, motion = PhraseMotion.LEAPS), Random(seed), GbSystem.noteRange).pitches)
        }
        assertTrue(steps.count { it <= 1 } > steps.size * 0.6)
        assertTrue(steps.all { it <= 2 })
        assertTrue(leaps.count { it >= 2 } > leaps.size * 0.8)
    }

    @Test fun strongRowsLandOnChordTonesWhenAChordIsGiven() {
        for (chord in cMajor.chords.take(6)) for (motion in PhraseMotion.entries) for (seed in 0L until 10L) {
            val classes = chord.intervals.map { it % 12 }.toSet()
            val phrase = Phrase.generate(cMajor, 48, chord, settings(16, PhraseRhythm.SYNCOPATED, motion), Random(seed), GbSystem.noteRange)
            for (row in phrase.onsets.filter { it % 4 == 0 }) {
                assertTrue("${chord.name} $motion $seed row $row", phrase.steps[row]!! % 12 in classes)
            }
        }
    }

    @Test fun keepRhythmVariesPitchesOnTheSameOnsets() {
        for (seed in 0L until 20L) {
            val phrase = Phrase.generate(cMajor, 48, null, settings(12), Random(seed), GbSystem.noteRange)
            val varied = Phrase.vary(phrase, cMajor, null, settings(12), Random(seed + 100), keepRhythm = true, keepPitches = false, GbSystem.noteRange)
            assertEquals(phrase.onsets, varied.onsets)
            assertEquals(phrase.length, varied.length)
            assertTrue(varied.pitches.all { cMajor.scale.contains(it) })
        }
    }

    @Test fun keepPitchesReplacesTheSameNotesOnNewOnsets() {
        var moved = 0
        for (seed in 0L until 20L) for (rhythm in PhraseRhythm.entries) {
            val phrase = Phrase.generate(cMajor, 48, null, settings(16, PhraseRhythm.SPARSE), Random(seed), GbSystem.noteRange)
            val varied = Phrase.vary(phrase, cMajor, null, settings(16, rhythm), Random(seed + 100), keepRhythm = false, keepPitches = true, GbSystem.noteRange)
            assertEquals(phrase.pitches, varied.pitches)
            assertEquals(phrase.length, varied.length)
            assertEquals(0, varied.onsets.first())
            if (varied.onsets != phrase.onsets) moved++
        }
        assertTrue(moved > 0)
    }

    @Test fun plainVaryIsASmallMutation() {
        for (seed in 0L until 40L) {
            val phrase = Phrase.generate(cMajor, 48, null, settings(16), Random(seed), GbSystem.noteRange)
            val varied = Phrase.vary(phrase, cMajor, null, settings(16), Random(seed + 7), keepRhythm = false, keepPitches = false, GbSystem.noteRange)
            assertNotEquals(phrase, varied)
            assertEquals(phrase.length, varied.length)
            assertEquals(phrase.pitches.size, varied.pitches.size)
            // Every changed note moved exactly one scale step; one to three of them changed.
            val changed = phrase.pitches.zip(varied.pitches).filter { (a, b) -> a != b }
            assertTrue(changed.size <= 3)
            for ((a, b) in changed) assertTrue("$a -> $b", b == cMajor.scale.step(a, 1, GbSystem.noteRange) || b == cMajor.scale.step(a, -1, GbSystem.noteRange))
            // At most one onset moved, by one row.
            val before = phrase.onsets.toSet()
            val after = varied.onsets.toSet()
            val left = before - after
            val arrived = after - before
            assertTrue(left.size <= 1 && left.size == arrived.size)
            if (left.isNotEmpty()) assertEquals(1, Math.abs(left.single() - arrived.single()))
            assertTrue(changed.isNotEmpty() || left.isNotEmpty())
        }
    }

    @Test fun varyCannotKeepBothRhythmAndPitches() {
        val phrase = Phrase.generate(cMajor, 48, null, settings(), Random(1), GbSystem.noteRange)
        assertThrows(IllegalArgumentException::class.java) {
            Phrase.vary(phrase, cMajor, null, settings(), Random(2), keepRhythm = true, keepPitches = true, GbSystem.noteRange)
        }
    }

    @Test fun copiedCellsSeedAPhrase() {
        val cells = listOf(Cell(54, 1), Cell(), Cell(Cell.CUT), Cell(56, 2, 0xC, 0x0F), Cell(instrument = 3), Cell(59, 1))
        assertEquals(listOf(54, null, null, 56, null, 59, null, null), Phrase.fromCells(cells, 8, GbSystem.noteRange).steps)
        assertEquals(listOf(54, null, null, 56), Phrase.fromCells(cells, 4, GbSystem.noteRange).steps)
        assertNull(Phrase.fromCells(listOf(Cell()), 4, GbSystem.noteRange).steps.firstOrNull { it != null })
    }
}
