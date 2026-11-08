package org.thortracker.system.sid

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.NumberText
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.BankSpec
import org.thortracker.system.ChannelKind
import org.thortracker.system.ChannelSpec
import org.thortracker.system.EffectSpec
import org.thortracker.system.ExportFormat
import org.thortracker.system.FinePitch
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SoundSystem
import org.thortracker.system.SharedState
import org.thortracker.system.SongSetting
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The Commodore 64's SID played by GoatTracker 2.77's playroutine on an emulated 6510 + reSID (native `"c64-sid"`):
 * three voices, one shared filter, [SLOTS] instruments shared by all voices with wave, pulse and filter programs, the
 * chip model, multispeed and hard-restart envelope per song. See docs/BACKENDS.md and docs/AUDIO_ENGINE.md (SID).
 */
object SidSystem : SoundSystem {
    override val id = "c64-sid"
    override val name = "C64 SID (GoatTracker)"

    /** GoatTracker's instrument limit. */
    const val SLOTS = 63

    override val channels = SidRules.CHANNEL_NAMES.map { ChannelSpec(it, ChannelKind.VOICE, 0, tonal = true) }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = SidRules.NOTES

    /** GoatTracker names notes in scientific pitch: A-4 = 440 Hz. */
    override val nameOctaveOffset = 0

    override fun noteName(note: Int): String = if (note == SidRules.KEY_ON) "ON" else Songs.noteName(note, nameOctaveOffset)

    override val effects: List<EffectSpec> get() = SidRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports: List<ExportFormat> = emptyList()
    override val fine: FinePitch = Fine
    override val help: SystemHelp = Help

    /** OFF is GoatTracker's key off, a note-column event. */
    override val offEffect: String? = null

    /** One player call per PAL frame (985248 Hz / 19656 cycles) at multispeed 1. */
    override val tickHz = 985248.0 / 19656.0
    override val maxOrders = 254

    override fun ticksPerSecond(song: Song): Double = tickHz * song.sid.multispeed

