package org.thortracker.model

import kotlin.random.Random

/** How a generated phrase places its note onsets. */
enum class PhraseRhythm(val label: String) {
    /** At most one onset per four rows, mostly on the strong rows. */
    SPARSE("Sparse"),
    /** Every other row, with an occasional extra off-row note. */
    EVEN("Even"),
    /** The 3+3+2 pattern (rows 0, 3, 6 of every eight), with occasional pickups. */
    SYNCOPATED("Syncopated"),
}

/** The contour of a generated phrase. */
enum class PhraseMotion(val label: String) {
    /** Mostly adjacent scale tones. */
    STEPS("Steps"),
    /** Thirds and wider (two or more scale tones), often turning back after a leap. */
    LEAPS("Leaps"),
    /** Never steps down: the line climbs through the register. */
    RISING("Rising"),
    /** Never steps up: the line descends through the register. */
    FALLING("Falling"),
}

/** What Sketch generates: [length] rows (4..16), the onset [rhythm], the pitch [motion], and whether strong rows land on chord tones. */
data class PhraseSettings(
    val length: Int = 8,
    val rhythm: PhraseRhythm = PhraseRhythm.EVEN,
    val motion: PhraseMotion = PhraseMotion.STEPS,
    val chordTones: Boolean = true,
) {
    init {
        require(length in MIN_LENGTH..MAX_LENGTH) { "Phrase length $length is outside $MIN_LENGTH..$MAX_LENGTH" }
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 16
    }
}

/**
 * A short monophonic line, one entry per pattern row: a note starts there (an onset); null means no new
 * note, so the previous one keeps sounding. Listening-only data until Sketch inserts it into a pattern.
 *
 * Generation is deterministic for a given [Random] seed. Generated notes come only from the guide's scale
 * (any semitone for Chromatic), within the register [baseNote, baseNote + 19] limited to the system's range; row 0 is
 * always an onset. With a chord, row 0 and every onset on a multiple of four rows is one of its tones.
 */
data class Phrase(val steps: List<Int?>) {
    init {
        require(steps.all { it == null || it >= 0 }) { "Phrase notes must be pitches (0 or above)" }
    }

    val length: Int get() = steps.size

    /** Rows that start a note, ascending. */
    val onsets: List<Int> get() = steps.indices.filter { steps[it] != null }

    /** The notes in playing order. */
    val pitches: List<Int> get() = steps.filterNotNull()

