package org.thortracker.system.pokey

import org.thortracker.backend.PokeyPlayerImage
import org.thortracker.system.nes.NesEnvelope

/** The pitch/effect and sample-phase subset of pokey_player.c used by [PokeyStraightPlay].
 * No PCM, register trace or per-level work: the timer model returns a bulk fire count for each channel.
 * Synthesized voices participate because their timers can later be inherited by a sample, or resync it.
 */
internal class PokeyOwnershipPlayback(private val sound: PokeySound, private val slots: IntArray) {
    private val clock = PokeySampleClock()
    private val base = IntArray(4)
    private val target = IntArray(4)
    private val slide = IntArray(4)
    private val mode = IntArray(4)
    private val speed = IntArray(4)
    private val arp = IntArray(4)
    private val arpStep = IntArray(4)
    private val vibrato = IntArray(4)
    private val phase = IntArray(4)
    private val filter = IntArray(4) { NO_FILTER }
    private val steps = Array(4) { IntArray(3) }
    private val pitchAcc = IntArray(4)
    private val released = BooleanArray(4)
    private val silenced = BooleanArray(4)
    private val index = LongArray(4)
    val playing = BooleanArray(4)
    private var cycleAcc = 0
    private val memoPitch = IntArray(4) { -1 }
    private val memoDist = IntArray(4)
    private val memoBase = BooleanArray(4)
    private val memoFilter = IntArray(4)
    private val counter = IntArray(4)
    private val partnerCounter = IntArray(4)

    fun note(lane: Int) { arp[lane] = 0; filter[lane] = NO_FILTER }
    fun target(lane: Int, note: Int) { target[lane] = note * 64 }
    fun effect(lane: Int, code: Int, param: Int) {
        when (code) {
            PokeyRules.FX_ARPEGGIO -> { arp[lane] = param; arpStep[lane] = 0 }
            PokeyRules.FX_SLIDE_UP, PokeyRules.FX_SLIDE_DOWN, PokeyRules.FX_PORTAMENTO -> {
                mode[lane] = if (param == 0) 0 else code
                speed[lane] = param
            }
            PokeyRules.FX_VIBRATO -> vibrato[lane] = param
            PokeyRules.FX_FILTER -> filter[lane] = if (param >= 128) param - 256 else param
        }
    }

    fun keyOn(lane: Int, note: Int, ins: PokeyInstrument) {
        base[lane] = note * 64
        target[lane] = base[lane]
        slide[lane] = 0
        arpStep[lane] = 0
        phase[lane] = 0
        steps[lane].fill(0)
        pitchAcc[lane] = 0
        released[lane] = false
        silenced[lane] = false
        index[lane] = 0
        playing[lane] = ins.kind == PokeyKind.SAMPLE && !sound.samples.getOrNull(ins.sample)?.levels.isNullOrEmpty()
        memoPitch[lane] = -1
    }

    fun release(lane: Int, ins: PokeyInstrument) {
        if (released[lane]) return
        released[lane] = true
        for (k in 0..2) {
            val e = envelope(ins, k)
            if (e.release >= 0) steps[lane][k] = e.release
        }
        if (ins.kind == PokeyKind.SAMPLE) playing[lane] = false
        else if (ins.volume.values.isEmpty() || ins.volume.release < 0) silenced[lane] = true
    }

    /** True when another update cannot change any timer's pitch or pitch-envelope state. */
    fun steady(): Boolean {
        for (lane in 0..3) {
            val ins = sound.instrument(slots[lane]) ?: continue
            if (arp[lane] != 0 || ((vibrato[lane] and 15) != 0 && vibrato[lane] shr 4 != 0)) return false
            if (mode[lane] == PokeyRules.FX_SLIDE_UP && slide[lane] < 6912 ||
                mode[lane] == PokeyRules.FX_SLIDE_DOWN && slide[lane] > -6912 ||
                mode[lane] == PokeyRules.FX_PORTAMENTO && base[lane] != target[lane]) return false
            if (ins.pitchRelative) {
                val step = value(ins, lane, 1)
                if (step > 0 && pitchAcc[lane] < PokeyPlayerImage.RELATIVE_LIMIT ||
                    step < 0 && pitchAcc[lane] > -PokeyPlayerImage.RELATIVE_LIMIT) return false
            }
            if (!silenced[lane]) for (k in 0..2) {
                if (k == 2 && ins.kind == PokeyKind.SAMPLE) continue
                val e = envelope(ins, k)
                if (e.values.isNotEmpty() && next(e, steps[lane][k], released[lane]) != steps[lane][k]) return false
            }
        }
        return true
    }

    fun tick(fine: IntArray, distortion: IntArray, base15: Boolean, twoTone: Int, restart: Boolean, stimer: Boolean,
             ticks: Int = 1) {
        var ctl = if (base15) 1 else 0
        for (lane in 0..3) {
            val ins = sound.instrument(slots[lane]) ?: continue
            ctl = ctl or update(lane, ins, fine[lane], distortion[lane], base15)
        }
        cycleAcc += ticks * (if (sound.machine == PokeyMachine.PAL) PokeyPlayerImage.FRAME_CYCLES_PAL else PokeyPlayerImage.FRAME_CYCLES_NTSC)
        val cycles = cycleAcc / sound.multispeed
        cycleAcc -= cycles * sound.multispeed
        clock.advance(cycles, ctl, twoTone, restart, stimer)
        for (lane in 0..3) {
            val ins = sound.instrument(slots[lane]) ?: continue
            if (ins.kind != PokeyKind.SAMPLE || !playing[lane]) continue
            val length = sound.samples.getOrNull(ins.sample)?.levels?.size ?: 0
            val next = index[lane] + clock.fires[lane]
            if (next < length) index[lane] = next
            else if (ins.loop in 0 until length) index[lane] = ins.loop + (next - length) % (length - ins.loop)
            else playing[lane] = false
        }
    }

