package org.thortracker.backend

import org.thortracker.backend.SmsImageLayout.ARPEGGIO
import org.thortracker.backend.SmsImageLayout.ARPEGGIO_OFF
import org.thortracker.backend.SmsImageLayout.END_LINE
import org.thortracker.backend.SmsImageLayout.EX_DUTY
import org.thortracker.backend.SmsImageLayout.EX_PITCH
import org.thortracker.backend.SmsImageLayout.FLAG_ARP_MACRO
import org.thortracker.backend.SmsImageLayout.FLAG_EX_MACRO
import org.thortracker.backend.SmsImageLayout.FLAG_VOLUME_MACRO
import org.thortracker.backend.SmsImageLayout.FM_CUSTOM
import org.thortracker.backend.SmsImageLayout.FM_DRUM
import org.thortracker.backend.SmsImageLayout.FM_DRUMS_FIXED
import org.thortracker.backend.SmsImageLayout.FM_FNUMS
import org.thortracker.backend.SmsImageLayout.FM_UNUSED
import org.thortracker.backend.SmsImageLayout.GAME_GEAR_PAN
import org.thortracker.backend.SmsImageLayout.IMAGE_SIZE
import org.thortracker.backend.SmsImageLayout.INSTRUMENT_CHANGE
import org.thortracker.backend.SmsImageLayout.LEGATO_OFF
import org.thortracker.backend.SmsImageLayout.LEGATO_ON
import org.thortracker.backend.SmsImageLayout.MAX_WAIT
import org.thortracker.backend.SmsImageLayout.META_SIZE
import org.thortracker.backend.SmsImageLayout.NOTE_DELAY
import org.thortracker.backend.SmsImageLayout.NOTE_OFF
import org.thortracker.backend.SmsImageLayout.NOTE_ON
import org.thortracker.backend.SmsImageLayout.NOTE_RELEASE
import org.thortracker.backend.SmsImageLayout.ORDER_JUMP
import org.thortracker.backend.SmsImageLayout.REGION_SIZE
import org.thortracker.backend.SmsImageLayout.SET_SPEED_1
import org.thortracker.backend.SmsImageLayout.SET_SPEED_2
import org.thortracker.backend.SmsImageLayout.SLIDE_DOWN
import org.thortracker.backend.SmsImageLayout.SLIDE_OFF
import org.thortracker.backend.SmsImageLayout.SLIDE_PORTA
import org.thortracker.backend.SmsImageLayout.SLIDE_UP
import org.thortracker.backend.SmsImageLayout.SN_NOISE_MODE
import org.thortracker.backend.SmsImageLayout.VIBRATO
import org.thortracker.backend.SmsImageLayout.VIBRATO_OFF
import org.thortracker.backend.SmsImageLayout.VOLUME
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.sms.SmsChannelKind
import org.thortracker.system.sms.SmsDrumKit
import org.thortracker.system.sms.SmsFmInstrument
import org.thortracker.system.sms.SmsInstrument
import org.thortracker.system.sms.SmsInstruments
import org.thortracker.system.sms.SmsLayout
import org.thortracker.system.sms.SmsPsgInstrument
import org.thortracker.system.sms.SmsRules
import org.thortracker.system.sms.SmsSystem
import org.thortracker.system.sms.sms

/**
 * Lowers a Master System song into Banjo song data ([BanjoSong]) and the engine's image (docs/AUDIO_ENGINE.md, "Master
 * System"). The arrangement ([SmsSystem.arranged]) becomes Banjo's orders; each (order, channel) is that track's rows
 * as Banjo pattern commands, byte-identical tracks stored once in first-use order. Instruments are records for every
 * slot up to the highest filled one (so any can be auditioned); identical macros and patches are stored once.
 *
 * Banjo counts a single pattern length for all orders: it is the longest order rounded up to even (so even rows run
 * speed 1 and odd rows speed 2 everywhere), and an order that is shorter, or the last one when the song loops to a
 * later order, ends with an explicit order jump on channel 1's last row unless that row jumps already.
 */
object SmsLowering {
    /** Facts of straight play (orders in sequence, jumps ignored) the lowering and the validator share. */
    class Straight(
        val patternLength: Int,
        val rows: IntArray,
        /** speed_1 / speed_2 as straight play enters each order. */
        val speed1: IntArray,
        val speed2: IntArray,
        /** Each order's row tick counts (the speed register its parity reads, as set by earlier rows). */
        val rowTicks: List<IntArray>,
        /** The instrument each channel's notes play at each order's start (0: none yet). */
        val instruments: List<IntArray>,
    )

