package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.Vrc6SawVolume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/**
 * Lowers an NES song to FamiStudio sound engine 4.5.3 data (FamiTracker tempo) at its player's song base
 * ([NesPlayer.songBase]), in the layout the native `"nes-famistudio"` engines expect (docs/AUDIO_ENGINE.md, NES image
 * layout): the FamiStudio song header, an instrument table of all [NesSystem.SLOTS] slots (index = slot − 1; with the
 * EPSM, FamiStudio's expansion instrument table of all slots too and each slot's 31-byte FM patch block), every
 * envelope as an entry of the envelope directory (instrument envelopes at `slot × 4 + kind`, the EPSM's SSG mixer and
 * noise envelopes at `256 + slot × 2 + 0/1`, effect envelopes from the player's [NesPlayer.effectEnvelopes]), and one
 * stream per channel (the 2A03's five, then the expansion's) made only of `$41 n ptr` (play order o's pattern stream)
 * per order and a `$42` loop to the loop order. Identical pattern streams are stored once.
 *
 * Each pattern stream is what FamiStudio's own exporter (`FamitoneMusicFile.GetSongData`, FamiStudio kernel, NTSC)
 * emits for those rows: the same opcode order, the same state it tracks (instrument, arpeggio, last volume, vibrato
 * and SSG envelope-period overrides), the same slide and volume-slide step arithmetic. Two deliberate additions make an
 * order self-contained for seeking, looping and live edits without changing what plays: each order re-sends its first
 * note's instrument and, when a set volume (not a slide) is in force, the channel volume. Two exceptions keep orders
 * exactly FamiStudio's stream: an EPSM FM channel does not re-send the sound it holds (loading one rewrites the
 * channel's 31 patch registers, which FamiStudio's export does not), nor does an SSG square re-send a held sound with a
 * manual envelope period (its load would set the shared period, which FamiStudio's export may hold at another value
 * there); the seek fallback that starts such an order on its own plays that channel without its sound until its next
 * instrument. FamiStudio's own byte layout (instrument sorting and merging, CRC-ordered envelopes, its search-based
 * stream compression) is not reproduced; the golden test therefore compares register traces of both through the same
 * player.
 */
internal object FamiStudioLowering {
    /** FamiStudio's NTSC note table (`famistudio_note_table_lsb/msb.bin`), index = FamiStudio note 0..96. */
    val NOTE_TABLE = intArrayOf(
        0, 3419, 3228, 3046, 2875, 2714, 2561, 2418, 2282, 2154, 2033, 1919, 1811, 1709, 1613, 1523, 1437, 1356, 1280,
        1208, 1140, 1076, 1016, 959, 905, 854, 806, 761, 718, 678, 640, 604, 570, 538, 507, 479, 452, 427, 403, 380, 359,
        338, 319, 301, 284, 268, 253, 239, 225, 213, 201, 189, 179, 169, 159, 150, 142, 134, 126, 119, 112, 106, 100, 94,
        89, 84, 79, 75, 70, 66, 63, 59, 56, 52, 49, 47, 44, 41, 39, 37, 35, 33, 31, 29, 27, 26, 24, 23, 21, 20, 19, 18, 17,
        16, 15, 14, 13,
    )

    /**
     * FamiStudio's PAL note table (`NesApu.GetOrCreateNoteTableSet`: 1662607 / 16 / f − 0.5, truncated). Only slide
     * steps use it: FamiStudio's music export targets NTSC and PAL at once and takes the larger of the two steps.
     */
    val NOTE_TABLE_PAL = intArrayOf(
        0, 3176, 2998, 2830, 2671, 2521, 2379, 2246, 2120, 2001, 1888, 1782, 1682, 1588, 1499, 1414, 1335, 1260, 1189,
        1122, 1059, 1000, 944, 891, 841, 793, 749, 707, 667, 629, 594, 561, 529, 499, 471, 445, 420, 396, 374, 353, 333,
        314, 297, 280, 264, 249, 235, 222, 209, 198, 186, 176, 166, 157, 148, 139, 132, 124, 117, 110, 104, 98, 93, 87, 82,
        78, 73, 69, 65, 62, 58, 55, 52, 49, 46, 43, 41, 38, 36, 34, 32, 30, 29, 27, 25, 24, 22, 21, 20, 19, 18, 17, 16, 15,
        14, 13, 12,
    )

