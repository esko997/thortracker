package org.thortracker.system

import org.thortracker.editor.Selection
import org.thortracker.editor.ViewKind
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.gb.GbSystem

/** What a channel's hardware is, in chip-neutral terms, so shared code can ask without naming a chip. */
enum class ChannelKind { PULSE, WAVE, TRIANGLE, NOISE }

/**
 * One channel of a [SoundSystem]. [bank] indexes [SoundSystem.banks]: the instruments its cells' sound numbers name.
 * [tonal]: its note numbers are musical pitches, so scales, tunings, Sketch and Fit apply; false for a noise channel,
 * whose note numbers select noise rates.
 */
data class ChannelSpec(val name: String, val kind: ChannelKind, val bank: Int, val tonal: Boolean)

/** An instrument bank of a [SoundSystem]: [slots] sounds, numbered 1..[slots] in cells. */
data class BankSpec(val name: String, val slots: Int)

/** One effect number of a [SoundSystem]: what it means, its parameter layout and whether it can be played/exported. */
data class EffectSpec(
    val code: Int,
    val name: String,
    /** Parameter layout, e.g. "xy: x=up, y=down". */
    val param: String,
    val description: String,
    /** False when the effect can never be exported/previewed. */
    val supported: Boolean,
)

/** A file format a song exports to, e.g. hUGETracker's `.uge`. [export] receives a song with no [SoundSystem.errors]. */
class ExportFormat(val extension: String, val export: (Song) -> ByteArray)

/**
 * How a system plays a pitch between its note-table notes (tuning realisation): a note plus an upward "fine" value
 * the cell itself carries. Each system writes it its own way (Game Boy: effect `40y`).
 */
interface FinePitch {
    /** How the fine value is written, for messages ("40y"). */
    val name: String

    /** Largest fine value. */
    val max: Int

    /** Fine values playable on [note] (within the system's note range). */
    fun range(note: Int): IntRange

    /** Upward bend of [fine] on [note], in cents. */
    fun cents(note: Int, fine: Int): Double

    /** The fine value [cell] plays its note with: 0 without one, null when another effect holds the slot it needs. */
    fun of(cell: Cell): Int?

    /**
     * [cell] playing [note] raised by [fine], removing an earlier fine value when [fine] is 0. Null when [fine] is
     * needed but the cell's effect slot holds another effect.
     */
    fun write(cell: Cell, note: Int, fine: Int): Cell?
}

/** Prose that differs per system, for the Help view. Keymap-dependent lines come from the system's own views. */
interface SystemHelp {
    /** What OFF does on this system. */
    val off: String

    /** Status after placing an OFF in the pattern. */
    val offEntered: String

    /** The pattern inspector's line for an OFF cell. */
    val offInspector: String

    /** Remarks before the effect list. */
    val effectsIntro: List<String>

    /** Which channels follow a pattern scale, e.g. "Pulse and Wave". */
    val tonalChannels: String

    /** The same, short: "Pulse/Wave". */
    val tonalChannelsShort: String

    /** What untonal channels do under a scale, e.g. "Noise keeps every pitch." */
    val untonalNote: String

    /** The Sketch channel choice, e.g. "Pulse 1, Pulse 2 or Wave; Noise has no tonal chord reference". */
    val sketchChannels: String

    /** The Help sections for the system's own views (heading, then key → meaning rows). */
    fun viewSections(keys: org.thortracker.input.Keymap): List<Pair<String, List<Pair<String, String>>>>

    /** "Limits used" rows of the song check beyond orders and patterns. */
    fun limits(song: Song): List<Pair<String, String>>
}

/**
 * A sound chip with the driver that plays it: everything about a song that is not shared. Shared code (editor, views,
 * menus, validation entry points, tuning) asks the song's system instead of naming a chip. See docs/BACKENDS.md.
 */
interface SoundSystem {
    /** Stable identifier stored in project files ("gb-huge"). */
    val id: String

    /** Display name, e.g. "Game Boy (hUGEDriver)". */
    val name: String