    fun straight(song: Song): Straight {
        val layout = song.sms.layout
        val channels = layout.channelCount
        val rows = IntArray(song.orders.size) { song.patterns[song.orders[it]].length }
        val longest = rows.maxOrNull() ?: 1
        val patternLength = (longest + 1) and 1.inv()
        var s1 = song.ticksPerRow
        var s2 = song.ticksPerRow
        val ins = IntArray(channels)
        val speed1 = IntArray(rows.size)
        val speed2 = IntArray(rows.size)
        val rowTicks = ArrayList<IntArray>()
        val instruments = ArrayList<IntArray>()
        song.orders.forEachIndexed { o, p ->
            speed1[o] = s1
            speed2[o] = s2
            instruments += ins.copyOf()
            val tracks = song.patterns[p].tracks
            val ticks = IntArray(rows[o])
            val pending = IntArray(channels)
            for (r in 0 until rows[o]) {
                ticks[r] = if (r % 2 == 0) s1 else s2
                for (c in 0 until channels) {
                    val cell = tracks[c][r]
                    if (layout.kind(c) == SmsChannelKind.DRUM) {
                        if (cell.instrument != 0) ins[c] = cell.instrument
                    } else {
                        if (cell.instrument != 0) pending[c] = cell.instrument
                        if (cell.note >= 0 && pending[c] != 0) { ins[c] = pending[c]; pending[c] = 0 }
                    }
                }
                for (c in 0 until channels) {
                    val cell = tracks[c][r]
                    for (col in 0..cell.more.size) {
                        val v = cell.effectParam(col)
                        when (cell.effectCode(col)) {
                            SmsRules.FX_SPEED -> if (v > 0) { s1 = v; s2 = v }
                            SmsRules.FX_ODD_SPEED -> if (v > 0) s2 = v
                        }
                    }
                }
            }
            rowTicks += ticks
        }
        return Straight(patternLength, rows, speed1, speed2, rowTicks, instruments)
    }

    /** The image of [document] (as played: parked orders left out). */
    fun lower(document: Song): ByteArray = ByteArray(IMAGE_SIZE).also { lower(document, it) }

    /**
     * Writes [document]'s image into [out] ([IMAGE_SIZE] bytes, overwritten entirely).
     * @throws IllegalArgumentException when the song does not fit Banjo's limits or the image.
     */
    fun lower(document: Song, out: ByteArray) {
        require(out.size >= IMAGE_SIZE) { "The Master System image needs $IMAGE_SIZE bytes" }
        out.fill(0, 0, IMAGE_SIZE)
        val song = document.system.arranged(document)
        val banjo = build(song, arranged = true)
        val region = banjo.region()
        require(region.size <= REGION_SIZE) { "The song data needs ${region.size} bytes; the Master System image holds $REGION_SIZE" }
        val straight = straight(song)
        SmsImageLayout.MAGIC.forEachIndexed { i, ch -> out[SmsImageLayout.M_MAGIC + i] = ch.code.toByte() }
        out[SmsImageLayout.M_VERSION] = SmsImageLayout.VERSION.toByte()
        out[SmsImageLayout.M_LAYOUT] = banjo.layout.code.toByte()
        out[SmsImageLayout.M_CHANNELS] = banjo.layout.channelCount.toByte()
        out[SmsImageLayout.M_ORDERS] = song.orders.size.toByte()
        out[SmsImageLayout.M_LOOP] = song.loopOrder.toByte()
        out[SmsImageLayout.M_PATTERN_LENGTH] = banjo.patternLength.toByte()
        u16(out, SmsImageLayout.M_DATA_SIZE, region.size)
        u16(out, SmsImageLayout.M_INSTRUMENTS, banjo.instruments.size)
        for (o in song.orders.indices) {
            out[SmsImageLayout.M_ROWS + o] = straight.rows[o].toByte()
            out[SmsImageLayout.M_SPEED1 + o] = straight.speed1[o].toByte()
            out[SmsImageLayout.M_SPEED2 + o] = straight.speed2[o].toByte()
        }
        region.copyInto(out, META_SIZE)
    }