    private fun envelope(ins: PokeyInstrument, k: Int): NesEnvelope = when (k) {
        0 -> ins.arpeggio
        1 -> ins.pitch
        else -> ins.distortion
    }
    private fun value(ins: PokeyInstrument, lane: Int, k: Int): Int {
        if (k == 2 && ins.kind == PokeyKind.SAMPLE) return PokeyDistortion.PURE
        val e = envelope(ins, k)
        return if (e.values.isEmpty()) (if (k == 2) PokeyDistortion.PURE else 0) else e.values[steps[lane][k].coerceAtMost(e.values.lastIndex)]
    }
    private fun next(e: NesEnvelope, step: Int, released: Boolean): Int {
        val next = step.coerceAtMost(e.values.lastIndex) + 1
        if (e.release >= 0 && !released && next == e.release) return if (e.loop >= 0) e.loop else e.release - 1
        if (next >= e.values.size) return if (e.loop >= 0 && e.release < 0) e.loop else e.values.lastIndex
        return next
    }

    private fun update(lane: Int, ins: PokeyInstrument, fine: Int, distortion: Int, base15: Boolean): Int {
        var penv = value(ins, lane, 1)
        if (ins.pitchRelative) {
            pitchAcc[lane] = (pitchAcc[lane] + penv).coerceIn(-PokeyPlayerImage.RELATIVE_LIMIT, PokeyPlayerImage.RELATIVE_LIMIT)
            penv = pitchAcc[lane]
        }
        val arpFx = when (arpStep[lane]) { 1 -> arp[lane] shr 4; 2 -> arp[lane] and 15; else -> 0 }
        val vib = (SINE[phase[lane]] * (vibrato[lane] and 15)) shr 4
        val pitch = (base[lane] + 64 * (value(ins, lane, 0) + arpFx) + fine + slide[lane] + vib + 4 * penv)
            .coerceIn(0, PokeyPlayerImage.PITCH_MAX)
        val dist = if (distortion >= 0) distortion else value(ins, lane, 2)
        val offset = if (filter[lane] == NO_FILTER) ins.filterOffset else filter[lane]
        val k = PokeyPitch.Counter.of(ins.kind.joined, ins.fast)
        if (memoPitch[lane] != pitch || memoDist[lane] != dist || memoBase[lane] != base15 || memoFilter[lane] != offset) {
            val n = when {
                ins.kind == PokeyKind.SAMPLE -> PokeyPitch.sampleCounter(sound.machine, base15, ins.fast, ins.rate, ins.root, pitch)
                ins.kind.filtered && ins.difference -> PokeyPitch.differenceCounter(sound.machine, base15, k, pitch, offset)
                else -> PokeyPitch.counter(sound.machine, base15, k, pitch, dist)
            }
            counter[lane] = n
            partnerCounter[lane] = if (ins.kind.filtered && ins.difference) n + offset.coerceIn(PokeyInstrument.DIFFERENCE_OFFSET)
                else (n + offset).coerceIn(0, k.max)
            memoPitch[lane] = pitch; memoDist[lane] = dist; memoBase[lane] = base15; memoFilter[lane] = offset
        }
        val n = counter[lane]
        val n2 = partnerCounter[lane]
        val f = clock.audf
        val bits = when (ins.kind) {
            PokeyKind.TONE, PokeyKind.SAMPLE -> {
                f[lane] = n
                if (ins.fast) (if (lane == 0) 0x40 else 0x20) else 0
            }
            PokeyKind.JOINED -> {
                f[lane - 1] = n and 255; f[lane] = n shr 8
                if (lane == 1) 0x10 or (if (ins.fast) 0x40 else 0) else 0x08 or (if (ins.fast) 0x20 else 0)
            }
            PokeyKind.FILTERED -> {
                f[lane] = n; f[lane + 2] = n2
                if (lane == 0) 0x04 or (if (ins.fast) 0x60 else 0) else 0x02
            }
            PokeyKind.JOINED_FILTERED -> {
                f[0] = n and 255; f[1] = n shr 8; f[2] = n2 and 255; f[3] = n2 shr 8
                0x1a or (if (ins.fast) 0x60 else 0)
            }
        }
        if (arp[lane] != 0) arpStep[lane] = (arpStep[lane] + 1) % 3
        when (mode[lane]) {
            PokeyRules.FX_SLIDE_UP -> slide[lane] = (slide[lane] + speed[lane]).coerceAtMost(6912)
            PokeyRules.FX_SLIDE_DOWN -> slide[lane] = (slide[lane] - speed[lane]).coerceAtLeast(-6912)
            PokeyRules.FX_PORTAMENTO -> base[lane] = if (base[lane] < target[lane]) minOf(target[lane], base[lane] + speed[lane])
                else maxOf(target[lane], base[lane] - speed[lane])
        }
        if (vibrato[lane] and 15 != 0) phase[lane] = (phase[lane] + (vibrato[lane] shr 4)) and 63
        if (!silenced[lane]) for (j in 0..2) {
            if (j == 2 && ins.kind == PokeyKind.SAMPLE) continue
            val e = envelope(ins, j)
            if (e.values.isNotEmpty()) steps[lane][j] = next(e, steps[lane][j], released[lane])
        }
        return bits
    }

    private companion object {
        const val NO_FILTER = 256
        val SINE = intArrayOf(
            0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
            127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
            0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
            -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
        )
    }
}
