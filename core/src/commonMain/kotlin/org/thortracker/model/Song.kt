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
 * Serialized compactly as `[note, instrument, effect, param]`.
 */
@Serializable(with = CellSerializer::class)
data class Cell(val note: Int = EMPTY, val instrument: Int = 0, val effect: Int = 0, val param: Int = 0) {
    val isBlank: Boolean get() = note == EMPTY && instrument == 0 && effect == 0 && param == 0
    val hasEffect: Boolean get() = effect != 0 || param != 0

    companion object {
        const val EMPTY = -1
        const val CUT = -2
        val BLANK = Cell()
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

/** Strict compact cell encoding: exactly four integers. Range checks belong to the song's system ([org.thortracker.system.SoundSystem.structuralErrors]). */
object CellSerializer : KSerializer<Cell> {
    private val delegate = IntArraySerializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("org.thortracker.model.Cell", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Cell) =
        encoder.encodeSerializableValue(delegate, intArrayOf(value.note, value.instrument, value.effect, value.param))

    override fun deserialize(decoder: Decoder): Cell {
        val values = decoder.decodeSerializableValue(delegate)
        if (values.size != 4) throw SerializationException("A cell must be [note, instrument, effect, param], got ${values.size} values")
        return Cell(values[0], values[1], values[2], values[3])
    }
}