    /** Bytes of song data [document] lowers to. */
    fun dataSize(document: Song): Int = build(document).dataSize

    /** [document]'s Banjo song data (its arrangement; [arranged] when [document] is one already). */
    fun build(document: Song, arranged: Boolean = false): BanjoSong {
        val song = if (arranged) document else document.system.arranged(document)
        require(song.orders.size in 1..SmsImageLayout.MAX_ORDERS) { "Banjo plays 1-${SmsImageLayout.MAX_ORDERS} orders; the song has ${song.orders.size}" }
        val sound = song.sms
        val layout = sound.layout
        val straight = straight(song)
        val data = Data()
        val highest = sound.instruments.indexOfLast { it != null } + 1
        val instruments = (1..highest).map { record(sound.instrument(it), data) }
        val trackIds = LinkedHashMap<List<Int>, Int>()
        val tracks = ArrayList<IntArray>()
        val orders = song.orders.indices.map { o ->
            IntArray(layout.channelCount) { c ->
                val bytes = encodeTrack(song, layout, straight, o, c)
                trackIds.getOrPut(bytes) { tracks += bytes.toIntArray(); tracks.size - 1 }
            }
        }
        return BanjoSong(layout, song.ticksPerRow, song.ticksPerRow, straight.patternLength, instruments, data.macros, data.patches, orders, tracks)
    }

    // ---- instruments -------------------------------------------------------------------------------------------

    private class Data {
        val macros = ArrayList<IntArray>()
        val patches = ArrayList<IntArray>()
        private val macroIds = HashMap<List<Int>, Int>()
        private val patchIds = HashMap<List<Int>, Int>()
        fun macro(bytes: IntArray): Int = macroIds.getOrPut(bytes.toList()) { macros += bytes; macros.size - 1 }
        fun patch(bytes: IntArray): Int = patchIds.getOrPut(bytes.toList()) { patches += bytes; patches.size - 1 }
    }

    private fun record(ins: SmsInstrument?, data: Data): BanjoInstrument = when (ins) {
        null -> BanjoInstrument(0, -1, -1, 0, -1, FM_UNUSED, -1)
        is SmsPsgInstrument -> {
            val vol = if (ins.volume.values.isEmpty()) -1 else data.macro(volumeMacro(ins.volume))
            val arp = if (ins.arpeggio.values.isEmpty()) -1 else data.macro(arpeggioMacro(ins.arpeggio))
            val (exType, ex) = when {
                ins.pitch.values.isNotEmpty() -> EX_PITCH to data.macro(pitchMacro(ins.pitch))
                ins.noise.values.isNotEmpty() -> EX_DUTY to data.macro(noiseMacro(ins.noise))
                else -> 0 to -1
            }
            BanjoInstrument(flags(vol, arp, ex), vol, arp, exType, ex, 0, -1)
        }
        is SmsFmInstrument -> {
            val vol = if (ins.volume.values.isEmpty()) -1 else data.macro(volumeMacro(ins.volume))
            val arp = if (ins.arpeggio.values.isEmpty()) -1 else data.macro(arpeggioMacro(ins.arpeggio))
            if (ins.isCustom) BanjoInstrument(flags(vol, arp, -1), vol, arp, 0, -1, FM_CUSTOM, data.patch(ins.custom.registers()))
            else BanjoInstrument(flags(vol, arp, -1), vol, arp, 0, -1, ins.patch shl 4, -1)
        }
        is SmsDrumKit ->
            if (ins.fixed) BanjoInstrument(0, -1, -1, 0, -1, FM_DRUMS_FIXED, data.patch(kitPitches(ins)))
            else BanjoInstrument(0, -1, -1, 0, -1, 0x00, -1)
    }

    private fun flags(vol: Int, arp: Int, ex: Int): Int =
        (if (vol >= 0) FLAG_VOLUME_MACRO else 0) or (if (arp >= 0) FLAG_ARP_MACRO else 0) or (if (ex >= 0) FLAG_EX_MACRO else 0)

