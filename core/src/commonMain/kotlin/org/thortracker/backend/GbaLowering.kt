package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.gba.GbaEnvelope

/**
 * Direct, pure Kotlin lowering to Maxmod's real GBA soundbank and MAS v0x18 format.
 * Layout/effect/envelope/PCM algorithms follow ISC-licensed mmutil (Mukunda Johnson 2008,
 * Antonio Niño Díaz 2025–2026), built with MM_MMUTIL_DEVKITPRO_COMPAT. No runtime compiler,
 * file I/O, host callback, or substitute sample player is involved.
 */
object GbaLowering {
    fun lower(song: Song, out: ByteArray) {
        require(out.size == GbaPlayerImage.SIZE) { "GBA image must be ${GbaPlayerImage.SIZE} bytes" }
        val bank = soundbank(song)
        require(bank.size <= out.size - GbaPlayerImage.BANK_OFFSET) { "GBA soundbank exceeds image capacity" }
        // Do not publish a half-written image if compilation rejects a song.
        out.fill(0)
        val header = GbaBytes(GbaPlayerImage.HEADER_SIZE)
        header.text("GBAM", 4)
        header.u32(GbaPlayerImage.VERSION)
        header.u32(GbaPlayerImage.BANK_OFFSET + bank.size)
        header.u32(GbaPlayerImage.BANK_OFFSET)
        header.u32(bank.size)
        header.u32(0)
        header.result().copyInto(out)
        bank.copyInto(out, GbaPlayerImage.BANK_OFFSET)
    }

    /** Standalone .bin bank, suitable for the GBA Maxmod mmInitDefault soundbank argument. */
    fun soundbank(song: Song): ByteArray = bank(GbaExportModel(song))

    private data class Pcm(val data: ByteArray, val loopLength: Int, val frequency: Int)

    private fun process(sample: GbaExportModel.Sample): Pcm {
        val s = sample.source
        val loop = sample.loopLength
        val end = if (loop > 0) s.loopEnd else s.pcm.size
        // mmutil FixSample_GBA repeats short loops, preserving phase and the original loop start.
        val addition = if (loop in 1..511) (512 / loop + 1) * loop else 0
        val data = ByteArray(end + addition)
        for (i in 0 until end) data[i] = (s.pcm[i] + 128).toByte()
        for (i in end until data.size) data[i] = data[s.loopStart + (i - end) % loop]
        return Pcm(data, if (loop == 0) -1 else loop + addition, sample.pitch.frequency)
    }

    private fun bank(model: GbaExportModel): ByteArray {
        val pcm = mutableListOf<Pcm>()
        val sampleIds = model.samples.map { sample ->
            val candidate = process(sample)
            val prior = pcm.indexOfFirst { it.loopLength == candidate.loopLength && it.data.contentEquals(candidate.data) }
            if (prior >= 0) prior else { pcm += candidate; pcm.lastIndex }
        }
        val out = GbaBytes()
        out.u16(pcm.size)
        out.u16(1)
        out.text("*maxmod*", 8)
        val pointers = out.size
        out.zeros((pcm.size + 2) * 4)
        out.patch32(pointers + (pcm.size + 1) * 4, -1) // no name dictionary
        pcm.forEachIndexed { i, sample ->
            out.align()
            out.patch32(pointers + i * 4, out.size)
            out.u32(sample.data.size + 16)
            out.u8(1); out.u8(0x18); out.u8(0); out.u8(0xBA)
            out.u32(sample.data.size)
            out.u32(sample.loopLength)
            out.u8(0); out.u8(0xBA)
            out.u16(((sample.frequency.toLong() * 1024 + 7884) / 15768).toInt())
            out.append(sample.data)
            val loopStart = sample.data.size - sample.loopLength
            repeat(4) { n -> out.u8(if (sample.loopLength >= 4) sample.data[loopStart + n].toInt() else 128) }
        }
        out.align()
        out.patch32(pointers + pcm.size * 4, out.size)
        out.append(module(model, sampleIds))
        return out.result()
    }

