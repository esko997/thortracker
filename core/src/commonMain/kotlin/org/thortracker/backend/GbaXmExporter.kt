package org.thortracker.backend

import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.gba.GbaEnvelope

/** FastTracker II XM 1.04, eight channels, linear periods, mono delta-coded signed 8-bit PCM. */
object GbaXmExporter {
    fun export(song: Song): ByteArray = encode(GbaExportModel(song))

    internal fun encode(model: GbaExportModel): ByteArray {
        // XM repeats samples per instrument. Its file can exceed the deduplicated native bank size.
        val capacity = 336 + model.patterns.sumOf { 9 + it.length * 8 * 5 } +
            model.instruments.fold(0) { total, inst -> total + if (inst.samples.isEmpty()) 29 else 263 } +
            model.samples.sumOf { 40 + it.source.pcm.size }
        val out = GbaBytes(capacity)
        out.text("Extended Module: ", 17)
        out.text(model.song.title, 20)
        out.u8(0x1A)
        out.text("Thor Tracker", 20)
        out.u16(0x0104)
        out.u32(276)
        out.u16(model.orders.size)
        out.u16(model.song.loopOrder)
        out.u16(8)
        out.u16(model.patterns.size)
        out.u16(model.instruments.size)
        out.u16(1)
        out.u16(model.song.ticksPerRow)
        out.u16(model.sound.bpm)
        repeat(256) { out.u8(model.orders.getOrElse(it) { 0 }) }
        model.patterns.forEach { pattern ->
            val packed = GbaBytes(256 * 8 * 6)
            repeat(pattern.length) { row ->
                repeat(8) { channel ->
                    val c = pattern.tracks[channel][row]
                    var mask = 0x80
                    if (c.note != Cell.EMPTY) mask = mask or 1
                    if (c.instrument != 0) mask = mask or 2
                    if (c.hasEffect) mask = mask or 24
                    packed.u8(mask)
                    if (mask and 1 != 0) packed.u8(if (c.note == Cell.CUT) 97 else c.note + 1)
                    if (mask and 2 != 0) packed.u8(c.instrument)
                    if (mask and 8 != 0) { packed.u8(c.effect); packed.u8(c.param) }
                }
            }
            out.u32(9)
            out.u8(0)
            out.u16(pattern.length)
            out.u16(packed.size)
            out.append(packed.result())
        }
        model.instruments.forEach { inst ->
            out.u32(if (inst.samples.isEmpty()) 29 else 263)
            out.text(inst.source.name, 22)
            out.u8(0)
            out.u16(inst.samples.size)
            if (inst.samples.isNotEmpty()) {
                out.u32(40)
                inst.keymap.forEach(out::u8)
                points(out, inst.source.volumeEnvelope)
                points(out, inst.source.panEnvelope)
                out.u8(inst.source.volumeEnvelope.points.size)
                out.u8(inst.source.panEnvelope.points.size)
                positions(out, inst.source.volumeEnvelope)
                positions(out, inst.source.panEnvelope)
                out.u8(flags(inst.source.volumeEnvelope))
                out.u8(flags(inst.source.panEnvelope))
                out.zeros(4) // auto-vibrato is not in this backend's instrument model
                out.u16(inst.source.fadeout / 32 * 32)
                out.zeros(22)
                inst.samples.forEach { index ->
                    val sample = model.samples[index]
                    val source = sample.source
                    out.u32(source.pcm.size)
                    out.u32(source.loopStart)
                    out.u32(sample.loopLength)
                    out.u8(sample.volume)
                    out.u8(sample.pitch.fineTune)
                    out.u8(if (sample.loopLength > 0) 1 else 0)
                    out.u8(sample.pan)
                    out.u8(sample.pitch.relativeNote)
                    out.u8(0)
                    out.text(source.name, 22)
                }
                inst.samples.forEach { index ->
                    var last = 0
                    model.samples[index].source.pcm.forEach { value -> out.u8(value - last); last = value }
                }
            }
        }
        return out.result()
    }

    private fun points(out: GbaBytes, envelope: GbaEnvelope) {
        repeat(12) { i -> out.u16(envelope.points.getOrNull(i)?.tick ?: 0); out.u16(envelope.points.getOrNull(i)?.value ?: 0) }
    }
    private fun positions(out: GbaBytes, envelope: GbaEnvelope) {
        out.u8(envelope.sustain.coerceAtLeast(0)); out.u8(envelope.loopStart.coerceAtLeast(0)); out.u8(envelope.loopEnd.coerceAtLeast(0))
    }
    private fun flags(e: GbaEnvelope) = (if (e.enabled) 1 else 0) or (if (e.sustain >= 0) 2 else 0) or (if (e.loopStart >= 0) 4 else 0)
}
