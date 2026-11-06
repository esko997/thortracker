package org.thortracker.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Instrument bank / channel hardware type of the GB/hUGE backend. */
@Serializable
enum class InstrumentKind { PULSE, WAVE, NOISE }

/**
 * One channel-row of a pattern.
 *
 * [note]: [EMPTY] (-1) = no note, [CUT] (-2) = explicit note cut, shown as "OFF" (exported as E00: immediate
 * silence, no envelope release), 0..71 = C-3..B-8 in hUGE numbering.
 * [instrument]: 0 = legato (hUGEDriver changes the pitch without restarting the note or reloading the instrument,
 * so it is silent after an OFF or before any instrument has played), 1..15 = slot in the channel's typed bank.
 * [effect]/[param]: raw hUGEDriver effect nibble and parameter byte; 0/0 ("000") is no effect.
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
 * A GB/hUGE instrument. Only the fields relevant to [kind] are audible; the rest are preserved.
 *
 * - envelopeDirection / sweepDirection: 0 = decrease, 1 = increase.
 * - length: raw length-timer register value (pulse/noise 0..63, wave 0..255); sound lasts (64 - length)/256 s
 *   (wave: (256 - length)/256 s) when [lengthEnabled].
 * - sweepTime 0..7 (0 = off), sweepShift 0..7: pulse frequency sweep, hardware exists on Pulse 1 only.
 * - wave 0..15: waveform index; waveVolume: 1 = 100%, 2 = 50%, 3 = 25%, 0 = mute.
 * - noiseWidth: 0 = 15-bit (long) LFSR, 1 = 7-bit (short, metallic) LFSR.
 */
@Serializable
data class Instrument(
    val name: String = "Sound",
    val kind: InstrumentKind = InstrumentKind.PULSE,
    val duty: Int = 2,
    val volume: Int = 12,
    val envelopeDirection: Int = 0,
    val envelopePace: Int = 0,
    val length: Int = 0,
    val lengthEnabled: Boolean = false,
    val sweepTime: Int = 0,
    val sweepDirection: Int = 0,
    val sweepShift: Int = 0,
    val wave: Int = 0,
    val waveVolume: Int = 1,
    val noiseWidth: Int = 0,
) {
    /** True when the pulse frequency sweep actually changes pitch (needs Pulse 1 hardware). */
    val usesSweep: Boolean get() = kind == InstrumentKind.PULSE && sweepTime != 0 && sweepShift != 0
}

/**
 * The whole GB/hUGE song document. [orders] holds one pattern index per order position, shared by all
 * channels; [loopOrder] is where playback continues after the last order. Typed banks hold exactly 15
 * instruments (slot n = index n-1); [waves] holds exactly 16 waveforms of 32 samples 0..15.
 * [tuning] is the optional song-wide microtuning: Pulse/Wave notes entered in it carry their detune as `40y` in the
 * cell itself, so the tuning only guides entry and validation and is not exported; null = plain 12-note table.
 * [newPatternScale] is the editor-only scale guide a newly created (blank) pattern starts with; like pattern
 * scales it is never exported or heard.
 * [parkedOrders] (ascending order indices) stay in the arrangement for editing but are skipped by song playback
 * and export ([HugeRules.arranged]).
 */
@Serializable
data class Song(
    val title: String = "Untitled",
    val artist: String = "",
    val comment: String = "",
    val backend: String = Songs.BACKEND,
    val ticksPerRow: Int = 6,
    val loopOrder: Int = 0,
    val patterns: List<Pattern>,
    val orders: List<Int>,
    val pulseInstruments: List<Instrument>,
    val waveInstruments: List<Instrument>,
    val noiseInstruments: List<Instrument>,
    val waves: List<List<Int>>,
    val tuning: Tuning? = null,
    val newPatternScale: Scale = Scale.CHROMATIC,
    val parkedOrders: List<Int> = emptyList(),
)

/** Strict compact cell encoding: exactly four integers. Range checks belong to [SongValidator]. */
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