    /**
     * FamiStudio's VRC6 saw note tables (`NesApu.GetOrCreateNoteTableSet`: clock × 16 / (14 f) − 0.5, truncated; the
     * NTSC one is `famistudio_saw_note_table_lsb/msb.bin`). The VRC6 pulses use [NOTE_TABLE] / [NOTE_TABLE_PAL].
     */
    val NOTE_TABLE_SAW = intArrayOf(
        0, 3908, 3689, 3482, 3286, 3102, 2928, 2763, 2608, 2462, 2323, 2193, 2070, 1954, 1844, 1740, 1643, 1550, 1463, 1381,
        1304, 1230, 1161, 1096, 1034, 976, 921, 870, 821, 775, 731, 690, 651, 615, 580, 547, 517, 488, 460, 434, 410, 387, 365,
        345, 325, 307, 290, 273, 258, 243, 230, 217, 204, 193, 182, 172, 162, 153, 144, 136, 128, 121, 114, 108, 102, 96, 91,
        85, 81, 76, 72, 68, 64, 60, 57, 53, 50, 47, 45, 42, 40, 37, 35, 33, 31, 30, 28, 26, 25, 23, 22, 21, 19, 18, 17, 16, 15,
    )
    val NOTE_TABLE_SAW_PAL = intArrayOf(
        0, 3630, 3427, 3234, 3053, 2881, 2719, 2567, 2423, 2287, 2158, 2037, 1923, 1815, 1713, 1617, 1526, 1440, 1359, 1283,
        1211, 1143, 1079, 1018, 961, 907, 856, 808, 762, 720, 679, 641, 605, 571, 539, 509, 480, 453, 427, 403, 381, 359, 339,
        320, 302, 285, 269, 254, 239, 226, 213, 201, 190, 179, 169, 159, 150, 142, 134, 126, 119, 112, 106, 100, 94, 89, 84, 79,
        75, 70, 66, 63, 59, 56, 53, 50, 47, 44, 42, 39, 37, 35, 33, 31, 29, 27, 26, 24, 23, 22, 20, 19, 18, 17, 16, 15, 14,
    )

    /**
     * FamiStudio's EPSM FM note table (`famistudio_epsm_note_table_lsb/msb.bin` of the pinned release;
     * `NesApu.GetOrCreateNoteTableSet`: octave 0 = 144 f × 2^20 / 8 MHz / 4, every higher octave that shifted left): the
     * frequency number shifted by the block (the engine halves it below `$200` to find the block). Larger is higher;
     * NTSC and PAL share it (the EPSM has its own clock). FamiStudio note n sounds an octave below its name here.
     */
    val NOTE_TABLE_EPSM_FM = intArrayOf(
        0, 154, 163, 173, 183, 194, 205, 218, 231, 244, 259, 274, 291, 308, 326, 346, 366, 388, 410, 436, 462, 488, 518, 548,
        582, 616, 652, 692, 732, 776, 820, 872, 924, 976, 1036, 1096, 1164, 1232, 1304, 1384, 1464, 1552, 1640, 1744, 1848,
        1952, 2072, 2192, 2328, 2464, 2608, 2768, 2928, 3104, 3280, 3488, 3696, 3904, 4144, 4384, 4656, 4928, 5216, 5536,
        5856, 6208, 6560, 6976, 7392, 7808, 8288, 8768, 9312, 9856, 10432, 11072, 11712, 12416, 13120, 13952, 14784, 15616,
        16576, 17536, 18624, 19712, 20864, 22144, 23424, 24832, 26240, 27904, 29568, 31232, 33152, 35072, 37248,
    )

    /**
     * FamiStudio's EPSM SSG note table (`famistudio_epsm_s_note_table_lsb/msb.bin`; 8 MHz / 32 / f rounded): the SSG tone
     * period, f = 8 MHz / 64 / period, so FamiStudio note n sounds an octave below its name (note 58 = 440.1 Hz).
     */
    val NOTE_TABLE_EPSM_SSG = intArrayOf(
        0, 7645, 7215, 6810, 6428, 6067, 5727, 5405, 5102, 4816, 4545, 4290, 4050, 3822, 3608, 3405, 3214, 3034, 2863, 2703,
        2551, 2408, 2273, 2145, 2025, 1911, 1804, 1703, 1607, 1517, 1432, 1351, 1276, 1204, 1136, 1073, 1012, 956, 902, 851,
        804, 758, 716, 676, 638, 602, 568, 536, 506, 478, 451, 426, 402, 379, 358, 338, 319, 301, 284, 268, 253, 239, 225,
        213, 201, 190, 179, 169, 159, 150, 142, 134, 127, 119, 113, 106, 100, 95, 89, 84, 80, 75, 71, 67, 63, 60, 56, 53,
        50, 47, 45, 42, 40, 38, 36, 34, 32,
    )

    /** FamiStudio's EPSM FM slide shift (`Channel.GetShiftsForType`): slide deltas lose 3 bits, the engine shifts back. */
    private const val EPSM_FM_SLIDE_SHIFT = 3

    /** Bytes of one expansion instrument (FamiStudio's `instruments_exp` entries). */
    const val EXP_INSTRUMENT_SIZE = 16

    /** An EPSM expansion instrument's octave byte for "manual period" (FamiStudio's special code). */
    private const val EPSM_MANUAL_PERIOD = 0x80

