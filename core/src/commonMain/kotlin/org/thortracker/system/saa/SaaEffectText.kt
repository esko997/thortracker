package org.thortracker.system.saa

import org.thortracker.model.Cell
import org.thortracker.model.Song

/**
 * The editor's longer reading of an SAA1099 effect where its row matters (`SoundSystem.effectDetail`): the notes an
 * arpeggio (`0xy`) steps through from the row's (or the sounding) note; what a hardware envelope (`Exx`) plays and
 * which lane clocks it; which lanes a noise clock (`Nxx`) reaches; and the chip a sync (`Yxx`) controls.
 */
object SaaEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val sound = song.sound as? SaaSound ?: return null
        val variant = sound.variant
        if (channel !in 0 until variant.channels) return null
        val names = variant.laneNames
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        val which = if (cell.note >= 0) "The note on this row" else "The sounding note (until its next note)"
        return when (code) {
            SaaRules.FX_ARPEGGIO -> {
                if (param == 0) return null
                val x = param shr 4
                val y = param and 0x0F
                val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
                listOf(root, root + x, root + y).joinToString(" → ") { name(variant, it) } + " every tick"
            }
            SaaRules.FX_ENVELOPE -> when {
                !SaaSound.hasEnvelope(channel) ->
                    "Ignored on ${names[channel]}: only lanes 3 and 6 of a chip have a hardware envelope"
                param == 0 -> "$which plays without the hardware envelope (its instrument's is switched off)"
                param !in SaaRules.ENVELOPE_CONTROLS -> null
                else -> {
                    val e = SaaHardwareEnvelope.ofControl(param) ?: return null
                    val clock = if (e.external) {
                        "the player steps it every R ticks (R = the note instrument's external rate, 1 without one)"
                    } else {
                        val lane = names[SaaSound.envelopeClockLane(channel)]
                        "$lane's tone generator clocks it: two steps per period, so $lane's pitch sets its speed" +
                            (SaaRules.envelopeInterval(e)?.let { " (one cycle sounds ${it / 12} octaves below $lane's note)" } ?: "")
                    }
                    "$which uses a ${SaaRules.envelopeText(param, variant, channel)} envelope, restarted; $clock"
                }
            }
            SaaRules.FX_NOISE -> {
                if (param > 3) return null
                val lanes = SaaSound.generatorLanes(SaaSound.chipOf(channel), SaaSound.generatorOf(channel))
                "$which plays noise at ${SaaRules.noiseText(param, variant, channel)} when its mixer has noise, and sets the " +
                    "generator ${names[lanes.first]}-${names[lanes.last]} share: their noise plays this clock too"
            }
            SaaRules.FX_SYNC -> {
                if (param > 3) return null
                val chip = SaaSound.chipOf(channel)
                val lanes = "${names[chip * SaaSound.CHIP_CHANNELS]}-${names[chip * SaaSound.CHIP_CHANNELS + SaaSound.CHIP_CHANNELS - 1]}"
                "Every lane of ${if (variant.chips == 1) "the chip" else "chip ${'A' + chip}"} ($lanes): ${SaaRules.syncText(param)}"
            }
            else -> null
        }
    }

    /** The note sounding at [row] of [track] from the rows above it (the pattern only), or null after an OFF or none. */
    private fun soundingNote(track: List<Cell>, row: Int): Int? {
        for (r in row - 1 downTo 0) {
            val n = track[r].note
            if (n >= 0) return n
            if (n == Cell.CUT) return null
        }
        return null
    }

    private fun name(variant: SaaVariant, note: Int): String =
        if (note in variant.noteRange) variant.system.noteName(note) else "(out of range)"
}