    private fun module(model: GbaExportModel, sampleIds: List<Int>): ByteArray {
        val out = GbaBytes()
        out.u32(0)
        out.u8(0); out.u8(0x18); out.u8(0xBA); out.u8(0xBA)
        val base = out.size
        out.u8(model.orders.size); out.u8(model.instruments.size); out.u8(model.samples.size); out.u8(model.patterns.size)
        out.u8(0x1E); out.u8(64); out.u8(model.song.ticksPerRow); out.u8(model.sound.bpm); out.u8(model.song.loopOrder)
        repeat(3) { out.u8(0xBA) }
        repeat(32) { out.u8(64) }
        repeat(32) { out.u8(128) }
        repeat(200) { out.u8(model.orders.getOrElse(it) { 255 }) }
        val pointers = out.size
        out.zeros(4 * (model.instruments.size + model.samples.size + model.patterns.size))
        model.instruments.forEachIndexed { i, inst ->
            out.align()
            out.patch32(pointers + i * 4, out.size - base)
            instrument(out, inst)
        }
        model.samples.forEachIndexed { i, sample ->
            out.align()
            out.patch32(pointers + (model.instruments.size + i) * 4, out.size - base)
            out.u8(sample.volume); out.u8((sample.pan shr 1) or 128); out.u16(sample.pitch.frequency / 4)
            out.u8(0); out.u8(0); out.u8(0); out.u8(64); out.u16(32768); out.u16(sampleIds[i])
        }
        val marks = Array(model.patterns.size) { BooleanArray(256).also { it[0] = true } }
        model.orders.forEachIndexed { o, p ->
            val pattern = model.patterns[p]
            repeat(pattern.length) { row -> repeat(8) { channel ->
                val c = pattern.tracks[channel][row]
                if (c.effect == 13 && c.param != 0) marks[model.orders[(o + 1) % model.orders.size]][(c.param shr 4) * 10 + (c.param and 15)] = true
                if (c.effect == 14 && c.param == 0x60) marks[p][row] = true
            } }
        }
        model.patterns.forEachIndexed { i, pattern ->
            out.patch32(pointers + (model.instruments.size + model.samples.size + i) * 4, out.size - base)
            pattern(out, pattern, marks[i])
        }
        out.align()
        out.patch32(0, out.size - base)
        return out.result()
    }

    private fun instrument(out: GbaBytes, inst: GbaExportModel.Instrument) {
        val s = inst.source
        val empty = inst.samples.isEmpty()
        val volume = !empty && s.volumeEnvelope.enabled
        val pan = !empty && s.panEnvelope.enabled
        val flags = (if (volume) 9 else 0) or (if (pan) 18 else 0)
        out.u8(if (empty) 0 else 128); out.u8(if (empty) 0 else s.fadeout / 32)
        out.u8(0); out.u8(0); out.u8(0); out.u8(flags); out.u8(0); out.u8(0)
        val notemap = IntArray(120) { note ->
            if (empty) 0 else {
                // Match mmutil's high-octave mapping; constraints forbid lossy user keymaps.
                val key = if (note in 12..95) note - 12 else 0
                ((inst.samples[inst.keymap[key]] + 1) shl 8) or note
            }
        }
        val single = !empty && notemap.all { it shr 8 == notemap[0] shr 8 }
        val size = 12 + (if (volume) s.volumeEnvelope.points.size * 4 + 8 else 0) + (if (pan) s.panEnvelope.points.size * 4 + 8 else 0)
        out.u16(if (single) 0x8000 or (notemap[0] shr 8) else size)
        out.u16(0)
        if (volume) envelope(out, s.volumeEnvelope)
        if (pan) envelope(out, s.panEnvelope)
        if (!single) notemap.forEach(out::u16)
    }

