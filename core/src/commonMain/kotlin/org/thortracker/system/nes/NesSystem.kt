package org.thortracker.system.nes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
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
import org.thortracker.system.SystemHelp
import org.thortracker.system.SystemView

/**
 * The NES 2A03 played by the FamiStudio sound engine 4.5.3 (FamiTracker tempo) on an emulated 6502 + 2A03: Pulse 1,
 * Pulse 2, Triangle and Noise (no DPCM), one bank of [SLOTS] envelope instruments shared by all four channels.
 * Exports `.nsf` and FamiStudio's text format. See docs/BACKENDS.md and docs/AUDIO_ENGINE.md.
 */
object NesSystem : SoundSystem {
    override val id = "nes-famistudio"
    override val name = "NES (FamiStudio)"

    /** FamiStudio's instrument limit without the extended range. */
    const val SLOTS = 64

    override val channels = NesRules.CHANNEL_NAMES.indices.map { ch ->
        ChannelSpec(NesRules.CHANNEL_NAMES[ch], NesRules.CHANNEL_KINDS[ch], 0, tonal = NesRules.CHANNEL_KINDS[ch] != ChannelKind.NOISE)
    }
    override val banks = listOf(BankSpec("Instruments", SLOTS))
    override val noteRange = (1 + NesRules.NOTE_OFFSET)..(NesRules.NOTE_COUNT + NesRules.NOTE_OFFSET)
    override val effects: List<EffectSpec> get() = NesRules.EFFECTS
    override val views = ViewKind.entries.toSet() - ViewKind.WAVE
    override val exports: List<ExportFormat> = emptyList()
    override val fine: FinePitch get() = TODO("NES fine pitch")
    override val help: SystemHelp get() = TODO("NES help")

    /** OFF is a note event of its own (release or stop); it needs no effect slot. */
    override val offEffect: String? = null

    /** The engine runs once per NTSC frame: 1789773 / 29780.5 Hz. */
    override val tickHz = 60.0988
    override val maxOrders = NesRules.MAX_ORDERS

    override fun blank(): Song = Song(
        title = "Untitled",
        patterns = listOf(Pattern.blank(channels.size, Songs.ROWS, "Pattern 00")),
        orders = listOf(0),
        sound = NesSound.blank(),
    )

    override val soundSerializer: KSerializer<NesSound> = NesSound.serializer()

    /** Project versions before 8 only held Game Boy songs. */
    override fun upgradeProject(song: JsonObject, version: Int): JsonObject =
        throw IllegalArgumentException("$name songs need project version 8 or later; version $version files are Game Boy songs")

    override fun demo(): Song = blank()

    override fun effectsFor(channel: Int): List<EffectSpec> = NesRules.effectsFor(channel)
    override fun describeEffect(song: Song, channel: Int, effect: Int, param: Int) = NesRules.describeEffect(channel, effect, param)
    override fun cellProblem(song: Song, channel: Int, cell: Cell): String? =
        NesRules.cellRangeProblem(cell) ?: NesRules.cellProblem(song, channel, cell)

    override fun structuralErrors(song: Song): List<String> = emptyList()
    override fun errors(song: Song): List<String> = emptyList()
    override fun warnings(song: Song): List<String> = emptyList()
    override fun arranged(song: Song): Song = song
    override fun patternLoop(song: Song, order: Int): Song = song

    /** FamiStudio has no jump effect: the song's loop order is its only jump. */
    override fun jumpTarget(cell: Cell): Int? = null
    override fun withJumpTarget(cell: Cell, order: Int): Cell = cell

    override val engine: PlaybackEngine get() = TODO("NES engine")

    override fun instrumentName(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.name
    override fun instrumentSummary(song: Song, bank: Int, slot: Int): String? = instrument(song, bank, slot)?.let { it.name }
    override fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>? = null

    /** Instrument [slot] (1..[SLOTS]) of the one bank, or null. */
    fun instrument(song: Song, bank: Int, slot: Int): NesInstrument? = if (bank != 0) null else song.nes.instruments.getOrNull(slot - 1)

    override fun selectSound(selection: Selection, song: Song): Selection = selection

    override fun view(kind: ViewKind): SystemView? = if (kind == ViewKind.INSTRUMENT) NesInstrumentView else null
}
