package org.thortracker.backend

import org.thortracker.backend.FamiStudioEnvelopes.Kind
import org.thortracker.model.Song
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/**
 * Lowers an NES song to FamiStudio sound engine 4.5.3 data (FamiTracker tempo) at [NesPlayerImage.SONG_BASE], in the
 * layout the native `"nes-famistudio"` engine expects (docs/AUDIO_ENGINE.md, NES image layout): the FamiStudio song
 * header, an instrument table of all [NesSystem.SLOTS] slots (index = slot − 1), every envelope as an entry of the
 * envelope directory (instrument envelopes at `slot × 4 + kind`, effect envelopes from 256), and one stream per
 * channel made only of `$41 n ptr` (play order o's pattern stream) per order and a `$42` loop to the loop order.
 * Identical pattern streams are stored once.
 *
 * Each pattern stream is what FamiStudio's own exporter (`FamitoneMusicFile.GetSongData`, FamiStudio kernel, NTSC)
 * emits for those rows: the same opcode order, the same state it tracks (instrument, arpeggio, last volume, vibrato
 * override), the same slide and volume-slide step arithmetic. Two deliberate additions make an order self-contained
 * for seeking, looping and live edits without changing what plays: each order re-sends its first note's instrument
 * and, when a set volume (not a slide) is in force, the channel volume. FamiStudio's own byte layout (instrument
 * sorting and merging, CRC-ordered envelopes, its search-based stream compression) is not reproduced; the golden
 * test therefore compares APU register traces of both through the same player.
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

    /** FamiTracker tempo 150: one row per `speed` frames (FamiStudio's integer formula for NTSC and PAL). */
    const val TEMPO = 150
    const val TEMPO_PAL = 256 * TEMPO / 125
    const val TEMPO_NTSC = 256 * TEMPO / 150

    const val CHANNELS = 5 // pulse 1, pulse 2, triangle, noise, DPCM
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
    private const val OP_VOLUME_BITS = 0x70
    private const val SINGLE_BYTE_NOTE_MIN = 12
    private const val SINGLE_BYTE_NOTE_MAX = SINGLE_BYTE_NOTE_MIN + 0x3f
    private const val MAX_REPEAT = 63

    /** One envelope of the directory: [index] (slot × 4 + kind, or ≥ 256), [kind], its bytes and CPU address. */
    class Envelope(val index: Int, val kind: Kind, val bytes: ByteArray) {
        var address = 0
    }

    /** The lowered song: FamiStudio data for [NesPlayerImage.SONG_BASE] and its envelope directory. */
    class Lowered(val data: ByteArray, val envelopes: List<Envelope>, val instrumentCount: Int)

    /** @throws IllegalArgumentException when the song has errors or its data does not fit the song region. */
    fun lower(song: Song): Lowered {
        val errors = NesSystem.errors(song)
        require(errors.isEmpty()) { errors.first() }
        return lower(FsSong.of(song))
    }

    fun lower(fs: FsSong): Lowered {
        val base = NesPlayerImage.SONG_BASE
        val out = Bytes()
        // Header: song count, instruments, samples - 4, five channel streams, tempo words.
        val headerSize = 1 + 2 + 2 + CHANNELS * 2 + 4
        val instrumentsAt = headerSize
        val instrumentCount = NesSystem.SLOTS
        var at = instrumentsAt + instrumentCount * 8
        // Envelopes: per slot in kind order (volume, arpeggio, duty, pitch), then effect envelopes.
        val envelopes = ArrayList<Envelope>()
        for ((slot, ins) in fs.instruments.withIndex()) {
            envelopes += Envelope(slot * 4 + Kind.VOLUME.ordinal, Kind.VOLUME, FamiStudioEnvelopes.encode(ins.volume, Kind.VOLUME))
            envelopes += Envelope(slot * 4 + Kind.ARPEGGIO.ordinal, Kind.ARPEGGIO, FamiStudioEnvelopes.encode(ins.arpeggio, Kind.ARPEGGIO))
            envelopes += Envelope(slot * 4 + Kind.DUTY.ordinal, Kind.DUTY, FamiStudioEnvelopes.encode(ins.duty, Kind.DUTY))
            envelopes += Envelope(slot * 4 + Kind.PITCH.ordinal, Kind.PITCH, FamiStudioEnvelopes.encode(ins.pitch, Kind.PITCH, ins.pitchRelative))
        }
        var next = EFFECT_ENVELOPES_START
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
        val samplesAt = at // FamiStudio's (empty) DPCM sample list label.
        val bySlot = envelopes.filter { it.index < EFFECT_ENVELOPES_START }.associateBy { it.index }
        val instrumentArpeggios = IntArray(instrumentCount) { bySlot.getValue(it * 4 + Kind.ARPEGGIO.ordinal).address }
        val streams = Streams(fs, noArpeggio, vibratoOff, arpeggios, vibratos, instrumentArpeggios)
        val patterns = LinkedHashMap<Bytes.Key, Int>() // pattern stream bytes → offset
        val patternOrder = ArrayList<ByteArray>()
        val channelRefs = Array(CHANNELS) { arrayOfNulls<ByteArray>(fs.orders) }
        val channelCounts = Array(CHANNELS) { IntArray(fs.orders) }
        for (c in 0 until CHANNELS) {
            val chunks = if (c == DPCM) streams.dpcm() else streams.channel(c)
            for (o in 0 until fs.orders) {
                channelRefs[c][o] = chunks[o].bytes
                channelCounts[c][o] = chunks[o].notes
            }
        }
        val channelAt = IntArray(CHANNELS)
        for (c in 0 until CHANNELS) {
            channelAt[c] = at
            at += fs.orders * 4 + 3
        }
        for (c in 0 until CHANNELS) for (o in 0 until fs.orders) {
            val bytes = channelRefs[c][o]!!
            val key = Bytes.Key(bytes)
            if (key !in patterns) {
                patterns[key] = at
                patternOrder += bytes
                at += bytes.size
            }
        }
        require(at <= NesPlayerImage.SONG_END - base) {
            "The song needs $at bytes of NES data; the player has room for ${NesPlayerImage.SONG_END - base}. Reuse patterns or shorten the song"
        }
        out.byte(1)
        out.word(base + instrumentsAt)
        out.word(base + samplesAt - 4)
        for (c in 0 until CHANNELS) out.word(base + channelAt[c])
        out.word(TEMPO_PAL)
        out.word(TEMPO_NTSC)
        check(out.size == instrumentsAt)
        for (slot in 0 until instrumentCount) {
            for (kind in listOf(Kind.VOLUME, Kind.ARPEGGIO, Kind.DUTY, Kind.PITCH)) out.word(bySlot.getValue(slot * 4 + kind.ordinal).address)
        }
        for (e in envelopes) out.bytes(e.bytes)
        check(out.size == samplesAt)
        for (c in 0 until CHANNELS) {
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
        return Lowered(out.toByteArray(), envelopes.sortedBy { it.index }, instrumentCount)
    }

    /** One order's pattern stream of one channel: [bytes] and its number of row terminators. */
    class Chunk(val bytes: ByteArray, val notes: Int)

    private class Streams(
        val fs: FsSong,
        val noArpeggio: Envelope,
        val vibratoOff: Envelope,
        val arpeggios: Map<Int, Envelope>,
        val vibratos: Map<Int, Envelope>,
        val instrumentArpeggios: IntArray,
    ) {
        /** FamiStudio's speed channel: the one with the most speed effects (the first on a tie). */
        val speedChannel: Int = run {
            var best = 0
            var bestCount = 0
            for (c in 0 until NesSystem.channels.size) {
                val count = fs.notes[c].count { it != null && it.speed >= 0 }
                if (count > bestCount) {
                    best = c
                    bestCount = count
                }
            }
            best
        }

        fun dpcm(): List<Chunk> = (0 until fs.orders).map { o ->
            val b = Bytes()
            var rows = fs.lengths[o]
            var notes = 0
            while (rows > 0) {
                val k = minOf(rows - 1, MAX_REPEAT)
                b.byte(0x81 or (k shl 1))
                rows -= k + 1
                notes++
            }
            Chunk(b.toByteArray(), notes)
        }

        fun channel(c: Int): List<Chunk> {
            val isSpeedChannel = c == speedChannel
            var currentSpeed = fs.speed
            var instrument = 0
            var arpeggio = -1
            var lastVolume = 15
            var vibratoOverride = false
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
                    if (note.cutDelay >= 0) {
                        b.byte(OP_DELAYED_CUT); b.byte(note.cutDelay)
                    }
                    if (note.isValid) {
                        if (note.isMusical && note.instrument != 0) {
                            val attack = note.attack || !canDisableAttack(instrument, note.instrument)
                            if (!attack) b.byte(OP_DISABLE_ATTACK)
                            else if (!frameVibrato && vibratoOverride) {
                                vibratoOverride = false
                                instrument = 0
                            }
                            if (note.instrument != instrument || !instrumentSent) {
                                b.byte(0x80 or ((note.instrument - 1) shl 1))
                                instrument = note.instrument
                                instrumentSent = true
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
                                note.value == FsNote.RELEASE -> b.byte(OP_RELEASE_NOTE)
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

        private fun canDisableAttack(i1: Int, i2: Int): Boolean = when {
            i1 == 0 || i2 == 0 -> false
            i1 == i2 -> true
            else -> FamiStudioEnvelopes.allIdentical(fs.instruments[i1 - 1], fs.instruments[i2 - 1])
        }

        /** `Channel.CountFramesBetween` (FamiTracker tempo 150: one frame per tick). */
        private fun framesBetween(from: Int, to: Int, speed: Int): Float {
            var s = speed
            var frames = 0f
            var r = from
            while (r != to && r < fs.totalRows) {
                val set = fs.speedAt(r)
                if (set >= 0) s = set
                frames += s.toFloat()
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

        /** `ComputeSlideNoteParams` (NTSC; the PAL step equals the NTSC one for an NTSC export), or null when the slide is flat. */
        private fun slideStep(c: Int, row: Int, note: FsNote, speed: Int): Int? {
            var delta = if (c == NesRules.NOISE) note.value - note.slideTarget else NOTE_TABLE[note.value] - NOTE_TABLE[note.slideTarget]
            if (delta == 0) return null
            delta = delta shl if (c == NesRules.NOISE) 4 else 1
            val next = if (note.cutDelay >= 0) row else minOf(row + minOf(distanceToNextNote(c, row), 256), fs.totalRows)
            val frames = if (next != row) {
                var delay = -(if (note.noteDelay >= 0) note.noteDelay else 0)
                fs.note(c, next)?.let { n ->
                    if (n.noteDelay >= 0) delay += if (n.cutDelay >= 0) minOf(n.noteDelay, n.cutDelay) else n.noteDelay
                    else if (n.cutDelay >= 0) delay += n.cutDelay
                }
                framesBetween(row, next, speed) + delay
            } else {
                (if (note.cutDelay >= 0) note.cutDelay else 0).toFloat()
            }
            return stepSize(delta, frames)
        }

        /** `ComputeVolumeSlideNoteParams` (volume has 4 fraction bits). */
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
