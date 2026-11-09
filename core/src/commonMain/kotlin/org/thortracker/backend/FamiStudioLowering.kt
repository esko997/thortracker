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
 * VRC7, the N163, the FDS or the 5B, FamiStudio's expansion instrument table of all slots too), every envelope as an entry
 * of the envelope directory (instrument envelopes at `slot × 4 + kind`; N163 and FDS: each slot's wave-index envelope at
 * 256 + slot − 1; 5B: each slot's mixer and noise envelopes at `256 + (slot − 1) × 2 + 0/1`; effect envelopes from the
 * player's [NesPlayer.effectEnvelopes]: 256, N163 and FDS 320, 5B 384), the N163 or FDS waves and their per-slot tables
 * (FDS: and the modulation tables), and one stream per channel (the 2A03's five, then the expansion's) made only of
 * `$41 n ptr` (play order o's pattern stream) per order and a `$42` loop to the loop order. Identical pattern streams,
 * waves, wave tables and modulation tables are stored once.
 *
 * Each pattern stream is what FamiStudio's own exporter (`FamitoneMusicFile.GetSongData`, FamiStudio kernel, NTSC)
 * emits for those rows: the same opcode order, the same state it tracks (instrument, arpeggio, last volume, vibrato,
 * FDS mod speed/depth and 5B envelope-period overrides, the VRC7 custom patch another channel loads), the same slide and
 * volume-slide step arithmetic. Two deliberate additions make an order self-contained for seeking, looping and live
 * edits without changing what plays: each order re-sends its first note's instrument and, when a set volume (not a
 * slide) is in force, the channel volume. Two sounds are not re-sent, because their load rewrites shared chip state
 * another channel may have changed since: a custom-patch VRC7 sound (the chip's shared patch registers) and a 5B sound
 * with a manual envelope period its square already holds (the shared period, which FamiStudio's export may hold at
 * another value there: another square's manual sound, an `Ixx`/`Jyy` before a legato note), so such orders are exactly
 * FamiStudio's stream. Streams load each note's own bank slot even where FamiStudio's export merges identical copies into
 * one instrument; on a 5B square switching between merged manual-period copies (where FamiStudio loads nothing) that load
 * is `$5F i`, which keeps the shared period. On an N163 or FDS channel a re-sent instrument reloads its envelopes but
 * uploads no wave unless the note has an attack. An FDS legato re-send or switch between merged copies uses `$5F i`
 * to retain the runtime modulation state while loading the actual bank slot. This also handles a loop entered with
 * different overrides from its intro: no guessed constants or omitted instrument loads. Two deliberate corrections: loop entries
 * restore a custom VRC7 patch if the loop uses another custom sound (delays can change the final writer); mid-song
 * restoration keeps instrument identity, so a no-attack note does not retrigger its held envelopes. FamiStudio's editor
 * preserves that legato, but its 4.5.3 exporter forces an attack after another custom patch. Its own byte layout
 * (instrument sorting/merging, CRC-ordered envelopes and stream compression) is not reproduced. Golden tests compare both
 * through the same player, with a separate upstream fixture for these two corrections.
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

    /**
     * FamiStudio's FDS note tables (`NesApu.GetOrCreateNoteTableSet`: f × 65536 / (clock / 16) + 0.5, truncated; the NTSC
     * one is `famistudio_fds_note_table_lsb/msb.bin`, vendored in third_party/famistudio-4.5.3/NoteTables): the chip's
     * 12-bit frequency, larger is higher. Notes above 93 (A-8) exceed 12 bits ([NesRules.FDS_HIGHEST_FS_NOTE]). The PAL
     * one only serves slide steps (FamiStudio's music export takes the larger of the NTSC and PAL steps). Slides keep one
     * fraction bit as on the 2A03 (`Channel.GetShiftsForType`: slide shift −1, pitch shift 0).
     */
    val NOTE_TABLE_FDS = intArrayOf(
        0, 19, 20, 22, 23, 24, 26, 27, 29, 30, 32, 34, 36, 38, 41, 43, 46, 48, 51, 54, 57, 61, 64, 68, 72, 77, 81, 86, 91,
        97, 102, 108, 115, 122, 129, 137, 145, 153, 162, 172, 182, 193, 205, 217, 230, 243, 258, 273, 289, 307, 325, 344,
        365, 386, 409, 434, 459, 487, 516, 546, 579, 613, 650, 688, 729, 772, 818, 867, 919, 973, 1031, 1092, 1157, 1226,
        1299, 1376, 1458, 1545, 1637, 1734, 1837, 1947, 2062, 2185, 2315, 2452, 2598, 2753, 2916, 3090, 3274, 3468, 3675,
        3893, 4125, 4370, 4630,
    )
    val NOTE_TABLE_FDS_PAL = intArrayOf(
        0, 21, 22, 23, 25, 26, 28, 29, 31, 33, 35, 37, 39, 41, 44, 46, 49, 52, 55, 58, 62, 65, 69, 74, 78, 83, 87, 93, 98,
        104, 110, 117, 124, 131, 139, 147, 156, 165, 175, 185, 196, 208, 220, 233, 247, 262, 278, 294, 311, 330, 350, 370,
        392, 416, 441, 467, 494, 524, 555, 588, 623, 660, 699, 741, 785, 832, 881, 933, 989, 1048, 1110, 1176, 1246, 1320,
        1399, 1482, 1570, 1663, 1762, 1867, 1978, 2095, 2220, 2352, 2492, 2640, 2797, 2963, 3140, 3326, 3524, 3734, 3956,
        4191, 4440, 4704, 4984,
    )

    /** Bytes of one expansion instrument (FamiStudio's `instruments_exp` entries). */
    const val EXP_INSTRUMENT_SIZE = 16

    /** A 5B expansion instrument's octave byte for "manual period" (FamiStudio's special code). */
    private const val S5B_MANUAL_PERIOD = 0x80

    /**
     * Set in the high byte of an FDS instrument's manual mod speed whose low byte is $80-$FF (Thor Tracker's patched
     * FDS player: manual speed = low | (high & $0F) << 8; FamiStudio's engine would read that low byte as auto-mod).
     */
    private const val FDS_MANUAL_SPEED_TAG = 0x80

    /** FamiTracker tempo 150: one row per `speed` frames (FamiStudio's integer formula for NTSC and PAL). */
    const val TEMPO = 150
    const val TEMPO_PAL = 256 * TEMPO / 125
    const val TEMPO_NTSC = 256 * TEMPO / 150

    const val DPCM = 4
    const val EFFECT_ENVELOPES_START = 256

    /** N163 and FDS images: slot s's wave-index envelope at directory index 256 + s − 1, effect envelopes from 320. */
    const val WAVE_ENVELOPES_START = 256
    const val WAVE_EFFECT_ENVELOPES_START = WAVE_ENVELOPES_START + 64

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
    private const val OP_N163_RELEASE_NOTE = 0x5a
    private const val OP_N163_PHASE_RESET = 0x5b
    private const val OP_PHASE_RESET = 0x53
    private const val OP_FDS_MOD_SPEED = 0x57
    private const val OP_FDS_MOD_DEPTH = 0x58
    private const val OP_FDS_RELEASE_NOTE = 0x59
    private const val OP_S5B_ENV_PERIOD = 0x5e

    /** `$5F i`: load the actual slot while keeping FDS modulation or the 5B shared period (Thor Tracker's opcode). */
    private const val OP_KEEP_EXPANSION_STATE_INSTRUMENT = 0x5f
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
        // FamiStudio's expansion instrument table (VRC7, N163, 5B: 16 bytes per instrument) follows the 2A03 table, one
        // entry per slot as well: the expansion's channels load entry slot - 1.
        val expInstrumentsAt = instrumentsAt + instrumentCount * 8
        var at = expInstrumentsAt + if (exp) instrumentCount * EXP_INSTRUMENT_SIZE else 0
        val n163 = fs.expansion.chip == NesChip.N163
        val fds = fs.expansion.chip == NesChip.FDS
        val waves = n163 || fds
        // Envelopes: per slot in kind order (volume, arpeggio, duty, pitch), [N163, FDS: each slot's wave-index envelope,]
        // [5B: each slot's mixer and noise envelopes,] then effect envelopes from the player's [NesPlayer.effectEnvelopes].
        val envelopes = ArrayList<Envelope>()
        for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(slot * 4 + Kind.VOLUME.ordinal, Kind.VOLUME, FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME))
            envelopes += Envelope(slot * 4 + Kind.ARPEGGIO.ordinal, Kind.ARPEGGIO, FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO))
            envelopes += Envelope(slot * 4 + Kind.DUTY.ordinal, Kind.DUTY, FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY))
            envelopes += Envelope(slot * 4 + Kind.PITCH.ordinal, Kind.PITCH, FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative))
        }
        // Every slot has the chip's data in an N163 or FDS image (2A03 sounds their default waves), so every expansion
        // entry is whole.
        val waveEnvelopes = if (!waves) emptyList() else fs.instruments.mapIndexed { slot, ins ->
            val bytes = if (fds) FamiStudioEnvelopes.waveIndex(ins.fds) else FamiStudioEnvelopes.waveIndex(ins.wave)
            Envelope(WAVE_ENVELOPES_START + slot, Kind.WAVE, bytes).also { envelopes += it }
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
        // N163 and FDS waves: each distinct wave's bytes once (`n163_instN_waves` / `fds_instN_waves` entries point at
        // them), then each slot's table of pointers to its stored waves (identical tables once); FDS: then each distinct
        // modulation table (the expansion entries point at it; FamiStudio stores it among its envelopes, deduplicated).
        val waveBlobs = ArrayList<ByteArray>()
        val tableBlobs = ArrayList<ByteArray>()
        val modBlobs = ArrayList<ByteArray>()
        val slotTables = IntArray(instrumentCount)
        val slotMods = IntArray(instrumentCount)
        if (waves) {
            val slotWaves = fs.instruments.map { ins ->
                if (fds) FamiStudioEnvelopes.fdsWaves(ins.fds).stored.map(FamiStudioEnvelopes::fdsWaveBytes)
                else FamiStudioEnvelopes.n163Waves(ins.wave).stored.map(FamiStudioEnvelopes::n163WaveBytes)
            }
            val waveOffsets = HashMap<Bytes.Key, Int>()
            for (stored in slotWaves) for (w in stored) {
                val key = Bytes.Key(w)
                if (key !in waveOffsets) {
                    waveOffsets[key] = at
                    waveBlobs += w
                    at += w.size
                }
            }
            val tables = HashMap<Bytes.Key, Int>()
            for ((slot, stored) in slotWaves.withIndex()) {
                val table = Bytes()
                for (w in stored) table.word(base + waveOffsets.getValue(Bytes.Key(w)))
                val bytes = table.toByteArray()
                slotTables[slot] = tables.getOrPut(Bytes.Key(bytes)) {
                    tableBlobs += bytes
                    at.also { at += bytes.size }
                }
            }
        }
        if (fds) {
            val mods = HashMap<Bytes.Key, Int>()
            for ((slot, ins) in fs.instruments.withIndex()) {
                val bytes = FamiStudioEnvelopes.fdsModBytes(ins.fds)
                slotMods[slot] = mods.getOrPut(Bytes.Key(bytes)) {
                    modBlobs += bytes
                    at.also { at += bytes.size }
                }
            }
        }
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
            check(out.size == expInstrumentsAt)
            for ((slot, ins) in fs.instruments.withIndex()) {
                for (kind in listOf(Kind.VOLUME, Kind.ARPEGGIO, Kind.PITCH)) out.word(bySlot.getValue(slot * 4 + kind.ordinal).address)
                when {
                    n163 -> {
                        // `OutputInstruments` for an N163 instrument: the wave-index envelope, the wave position and size
                        // in samples, the wave table, 4 zero bytes.
                        out.word(waveEnvelopes[slot].address)
                        out.byte(ins.wave.position)
                        out.byte(ins.wave.size)
                        out.word(base + slotTables[slot])
                        repeat(4) { out.byte(0) }
                    }
                    fds -> {
                        // `OutputInstruments` for an FDS instrument: the wave-index envelope, (mod depth << 2) | master
                        // volume, the wave table, the modulation table, then the mod speed word or, with auto-mod, $80 |
                        // numerator and the denominator (reduced: `Utils.SimplifyFraction`), and the mod delay. Upstream
                        // FamiStudio's engine reads bit 7 of the speed's low byte as the auto-mod flag, so its export plays
                        // a manual speed with a low byte of $80-$FF as auto-mod; Thor Tracker's FDS player (a pinned patch
                        // of the engine) takes such a speed with bit 7 of its high byte set as a manual one (the speed
                        // is 12-bit, so that bit is otherwise clear): every manual speed 0..4095 plays as itself, and the
                        // other speeds keep FamiStudio's bytes.
                        val f = ins.fds
                        out.word(waveEnvelopes[slot].address)
                        out.byte((f.modDepth shl 2) or f.masterVolume.register)
                        out.word(base + slotTables[slot])
                        out.word(base + slotMods[slot])
                        if (f.autoMod) {
                            val (numer, denom) = f.autoModFraction
                            out.byte(0x80 or numer)
                            out.byte(denom)
                        } else {
                            out.byte(f.modSpeed and 0xFF)
                            out.byte((f.modSpeed shr 8) or (if (f.modSpeed and 0x80 != 0) FDS_MANUAL_SPEED_TAG else 0))
                        }
                        out.byte(f.modDelay)
                    }
                    s5b -> {
                        // `OutputInstruments` for a 5B instrument: the mixer and noise envelopes, the envelope shape
                        // ($08-$0F, 0 = none), the auto-period octave ($80 = manual period), the manual period and two
                        // zero bytes.
                        out.word(bySlot.getValue(S5B_ENVELOPES + slot * 2).address)
                        out.word(bySlot.getValue(S5B_ENVELOPES + slot * 2 + 1).address)
                        out.byte(ins.s5b.shape.register)
                        out.byte(if (ins.s5b.autoPitch) ins.s5b.octave and 0xff else S5B_MANUAL_PERIOD)
                        out.word(ins.s5b.period)
                        out.word(0)
                    }
                    else -> {
                        // `FamitoneMusicFile.OutputInstruments` for a VRC7 instrument: volume, arpeggio and pitch
                        // envelopes, the release/stop overrides ($20 / $80: the song's, so every instrument carries the
                        // same), the patch number << 4 and the 8 patch registers (a preset's ROM values, as FamiStudio's
                        // `Vrc7PatchRegs` holds them; the engine loads them only for the custom patch, 0).
                        out.byte(fs.vrc7Sustain.engine)
                        out.byte(ins.fm.preset shl 4)
                        for (r in ins.fm.sounding) out.byte(r)
                    }
                }
            }
        }
        for (e in envelopes) out.bytes(e.bytes)
        check(out.size == samplesAt)
        for (e in dpcm.entries) for (b in dpcm.entryBytes(e)) out.byte(b)
        for (w in waveBlobs) out.bytes(w)
        for (t in tableBlobs) out.bytes(t)
        for (m in modBlobs) out.bytes(m)
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
         * Each slot's instrument in FamiStudio's export, which merges identical instruments before `GetSongData`
         * (`Project.MergeIdenticalInstruments`: a CRC of the instrument without its name and colour) and then compares
         * instruments by identity: the loop's first and last instrument and `Channel.CanDisableAttack`. The first slot
         * with the same exported sound ([FamiStudioText.mergeKey]), on every chip, so those decisions treat renamed
         * copies as one. Every stream still loads each note's own slot: equal sounds can share legato decisions and
         * VRC7 custom-patch comparisons, but live edits must follow the played bank entry, not its first equal copy.
         * An instrument load without an attack only re-points the envelopes, keeping their positions.
         */
        private val equivalent: IntArray = IntArray(fs.instruments.size + 1) { it }.also { m ->
            val first = HashMap<List<String>, Int>()
            for (slot in 1..fs.instruments.size) m[slot] = first.getOrPut(FamiStudioText.mergeKey(fs, slot)) { slot }
        }

        /** Possible custom patches in the loop; delayed notes make the final writer unknowable from row order alone. */
        private val customPatchesInLoop: Long = run {
            var patches = 0L
            if (fs.expansion == NesExpansion.VRC7) {
                for (row in fs.orderStart[fs.loopOrder] until fs.totalRows) {
                    for (ch in NesRules.VRC7_FM1 until fs.channels) {
                        val n = fs.notes[ch][row] ?: continue
                        if (n.isMusical && n.instrument != 0 && isCustomVrc7(n.instrument)) {
                            patches = patches or (1L shl (equivalent[n.instrument] - 1))
                        }
                    }
                }
            }
            patches
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
            val isVrc7 = NesRules.isVrc7(fs.expansion, c)
            val isN163 = NesRules.isN163(fs.expansion, c)
            val isFds = NesRules.isFds(fs.expansion, c)
            val isS5b = NesRules.isS5b(fs.expansion, c)
            var currentSpeed = fs.speed
            var instrument = 0
            var reloadCustom = false
            var arpeggio = -1
            // The VRC6 saw's master volume the engine holds (famistudio_music_play starts at Half), sent with instruments.
            var sawVolume = Vrc6SawVolume.HALF
            var lastVolume = 15
            var vibratoOverride = false
            // FamiStudio's `OverrideFlags.FdsModSpeed/FdsModDepth`: set by an H/I/J row, cleared by the attack that re-binds.
            var speedOverride = false
            var depthOverride = false
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
                    // FamiStudio compares the merged instruments ([equivalent]): renamed copies keep a legato loop entry.
                    if (equivalent[lastInLoop] != equivalent[firstInLoop]) instrument = 0
                    // Reload shared patch state without invalidating the channel's instrument/legato state.
                    if (isVrc7 && firstInLoop != 0 && isCustomVrc7(firstInLoop)) {
                        reloadCustom = customPatchesInLoop and (1L shl (equivalent[firstInLoop] - 1)).inv() != 0L
                    }
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
                    if (isVrc7 && otherVrc7ChannelUsesCustomPatch(c, instrument, row)) reloadCustom = true
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
                    // FamiStudio writes the phase reset after the vibrato and before the arpeggio (N163: its own opcode;
                    // FDS: the generic one).
                    if (note.phaseReset) b.byte(if (isN163) OP_N163_PHASE_RESET else OP_PHASE_RESET)
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
                    // FDS mod speed and depth (`FdsModSpeed`, `FdsModDepth`): the engine keeps them over the sound's
                    // until it loads an instrument in a row without them.
                    if (note.fdsModSpeed >= 0) {
                        b.byte(OP_FDS_MOD_SPEED); b.word(note.fdsModSpeed)
                        speedOverride = true
                    }
                    if (note.fdsModDepth >= 0) {
                        b.byte(OP_FDS_MOD_DEPTH); b.byte(note.fdsModDepth)
                        depthOverride = true
                    }
                    if (note.envelopePeriod >= 0) {
                        b.byte(OP_S5B_ENV_PERIOD); b.word(note.envelopePeriod)
                    }
                    if (note.cutDelay >= 0) {
                        b.byte(OP_DELAYED_CUT); b.byte(note.cutDelay)
                    }
                    if (note.isValid) {
                        if (note.isMusical && note.instrument != 0) {
                            val slot = note.instrument
                            val attack = note.attack || !canDisableAttack(c, instrument, slot)
                            if (!attack) b.byte(OP_DISABLE_ATTACK)
                            else {
                                // FamiStudio re-binds the instrument on an attack after an FDS mod speed or depth override
                                // or a 5B envelope period effect that this row does not repeat, and after vibrato, so the
                                // sound's own values return.
                                if (note.fdsModSpeed < 0 && speedOverride) {
                                    speedOverride = false
                                    instrument = 0
                                }
                                if (note.fdsModDepth < 0 && depthOverride) {
                                    depthOverride = false
                                    instrument = 0
                                }
                                if (!frameEnv && envOverride) {
                                    envOverride = false
                                    instrument = 0
                                }
                                if (!frameVibrato && vibratoOverride) {
                                    vibratoOverride = false
                                    instrument = 0
                                }
                            }
                            // Each order re-sends its first instrument (self-contained orders), except two sounds whose
                            // load rewrites shared chip state another channel may have changed since: a custom-patch VRC7
                            // sound (the chip's one custom patch, $00-$07) and a 5B sound with a manual envelope period
                            // the square already holds (the shared period, which FamiStudio's export may leave at another
                            // square's or an `Ixx`/`Jyy`'s value there), so those orders play exactly FamiStudio's stream.
                            val resend = !instrumentSent && !(isVrc7 && isCustomVrc7(slot)) && !(isS5b && hasManualPeriod(slot))
                            if (slot != instrument || resend || reloadCustom && isCustomVrc7(slot)) {
                                // FamiStudio loads nothing for a legato re-send or a merged FDS copy. Keep the runtime
                                // modulation, not constants from the intro: the loop may arrive with different H/I/J.
                                val fdsLegato = isFds && !attack && equivalent[slot] == equivalent[instrument]
                                // FamiStudio sends `$55` before a saw instrument whose master volume differs; each order
                                // also sends it with its first instrument, so the order is self-contained.
                                val volume = fs.instruments[slot - 1].sawVolume
                                if (isSaw && (volume != sawVolume || !instrumentSent)) {
                                    b.byte(OP_VRC6_SAW_VOLUME); b.byte(volume.engine)
                                    sawVolume = volume
                                }
                                // The same opcode keeps a 5B manual-period copy from overwriting another square's
                                // shared period. Both paths retain the actual slot so live edits reach the played copy.
                                val s5bCopy = isS5b && instrument != 0 && slot != instrument &&
                                    equivalent[slot] == equivalent[instrument] && hasManualPeriod(slot)
                                if (fdsLegato || s5bCopy) {
                                    b.byte(OP_KEEP_EXPANSION_STATE_INSTRUMENT); b.byte(slot - 1)
                                } else {
                                    b.byte(0x80 or ((slot - 1) shl 1))
                                }
                                instrument = slot
                                instrumentSent = true
                                reloadCustom = false
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
                                // VRC7, N163 and FDS channels release with their own opcodes (VRC7: key off, then the
                                // volume envelope's release; N163, FDS: the volume and the wave-index envelopes' releases).
                                note.value == FsNote.RELEASE -> b.byte(
                                    when {
                                        isVrc7 -> OP_VRC7_RELEASE_NOTE
                                        isN163 -> OP_N163_RELEASE_NOTE
                                        isFds -> OP_FDS_RELEASE_NOTE
                                        else -> OP_RELEASE_NOTE
                                    }
                                )
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
                            if (isVrc7 && otherVrc7ChannelUsesCustomPatch(c, instrument, row)) reloadCustom = true
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

        /** A 5B sound whose hardware envelope takes the manual period (octave byte `$80`). */
        private fun hasManualPeriod(slot: Int): Boolean = !fs.instruments[slot - 1].s5b.autoPitch

        /** FamiStudio's `Square1HasEnvelopePeriodEffect`: 5B Square 1 sets the envelope period on [row]. */
        private fun square1SetsEnvPeriod(row: Int): Boolean = (fs.notes[NesRules.S5B_SQUARE1][row]?.envelopePeriod ?: -1) >= 0

        /** Address of instrument [slot]'s own arpeggio envelope (what a note without 0xy returns to). */
        private fun instrumentArpeggio(slot: Int): Int = instrumentArpeggios[slot - 1]

        /**
         * `Channel.CanDisableAttack` on FamiStudio's merged instruments ([equivalent]): the same instrument (or a renamed
         * copy) continues without an attack; on the N163's channels only that, elsewhere (the FDS too) also instruments
         * whose envelopes are all identical ([FamiStudioEnvelopes.allIdentical]).
         */
        private fun canDisableAttack(c: Int, i1: Int, i2: Int): Boolean = when {
            i1 == 0 || i2 == 0 -> false
            i1 == i2 || equivalent[i1] == equivalent[i2] -> true
            NesRules.isN163(fs.expansion, c) -> false
            else -> FamiStudioEnvelopes.allIdentical(fs.instruments[i1 - 1], fs.instruments[i2 - 1])
        }

        /**
         * FamiStudio's `OtherVrc7ChannelUsesCustomPatch`: true when VRC7 channel [c]'s current instrument [instrument] is
         * a custom patch and another VRC7 channel has a note with another custom-patch instrument (after the merge) on
         * [row], which loads the chip's one custom patch; the channel then sends its instrument again with its next note.
         */
        private fun otherVrc7ChannelUsesCustomPatch(c: Int, instrument: Int, row: Int): Boolean {
            if (instrument == 0 || !isCustomVrc7(instrument)) return false
            for (other in NesRules.VRC7_FM1 until fs.channels) {
                if (other == c) continue
                val n = fs.notes[other][row] ?: continue
                if (n.instrument != 0 && equivalent[n.instrument] != equivalent[instrument] && isCustomVrc7(n.instrument)) return true
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
            val n163 = NesRules.isN163(fs.expansion, c)
            val table = when {
                vrc7 -> NOTE_TABLE_VRC7
                n163 -> (if (pal) N163NoteTables.PAL else N163NoteTables.NTSC)[fs.expansion.n163Channels - 1]
                // The FDS: its own frequency table (FamiStudio's `NesApu.GetNoteTableForChannelType`), one fraction bit.
                NesRules.isFds(fs.expansion, c) -> if (pal) NOTE_TABLE_FDS_PAL else NOTE_TABLE_FDS
                NesRules.isVrc6Saw(fs.expansion, c) -> if (pal) NOTE_TABLE_SAW_PAL else NOTE_TABLE_SAW
                pal -> NOTE_TABLE_PAL
                else -> NOTE_TABLE
            }
            var delta = if (c == NesRules.NOISE) note.value - note.slideTarget else table[note.value] - table[note.slideTarget]
            if (delta == 0) return null
            // `GetShiftsForType`: 1 fraction bit on most channels, 4 on Noise; VRC7 frequency deltas drop 3 bits.
            delta = when {
                vrc7 -> delta shr VRC7_SLIDE_SHIFT
                // `GetShiftsForType`: N163 slides drop the channel count's pitch shift; the engine shifts them back.
                n163 -> delta shr NesRules.n163PitchShift(fs.expansion.n163Channels)
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
