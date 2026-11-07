package org.thortracker.system.gb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.thortracker.backend.HugeImageLayout
import org.thortracker.backend.HugeLimits
import org.thortracker.backend.HugeLowering
import org.thortracker.backend.UgeExporter
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.BankSpec
import org.thortracker.system.ChannelKind
import org.thortracker.system.ChannelSpec
import org.thortracker.system.EffectSpec
import org.thortracker.system.ExportFormat
import org.thortracker.system.FinePitch
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView
import kotlin.math.log2

/**
 * The Game Boy played by hUGEDriver 6.1.3 and exported as hUGETracker `.uge`: four channels (two pulse, wave, noise),
 * three typed 15-slot banks, the 16 wave tables and their Wave view. Wraps [HugeRules], [GbValidator], [HugeLimits],
 * `HugeLowering` and [UgeExporter]; their behaviour is the Game Boy's and is pinned by the golden tests.
 */
object GbSystem : SoundSystem {
    override val id = "gb-huge"
    override val name = "Game Boy (hUGEDriver)"

    /** Bank index of each [GbBank]: its ordinal. */
    fun bankOf(kind: GbBank): Int = kind.ordinal

    fun kindOf(bank: Int): GbBank = GbBank.entries[bank.coerceIn(0, GbBank.entries.size - 1)]

    override val channels = HugeRules.CHANNEL_NAMES.indices.map { ch ->
        val kind = HugeRules.CHANNEL_KINDS[ch]
        ChannelSpec(
            HugeRules.CHANNEL_NAMES[ch],
            when (kind) { GbBank.PULSE -> ChannelKind.PULSE; GbBank.WAVE -> ChannelKind.WAVE; GbBank.NOISE -> ChannelKind.NOISE },
            bankOf(kind),
            tonal = kind != GbBank.NOISE,
        )
    }

    override val banks = GbBank.entries.map { BankSpec(it.name.lowercase().replaceFirstChar(Char::uppercase), HugeRules.INSTRUMENT_SLOTS) }
    override val noteRange = HugeRules.NOTE_OFFSET until HugeRules.NOTE_OFFSET + HugeRules.NOTE_COUNT
    override val effects: List<EffectSpec> get() = HugeRules.EFFECTS
    override val views = ViewKind.entries.toSet()
    override val exports = listOf(ExportFormat("uge") { UgeExporter.export(it) })
    override val fine: FinePitch = Detune
    override val help: SystemHelp = Help

    /** hUGEDriver has no note-off: OFF is lowered to E00 in the row's effect slot. */
    override val offEffect = "E00"

    /** hUGEDriver ticks once per Game Boy vblank. */
    override val tickHz = 59.73
    override val maxOrders = HugeLimits.MAX_ORDERS

    override fun blank(): Song = HugeRules.blankSong()

    override val soundSerializer: KSerializer<GbSound> = GbSound.serializer()

