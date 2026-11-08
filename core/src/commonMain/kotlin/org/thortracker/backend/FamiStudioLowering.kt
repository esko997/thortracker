package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.nes.Dpcm
import org.thortracker.system.nes.NesChip
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.Vrc6SawVolume
import org.thortracker.system.nes.Vrc7Presets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/**
 * Lowers an NES song to FamiStudio sound engine 4.5.3 data (FamiTracker tempo) at its player's song base
 * ([NesPlayer.songBase]), in the layout the native `"nes-famistudio"` engines expect (docs/AUDIO_ENGINE.md, NES image
 * layout): the FamiStudio song header, an instrument table of all [NesSystem.SLOTS] slots (index = slot − 1; with the
 * VRC7 or the 5B, FamiStudio's expansion instrument table of all slots too), every envelope as an entry of the envelope
 * directory (instrument envelopes at `slot × 4 + kind`, the 5B's mixer and noise envelopes at `256 + slot × 2 + 0/1`,
 * effect envelopes from the player's [NesPlayer.effectEnvelopes]), and one stream per channel (the 2A03's five, then
 * the expansion's) made only of `$41 n ptr` (play order o's pattern stream) per order and a `$42` loop to the loop
 * order. Identical pattern streams are stored once.
 *
 * Each pattern stream is what FamiStudio's own exporter (`FamitoneMusicFile.GetSongData`, FamiStudio kernel, NTSC)
 * emits for those rows: the same opcode order, the same state it tracks (instrument, arpeggio, last volume, vibrato
 * and 5B envelope-period overrides, the VRC7 custom patch another channel loads), the same slide and volume-slide step
 * arithmetic. Two deliberate additions make an order self-contained for seeking, looping and live edits without
 * changing what plays: each order re-sends its first note's instrument and, when a set volume (not a slide) is in
 * force, the channel volume. (On a VRC7 channel a re-sent custom patch rewrites the chip's patch registers with the
 * values they hold. On a 5B square a re-sent sound with a manual envelope period would set the shared period to its
 * own; where FamiStudio's export holds another there (another square's manual sound, or an `Ixx`/`Jyy` before a legato
 * note), a `$5E` with that period goes first; where the song's first pass and its loop passes hold different periods
 * there, the order does not re-send the sound.) FamiStudio's own byte layout (instrument sorting and merging, CRC-ordered envelopes, its
 * search-based stream compression) is not reproduced; the golden test therefore compares register traces of both
 * through the same player.
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
     * FamiStudio's VRC7 note table (`famistudio_vrc7_note_table_lsb/msb.bin`; `NesApu.GetOrCreateNoteTableSet`: octave 0
     * = f × 2^18 / 49715 + 0.5 truncated, every higher octave that shifted left): the FM frequency number shifted by the
     * block (the engine halves it until it is below `$200` to find the block). Larger is higher, unlike the 2A03's
     * periods; NTSC and PAL share it (the VRC7 has its own clock).
     */
    val NOTE_TABLE_VRC7 = intArrayOf(
        0, 172, 183, 194, 205, 217, 230, 244, 258, 274, 290, 307, 326, 344, 366, 388, 410, 434, 460, 488, 516, 548, 580,
        614, 652, 688, 732, 776, 820, 868, 920, 976, 1032, 1096, 1160, 1228, 1304, 1376, 1464, 1552, 1640, 1736, 1840,
        1952, 2064, 2192, 2320, 2456, 2608, 2752, 2928, 3104, 3280, 3472, 3680, 3904, 4128, 4384, 4640, 4912, 5216, 5504,
        5856, 6208, 6560, 6944, 7360, 7808, 8256, 8768, 9280, 9824, 10432, 11008, 11712, 12416, 13120, 13888, 14720, 15616,
        16512, 17536, 18560, 19648, 20864, 22016, 23424, 24832, 26240, 27776, 29440, 31232, 33024, 35072, 37120, 39296,
        41728,
    )

    /** FamiStudio's VRC7 slide shift (`Channel.GetShiftsForType`): slide deltas lose 3 bits, the engine shifts back. */
    private const val VRC7_SLIDE_SHIFT = 3

    /** Bytes of one expansion instrument (FamiStudio's `instruments_exp` entries). */
    const val EXP_INSTRUMENT_SIZE = 16

    /** A 5B expansion instrument's octave byte for "manual period" (FamiStudio's special code). */
    private const val S5B_MANUAL_PERIOD = 0x80

    /** In a 5B re-send plan: no period fits both passes through the loop, so the order does not re-send the sound. */
    private const val KEEP_PERIOD = -1

    /** FamiTracker tempo 150: one row per `speed` frames (FamiStudio's integer formula for NTSC and PAL). */
    const val TEMPO = 150
    const val TEMPO_PAL = 256 * TEMPO / 125
    const val TEMPO_NTSC = 256 * TEMPO / 150

    const val DPCM = 4
    const val EFFECT_ENVELOPES_START = 256

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
    private const val OP_VRC7_RELEASE_NOTE = 0x56
    private const val OP_S5B_ENV_PERIOD = 0x5e
    private const val OP_VOLUME_BITS = 0x70
    private const val SINGLE_BYTE_NOTE_MIN = 12
    private const val SINGLE_BYTE_NOTE_MAX = SINGLE_BYTE_NOTE_MIN + 0x3f
    private const val MAX_REPEAT = 63

    /** The 5B build's mixer (`+ slot × 2`) and noise (`+ slot × 2 + 1`) envelopes start at this directory index. */
    const val S5B_ENVELOPES = 256

    /** One envelope of the directory: [index] (slot × 4 + kind, the 5B's from [S5B_ENVELOPES], effect ones above), [kind], its bytes and CPU address. */
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
        val s5b = fs.expansion == NesExpansion.S5B
        val headerSize = 1 + 2 + (if (exp) 2 else 0) + 2 + channels * 2 + 4
        val instrumentsAt = headerSize
        val instrumentCount = NesSystem.SLOTS
        // FamiStudio's expansion instrument table (VRC7, 5B: 16 bytes per instrument) follows the 2A03 table, one entry
        // per slot as well: the expansion's channels load entry slot - 1.
        val expInstrumentsAt = instrumentsAt + instrumentCount * 8
        var at = expInstrumentsAt + if (exp) instrumentCount * EXP_INSTRUMENT_SIZE else 0
        // Envelopes: per slot in kind order (volume, arpeggio, duty, pitch), the 5B's mixer and noise, then effect envelopes.
        val envelopes = ArrayList<Envelope>()
        for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(slot * 4 + Kind.VOLUME.ordinal, Kind.VOLUME, FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME))
            envelopes += Envelope(slot * 4 + Kind.ARPEGGIO.ordinal, Kind.ARPEGGIO, FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO))
            envelopes += Envelope(slot * 4 + Kind.DUTY.ordinal, Kind.DUTY, FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY))
            envelopes += Envelope(slot * 4 + Kind.PITCH.ordinal, Kind.PITCH, FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative))
        }
        if (s5b) for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(S5B_ENVELOPES + slot * 2, Kind.MIXER, FamiStudioEnvelopes.encode(ins.mixer, Kind.MIXER))
            envelopes += Envelope(S5B_ENVELOPES + slot * 2 + 1, Kind.NOISE, FamiStudioEnvelopes.encode(ins.noise, Kind.NOISE))
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
            // `FamitoneMusicFile.OutputInstruments`: volume, arpeggio and pitch envelopes, then for a VRC7 instrument the
            // release/stop overrides ($20 / $80), the patch number << 4 and the custom patch's 8 registers; for a 5B
            // instrument the mixer and noise envelopes, the envelope shape ($08-$0F, 0 = none), the auto-period octave
            // ($80 = manual period), the manual period and two zero bytes.
            check(out.size == expInstrumentsAt)
            for ((slot, ins) in fs.instruments.withIndex()) {
                for (kind in listOf(Kind.VOLUME, Kind.ARPEGGIO, Kind.PITCH)) out.word(bySlot.getValue(slot * 4 + kind.ordinal).address)
                if (s5b) {
                    out.word(bySlot.getValue(S5B_ENVELOPES + slot * 2).address)
                    out.word(bySlot.getValue(S5B_ENVELOPES + slot * 2 + 1).address)
                    out.byte(ins.s5b.shape.register)
                    out.byte(if (ins.s5b.autoPitch) ins.s5b.octave and 0xff else S5B_MANUAL_PERIOD)
                    out.word(ins.s5b.period)
                    out.word(0)
                } else {
                    out.byte((if (ins.fm.sustainRelease) 0x20 else 0) or (if (ins.fm.sustainStop) 0x80 else 0))
                    out.byte(ins.fm.preset shl 4)
                    for (r in ins.fm.regs) out.byte(r)
                }
            }
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

        fun channel(c: Int): List<Chunk> =
            channel(c, if (NesRules.isS5b(fs.expansion, c)) s5bResends[c - NesRules.S5B_SQUARE1] else emptyMap(), null)

        /**
         * Per 5B square, the rows whose order-start re-send of a manual-period sound (one FamiStudio's export does not load
         * there: Thor Tracker's addition) needs the shared period set first: row → the period FamiStudio's stream holds
         * at the end of that frame, sent with `$5E` before the instrument (the `$5E` also keeps the load from setting the
         * period), so the re-send leaves the period as FamiStudio's export has it. [KEEP_PERIOD] where the song's first
         * pass and its loop passes hold different periods there: no value fits both, so that order does not re-send the
         * sound and plays exactly as FamiStudio's export; the order started on its own (the seek fallback) then plays
         * that square's notes without its sound until the square's next instrument.
         */
        private val s5bResends: List<Map<Int, Int>> by lazy { s5bResendPlan() }

        private fun s5bResendPlan(): List<Map<Int, Int>> {
            val first = NesRules.S5B_SQUARE1
            fun frame(ch: Int, row: Int) = (fs.notes[ch][row]?.noteDelay ?: -1).coerceAtLeast(0)
            // Period events as the engine meets them in a frame: squares in order, a square's `$5E` before its load.
            // Kinds: 0 `$5E` (moved to Square 1), 1 FamiStudio's load of a manual-period sound (sets the period
            // unless a `$5E` came earlier in the frame), 2 Thor Tracker's re-send of one (value: the sound's period).
            val events = ArrayList<IntArray>() // row, frame, square, kind, value
            for (row in 0 until fs.totalRows) {
                val period = fs.notes[first][row]?.envelopePeriod ?: -1
                if (period >= 0) events += intArrayOf(row, frame(first, row), 0, 0, period)
            }
            for (i in 0 until NesExpansion.S5B.extraChannels) {
                channel(first + i, emptyMap()) { row, slot, forced ->
                    val env = fs.instruments[slot - 1].s5b
                    if (!env.autoPitch) events += intArrayOf(row, frame(first + i, row), i, if (forced) 2 else 1, env.period)
                }
            }
            events.sortWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
            // The period at the end of each re-send's frame (key row * 3 + square) through rows from [from] with the
            // period [start]; returns the map and the period at the end.
            fun pass(from: Int, start: Int): Pair<Map<Int, Int>, Int> {
                val atResend = HashMap<Int, Int>()
                var period = start
                var k = events.indexOfFirst { it[0] >= from }.let { if (it < 0) events.size else it }
                while (k < events.size) {
                    val row = events[k][0]
                    val frameNo = events[k][1]
                    var override = false
                    val resends = ArrayList<Int>()
                    while (k < events.size && events[k][0] == row && events[k][1] == frameNo) {
                        val e = events[k++]
                        when (e[3]) {
                            0 -> { period = e[4]; override = true }
                            1 -> if (!override) period = e[4]
                            else -> resends += row * 3 + e[2]
                        }
                    }
                    for (key in resends) atResend[key] = period
                }
                return atResend to period
            }
            // famistudio_music_play clears the period; every loop pass starts from where the song's end left it (the
            // same each time: the loop's own events set it alike, or none and it stays).
            val (firstPass, end) = pass(0, 0)
            val loopPass = pass(fs.orderStart[fs.loopOrder], end).first
            val plan = List(NesExpansion.S5B.extraChannels) { HashMap<Int, Int>() }
            for (e in events) if (e[3] == 2) {
                val key = e[0] * 3 + e[2]
                val value = firstPass.getValue(key)
                plan[e[2]][e[0]] = when {
                    loopPass[key]?.let { it != value } == true -> KEEP_PERIOD
                    value == e[4] -> continue // the re-send sets what FamiStudio's export holds anyway
                    else -> value
                }
            }
            return plan
        }

        /**
         * Channel [c]'s order streams; [resends]: 5B `$5E` periods before order-start re-sends (see [s5bResends]);
         * [record] sees each instrument load: row, slot, and whether it is Thor Tracker's re-send (FamiStudio's export
         * would not load there).
         */
        private fun channel(c: Int, resends: Map<Int, Int>, record: ((Int, Int, Boolean) -> Unit)?): List<Chunk> {
            val isSpeedChannel = c == speedChannel
            val isSaw = NesRules.isVrc6Saw(fs.expansion, c)
            val isVrc7 = NesRules.isVrc7(fs.expansion, c)
            val isS5b = NesRules.isS5b(fs.expansion, c)
            var currentSpeed = fs.speed
            var instrument = 0
            var arpeggio = -1
            // The VRC6 saw's master volume the engine holds (famistudio_music_play starts at Half), sent with instruments.
            var sawVolume = Vrc6SawVolume.HALF
            var lastVolume = 15
            var vibratoOverride = false
            // FamiStudio's `OverrideFlags.EnvPeriod`: a 5B envelope period effect played since this square last loaded
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
                    if (isVrc7 && otherVrc7ChannelUsesCustomPatch(c, instrument, row)) instrument = 0
                    val frameEnv = isS5b && square1SetsEnvPeriod(row)
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
                        b.byte(OP_S5B_ENV_PERIOD); b.word(note.envelopePeriod)
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
                            if (note.instrument != instrument || !instrumentSent) {
                                val resend = if (note.instrument == instrument) resends[at] else null
                                if (resend == KEEP_PERIOD) {
                                    instrumentSent = true
                                } else {
                                    record?.invoke(at, note.instrument, note.instrument == instrument)
                                    if (resend != null) {
                                        b.byte(OP_S5B_ENV_PERIOD); b.word(resend)
                                    }
                                    // FamiStudio sends `$55` before a saw instrument whose master volume differs; each order
                                    // also sends it with its first instrument, so the order is self-contained.
                                    val volume = fs.instruments[note.instrument - 1].sawVolume
                                    if (isSaw && (volume != sawVolume || !instrumentSent)) {
                                        b.byte(OP_VRC6_SAW_VOLUME); b.byte(volume.engine)
                                        sawVolume = volume
                                    }
                                    b.byte(0x80 or ((note.instrument - 1) shl 1))
                                    instrument = note.instrument
                                    instrumentSent = true
                                }
                            }
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
                                // VRC7 channels release with their own opcode (key off, then the volume envelope's release).
                                note.value == FsNote.RELEASE -> b.byte(if (isVrc7) OP_VRC7_RELEASE_NOTE else OP_RELEASE_NOTE)
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
                            if (isVrc7 && otherVrc7ChannelUsesCustomPatch(c, instrument, row)) instrument = 0
                            if (empty >= MAX_REPEAT || n.isValid || n.hasRunBreakingEffect || (isSpeedChannel && fs.speedAt(row) >= 0)) break
                            if (isS5b && square1SetsEnvPeriod(row)) envOverride = true
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

        /** FamiStudio's `Square1HasEnvelopePeriodEffect`: 5B Square 1 sets the envelope period on [row]. */
        private fun square1SetsEnvPeriod(row: Int): Boolean = (fs.notes[NesRules.S5B_SQUARE1][row]?.envelopePeriod ?: -1) >= 0

        /** Address of instrument [slot]'s own arpeggio envelope (what a note without 0xy returns to). */
        private fun instrumentArpeggio(slot: Int): Int = instrumentArpeggios[slot - 1]

        private fun canDisableAttack(i1: Int, i2: Int): Boolean = when {
            i1 == 0 || i2 == 0 -> false
            i1 == i2 -> true
            else -> FamiStudioEnvelopes.allIdentical(fs.instruments[i1 - 1], fs.instruments[i2 - 1])
        }

        /**
         * FamiStudio's `OtherVrc7ChannelUsesCustomPatch`: true when VRC7 channel [c]'s current instrument [instrument] is
         * a custom patch and another VRC7 channel has a note with another custom-patch instrument on [row], which loads
         * the chip's one custom patch; the channel then sends its instrument again with its next note.
         */
        private fun otherVrc7ChannelUsesCustomPatch(c: Int, instrument: Int, row: Int): Boolean {
            if (instrument == 0 || !isCustomVrc7(instrument)) return false
            for (other in NesRules.VRC7_FM1 until fs.channels) {
                if (other == c) continue
                val n = fs.notes[other][row] ?: continue
                if (n.instrument != 0 && n.instrument != instrument && isCustomVrc7(n.instrument)) return true
            }
            return false
        }

        private fun isCustomVrc7(slot: Int): Boolean =
            fs.instruments[slot - 1].let { it.chip == NesChip.VRC7 && it.fm.preset == Vrc7Presets.CUSTOM }

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
            val vrc7 = NesRules.isVrc7(fs.expansion, c)
            val table = when {
                vrc7 -> NOTE_TABLE_VRC7
                NesRules.isVrc6Saw(fs.expansion, c) -> if (pal) NOTE_TABLE_SAW_PAL else NOTE_TABLE_SAW
                pal -> NOTE_TABLE_PAL
                else -> NOTE_TABLE
            }
            var delta = if (c == NesRules.NOISE) note.value - note.slideTarget else table[note.value] - table[note.slideTarget]
            if (delta == 0) return null
            // `GetShiftsForType`: 1 fraction bit on most channels, 4 on Noise; VRC7 frequency deltas drop 3 bits.
            delta = when {
                vrc7 -> delta shr VRC7_SLIDE_SHIFT
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