    companion object {
        /** Span of the generation register above its base note, in semitones. */
        const val REGISTER = 19

        fun generate(guide: TonalGuide, baseNote: Int, chord: TonalGuide.Chord?, settings: PhraseSettings, random: Random, range: IntRange): Phrase {
            val rows = onsetRows(settings.length, settings.rhythm, random)
            val pool = pool(guide.scale, register(baseNote, range))
            return assemble(settings.length, rows, walk(rows, pool, chordClasses(guide, chord), settings.motion, random))
        }

        /**
         * A variation of [phrase] in [guide]'s key. [keepRhythm]: new pitches (shaped by [settings]' motion and
         * [chord]) on the same onsets. [keepPitches]: the same pitch sequence on new onsets (shaped by [settings]'
         * rhythm, row 0 always included). Neither: a small mutation, 1-3 notes moved by one scale step and/or one
         * onset shifted by a row. Keeping both leaves nothing to vary and is refused. The register follows the
         * phrase's own notes, so copied or earlier phrases keep their octave.
         */
        fun vary(
            phrase: Phrase,
            guide: TonalGuide,
            chord: TonalGuide.Chord?,
            settings: PhraseSettings,
            random: Random,
            keepRhythm: Boolean,
            keepPitches: Boolean,
            range: IntRange,
        ): Phrase {
            require(!(keepRhythm && keepPitches)) { "Keeping both rhythm and pitches leaves nothing to vary" }
            val pitches = phrase.pitches
            if (pitches.isEmpty()) return phrase
            return when {
                keepRhythm -> {
                    val rows = phrase.onsets
                    val pool = pool(guide.scale, around(pitches, range))
                    val chordPcs = chordClasses(guide, chord)
                    var notes = walk(rows, pool, chordPcs, settings.motion, random)
                    repeat(ATTEMPTS) { if (notes == pitches) notes = walk(rows, pool, chordPcs, settings.motion, random) }
                    assemble(phrase.length, rows, notes)
                }
                keepPitches -> {
                    var rows = chooseOnsets(phrase.length, pitches.size, settings.rhythm, random)
                    repeat(ATTEMPTS) { if (rows == phrase.onsets) rows = chooseOnsets(phrase.length, pitches.size, settings.rhythm, random) }
                    assemble(phrase.length, rows, pitches)
                }
                else -> mutate(phrase, guide.scale, random, range)
            }
        }

        /** A phrase from copied pattern cells of one channel: notes are onsets; empty rows, OFF and missing rows are null. */
        fun fromCells(cells: List<Cell>, length: Int, range: IntRange): Phrase =
            Phrase(List(length) { row -> cells.getOrNull(row)?.note?.takeIf { it in range } })

        private const val ATTEMPTS = 8

        private fun register(baseNote: Int, range: IntRange): IntRange {
            val lo = baseNote.coerceIn(range)
            val hi = minOf(range.last, lo + REGISTER)
            // Near the top of the range keep at least an octave to move in.
            return if (hi - lo >= 12) lo..hi else maxOf(range.first, hi - 12)..hi
        }

        /** A register around existing notes: their own span, or [REGISTER] semitones centred on them. */
        private fun around(pitches: List<Int>, range: IntRange): IntRange {
            val lo = pitches.min()
            val hi = pitches.max()
            if (hi - lo >= REGISTER) return lo..hi
            val start = ((lo + hi) / 2 - REGISTER / 2).coerceIn(range.first, range.last - REGISTER)
            return start..start + REGISTER
        }

        private fun pool(scale: Scale, range: IntRange): List<Int> = range.filter { scale.contains(it) }

        private fun chordClasses(guide: TonalGuide, chord: TonalGuide.Chord?): Set<Int>? =
            chord?.intervals?.map { (guide.scale.root + it).mod(12) }?.toSet()

        private fun assemble(length: Int, rows: List<Int>, notes: List<Int>): Phrase {
            val steps = arrayOfNulls<Int>(length)
            for (k in rows.indices) if (k < notes.size) steps[rows[k]] = notes[k]
            return Phrase(steps.toList())
        }

        /** Onset rows for [rhythm], ascending, always starting with row 0. */
        internal fun onsetRows(length: Int, rhythm: PhraseRhythm, random: Random): List<Int> {
            val on = BooleanArray(length)
            on[0] = true
            when (rhythm) {
                PhraseRhythm.SPARSE -> {
                    // At most one onset in each later four-row block, usually on its first row.
                    var block = 4
                    while (block < length) {
                        if (random.nextFloat() < 0.75f) on[if (block + 2 < length && random.nextFloat() < 0.25f) block + 2 else block] = true
                        block += 4
                    }
                }
                PhraseRhythm.EVEN -> for (row in 1 until length) on[row] = row % 2 == 0 || random.nextFloat() < 0.12f
                PhraseRhythm.SYNCOPATED -> for (row in 1 until length) on[row] = when (row % 8) {
                    3, 6 -> true
                    0 -> random.nextFloat() < 0.7f
                    7 -> random.nextFloat() < 0.25f
                    5 -> random.nextFloat() < 0.1f
                    else -> false
                }
            }
            return (0 until length).filter { on[it] }
        }

        /** Exactly [count] onset rows (row 0 first), drawn without replacement by [rhythm]'s preferred positions. */
        private fun chooseOnsets(length: Int, count: Int, rhythm: PhraseRhythm, random: Random): List<Int> {
            if (count <= 0) return emptyList()
            val chosen = mutableListOf(0) // distinct: candidates exclude 0 and each is removed when drawn
            val candidates = (1 until length).toMutableList()
            while (chosen.size < minOf(count, length) && candidates.isNotEmpty()) {
                val total = candidates.sumOf { weight(it, rhythm) }
                var pick = random.nextDouble() * total
                var index = candidates.lastIndex
                for (i in candidates.indices) {
                    pick -= weight(candidates[i], rhythm)
                    if (pick < 0) { index = i; break }
                }
                chosen += candidates.removeAt(index)
            }
            return chosen.sorted()
        }

        private fun weight(row: Int, rhythm: PhraseRhythm): Double = when (rhythm) {
            PhraseRhythm.SPARSE -> if (row % 4 == 0) 6.0 else if (row % 2 == 0) 2.0 else 0.3
            PhraseRhythm.EVEN -> if (row % 2 == 0) 5.0 else 0.6
            PhraseRhythm.SYNCOPATED -> when (row % 8) { 3, 6 -> 6.0; 0 -> 3.0; 5, 7 -> 1.5; else -> 0.4 }
        }

        /** One pitch from [pool] (ascending scale notes) per onset of [rows], following [motion]. */
        private fun walk(rows: List<Int>, pool: List<Int>, chord: Set<Int>?, motion: PhraseMotion, random: Random): List<Int> {
            if (pool.isEmpty() || rows.isEmpty()) return emptyList()
            val last = pool.lastIndex
            val quarter = pool.size / 4
            var index = when (motion) {
                PhraseMotion.RISING -> random.nextInt(0, quarter + 1)
                PhraseMotion.FALLING -> last - random.nextInt(0, quarter + 1)
                else -> random.nextInt(quarter, pool.size / 2 + 1)
            }
            var direction = when (motion) {
                PhraseMotion.RISING -> 1
                PhraseMotion.FALLING -> -1
                else -> if (random.nextBoolean()) 1 else -1
            }
            val oneWay = motion == PhraseMotion.RISING || motion == PhraseMotion.FALLING
            val out = ArrayList<Int>(rows.size)
            for (k in rows.indices) {
                if (k > 0) {
                    val r = random.nextFloat()
                    val delta = when (motion) {
                        PhraseMotion.STEPS -> {
                            if (random.nextFloat() < 0.35f) direction = -direction
                            direction * when { r < 0.7f -> 1; r < 0.9f -> 2; else -> 0 }
                        }
                        PhraseMotion.LEAPS -> {
                            if (random.nextFloat() < 0.6f) direction = -direction
                            direction * when { r < 0.5f -> 2; r < 0.85f -> 3; else -> 4 }
                        }
                        PhraseMotion.RISING, PhraseMotion.FALLING -> direction * when { r < 0.6f -> 1; r < 0.85f -> 2; else -> 0 }
                    }
                    var next = index + delta
                    if (next !in 0..last && !oneWay) {
                        direction = -direction
                        next = index - delta
                    }
                    index = next.coerceIn(0, last)
                }
                if (chord != null && rows[k] % 4 == 0) index = snap(pool, index, chord, direction, oneWay)
                out += pool[index]
            }
            return out
        }

        /** The nearest chord tone in [pool] to [index], preferring [toward]; [oneWay] searches that way first. */
        private fun snap(pool: List<Int>, index: Int, chord: Set<Int>, toward: Int, oneWay: Boolean): Int {
            fun isChord(i: Int) = i in pool.indices && pool[i].mod(12) in chord
            if (oneWay) {
                var i = index
                while (i in pool.indices) { if (isChord(i)) return i; i += toward }
            }
            for (d in 0..pool.size) {
                if (isChord(index + d * toward)) return index + d * toward
                if (isChord(index - d * toward)) return index - d * toward
            }
            return index
        }

        private fun mutate(phrase: Phrase, scale: Scale, random: Random, range: IntRange): Phrase {
            val steps = phrase.steps.toMutableList()
            val onsets = phrase.onsets
            val moves = 1 + random.nextInt(minOf(3, onsets.size))
            for (row in onsets.shuffled(random).take(moves)) {
                val note = steps[row] ?: continue
                val d = if (random.nextBoolean()) 1 else -1
                steps[row] = scale.step(note, d, range) ?: scale.step(note, -d, range) ?: note
            }
            if (random.nextBoolean()) {
                // Shift one later onset by a row into an empty neighbour; row 0 stays the downbeat.
                val movable = steps.indices.filter { r ->
                    r > 0 && steps[r] != null && ((r - 1 > 0 && steps[r - 1] == null) || (r + 1 < steps.size && steps[r + 1] == null))
                }
                if (movable.isNotEmpty()) {
                    val r = movable[random.nextInt(movable.size)]
                    val targets = listOf(r - 1, r + 1).filter { it > 0 && it < steps.size && steps[it] == null }
                    val to = targets[random.nextInt(targets.size)]
                    steps[to] = steps[r]
                    steps[r] = null
                }
            }
            return Phrase(steps)
        }
    }
}
