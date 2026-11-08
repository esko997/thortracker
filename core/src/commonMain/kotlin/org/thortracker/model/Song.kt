package org.thortracker.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemSound

/**
 * One channel-row of a pattern.
 *
 * [note]: [EMPTY] (-1) = no note, [CUT] (-2) = explicit note cut, shown as "OFF", 0 and up = scientific pitch
 * (0 = C0, 57 = A4 = 440 Hz), within the song system's [org.thortracker.system.SoundSystem.noteRange]; names are the
 * system's ([org.thortracker.system.SoundSystem.noteName]).
 * [instrument]: 0 = no sound number (legato), 1.. = slot in the bank of the channel
 * ([org.thortracker.system.ChannelSpec.bank]).
 * [effect]/[param]: the system's raw effect number (0-35, written 0-9 then A-Z) and parameter byte; 0/0 ("000") is no effect.
 * This is effect column 0, the only one on systems with one column (Game Boy, NES, SID).
 * [more]: effect columns 1.. on systems that declare more ([org.thortracker.system.SoundSystem.effectColumns], the OPL3:
 * up to 4 in all); always canonical: at most [MAX_EFFECT_COLUMNS] − 1 entries, the last one not blank (trailing blank
 * columns are dropped, so a cell that uses only column 0 equals and saves exactly as before). Use [withEffect].
 * Serialized compactly as `[note, instrument, effect, param]`, followed by `effect, param` per entry of [more].
 */
@Serializable(with = CellSerializer::class)
data class Cell(
    val note: Int = EMPTY,
    val instrument: Int = 0,
    val effect: Int = 0,
    val param: Int = 0,
    val more: List<Fx> = emptyList(),
) {
    init {
        if (more.isNotEmpty()) {
            require(more.size < MAX_EFFECT_COLUMNS) { "A cell has at most $MAX_EFFECT_COLUMNS effect columns" }
            require(!more.last().isBlank) { "A cell's last extra effect column must not be blank" }
        }
    }

    val isBlank: Boolean get() = note == EMPTY && instrument == 0 && effect == 0 && param == 0 && more.isEmpty()

    /** Effect column 0 holds an effect (on one-column systems: the cell has an effect). */
    val hasEffect: Boolean get() = effect != 0 || param != 0

    /** Any effect column holds an effect. */
    val hasAnyEffect: Boolean get() = hasEffect || more.isNotEmpty()

    /** The highest effect column holding an effect, or -1 when none does. */
    val lastEffectColumn: Int get() = if (more.isNotEmpty()) more.size else if (hasEffect) 0 else -1

    /** Effect code of column [column] (0 = [effect]); 0 beyond the used columns. */
    fun effectCode(column: Int): Int = if (column == 0) effect else more.getOrNull(column - 1)?.effect ?: 0

    /** Parameter of column [column] (0 = [param]); 0 beyond the used columns. */
    fun effectParam(column: Int): Int = if (column == 0) param else more.getOrNull(column - 1)?.param ?: 0

    /** This cell with column [column] (0 until [MAX_EFFECT_COLUMNS]) set to [code]/[param]; 0/0 clears it. Canonical. */
    fun withEffect(column: Int, code: Int, param: Int): Cell {
        require(column in 0 until MAX_EFFECT_COLUMNS) { "Effect column $column is out of range" }
        if (column == 0) return if (code == effect && param == this.param) this else copy(effect = code, param = param)
        if (effectCode(column) == code && effectParam(column) == param) return this
        val list = more.toMutableList()
        while (list.size < column) list += Fx.BLANK
        list[column - 1] = Fx(code, param)
        while (list.isNotEmpty() && list.last().isBlank) list.removeAt(list.lastIndex)
        return copy(more = list)
    }

    /** This cell with every effect column cleared. */
    fun withoutEffects(): Cell = if (!hasAnyEffect) this else copy(effect = 0, param = 0, more = emptyList())

    companion object {
        const val EMPTY = -1
        const val CUT = -2

        /** Most effect columns any system may declare. */
        const val MAX_EFFECT_COLUMNS = 4
        val BLANK = Cell()
    }
}