    /**
     * Versions 1-7 kept the banks and waves at the top of the song (`pulseInstruments`, `waveInstruments`,
     * `noiseInstruments`, `waves`) and numbered notes (and the tuning root) from C-3; version 8 nests them in `sound`
     * and counts from C-0. Sentinels (no note, OFF) and anything malformed are left for strict decoding to report.
     */
    override fun upgradeProject(song: JsonObject, version: Int): JsonObject {
        val moved = mapOf("pulseInstruments" to "pulse", "waveInstruments" to "wave", "noiseInstruments" to "noise", "waves" to "waves")
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in song) {
            when (key) {
                in moved -> Unit
                "patterns" -> out[key] = (value as? JsonArray)?.let { JsonArray(it.map(::upgradePattern)) } ?: value
                "tuning" -> out[key] = (value as? JsonObject)?.let { t -> JsonObject(t.mapValues { (k, v) -> if (k == "root") shifted(v) else v }) } ?: value
                else -> out[key] = value
            }
            if (key == "orders") out["sound"] = JsonObject(moved.entries.mapNotNull { (old, new) -> song[old]?.let { new to it } }.toMap())
        }
        if ("sound" !in out) out["sound"] = JsonObject(moved.entries.mapNotNull { (old, new) -> song[old]?.let { new to it } }.toMap())
        return JsonObject(out)
    }

    private fun upgradePattern(pattern: JsonElement): JsonElement {
        if (pattern !is JsonObject) return pattern
        val tracks = pattern["tracks"] as? JsonArray ?: return pattern
        val upgraded = JsonArray(tracks.map { track ->
            if (track !is JsonArray) track
            else JsonArray(track.map { cell -> if (cell is JsonArray && cell.isNotEmpty()) JsonArray(listOf(shifted(cell[0])) + cell.drop(1)) else cell })
        })
        return JsonObject(pattern + ("tracks" to upgraded))
    }

    /** A note number counted from C-3 as one counted from C-0; sentinels and non-numbers unchanged. */
    private fun shifted(note: JsonElement): JsonElement {
        val n = (note as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return note
        return if (n >= 0) JsonPrimitive(n + HugeRules.NOTE_OFFSET) else note
    }
    override fun demo(): Song = DemoSong.build()

    override fun effectsFor(channel: Int): List<EffectSpec> = HugeRules.effectsFor(channel)
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) = HugeRules.describeEffect(song, channel, effect, param)

    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? =
        GbValidator.cellRangeProblem(cell) ?: HugeRules.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song) = GbValidator.structuralErrors(song)
    override fun errors(song: Song) = GbValidator.errors(song)
    override fun warnings(song: Song) = GbValidator.warnings(song)
    override fun arranged(song: Song) = HugeRules.arranged(song)
    override fun patternLoop(song: Song, order: Int) = HugeRules.patternLoop(song, order)

    /** Bxx with xx >= 01 jumps to order xx-1; B00 means "the next order" and names none. */
    override fun jumpTarget(cell: Cell): Int? =
        if (cell.effect == HugeRules.FX_POSITION_JUMP && cell.param >= 1) cell.param - 1 else null

    override fun withJumpTarget(cell: Cell, order: Int): Cell = cell.copy(param = order + 1)

    override val engine: PlaybackEngine = Engine

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.name

    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.let(SoundParams::summary)

    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? {
        val ins = instrument(song, bank, slot)?.takeIf { it.kind == GbBank.WAVE } ?: return null
        return song.gb.waves.getOrNull(ins.wave)?.map { it / 15f }
    }

    /** Instrument [slot] (1..15) of [bank], or null. */
    fun instrument(song: Song, bank: Int, slot: Int): GbInstrument? =
        if (bank !in banks.indices) null else HugeRules.bank(song, kindOf(bank)).getOrNull(slot - 1)

    override fun selectSound(selection: Selection, song: Song): Selection =
        if (selection.bank != bankOf(GbBank.WAVE)) selection
        else selection.copy(wave = instrument(song, selection.bank, selection.instrument)?.wave ?: selection.wave)

    override fun view(kind: ViewKind): SystemView? = when (kind) {
        ViewKind.INSTRUMENT -> GbSoundView
        ViewKind.WAVE -> GbWaveView
        else -> null
    }

    /**
     * hUGEDriver 6.1.3 on binjgb (native `"gb-huge"`, huge_chip.c over huge_machine.c): the song lowered as for `.uge`
     * export ([HugeLowering]) and placed in the 64 KiB Game Boy memory image ([HugeImageLayout]).
     */
    private object Engine : PlaybackEngine {
        override val id = "gb-huge"
        override val imageSize = HugeImageLayout.SIZE

        /** At 59.73 ticks per second, ~3.3 minutes of song. */
        override val seekTicks = 12000

        override fun lower(song: Song, out: ByteArray) = HugeImageLayout.build(HugeLowering.lower(song), out)

        override fun auditionProblem(channel: Int, note: Int, instrument: Int, fine: Int): String? = when {
            instrument !in 1..HugeRules.INSTRUMENT_SLOTS -> "Choose an instrument (01-0F) to audition"
            note !in noteRange || channel !in channels.indices -> "Cannot audition note $note on channel $channel"
            fine !in 0..Detune.max -> "Cannot audition detune $fine (00-0F)"
            else -> null
        }

        /** hUGEDriver numbers notes from C-3. */
        override fun note(note: Int): Int = note - HugeRules.NOTE_OFFSET

        /** The audition plays 40y itself: the detune passes unchanged. */
        override fun fine(note: Int, fine: Int): Int = fine
    }

    /**
     * Tuned pitches play with stock hUGEDriver as a note-table note plus `40y` (vibrato with tick mask 0 = constant
     * upward detune of y period units from tick 1; tick 0 plays the plain note). GB pitch is 131072 / (2048 - period)
     * (the wave channel sounds an octave lower; the ratio is the same).
     */
    private object Detune : FinePitch {
        override val name = "40y"
        override val max = 15

        override fun range(note: Int): IntRange = 0..minOf(max, 2047 - HugeRules.NOTE_PERIODS[note - HugeRules.NOTE_OFFSET])

        override fun cents(note: Int, fine: Int): Double {
            val gap = 2048 - HugeRules.NOTE_PERIODS[note - HugeRules.NOTE_OFFSET]
            return 1200.0 * log2(gap.toDouble() / (gap - fine))
        }

        override fun of(cell: Cell): Int? = HugeRules.detuneOf(cell)

        override fun write(cell: Cell, note: Int, fine: Int): Cell? {
            val current = HugeRules.detuneOf(cell)
            return when {
                fine > 0 -> if (current == null) null else cell.copy(note = note, effect = HugeRules.FX_VIBRATO, param = fine)
                current != null && current > 0 -> cell.copy(note = note, effect = 0, param = 0)
                else -> cell.copy(note = note)
            }
        }
    }

    private object Help : SystemHelp {
        override val off = "Silences the channel at once (exported as E00). GB sounds have no release phase: for a tail, " +
            "give the sound a falling volume envelope, or end it later in the row with Exx"
        override val offEntered = "OFF: instant silence (E00), no release tail; for a fade use a sound's falling envelope"
        override val offInspector = "OFF: silent at once (E00), no release tail"
        override val effectsIntro = listOf(
            "There is no stutter or retrigger effect in the supported hUGE set. For a stutter, repeat the note " +
                "on consecutive rows with its sound number (each restarts the sound); Exx shortens each hit and 7xx delays one.",
        )
        override val tonalChannels = "Pulse and Wave"
        override val tonalChannelsShort = "Pulse/Wave"
        override val untonalNote = "Noise keeps every pitch."
        override val sketchChannels = "Pulse 1, Pulse 2 or Wave; Noise has no tonal chord reference"

        override fun viewSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
            val edit = keys.label(org.thortracker.editor.Action.EDIT)
            val lr = keys.pair(org.thortracker.editor.Action.LEFT, org.thortracker.editor.Action.RIGHT)
            val channels = keys.pair(org.thortracker.editor.Action.PREV_CHANNEL, org.thortracker.editor.Action.NEXT_CHANNEL)
            return listOf(
                "Sounds" to listOf(
                    lr to "Choose the sound slot; $channels change bank",
                    keys.held(org.thortracker.editor.Action.EDIT, lr) to "Adjust the setting; $edit tap toggles on/off settings",
                ),
                "Waves" to listOf(
                    keys.dpad() to "Move the pen across samples and levels; ${keys.label(org.thortracker.editor.Action.UP)} at the top reaches the tools",
                    "$edit hold" to "Draw at the pen height while moving",
                ),
            )
        }

        override fun limits(song: Song): List<Pair<String, String>> = buildList {
            for (kind in GbBank.entries) {
                val used = HashSet<Int>()
                for (ch in channels.indices) {
                    if (HugeRules.channelKind(ch) != kind) continue
                    for (pat in song.orders.toSet()) for (cell in song.patterns[pat].tracks[ch]) if (cell.instrument > 0) used += cell.instrument
                }
                add(kind.name.lowercase().replaceFirstChar { it.uppercase() } to "${used.size} of ${HugeRules.bank(song, kind).size} sounds used")
            }
            val usedWaves = song.gb.wave.map { it.wave }.toSet()
            add("Waves" to "${song.gb.waves.size} tables, ${usedWaves.size} assigned to sounds")
        }
    }
}
