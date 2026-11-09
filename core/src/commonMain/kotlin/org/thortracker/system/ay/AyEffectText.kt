package org.thortracker.system.ay

import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.model.Songs

/**
 * The editor's longer reading of an AY effect where its row matters (`SoundSystem.effectDetail`): the notes an arpeggio
 * (`0xy`) steps through, and for the note settings (`E`, `H`, `I`, `J`, `V`, `W`) what they make of the row's note (or
 * the sounding note they change at once) — its envelope shape and period, its gates, its noise period — and who owns
 * the shared envelope and noise generator there in straight play.
 */
object AyEffectText {
    fun detail(song: Song, pattern: Int, channel: Int, row: Int, column: Int): String? {
        if (song.sound !is AySound) return null
        val track = song.patterns.getOrNull(pattern)?.tracks?.getOrNull(channel) ?: return null
        val cell = track.getOrNull(row) ?: return null
        val code = cell.effectCode(column)
        val param = cell.effectParam(column)
        if (code == AyRules.FX_ARPEGGIO && param != 0) {
            val x = param shr 4
            val y = param and 0x0F
            val root = if (cell.note >= 0) cell.note else soundingNote(track, row) ?: return "note, +$x, +$y: no note sounding here"
            return listOf(root, root + x, root + y).joinToString(" → ") { name(song, it) } + " every tick"
        }
        if (code !in AyRules.NOTE_SETTINGS || AyRules.paramProblem(channel, code, param, song.orders.size) != null) return null
        val system = song.ay.variant.system
        val described = AyRules.describe(song, channel, code, param)
        val note = if (cell.note >= 0) cell.note else soundingNote(track, row)
            ?: return "$described: ignored, no note sounds on this channel here (it applies to the note on its row)"
        val slot = if (cell.instrument != 0) cell.instrument else soundAbove(track, row)
        val ins = song.ay.instrument(slot)
        val target = if (cell.note >= 0) "${system.noteName(note)} on this row" else "the sounding ${system.noteName(note)}, at once"
        val parts = ArrayList<String>()
        parts += "$described → $target"
        val s = Settings.of(ins, track, keyRow(track, row), row)
        when (code) {
            AyRules.FX_ENV_SHAPE, AyRules.FX_ENV_HIGH, AyRules.FX_ENV_LOW, AyRules.FX_ENV_AUTO -> {
                if (s.shape == 0) {
                    parts += "no envelope shape, so it plays its fixed volume and leaves the envelope to the other channels"
                } else {
                    val v = song.ay.variant
                    val ep = if (s.auto) AyRules.autoEnvelopePeriod(v, note * 64, s.octave, s.shape) else s.period
                    val mode = if (s.auto) "auto ${AyRules.autoText(s.octave)}" else "manual"
                    parts += "envelope ${NumberText.hex(s.shape)} ${AyRules.SHAPE_GLYPHS[s.shape]} $mode: period ${NumberText.hex(ep, 4)} " +
                        "(${NumberText.fixed(AyRules.envelopeHz(v, ep, s.shape), 1)} Hz)"
                    parts += "the chip has one envelope: the latest sounding note using it owns it, and every channel using it plays the owner's"
                }
            }
            AyRules.FX_MIXER -> parts += "plays ${AyRules.mixerText(s.mixer)}" + when {
                s.mixer and AyRules.MIXER_NOISE == 0 -> ""
                s.noise >= 0 -> "; asks the shared noise generator for period ${s.noise} (the latest note asking owns it)"
                else -> "; no noise period of its own, so it plays whatever the shared generator holds"
            }
            AyRules.FX_NOISE -> parts += if (s.mixer and AyRules.MIXER_NOISE == 0) {
                "its noise gate is closed (${AyRules.mixerText(s.mixer)}), so it asks for nothing; add V02 or V03"
            } else "the chip has one noise generator: the latest sounding note asking for a period owns it"
        }
        owners(song, pattern, row)?.let { parts += it }
        return parts.joinToString("; ")
    }

    /** The note settings a note gets: its sound's, then the row's in column order (as the player's key-on). */
    private class Settings(val shape: Int, val auto: Boolean, val octave: Int, val period: Int, val mixer: Int, val noise: Int) {
        companion object {
            /**
             * The settings of the note keyed at row [from] of [track] when row [to] has run: the image's fields of its
             * sound (only the active mode's octave or period), then each row's note settings in column order.
             */
            fun of(ins: AyInstrument?, track: List<Cell>, from: Int, to: Int): Settings {
                val env = ins?.envelope ?: AyEnvelopeSettings.OFF
                var shape = env.shape
                var auto = env.auto
                var octave = env.imageOctave
                var period = env.imagePeriod
                var mixer = ins?.mixer?.values?.firstOrNull() ?: AyRules.MIXER_DEFAULT
                var noise = ins?.noise?.values?.firstOrNull() ?: -1
                for (r in from..to) for (col in 0..track[r].more.size) {
                    val cell = track[r]
                    val p = cell.effectParam(col)
                    when (cell.effectCode(col)) {
                        AyRules.FX_ENV_SHAPE -> shape = p
                        AyRules.FX_ENV_HIGH -> { period = (p shl 8) or (period and 0xFF); auto = false }
                        AyRules.FX_ENV_LOW -> { period = (period and 0xFF00) or p; auto = false }
                        AyRules.FX_ENV_AUTO -> { auto = true; octave = AyRules.octaveOf(p) }
                        AyRules.FX_MIXER -> mixer = p
                        AyRules.FX_NOISE -> noise = p
                    }
                }
                return Settings(shape, auto, octave, period, mixer, noise)
            }
        }
    }

    /** Who owns the envelope and noise at [row] of the first played order showing [pattern], in straight play. */
    private fun owners(song: Song, pattern: Int, row: Int): String? {
        val order = Songs.playedOrders(song).firstOrNull { song.orders[it] == pattern } ?: return null
        val s = AySharedState.at(song, order, row) ?: return null
        val env = if (s.envelopeOwner >= 0) AyRules.CHANNEL_NAMES[s.envelopeOwner] else "none"
        val noise = if (s.noiseOwner >= 0) AyRules.CHANNEL_NAMES[s.noiseOwner] else "none"
        return "at order ${NumberText.hex(order, 2)}: envelope owner $env, noise owner $noise"
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

    /** The row of [track] at or above [row] whose note the channel plays at [row] (the pattern only; [row] if none). */
    private fun keyRow(track: List<Cell>, row: Int): Int {
        for (r in row downTo 0) if (track[r].note >= 0) return r
        return row
    }

    /** The sound named on or above [row] of [track] (the pattern only), or 0. */
    private fun soundAbove(track: List<Cell>, row: Int): Int {
        for (r in row downTo 0) if (track[r].instrument != 0) return track[r].instrument
        return 0
    }

    private fun name(song: Song, note: Int): String = if (note in AyRules.NOTES) song.system.noteName(note) else "(out of range)"
}