    /**
     * FamiStudio's `epsmRegOrder`: the order an EPSM patch block holds `EpsmPatchRegs` in (`$B0`, `$B4`, each operator's
     * registers but its level, then the four operator levels, then `$22`).
     */
    private val EPSM_REG_ORDER = intArrayOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16, 18, 19, 20, 21, 22, 23, 25, 26,
        27, 28, 29, 3, 10, 17, 24, 30)

    /** FamiTracker tempo 150: one row per `speed` frames (FamiStudio's integer formula for NTSC and PAL). */
    const val TEMPO = 150
    const val TEMPO_PAL = 256 * TEMPO / 125
    const val TEMPO_NTSC = 256 * TEMPO / 150

    const val DPCM = 4

    /** The EPSM build's SSG mixer (`+ slot × 2`) and noise (`+ slot × 2 + 1`) envelopes start at this directory index. */
    const val MIXER_ENVELOPES = 256

    private const val OP_EXTENDED_NOTE = 0x40
    private const val OP_SET_REFERENCE = 0x41
    private const val OP_LOOP = 0x42
    private const val OP_DISABLE_ATTACK = 0x43
    private const val OP_RELEASE_NOTE = 0x45
    private const val OP_SPEED = 0x46
    private const val OP_DELAYED_NOTE = 0x47
    private const val OP_DELAYED_CUT = 0x48
    private const val OP_OVERRIDE_PITCH_ENV = 0x49
    private const val OP_CLEAR_PITCH_OVERRIDE = 0x4a
    private const val OP_OVERRIDE_ARP_ENV = 0x4b
    private const val OP_CLEAR_ARP_OVERRIDE = 0x4c
    private const val OP_RESET_ARP_ENV = 0x4d
    private const val OP_FINE_PITCH = 0x4e
    private const val OP_DUTY = 0x4f
    private const val OP_SLIDE = 0x50
    private const val OP_VOLUME_SLIDE = 0x51
    private const val OP_DELTA_COUNTER = 0x52
    private const val OP_VRC6_SAW_VOLUME = 0x55
    private const val OP_EPSM_RELEASE_NOTE = 0x5c
    private const val OP_EPSM_ENV_PERIOD = 0x5d
    private const val OP_VOLUME_BITS = 0x70
    private const val SINGLE_BYTE_NOTE_MIN = 12
    private const val SINGLE_BYTE_NOTE_MAX = SINGLE_BYTE_NOTE_MIN + 0x3f
    private const val MAX_REPEAT = 63

    /** One envelope of the directory: [index] (slot × 4 + kind, or ≥ 256), [kind], its bytes and CPU address. */
    class Envelope(val index: Int, val kind: Kind, val bytes: ByteArray) {
        var address = 0
    }

    /**
     * The lowered song: FamiStudio data for [NesPlayer.songBase] of its [player], its envelope directory, and the DPCM
     * layout ([NesDpcm]): the sample block for [NesDpcm.Layout.start] and the sample list.
     */
    class Lowered(val data: ByteArray, val envelopes: List<Envelope>, val instrumentCount: Int, val dpcm: NesDpcm.Layout, val player: NesPlayer) {
        val samples: ByteArray get() = dpcm.bytes
        val sampleStart: Int get() = dpcm.start
        val mappingCount: Int get() = dpcm.entries.size
    }

    /**
     * The song as exported: only the samples and mappings it plays. [withUnplayed] (the preview image) adds every other
     * DPCM mapping that fits, for auditioning, without taking room the song needs.
     * @throws IllegalArgumentException when the song has errors or its data and samples do not fit the NES memory.
     */
    fun lower(song: Song, withUnplayed: Boolean = false): Lowered {
        val errors = NesSystem.errors(song)
        require(errors.isEmpty()) { errors.first() }
        return lower(FsSong.of(song), withUnplayed)
    }

    fun lower(fs: FsSong, withUnplayed: Boolean = false): Lowered {
        val played = lower(fs, NesDpcm.layout(fs))
        if (!withUnplayed) return played
        // Unplayed mappings only append list entries and samples: the song's own bytes and entry numbers stay.
        val preview = NesDpcm.layout(fs, songBytes = played.data.size)
        return if (preview.entries.size == played.mappingCount && preview.bytes.size == played.samples.size) played else lower(fs, preview)
    }

    private fun lower(fs: FsSong, dpcm: NesDpcm.Layout): Lowered {
        val player = fs.player
        val base = player.songBase
        val channels = fs.channels
        val out = Bytes()
        // Header: song count, instruments, [expansion instruments,] samples - 4, a stream per channel, tempo words.
        val exp = player.expInstruments
        val epsm = fs.expansion == NesExpansion.EPSM
        val headerSize = 1 + 2 + (if (exp) 2 else 0) + 2 + channels * 2 + 4
        val instrumentsAt = headerSize
        val instrumentCount = NesSystem.SLOTS
        // FamiStudio's expansion instrument table (EPSM: 16 bytes per instrument) follows the 2A03 table, one entry per
        // slot as well: the expansion's channels load entry slot - 1.
        val expInstrumentsAt = instrumentsAt + instrumentCount * 8
        var at = expInstrumentsAt + if (exp) instrumentCount * EXP_INSTRUMENT_SIZE else 0
        // EPSM patch blocks (FamiStudio's `instrument_epsm_extra_patch`): one per distinct block, shared by equal patches.
        val patchAt = LinkedHashMap<List<Int>, Int>()
        val slotPatch = ArrayList<List<Int>>()
        if (epsm) for (ins in fs.instruments) {
            val regs = ins.epsm.registers(fs.epsmLfo)
            val block = EPSM_REG_ORDER.map { regs[it] }
            slotPatch += block
            if (block !in patchAt) {
                patchAt[block] = at
                at += block.size
            }
        }
        // Envelopes: per slot in kind order (volume, arpeggio, duty, pitch), the EPSM's mixer and noise, then effect envelopes.
        val envelopes = ArrayList<Envelope>()
        for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(slot * 4 + Kind.VOLUME.ordinal, Kind.VOLUME, FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME))
            envelopes += Envelope(slot * 4 + Kind.ARPEGGIO.ordinal, Kind.ARPEGGIO, FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO))
            envelopes += Envelope(slot * 4 + Kind.DUTY.ordinal, Kind.DUTY, FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY))
            envelopes += Envelope(slot * 4 + Kind.PITCH.ordinal, Kind.PITCH, FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative))
        }
        if (epsm) for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(MIXER_ENVELOPES + slot * 2, Kind.MIXER, FamiStudioEnvelopes.encode(ins.mixer, Kind.MIXER))
            envelopes += Envelope(MIXER_ENVELOPES + slot * 2 + 1, Kind.NOISE, FamiStudioEnvelopes.encode(ins.noise, Kind.NOISE))
        }
        var next = player.effectEnvelopes
        val noArpeggio = Envelope(next++, Kind.ARPEGGIO, FamiStudioEnvelopes.DEFAULT_ENV).also { envelopes += it }
        val vibratoOff = Envelope(next++, Kind.PITCH, FamiStudioEnvelopes.DEFAULT_PITCH_OR_RELEASE_ENV).also { envelopes += it }
        val arpeggios = LinkedHashMap<Int, Envelope>()
        val vibratos = LinkedHashMap<Int, Envelope>()
        for (ch in fs.notes) for (n in ch) {
            if (n == null) continue
            if (n.isMusical && n.arpeggio >= 0 && n.arpeggio !in arpeggios) {
                arpeggios[n.arpeggio] = Envelope(next++, Kind.ARPEGGIO, FamiStudioEnvelopes.arpeggio(n.arpeggio)).also { envelopes += it }
            }
            if (n.vibrato > 0 && (n.vibrato shr 4) != 0 && (n.vibrato and 0xF) != 0 && n.vibrato !in vibratos) {
                vibratos[n.vibrato] = Envelope(next++, Kind.PITCH, FamiStudioEnvelopes.vibrato(n.vibrato)).also { envelopes += it }
            }
        }
        require(next <= NesPlayerImage.MAX_ENVELOPES) {
            "The song uses ${next - player.effectEnvelopes - 2} different 0xy arpeggios and vibratos; the player's envelope " +
                "directory holds ${NesPlayerImage.MAX_ENVELOPES - player.effectEnvelopes - 2} with this chip. Reuse fewer distinct ones"
        }
        for (e in envelopes) {
            require(e.bytes.size in 2..255) { "An envelope is ${e.bytes.size} bytes once encoded; FamiStudio's limit is 255. Shorten it" }
            e.address = base + at
            at += e.bytes.size
        }
        // FamiStudio's DPCM sample list (`samples:`); the header points 4 bytes before it (entries count from 1).
        val samplesAt = at
        at += 4 * dpcm.entries.size
        val bySlot = envelopes.filter { it.index < player.effectEnvelopes }.associateBy { it.index }
        val instrumentArpeggios = IntArray(instrumentCount) { bySlot.getValue(it * 4 + Kind.ARPEGGIO.ordinal).address }
        val streams = Streams(fs, noArpeggio, vibratoOff, arpeggios, vibratos, instrumentArpeggios, dpcm)
        val patterns = LinkedHashMap<Bytes.Key, Int>() // pattern stream bytes → offset
        val patternOrder = ArrayList<ByteArray>()
        val channelRefs = Array(channels) { arrayOfNulls<ByteArray>(fs.orders) }
        val channelCounts = Array(channels) { IntArray(fs.orders) }
        for (c in 0 until channels) {
            val chunks = if (c == DPCM) streams.dpcm() else streams.channel(c)
            for (o in 0 until fs.orders) {
                channelRefs[c][o] = chunks[o].bytes
                channelCounts[c][o] = chunks[o].notes
            }
        }
        val channelAt = IntArray(channels)
        for (c in 0 until channels) {
            channelAt[c] = at
            at += fs.orders * 4 + 3
        }
        for (c in 0 until channels) for (o in 0 until fs.orders) {
            val bytes = channelRefs[c][o]!!
            val key = Bytes.Key(bytes)
            if (key !in patterns) {
                patterns[key] = at
                patternOrder += bytes
                at += bytes.size
            }
        }
        val sampleBytes = dpcm.bytes.size
        require(sampleBytes <= Dpcm.MEMORY) {
            "The DPCM samples the song plays need $sampleBytes bytes in 64-byte slots; at most ${Dpcm.MEMORY} fit " +
                "(\$C000-\$FFF9). Use fewer or shorter samples"
        }
        val room = (if (sampleBytes == 0) player.regionEnd else dpcm.start) - base
        require(at <= room) {
            if (sampleBytes == 0) {
                "The song needs ${kb(at)} of NES song data; the player has room for ${kb(room)} " +
                    "(\$${NumberText.hex(base, 4)}-\$FFF9). Reuse patterns or shorten the song"
            } else {
                "Song data ${kb(at)} + DPCM samples ${kb(sampleBytes)} exceed the NES's ${kb(player.regionSize)} " +
                    "(\$${NumberText.hex(base, 4)}-\$FFF9, samples from \$${NumberText.hex(dpcm.start, 4)}). " +
                    "Reuse patterns, shorten the song or use fewer or shorter samples"
            }
        }
        out.byte(1)
        out.word(base + instrumentsAt)
        if (exp) out.word(base + expInstrumentsAt)
        out.word(base + samplesAt - 4)
        for (c in 0 until channels) out.word(base + channelAt[c])
        out.word(TEMPO_PAL)
        out.word(TEMPO_NTSC)
        check(out.size == instrumentsAt)
        for (slot in 0 until instrumentCount) {
            for (kind in listOf(Kind.VOLUME, Kind.ARPEGGIO, Kind.DUTY, Kind.PITCH)) out.word(bySlot.getValue(slot * 4 + kind.ordinal).address)
        }
        if (exp) {
            // `FamitoneMusicFile.OutputInstruments` for an EPSM instrument: volume, arpeggio and pitch envelopes, the SSG
            // mixer and noise envelopes, the hardware envelope shape ($08-$0F, 0 = none), the auto-period octave ($80 =
            // manual period), the manual period and the pointer to its FM patch block.
            check(out.size == expInstrumentsAt)
            for ((slot, ins) in fs.instruments.withIndex()) {
                for (kind in listOf(Kind.VOLUME, Kind.ARPEGGIO, Kind.PITCH)) out.word(bySlot.getValue(slot * 4 + kind.ordinal).address)
                out.word(bySlot.getValue(MIXER_ENVELOPES + slot * 2).address)
                out.word(bySlot.getValue(MIXER_ENVELOPES + slot * 2 + 1).address)
                out.byte(ins.epsmEnvelope.shape.register)
                out.byte(if (ins.epsmEnvelope.autoPitch) ins.epsmEnvelope.octave and 0xff else EPSM_MANUAL_PERIOD)
                out.word(ins.epsmEnvelope.period)
                out.word(base + patchAt.getValue(slotPatch[slot]))
            }
            for (block in patchAt.keys) for (r in block) out.byte(r)
        }
        for (e in envelopes) out.bytes(e.bytes)
        check(out.size == samplesAt)
        for (e in dpcm.entries) for (b in dpcm.entryBytes(e)) out.byte(b)
        for (c in 0 until channels) {
            check(out.size == channelAt[c])
            for (o in 0 until fs.orders) {
                out.byte(OP_SET_REFERENCE)
                out.byte(channelCounts[c][o])
                out.word(base + patterns.getValue(Bytes.Key(channelRefs[c][o]!!)))
            }
            out.byte(OP_LOOP)
            out.word(base + channelAt[c] + 4 * fs.loopOrder)
        }
        for (bytes in patternOrder) out.bytes(bytes)
        check(out.size == at)
        return Lowered(out.toByteArray(), envelopes.sortedBy { it.index }, instrumentCount, dpcm, player)
    }

    /** "20.1 KB (20582 bytes)": sizes in budget messages. */
    internal fun kb(bytes: Int): String = "${bytes / 1024}.${bytes % 1024 * 10 / 1024} KB ($bytes bytes)"

    /** One order's pattern stream of one channel: [bytes] and its number of row terminators. */
    class Chunk(val bytes: ByteArray, val notes: Int)

    private class Streams(
        val fs: FsSong,
        val noArpeggio: Envelope,
        val vibratoOff: Envelope,
        val arpeggios: Map<Int, Envelope>,
        val vibratos: Map<Int, Envelope>,
        val instrumentArpeggios: IntArray,
        val dpcm: NesDpcm.Layout,
    ) {
        /** FamiStudio's speed channel: the one whose patterns hold the most speed effects (the first on a tie). */
        val speedChannel: Int = run {
            var best = 0
            var bestCount = 0
            for (c in 0 until fs.channels) {
                val count = FamiStudioText.distinctPatternOrders(fs, c).sumOf { o ->
                    (fs.orderStart[o] until fs.orderStart[o + 1]).count { (fs.notes[c][it]?.speed ?: -1) >= 0 }
                }
                if (count > bestCount) {
                    best = c
                    bestCount = count
                }
            }
            best
        }

        /**
         * The DPCM channel as `GetSongData` writes it: no instruments, volumes or pitches; a note is its sample list
         * entry (`$43` first when it has no attack: the sample then keeps the current output level), `$52` the DMC level
         * (bit 7 set: at once, as no note starts on that row), delays and cuts as on the other channels.
         */
        fun dpcm(): List<Chunk> {
            val c = DPCM
            val isSpeedChannel = c == speedChannel
            return (0 until fs.orders).map { o ->
                val b = Bytes()
                var notes = 0
                val end = fs.orderStart[o + 1]
                var row = fs.orderStart[o]
                while (row < end) {
                    val note = fs.notes[c][row] ?: EMPTY
                    if (isSpeedChannel) fs.speedAt(row).let { if (it >= 0) { b.byte(OP_SPEED); b.byte(it) } }
                    row++
                    if (note.noteDelay >= 0) { b.byte(OP_DELAYED_NOTE); b.byte(note.noteDelay - 1) }
                    if (note.cutDelay >= 0) { b.byte(OP_DELAYED_CUT); b.byte(note.cutDelay) }
                    if (note.deltaCounter >= 0) {
                        b.byte(OP_DELTA_COUNTER); b.byte((if (note.isMusical) 0 else 0x80) or note.deltaCounter)
                    }
                    if (note.isValid) {
                        if (note.isMusical) {
                            if (!note.attack) b.byte(OP_DISABLE_ATTACK)
                            b.byte(dpcm.index.getValue(note.instrument to note.value + NesRules.NOTE_OFFSET))
                        } else {
                            b.byte(0) // stop (OFF has no release on DPCM)
                        }
                    } else {
                        var empty = 0
                        while (row < end) {
                            val n = fs.notes[c][row] ?: EMPTY
                            if (empty >= MAX_REPEAT || n.isValid || n.hasRunBreakingEffect || (isSpeedChannel && fs.speedAt(row) >= 0)) break
                            empty++
                            row++
                        }
                        b.byte(0x81 or (empty shl 1))
                    }
                    notes++
                }
                Chunk(b.toByteArray(), notes)
            }
        }

        fun channel(c: Int): List<Chunk> {
            val isSpeedChannel = c == speedChannel
            val isSaw = NesRules.isVrc6Saw(fs.expansion, c)
            val isFm = NesRules.isEpsmFm(fs.expansion, c)
            val isSsg = NesRules.isEpsmSsg(fs.expansion, c)
            var currentSpeed = fs.speed
            var instrument = 0
            var arpeggio = -1
            // The VRC6 saw's master volume the engine holds (famistudio_music_play starts at Half), sent with instruments.
            var sawVolume = Vrc6SawVolume.HALF
            var lastVolume = 15
            var vibratoOverride = false
            // FamiStudio's `OverrideFlags.EnvPeriod`: an SSG envelope period effect played since this square last loaded
            // its instrument, so its next note with attack loads it again (restoring a manual period).
            var envOverride = false
            // Volume the engine holds (null while a volume slide may still be moving), for re-sending per order.
            var heldVolume: Int? = 15
            var firstInLoop = 0
            var lastInLoop = 0
            for (row in fs.orderStart[fs.loopOrder] until fs.totalRows) {
                val n = fs.notes[c][row] ?: continue
                if (n.isMusical && n.instrument != 0) {
                    if (firstInLoop == 0) firstInLoop = n.instrument
                    lastInLoop = n.instrument
                }
            }
            val usesArpeggios = fs.notes[c].any { it != null && it.isMusical && it.arpeggio >= 0 }
            val chunks = ArrayList<Chunk>()
            for (o in 0 until fs.orders) {
                val b = Bytes()
                var notes = 0
                var instrumentSent = false
                if (o == fs.loopOrder) {
                    if (lastInLoop != firstInLoop) instrument = 0
                    lastVolume = -1
                    if (usesArpeggios) b.byte(OP_CLEAR_ARP_OVERRIDE)
                }
                // The loop order is also entered from the song's end, where the volume may differ: FamiStudio leaves it.
                if (o > 0 && o != fs.loopOrder && heldVolume != null && heldVolume != 15) {
                    b.byte(OP_VOLUME_BITS or heldVolume!!)
                    lastVolume = heldVolume!!
                }
                val start = fs.orderStart[o]
                val end = fs.orderStart[o + 1]
                var row = start
                while (row < end) {
                    val note = fs.notes[c][row] ?: EMPTY
                    var frameVibrato = false
                    if (isSpeedChannel) {
                        val speed = fs.speedAt(row)
                        if (speed >= 0) {
                            currentSpeed = speed
                            b.byte(OP_SPEED); b.byte(speed)
                        }
                    }
                    val frameEnv = isSsg && ssg1SetsEnvPeriod(row)
                    if (frameEnv) envOverride = true
                    val at = row++
                    if (note.noteDelay >= 0) {
                        b.byte(OP_DELAYED_NOTE); b.byte(note.noteDelay - 1)
                    }
                    if (note.hasVolume) {
                        if (note.volume != lastVolume) {
                            b.byte(OP_VOLUME_BITS or note.volume)
                            lastVolume = note.volume
                        }
                        heldVolume = note.volume
                        if (note.volumeSlideTarget >= 0) {
                            b.byte(OP_VOLUME_SLIDE)
                            b.byte(volumeSlideStep(c, at, note, currentSpeed))
                            b.byte(note.volumeSlideTarget shl 4)
                            lastVolume = note.volumeSlideTarget
                            heldVolume = null
                        }
                    }
                    if (note.finePitch != FsNote.NO_FINE) {
                        b.byte(OP_FINE_PITCH); b.byte(note.finePitch)
                    }
                    if (note.vibrato >= 0) {
                        val vib = if ((note.vibrato shr 4) == 0 || (note.vibrato and 0xF) == 0) 0 else note.vibrato
                        b.byte(OP_OVERRIDE_PITCH_ENV)
                        b.word((if (vib == 0) vibratoOff else vibratos.getValue(vib)).address)
                        if (vib == 0) b.byte(OP_CLEAR_PITCH_OVERRIDE)
                        else {
                            frameVibrato = true
                            vibratoOverride = true
                        }
                    }
                    if (note.isMusical) {
                        if (note.arpeggio != arpeggio) {
                            b.byte(OP_OVERRIDE_ARP_ENV)
                            b.word(
                                when {
                                    note.arpeggio >= 0 -> arpeggios.getValue(note.arpeggio).address
                                    instrument != 0 -> instrumentArpeggio(instrument)
                                    else -> noArpeggio.address
                                }
                            )
                            if (note.arpeggio < 0) b.byte(OP_CLEAR_ARP_OVERRIDE)
                            arpeggio = note.arpeggio
                        } else if (note.attack && arpeggio >= 0) {
                            b.byte(OP_RESET_ARP_ENV)
                        }
                    }
                    if (note.duty >= 0) {
                        b.byte(OP_DUTY); b.byte(note.duty)
                    }
                    if (note.envelopePeriod >= 0) {
                        b.byte(OP_EPSM_ENV_PERIOD); b.word(note.envelopePeriod)
                    }
                    if (note.cutDelay >= 0) {
                        b.byte(OP_DELAYED_CUT); b.byte(note.cutDelay)
                    }
                    if (note.isValid) {
                        if (note.isMusical && note.instrument != 0) {
                            val attack = note.attack || !canDisableAttack(instrument, note.instrument)
                            if (!attack) b.byte(OP_DISABLE_ATTACK)
                            else {
                                if (!frameEnv && envOverride) {
                                    envOverride = false
                                    instrument = 0
                                }
                                if (!frameVibrato && vibratoOverride) {
                                    vibratoOverride = false
                                    instrument = 0
                                }
                            }
                            // Each order re-sends its first instrument (self-contained orders), except the sound an FM
                            // channel holds (its load rewrites the channel's patch registers) and an SSG sound with a manual
                            // envelope period the square holds (its load would set the shared period FamiStudio's export
                            // leaves alone there), so those orders play exactly the stream FamiStudio exports.
                            val held = note.instrument == instrument
                            val keepHeld = isFm || (isSsg && !fs.instruments[note.instrument - 1].epsmEnvelope.autoPitch)
                            if (!held || (!instrumentSent && !keepHeld)) {
                                // FamiStudio sends `$55` before a saw instrument whose master volume differs; each order
                                // also sends it with its first instrument, so the order is self-contained.
                                val volume = fs.instruments[note.instrument - 1].sawVolume
                                if (isSaw && (volume != sawVolume || !instrumentSent)) {
                                    b.byte(OP_VRC6_SAW_VOLUME); b.byte(volume.engine)
                                    sawVolume = volume
                                }
                                b.byte(0x80 or ((note.instrument - 1) shl 1))
                                instrument = note.instrument
                            }
                            instrumentSent = true
                        }
                        var emittedSlide = false
                        if (note.isMusical && note.slideTarget != 0) {
                            val step = slideStep(c, at, note, currentSpeed)
                            if (step != null) {
                                b.byte(OP_SLIDE); b.byte(step); b.byte(note.value); b.byte(note.slideTarget)
                                emittedSlide = true
                            }
                        }
                        if (!emittedSlide) {
                            when {
                                // EPSM FM channels release with their own opcode (key off, then the volume envelope's release).
                                note.value == FsNote.RELEASE -> b.byte(if (isFm) OP_EPSM_RELEASE_NOTE else OP_RELEASE_NOTE)
                                note.isMusical && (note.value <= SINGLE_BYTE_NOTE_MIN || note.value > SINGLE_BYTE_NOTE_MAX) -> {
                                    b.byte(OP_EXTENDED_NOTE); b.byte(note.value)
                                }
                                note.isMusical -> b.byte(note.value - SINGLE_BYTE_NOTE_MIN)
                                else -> b.byte(0) // stop
                            }
                        }
                    } else {
                        var empty = 0
                        while (row < end) {
                            val n = fs.notes[c][row] ?: EMPTY
                            if (empty >= MAX_REPEAT || n.isValid || n.hasRunBreakingEffect || (isSpeedChannel && fs.speedAt(row) >= 0)) break
                            if (isSsg && ssg1SetsEnvPeriod(row)) envOverride = true
                            empty++
                            row++
                        }
                        b.byte(0x81 or (empty shl 1))
                    }
                    notes++
                }
                chunks += Chunk(b.toByteArray(), notes)
            }
            return chunks
        }

        /** Address of instrument [slot]'s own arpeggio envelope (what a note without 0xy returns to). */
        private fun instrumentArpeggio(slot: Int): Int = instrumentArpeggios[slot - 1]

        /** FamiStudio's `Square1HasEnvelopePeriodEffect`: SSG 1 sets the envelope period on [row]. */
        private fun ssg1SetsEnvPeriod(row: Int): Boolean = (fs.notes[NesRules.EPSM_SSG1][row]?.envelopePeriod ?: -1) >= 0

        private fun canDisableAttack(i1: Int, i2: Int): Boolean = when {
            i1 == 0 || i2 == 0 -> false
            i1 == i2 -> true
            else -> FamiStudioEnvelopes.allIdentical(fs.instruments[i1 - 1], fs.instruments[i2 - 1])
        }

        /** `Song.CountFramesBetween` at FamiTracker tempo [TEMPO]: NTSC runs one frame per tick, PAL 125/150 of one. */
        private fun framesBetween(from: Int, to: Int, speed: Int, pal: Boolean = false): Float {
            val ratio = (if (pal) 125 else 150) / TEMPO.toFloat()
            var s = speed
            var frames = 0f
            var r = from
            while (r != to && r < fs.totalRows) {
                val set = fs.speedAt(r)
                if (set >= 0) s = set
                frames += s * ratio
                r++
            }
            return frames
        }

        /** `Channel.GetDistanceToNextNote` with `CutDurationMask` and no extra row after a cut. */
        private fun distanceToNextNote(c: Int, row: Int): Int {
            for (r in row + 1 until fs.totalRows) {
                val n = fs.notes[c][r] ?: continue
                if (n.isMusical || n.value == FsNote.STOP || n.cutDelay >= 0) return r - row
            }
            return fs.totalRows - row
        }

        /**
         * The slide opcode's step: `ComputeSlideNoteParams` for NTSC and for PAL, the larger magnitude with the NTSC sign
         * (FamiStudio's music export is always for both machines), or null when the slide is flat.
         */
        private fun slideStep(c: Int, row: Int, note: FsNote, speed: Int): Int? {
            val ntsc = slideStep(c, row, note, speed, pal = false) ?: return null
            val pal = slideStep(c, row, note, speed, pal = true) ?: return null
            return (maxOf(abs(ntsc), abs(pal)) * ntsc.sign).coerceIn(-128, 127)
        }

        private fun slideStep(c: Int, row: Int, note: FsNote, speed: Int, pal: Boolean): Int? {
            val fm = NesRules.isEpsmFm(fs.expansion, c)
            val table = when {
                fm -> NOTE_TABLE_EPSM_FM
                NesRules.isEpsmSsg(fs.expansion, c) -> NOTE_TABLE_EPSM_SSG
                NesRules.isVrc6Saw(fs.expansion, c) -> if (pal) NOTE_TABLE_SAW_PAL else NOTE_TABLE_SAW
                pal -> NOTE_TABLE_PAL
                else -> NOTE_TABLE
            }
            var delta = if (c == NesRules.NOISE) note.value - note.slideTarget else table[note.value] - table[note.slideTarget]
            if (delta == 0) return null
            // `GetShiftsForType`: 1 fraction bit on most channels, 4 on Noise; EPSM FM frequency deltas drop 3 bits.
            delta = when {
                fm -> delta shr EPSM_FM_SLIDE_SHIFT
                c == NesRules.NOISE -> delta shl 4
                else -> delta shl 1
            }
            val next = if (note.cutDelay >= 0) row else minOf(row + minOf(distanceToNextNote(c, row), 256), fs.totalRows)
            val frames = if (next != row) {
                var delay = -(if (note.noteDelay >= 0) note.noteDelay else 0)
                fs.note(c, next)?.let { n ->
                    if (n.noteDelay >= 0) delay += if (n.cutDelay >= 0) minOf(n.noteDelay, n.cutDelay) else n.noteDelay
                    else if (n.cutDelay >= 0) delay += n.cutDelay
                }
                framesBetween(row, next, speed, pal) + delay
            } else {
                (if (note.cutDelay >= 0) note.cutDelay else 0).toFloat()
            }
            return stepSize(delta, frames)
        }

        /** `ComputeVolumeSlideNoteParams` (volume has 4 fraction bits; FamiStudio computes the PAL step as NTSC too). */
        private fun volumeSlideStep(c: Int, row: Int, note: FsNote, speed: Int): Int {
            val delta = note.volume - note.volumeSlideTarget
            if (delta == 0) return 0
            val limit = minOf(row + 256, fs.totalRows)
            var next = fs.totalRows
            for (r in row + 1 until fs.totalRows) if (fs.notes[c][r]?.hasVolume == true) {
                next = r
                break
            }
            next = minOf(next, limit)
            var delay = -(if (note.noteDelay >= 0) note.noteDelay else 0)
            fs.note(c, next)?.let { if (it.noteDelay >= 0) delay += it.noteDelay }
            return stepSize(delta shl 4, framesBetween(row, next, speed) + delay)
        }

        /** FamiStudio's `(int)Math.Ceiling(|delta| / max(1, frames)) * -sign(delta)`, clamped to a signed byte. */
        private fun stepSize(delta: Int, frames: Float): Int {
            val perFrame = abs(delta).toFloat() / max(1f, frames)
            return (ceil(perFrame.toDouble()).toInt() * -delta.sign).coerceIn(-128, 127)
        }

        companion object {
            val EMPTY = FsNote()
        }
    }
}

/** A growable little-endian byte buffer. */
internal class Bytes {
    private var buf = ByteArray(256)
    var size = 0
        private set

    fun byte(v: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = v.toByte()
    }

    fun word(v: Int) {
        byte(v and 0xff)
        byte((v shr 8) and 0xff)
    }

    fun bytes(b: ByteArray) {
        for (x in b) byte(x.toInt())
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** [bytes] compared by content, as a map key. */
    class Key(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Key && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}