    val channels: List<ChannelSpec>
    val banks: List<BankSpec>

    /** Playable notes, in semitones from C-0. */
    val noteRange: IntRange

    /** Every effect number (0..F), including unsupported ones. */
    val effects: List<EffectSpec>

    /** View kinds this system can show; the shared ones plus its own (e.g. the Game Boy's Wave view). */
    val views: Set<ViewKind>

    val exports: List<ExportFormat>
    val fine: FinePitch
    val help: SystemHelp

    /** The effect OFF is exported as when it takes the row's effect slot (Game Boy: "E00"); null when it needs none. */
    val offEffect: String?

    /** Driver ticks per second (row duration = ticks per row / [tickHz]). */
    val tickHz: Double

    /** Most orders the driver plays. */
    val maxOrders: Int

    fun blank(): Song
    fun demo(): Song

    /** Effects usable on [channel]; "no effect" is not an entry (the editor offers it separately). */
    fun effectsFor(channel: Int): List<EffectSpec>

    /** Contextual, human-readable meaning of a raw effect on [channel]. */
    fun describeEffect(song: Song, channel: Int, effect: Int, param: Int): String

    /** Why [cell] on [channel] cannot be exported or previewed (out-of-range values included), or null. */
    fun cellProblem(song: Song, channel: Int, cell: Cell): String?

    /** Shape and range problems; a song with any is neither saved nor loaded. */
    fun structuralErrors(song: Song): List<String>

    /** Structural errors plus everything that blocks playback or export. */
    fun errors(song: Song): List<String>

    /** Legal but probably unintended details. */
    fun warnings(song: Song): List<String>

    /** The song as played and exported: parked orders removed, every order reference renumbered. */
    fun arranged(song: Song): Song

    /** The song as heard while looping the pattern at [order]. Editor playback only. */
    fun patternLoop(song: Song, order: Int): Song

    /** The order [cell] jumps to (an absolute position jump), or null. */
    fun jumpTarget(cell: Cell): Int?

    /** [cell] with its jump aimed at [order]; only called when [jumpTarget] is not null. */
    fun withJumpTarget(cell: Cell, order: Int): Cell

    /** The note number the playback engine ([org.thortracker.audio.TrackerAudio.audition]) takes for [note]. */
    fun engineNote(note: Int): Int

    /** Name of instrument [slot] (1-based) of [bank], or null for no such slot. */
    fun instrumentName(song: Song, bank: Int, slot: Int): String?

    /** One-line reading of instrument [slot]'s settings, or null for no such slot. */
    fun instrumentSummary(song: Song, bank: Int, slot: Int): String?

    /** The waveform instrument [slot] plays, as levels 0..1 for a thumbnail, or null when it has none to show. */
    fun instrumentShape(song: Song, bank: Int, slot: Int): List<Float>?

    /**
     * [selection] after a shared view (Pattern, Keyboard) selected sound [Selection.instrument] of [Selection.bank]:
     * the system points its own views' subjects at it (Game Boy: the wave a wave sound plays).
     */
    fun selectSound(selection: Selection, song: Song): Selection

    /** The editor and painter of one of the system's own [views] (Instrument, Wave), or null for shared views. */
    fun view(kind: ViewKind): SystemView?
}

/** The systems this build knows, by id. */
object SoundSystems {
    val ALL: List<SoundSystem> = listOf(GbSystem)

    /** The system new songs use until New lets the user choose. */
    val DEFAULT: SoundSystem = GbSystem

    fun find(id: String): SoundSystem? = ALL.firstOrNull { it.id == id }

    /** @throws IllegalArgumentException naming the unknown [id] and the known ones. */
    fun of(id: String): SoundSystem = find(id)
        ?: throw IllegalArgumentException("Sound system \"$id\" is not supported; this version knows ${ALL.joinToString { "\"${it.id}\"" }}")
}

/** The system this song is written for. */
val Song.system: SoundSystem get() = SoundSystems.of(backend)