    /**
     * A volume macro: [sustain length, sustain loop, release length, release loop] then attenuations (15 − level).
     * Positions count from the macro's start (the header is 4 bytes; the driver's first step reads byte 4). Without a
     * release both pairs are equal; with one the sustain part ends before the release point, looping, and after OFF
     * the driver runs on to the end (release loop 0: the last value holds).
     */
    fun volumeMacro(e: NesEnvelope): IntArray {
        val n = e.values.size
        val loop = if (e.loop >= 0) e.loop + 4 else 0
        val header = if (e.release >= 0) intArrayOf(e.release + 4, loop, n + 4, 0) else intArrayOf(n + 4, loop, n + 4, loop)
        return header + e.values.map { 15 - it.coerceIn(0, 15) }.toIntArray()
    }

    /**
     * An arpeggio or extra macro: [length, loop] then the values. The driver's end-of-macro path reads the byte at
     * the loop position itself, so the loop index is value index + 2 (json2sms 7aaf837 writes + 1, one step early).
     */
    private fun shortMacro(e: NesEnvelope, values: IntArray): IntArray =
        intArrayOf(values.size + 2, if (e.loop >= 0) e.loop + 2 else 0) + values

    /** Arpeggio: relative semitones in 7 bits (bit 7 clear). */
    fun arpeggioMacro(e: NesEnvelope): IntArray = shortMacro(e, e.values.map { it and 0x7F }.toIntArray())

    /** Pitch: 1/128 semitone per step, added to Banjo's 1/256-semitone note fraction as a signed byte. */
    fun pitchMacro(e: NesEnvelope): IntArray = shortMacro(e, e.values.map { (it * 2) and 0xFF }.toIntArray())

    /** Noise mode per tick; bit 7 marks a change (json2sms's rule: the first value and every value unlike the previous). */
    fun noiseMacro(e: NesEnvelope): IntArray =
        shortMacro(e, e.values.mapIndexed { j, v -> (v and 3) or (if (j == 0 || v != e.values[j - 1]) 0x80 else 0) }.toIntArray())

    /** A fixed-pitch kit: F-number low and block/F-number high byte for registers $16/$26, $17/$27, $18/$28. */
    fun kitPitches(kit: SmsDrumKit): IntArray {
        val out = IntArray(6)
        listOf(kit.kick, kit.snareHat, kit.tomCymbal).forEachIndexed { i, note ->
            val n = note.coerceIn(0, SmsInstruments.FM_TOP)
            val fnum = FM_FNUMS[n % 12]
            out[2 * i] = fnum and 0xFF
            out[2 * i + 1] = ((n / 12) shl 1) or (fnum shr 8)
        }
        return out
    }

    // ---- tracks ------------------------------------------------------------------------------------------------

    private enum class Arp { UNKNOWN, ON, OFF }

    /** Whether row [row] of order [o] jumps (Bxx or Dxx on any channel). */
    private fun rowJumps(song: Song, o: Int, row: Int): Boolean = song.patterns[song.orders[o]].tracks.any { track ->
        val cell = track[row]
        (0..cell.more.size).any { cell.effectCode(it) == SmsRules.FX_JUMP || cell.effectCode(it) == SmsRules.FX_BREAK }
    }

