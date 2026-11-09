package org.thortracker.system.pokey

import org.thortracker.backend.PokeyPlayerImage

/** Timer-only counterpart of pokey_chip.c, for straight-play sample ownership (not audio synthesis).
 * AUDF writes change the next reload, not the running count. Key-ons never reset these counters.
 * Independent timers advance in bulk; joined/two-tone pairs skip recurring reload/resync states in bulk too.
 * All events at the end cycle run before the next player tick, as in pokey_machine.c.
 */
internal class PokeySampleClock {
    val audf = IntArray(4)
    val fires = LongArray(4)
    private val pairs = Array(2) { PairTimers(it * 2) }
    private var now = 0L
    private var exit = 0L

    fun advance(cycles: Int, audctl: Int, twoTone: Int, restart: Boolean, stimer: Boolean) {
        if (restart) exit = now
        fires.fill(0)
        for (pair in pairs) {
            if (stimer) pair.reset()
            pair.advance(now, now + cycles, exit, audctl, if (pair.low == 0) twoTone else 0)
        }
        now += cycles
    }

    private inner class PairTimers(val low: Int) {
        private val count = intArrayOf(1, 1)
        private val pending = longArrayOf(NEVER, NEVER)
        private val resync = LongArray(4) { NEVER }
        private var resyncs = 0
        private var now = 0L
        private var first = 0L
        private var unit = 28
        private var fast = false
        private var joined = false
        private var twoTone = 0
        // At a reload/resync both counts and pending fires are canonical. Only prescaler phase and the
        // next two cycles' resyncs distinguish states; fixed arrays avoid allocating per event/tick.
        private val seen = IntArray(114 * 4)
        private val seenAt = LongArray(114 * 4)
        private val seenLow = LongArray(114 * 4)
        private val seenHigh = LongArray(114 * 4)
        private var generation = 0

        fun reset() {
            for (c in 0..1) {
                count[c] = audf[low + c] + 1
                pending[c] = NEVER
            }
            resyncs = 0
        }

        fun advance(start: Long, end: Long, exit: Long, ctl: Int, tone: Int) {
            now = start
            unit = if (ctl and 1 != 0) PokeyPlayerImage.BASE_15 else PokeyPlayerImage.BASE_64
            first = exit + if (unit == PokeyPlayerImage.BASE_15) 78 else 19
            fast = ctl and (if (low == 0) 0x40 else 0x20) != 0
            joined = ctl and (if (low == 0) 0x10 else 0x08) != 0
            twoTone = tone
            if (!joined && twoTone == 0 && resyncs == 0) {
                independent(0, end)
                independent(1, end)
                return
            }
            if (++generation == 0) { seen.fill(0); generation = 1 }
            while (now < end) {
                skipRepeats(end)
                if (now == end) break
                var event = if (resyncs > 0) resync[0] else NEVER
                for (c in 0..1) event = minOf(event, pending[c], underflow(c))
                if (event > end) { skip(end); break }
                skip(event - 1)
                now = event
                for (c in 0..1) {
                    if ((c == 0 && fast) || (!(c == 1 && joined) && pulse(event))) decrement(c)
                }
                // Channels 3/4 have no two-tone resync; channels 1/2 fire after resyncs in this cycle.
                while (resyncs > 0 && resync[0] == now) {
                    for (i in 1 until resyncs) resync[i - 1] = resync[i]
                    --resyncs
                    for (c in 0..1) { count[c] = audf[low + c] + 1; pending[c] = NEVER }
                }
                if (pending[0] == now) fire(0)
                if (pending[1] == now) fire(1)
            }
        }

        private fun pulse(t: Long) = t >= first && (t - first) % unit == 0L
        private fun nextPulse(): Long = if (now < first) first else first + ((now - first) / unit + 1) * unit
        private fun pulses(a: Long, b: Long): Long {
            if (b < first) return 0
            return (b - first) / unit + 1 - if (a < first) 0 else (a - first) / unit + 1
        }
        private fun underflow(c: Int): Long = when {
            c == 0 && fast -> now + count[c]
            c == 1 && joined -> NEVER
            else -> nextPulse() + (count[c] - 1L) * unit
        }
        private fun skip(to: Long) {
            if (to <= now) return
            val baseCounts = pulses(now, to).toInt()
            for (c in 0..1) {
                if (c == 0 && fast) count[c] -= (to - now).toInt()
                else if (!(c == 1 && joined)) count[c] -= baseCounts
            }
            now = to
        }
        private fun decrement(c: Int) {
            if (--count[c] == 0) {
                count[c] = 256
                if (pending[c] == NEVER) pending[c] = now + 3
            }
        }
        private fun fire(c: Int) {
            pending[c] = NEVER
            ++fires[low + c]
            if (c == 0 && joined) decrement(1)
            else {
                if (c == 1 && joined) count[0] = audf[low] + 1
                count[c] = audf[low + c] + 1
                if (twoTone != 0 && (c == 1 || twoTone == 1)) scheduleResync()
            }
        }
        private fun scheduleResync() {
            val t = now + 2
            if (resyncs > 0 && resync[resyncs - 1] == t) return
            resync[resyncs++] = t
        }

        private fun independent(c: Int, end: Long) {
            val start = now
            val firstFire = minOf(pending[c], underflow(c) + 3)
            if (firstFire <= end) {
                ++fires[low + c]
                now = firstFire
                count[c] = audf[low + c] + 1
                pending[c] = NEVER
                // A pending fire survives a clock switch or R00 and need not lie on the new prescaler's
                // phase. Only the following fire establishes the repeating period on that clock.
                val secondFire = underflow(c) + 3
                if (secondFire <= end) {
                    val period = if (c == 0 && fast) audf[low + c] + 4 else unit * (audf[low + c] + 1)
                    val n = 1 + (end - secondFire) / period
                    fires[low + c] += n
                    now = secondFire + (n - 1) * period
                }
            }
            val under = underflow(c)
            val counts = if (c == 0 && fast) end - now else pulses(now, end)
            if (under <= end) {
                count[c] = (count[c] - counts + 256).toInt()
                if (pending[c] == NEVER) pending[c] = under + 3
            } else count[c] -= counts.toInt()
            now = start
        }

        private fun skipRepeats(end: Long) {
            if (pending[0] != NEVER || pending[1] != NEVER ||
                count[0] != audf[low] + 1 || count[1] != audf[low + 1] + 1) return
            val base = !fast || !joined
            if (base && now < first) return
            var mask = 0
            for (i in 0 until resyncs) mask = mask or (1 shl (resync[i] - now - 1).toInt())
            val phase = if (base) ((now - first) % unit).toInt() else 0
            val key = phase * 4 + mask
            if (seen[key] == generation) {
                val period = now - seenAt[key]
                val repeats = (end - now) / period
                if (repeats > 0) {
                    fires[low] += (fires[low] - seenLow[key]) * repeats
                    fires[low + 1] += (fires[low + 1] - seenHigh[key]) * repeats
                    val elapsed = period * repeats
                    now += elapsed
                    for (i in 0 until resyncs) resync[i] += elapsed
                }
            } else {
                seen[key] = generation
                seenAt[key] = now
                seenLow[key] = fires[low]
                seenHigh[key] = fires[low + 1]
            }
        }
    }

    private companion object { const val NEVER = Long.MAX_VALUE / 2 }
}
