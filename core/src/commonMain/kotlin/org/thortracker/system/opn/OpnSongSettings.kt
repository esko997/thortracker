package org.thortracker.system.opn

import org.thortracker.backend.OpnPlayerImage
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SongSetting

/**
 * The Song view's OPN-family rows: the tick rate (timer B), FM 3's mode and the CSM rate (timer A) on both chips; the
 * YM2608 adds its LFO and the rhythm total level. Edit+←→ steps a value by one, Edit+↑↓ in bigger steps; Clear resets it.
 */
object OpnSongSettings {
    private fun with(song: Song, f: (OpnSound) -> OpnSound): Song = f(song.opn).let { if (it == song.opn) song else song.copy(sound = it) }

    /** LFO choices in stepping order: off, then rates 0-7 (register `$22` values 0, 8..15). */
    private val LFO_STEPS = listOf(0) + (0x08..0x0F)

    private fun hz(value: Double): String = if (value >= 100.0) "${NumberText.fixed(value, 0)} Hz" else "${NumberText.fixed(value, 2)} Hz"

    private val TICK_RATE = SongSetting("Tick rate", "Timer B: player ticks per second", "Set tick rate",
        value = { s ->
            val tb = s.opn.timerB
            val note = if (tb == OpnPlayerImage.TIMER_B_DEFAULT) "   the PC-88/98 default" else ""
            "${hz(OpnPlayerImage.tickHz(tb))}   timer B $tb (${OpnPlayerImage.TIMER_B_MIN}-${OpnPlayerImage.TIMER_B_MAX}): " +
                "a tick every ${16 * (256 - tb)} chip samples$note"
        },
        adjust = { s, f, c ->
            with(s) { it.copy(timerB = (it.timerB + f + 8 * c).coerceIn(OpnPlayerImage.TIMER_B_MIN, OpnPlayerImage.TIMER_B_MAX)) }
        },
        reset = { s -> with(s) { it.copy(timerB = OpnPlayerImage.TIMER_B_DEFAULT) } },
    )

    private val FM3_MODE = SongSetting("FM 3 mode", "FM 3: normal, special or CSM", "Set FM 3 mode",
        value = { s ->
            when (s.opn.fm3Mode) {
                OpnFm3Mode.NORMAL -> "Normal   FM 3 plays like FM 1-2"
                OpnFm3Mode.SPECIAL -> "Special   FM 3's four operators each sound their voice's Ch3 pitch offset"
                OpnFm3Mode.CSM -> "CSM   timer A keys FM 3 at the CSM rate (speech-like buzz); notes set its pitches"
            }
        },
        adjust = { s, f, c ->
            val d = f + c
            if (d == 0) s else with(s) { it.copy(fm3Mode = OpnFm3Mode.entries[(it.fm3Mode.ordinal + d).mod(OpnFm3Mode.entries.size)]) }
        },
        reset = { s -> with(s) { it.copy(fm3Mode = OpnFm3Mode.NORMAL) } },
    )

    private val CSM_RATE = SongSetting("CSM rate", "Timer A: how often CSM keys FM 3", "Set CSM rate",
        value = { s ->
            val na = s.opn.timerA
            val used = if (s.opn.fm3Mode == OpnFm3Mode.CSM) "" else "   (heard in CSM mode only)"
            "${hz(OpnRules.timerAHz(na))}   timer A $na (0-${OpnPlayerImage.TIMER_A_MAX}); Txx changes it$used"
        },
        adjust = { s, f, c -> with(s) { it.copy(timerA = (it.timerA + f + 32 * c).coerceIn(0, OpnPlayerImage.TIMER_A_MAX)) } },
        reset = { s -> with(s) { it.copy(timerA = 0) } },
    )

    private val LFO = SongSetting("LFO", "YM2608 LFO rate", "Set LFO",
        value = { s ->
            val v = s.opn.lfo
            if (v and 8 == 0) "Off   FM voices' AMS/FMS and AM operators have no effect; Lxx turns it on"
            else "Rate ${v and 7}   ${OpnRules.LFO_HZ[v and 7]} Hz (FM voices set the depths)"
        },
        adjust = { s, f, c ->
            with(s) {
                val i = LFO_STEPS.indexOf(it.lfo).coerceAtLeast(0)
                it.copy(lfo = LFO_STEPS[(i + f + 4 * c).coerceIn(LFO_STEPS.indices)])
            }
        },
        reset = { s -> with(s) { it.copy(lfo = 0) } },
    )

    private val RHYTHM_LEVEL = SongSetting("Rhythm level", "Rhythm total level", "Set rhythm level",
        value = { s ->
            val v = s.opn.rhythmLevel
            "$v   ${if (v == 63) "full" else "−${NumberText.fixed((63 - v) * 0.75, 2)} dB"} on all six drums (register \$11); Rxx changes it"
        },
        adjust = { s, f, c -> with(s) { it.copy(rhythmLevel = (it.rhythmLevel + f + 8 * c).coerceIn(0, 63)) } },
        reset = { s -> with(s) { it.copy(rhythmLevel = 63) } },
    )

    private val OPN = listOf(TICK_RATE, FM3_MODE, CSM_RATE)
    private val OPNA = OPN + listOf(LFO, RHYTHM_LEVEL)

    /** The Song view's rows for songs of [chip]. */
    fun forChip(chip: OpnChip): List<SongSetting> = if (chip.opna) OPNA else OPN
}