    private fun encodeTrack(song: Song, layout: SmsLayout, straight: Straight, o: Int, c: Int): List<Int> {
        val sound = song.sms
        val pattern = song.patterns[song.orders[o]]
        val rows = straight.rows[o]
        val lastOrder = song.orders.size - 1
        val next = if (o < lastOrder) o + 1 else song.loopOrder
        val kind = layout.kind(c)
        val autoJump = c == 0 && (rows < straight.patternLength || (o == lastOrder && song.loopOrder != 0)) && !rowJumps(song, o, rows - 1)
        var emitted = -1
        var current = straight.instruments[o][c]
        var pending = 0
        var arp = Arp.UNKNOWN
        val out = ArrayList<Int>()
        for (r in 0 until rows) {
            val cell = pattern.tracks[c][r]
            val fx = (0..cell.more.size).map { cell.effectCode(it) to cell.effectParam(it) }.filter { it.first != 0 || it.second != 0 }
            fun last(code: Int) = fx.lastOrNull { it.first == code }?.second
            val cmds = ArrayList<Int>()
            val jump = fx.firstOrNull { it.first == SmsRules.FX_JUMP }?.second
            when {
                jump != null -> { cmds += ORDER_JUMP; cmds += jump }
                fx.any { it.first == SmsRules.FX_BREAK } || (autoJump && r == rows - 1) -> { cmds += ORDER_JUMP; cmds += next }
            }
            last(SmsRules.FX_SPEED)?.let { if (it > 0) { cmds += SET_SPEED_1; cmds += it; cmds += SET_SPEED_2; cmds += it } }
            last(SmsRules.FX_ODD_SPEED)?.let { if (it > 0) { cmds += SET_SPEED_2; cmds += it } }
            last(SmsRules.FX_DELAY)?.let { if (it > 0) { cmds += NOTE_DELAY; cmds += it } }
            if (kind == SmsChannelKind.DRUM) {
                if (cell.instrument != 0) {
                    if (cell.instrument != emitted) { cmds += FM_DRUM; cmds += cell.instrument - 1 }
                    emitted = cell.instrument
                    current = cell.instrument
                }
            } else {
                if (cell.instrument != 0) pending = cell.instrument
                if (cell.note >= 0 && pending != 0) {
                    if (pending != emitted) { cmds += INSTRUMENT_CHANGE; cmds += pending - 1 }
                    emitted = pending
                    current = pending
                    pending = 0
                }
            }
            if (kind != SmsChannelKind.DRUM) last(SmsRules.FX_LEGATO)?.let { cmds += if (it != 0) LEGATO_ON else LEGATO_OFF }
            if (kind == SmsChannelKind.NOISE) last(SmsRules.FX_NOISE)?.let { cmds += SN_NOISE_MODE; cmds += ((it shr 4) and 1) or ((it and 1) shl 1) }
            if (layout.stereo && c < 4) last(SmsRules.FX_PAN)?.let {
                val bits = (if (it shr 4 != 0) 1 shl (c + 4) else 0) or (if (it and 0x0F != 0) 1 shl c else 0)
                cmds += GAME_GEAR_PAN; cmds += bits; cmds += ((1 shl c) or (1 shl (c + 4))).inv() and 0xFF
            }
            last(SmsRules.FX_VOLUME)?.let { cmds += VOLUME or (it and 0x0F) }
            var pitchOn = false
            if (kind != SmsChannelKind.DRUM) for ((code, p) in fx) {
                when (code) {
                    SmsRules.FX_ARPEGGIO -> { cmds += ARPEGGIO; cmds += p; arp = Arp.ON; pitchOn = true }
                    SmsRules.FX_SLIDE_UP, SmsRules.FX_SLIDE_DOWN, SmsRules.FX_PORTAMENTO -> if (p == 0) cmds += SLIDE_OFF else {
                        cmds += when (code) { SmsRules.FX_SLIDE_UP -> SLIDE_UP; SmsRules.FX_SLIDE_DOWN -> SLIDE_DOWN; else -> SLIDE_PORTA }
                        cmds += p
                        arp = Arp.OFF
                        pitchOn = true
                    }
                    SmsRules.FX_VIBRATO -> if (p shr 4 == 0 || p and 0x0F == 0) cmds += VIBRATO_OFF else {
                        cmds += VIBRATO; cmds += ((p and 0x0F) shl 4) or (p shr 4)
                        arp = Arp.OFF
                        pitchOn = true
                    }
                }
            }
            when {
                cell.note >= 0 -> {
                    if (kind != SmsChannelKind.DRUM && !pitchOn && arp != Arp.OFF) { cmds += ARPEGGIO_OFF; arp = Arp.OFF }
                    cmds += NOTE_ON or SmsRules.driverNote(layout, c, cell.note)
                }
                cell.note == Cell.CUT -> cmds += off(kind, sound.instrument(current))
            }
            last(SmsRules.FX_CUT)?.let { if (it > 0) { cmds += NOTE_DELAY; cmds += it; cmds += off(kind, sound.instrument(current)) } }
            // Every row ends with END_LINE; an empty row extends the previous one's wait (json2sms's run-length rule).
            if (cmds.isEmpty() && out.isNotEmpty() && (out.last() and MAX_WAIT) < MAX_WAIT) {
                out[out.size - 1] = out.last() + 1
            } else {
                out += cmds
                out += END_LINE
            }
        }
        return out
    }

    /** OFF: the volume envelope's release part when the sound has one (Banjo's macro release), else note off (FM: key off). */
    private fun off(kind: SmsChannelKind, ins: SmsInstrument?): Int =
        if (kind != SmsChannelKind.DRUM && SmsInstruments.releases(ins)) NOTE_RELEASE else NOTE_OFF

    private fun u16(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v shr 8).toByte()
    }
}