    private fun envelope(out: GbaBytes, e: GbaEnvelope) {
        out.u8(e.points.size * 4 + 8)
        out.u8(e.loopStart); out.u8(e.loopEnd); out.u8(e.sustain); out.u8(e.sustain)
        out.u8(e.points.size); out.u8(0); out.u8(0xBA)
        e.points.forEachIndexed { i, point ->
            val next = e.points.getOrNull(i + 1)
            val range = if (next == null) 0 else next.tick - point.tick
            var delta = if (next == null) 0 else (((next.value - point.value) * 512 + range / 2) / range).coerceIn(-32768, 32767)
            while (point.value + ((delta * range) shr 9) > 64) delta--
            while (point.value + ((delta * range) shr 9) < 0) delta++
            out.u16(delta); out.u16(point.value or (range shl 7))
        }
    }

    /** IT packing, with mmutil's restart marks so loops/breaks never reuse stale cached row values. */
    private fun pattern(out: GbaBytes, p: Pattern, marks: BooleanArray) {
        val lastMask = IntArray(8)
        val lastNote = IntArray(8)
        val lastInst = IntArray(8)
        val lastEffect = IntArray(8)
        out.u8(p.length - 1)
        repeat(p.length) { row ->
            if (marks[row]) { lastMask.fill(-1); lastNote.fill(-1); lastInst.fill(-1); lastEffect.fill(-1) }
            repeat(8) { channel ->
                val c = p.tracks[channel][row]
                if (!c.isBlank) {
                    val note = if (c.note == Cell.CUT) 255 else if (c.note == Cell.EMPTY) 250 else c.note + 12
                    val fx = effect(c.effect, c.param)
                    var mask = (if (note != 250) 0x11 else 0) or (if (c.instrument != 0) 0x22 else 0) or (if (fx != 0) 0x88 else 0)
                    if (note > 250) mask = mask and 0xCF
                    if (mask and 1 != 0) {
                        if (lastNote[channel] == note) mask = mask and 0xFE
                        else lastNote[channel] = if (note >= 254) -1 else note
                    }
                    if (mask and 2 != 0) {
                        if (lastInst[channel] == c.instrument) mask = mask and 0xFD else lastInst[channel] = c.instrument
                    }
                    if (mask and 8 != 0) {
                        if (lastEffect[channel] == fx) mask = mask and 0xF7 else lastEffect[channel] = fx
                    }
                    val changed = mask != lastMask[channel]
                    out.u8(channel + 1 or if (changed) 128 else 0)
                    if (changed) { out.u8(mask); lastMask[channel] = mask }
                    if (mask and 1 != 0) out.u8(note)
                    if (mask and 2 != 0) out.u8(c.instrument)
                    if (mask and 8 != 0) { out.u8(fx shr 8); out.u8(fx) }
                }
            }
            out.u8(0)
        }
    }

    /** High byte = real MAS effect identifier, low byte = real MAS parameter. */
    internal fun effect(code: Int, param: Int): Int {
        GbaExportConstraints.effectError(code, param)?.let { throw IllegalArgumentException(it) }
        fun fx(code: Int, value: Int = param) = (code shl 8) or value
        return when (code) {
            0 -> if (param == 0) 0 else fx(10)
            1 -> fx(6)
            2 -> fx(5)
            3 -> fx(7)
            4 -> fx(8)
            5 -> fx(12)
            6 -> fx(11)
            7 -> fx(18)
            8 -> fx(24)
            9 -> fx(15)
            10 -> fx(4)
            11 -> fx(2)
            12 -> fx(27)
            13 -> fx(3, (param shr 4) * 10 + (param and 15))
            14 -> {
                val x = param shr 4
                val y = param and 15
                when (x) {
                    1, 2 -> fx(if (x == 1) 6 else 5, if (y == 0) 0 else 0xF0 or y)
                    4, 7 -> fx(19, (if (x == 4) 0x30 else 0x40) or intArrayOf(0, 1, 4, 6, 8, 9, 12, 6)[y])
                    6 -> fx(19, 0xB0 or y)
                    8 -> fx(24, y * 16)
                    9 -> fx(19, 0x20 or y)
                    10 -> fx(19, y)
                    11 -> fx(19, 0x10 or y)
                    else -> fx(19)
                }
            }
            else -> fx(if (param < 32) 1 else 20)
        }
    }
}