/** One effect column of a [Cell] beyond the first: the system's effect [effect] (0-35) and [param]; 0/0 = blank. */
data class Fx(val effect: Int, val param: Int) {
    val isBlank: Boolean get() = effect == 0 && param == 0

    companion object {
        val BLANK = Fx(0, 0)
    }
}

/**
 * Channel-major tracks (`tracks[channel][row]`, always [Songs.ROWS] rows); a pattern's index is its position in
 * [Song.patterns]. [length] (1..[Songs.ROWS]) is how many rows play; rows past it are kept but hidden, not
 * heard and exported blank, so shortening then lengthening loses nothing.
 * [scale] is editor-only note-entry guidance: it never alters [tracks] and is not exported or heard.
 */
@Serializable
data class Pattern(
    val name: String = "Pattern",
    val tracks: List<List<Cell>>,
    val scale: Scale = Scale.CHROMATIC,
    val length: Int = Songs.ROWS,
) {
    companion object {
        fun blank(channels: Int, rows: Int = Songs.ROWS, name: String = "Pattern"): Pattern =
            Pattern(name, List(channels) { List(rows) { Cell.BLANK } })
    }
}

/**
 * The whole song document. [orders] holds one pattern index per order position, shared by all channels;
 * [loopOrder] is where playback continues after the last order. [sound] is everything about the chip (instruments,
 * wave tables); [system] is the chip it is written for.
 * [tuning] is the optional song-wide microtuning: notes entered in it carry their fine pitch in the cell itself
 * ([org.thortracker.system.SoundSystem.fine]), so the tuning only guides entry and validation and is not exported;
 * null = plain 12-note table.
 * [newPatternScale] is the editor-only scale guide a newly created (blank) pattern starts with; like pattern
 * scales it is never exported or heard.
 * [parkedOrders] (ascending order indices) stay in the arrangement for editing but are skipped by song playback
 * and export ([org.thortracker.system.SoundSystem.arranged]).
 */
data class Song(
    val title: String = "Untitled",
    val artist: String = "",
    val comment: String = "",
    val ticksPerRow: Int = 6,
    val loopOrder: Int = 0,
    val patterns: List<Pattern>,
    val orders: List<Int>,
    val sound: SystemSound,
    val tuning: Tuning? = null,
    val newPatternScale: Scale = Scale.CHROMATIC,
    val parkedOrders: List<Int> = emptyList(),
) {
    /** The chip the song is written for. */
    val system: SoundSystem get() = sound.system
}

/**
 * Strict compact cell encoding: four integers, then two per extra effect column ([Cell.more]), canonical (no trailing
 * blank column). A one-column cell is exactly `[note, instrument, effect, param]` as before. Range checks (including how
 * many columns a channel may use) belong to the song's system ([org.thortracker.system.SoundSystem.structuralErrors]).
 */
object CellSerializer : KSerializer<Cell> {
    private val delegate = IntArraySerializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("org.thortracker.model.Cell", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Cell) {
        val out = IntArray(4 + 2 * value.more.size)
        out[0] = value.note; out[1] = value.instrument; out[2] = value.effect; out[3] = value.param
        value.more.forEachIndexed { i, fx -> out[4 + 2 * i] = fx.effect; out[5 + 2 * i] = fx.param }
        encoder.encodeSerializableValue(delegate, out)
    }

    override fun deserialize(decoder: Decoder): Cell {
        val values = decoder.decodeSerializableValue(delegate)
        if (values.size < 4 || values.size % 2 != 0 || values.size > 2 + 2 * Cell.MAX_EFFECT_COLUMNS) {
            throw SerializationException("A cell must be [note, instrument, effect, param] plus effect, param per further " +
                "effect column (at most ${Cell.MAX_EFFECT_COLUMNS} columns), got ${values.size} values")
        }
        val more = List((values.size - 4) / 2) { Fx(values[4 + 2 * it], values[5 + 2 * it]) }
        if (more.isNotEmpty() && more.last().isBlank) throw SerializationException("A cell's last effect column must not be blank (000)")
        return Cell(values[0], values[1], values[2], values[3], more)
    }
}