    /** GoatTracker's key on (`+++`): Clear on an OFF enters it. */
    override val keyOn: Int get() = SidRules.KEY_ON

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = SidTables.place(null, SidSound.blank()),
    )

    override val soundSerializer: KSerializer<SidSound> = SidSound.serializer()

    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        if (version >= 9) song
        else throw IllegalArgumentException("$name songs need project version 9 or later; version $version files are older systems' songs")

    override val backendVersion = 1

    override fun upgradeBackend(song: JsonObject, backendVersion: Int): JsonObject = song

    override fun demo(): Song = blank()

    override fun effectsFor(channel: Int): List<EffectSpec> = SidRules.EFFECTS
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) =
        if (SidRules.effect(effect) == null && effect != 0) "Unknown effect" else SidRules.describe(effect, param)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? =
        SidRules.cellRangeProblem(cell) ?: SidRules.cellProblem(song, cell)

    override fun structuralErrors(song: Song) = SidValidator.structuralErrors(song)
    override fun errors(song: Song) = SidValidator.errors(song)
    override fun warnings(song: Song) = SidValidator.warnings(song)

    /** Parked orders removed and the loop order renumbered (as on the NES: no jump effect to renumber). */
    override fun arranged(song: Song): Song {
        if (song.parkedOrders.isEmpty()) return song
        val played = Songs.playedOrders(song)
        if (played.isEmpty()) return song
        val loop = played.indexOfFirst { it >= song.loopOrder }.let { if (it >= 0) it else 0 }
        return song.copy(orders = played.map { song.orders[it] }, loopOrder = loop, parkedOrders = emptyList())
    }

    override fun patternLoop(song: Song, order: Int): Song =
        song.copy(orders = listOf(song.orders[order.coerceIn(0, song.orders.size - 1)]), loopOrder = 0, parkedOrders = emptyList())

    /** GoatTracker's packed songs have no jumps: the loop order is the only one. */
    override fun jumpTarget(cell: Cell): Int? = null
    override fun withJumpTarget(cell: Cell, order: Int): Cell = cell

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.name

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.let(::summary)

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** Instrument [slot] (1..[SLOTS]) of the one bank, or null. */
    fun instrument(song: Song, bank: Int, slot: Int): SidInstrument? = if (bank != 0) null else song.sid.instruments.getOrNull(slot - 1)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) SidInstrumentView else null

    /** Chip model, multispeed and the hard-restart envelope, after the Song view's shared rows. */
    override val songSettings: List<SongSetting> get() = SidSongSettings.ALL

    /** The one filter: which voice took it last ([SidFilterOwner]). */
    override fun sharedState(song: Song, order: Int, row: Int): SharedState = SidFilterOwner.state(song, order, row)

    /** "ADSR 0 9 A 9 · Saw+gate · pulse · filter · vibrato" */
    fun summary(ins: SidInstrument): String {
        val first = ins.wave.rows.firstOrNull()?.left
        val wave = if (first != null && first in SidTables.WAVE_FIRST..SidTables.WAVE_LAST) SidTables.waveformName(first) else null
        val parts = listOfNotNull(
            "ADSR ${NumberText.hex(ins.attack)} ${NumberText.hex(ins.decay)} ${NumberText.hex(ins.sustain)} ${NumberText.hex(ins.release)}",
            wave,
            "wave ${ins.wave.rows.size} rows".takeIf { ins.wave.rows.size > 1 },
            "pulse".takeIf { ins.pulse.rows.isNotEmpty() },
            "filter".takeIf { ins.filter.rows.isNotEmpty() },
            "vibrato".takeIf { ins.vibrato.active },
            when (ins.restart) { SidRestart.HARD -> null; SidRestart.GATE_OFF -> "no HR"; SidRestart.LEGATO -> "legato" },
        )
        return parts.joinToString(" · ")
    }

    private object Engine : PlaybackEngine {
        override val id = "c64-sid"
        override val imageSize = 47200

        /** At 50.1 ticks per second, ~4 minutes of song (multispeed songs: proportionally less). */
        override val seekTicks = 12000

        override fun lower(song: Song, out: ByteArray) = throw IllegalArgumentException("SID playback is not built yet")

        override fun auditionProblem(song: Song, channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
            instrument !in 1..SLOTS -> "Choose an instrument (01-${NumberText.hex(SLOTS, 2)}) to audition"
            channel !in channels.indices || note !in noteRange -> "Cannot audition note ${noteName(note)} on channel $channel"
            fine != 0 -> "The SID has no fine pitch; tunings play through the song's frequency table"
            else -> null
        }

        /** GoatTracker's note numbers are absolute notes (0 = C-0). */
        override fun note(song: Song, channel: Int, note: Int, instrument: Int): Int = note

        override fun fine(note: Int, fine: Int): Int = 0
    }

    /** No per-note fine pitch: a tuning retunes the song's frequency table instead ([SidTuning]). */
    private object Fine : FinePitch {
        override val name = "frequency table"
        override val max = 0
        override fun range(note: Int): IntRange = 0..0
        override fun cents(note: Int, fine: Int): Double = 0.0
        override fun of(cell: Cell): Int = 0
        override fun write(cell: Cell, note: Int, fine: Int): Cell = cell.copy(note = note)
    }

    private object Help : SystemHelp {
        override val off = "Key off: the voice's gate closes and the note fades with its release. ON (key on) opens " +
            "the gate again without restarting the note"
        override val offEntered = "OFF: key off (the note fades with its instrument's release)"
        override val offInspector = "OFF: key off (release phase)"
        override val effectsIntro = listOf(
            "Played by GoatTracker 2's playroutine on a C64 (PAL, 50 ticks per second times the song's multispeed).",
            "Effects are GoatTracker's commands 1-F; the speeds of 1-4 and E are values (GoatTracker 1 style), not table rows.",
            "Commands 5-F run once and let a running 1-4 continue; 000 returns to the instrument's vibrato.",
            "There are no jumps: the loop order and each pattern's length arrange the song.",
        )
        override val tonalChannels = "all three voices"
        override val tonalChannelsShort = "Voices"
        override val untonalNote = "Every voice is tonal; drums are wave programs with absolute notes."
        override val sketchChannels = "Voice 1, 2 or 3"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> = SidInstrumentView.helpSections(keys)

        override fun limits(song: Song): List<Pair<String, String>> = SidValidator.limits(song)
    }
}
