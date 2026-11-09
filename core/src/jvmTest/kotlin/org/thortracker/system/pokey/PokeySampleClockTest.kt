package org.thortracker.system.pokey

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PokeySampleClockTest {
    @Test
    fun bulkOwnershipTimersMatchCycleSteppingAcrossRegisterWrites() {
        val actual = PokeySampleClock()
        val reference = CycleTimers()
        val controls = intArrayOf(0, 1, 0x40, 0x41, 0x60, 0x61, 0x10, 0x11, 0x50, 0x51, 0x18, 0x78, 0x79)
        val reloads = intArrayOf(0, 1, 4, 7, 110, 220, 254, 255)
        for (i in 0 until 312) {
            for (c in 0..3) {
                val n = reloads[(i * (c + 1) + c) % reloads.size]
                actual.audf[c] = n
                reference.audf[c] = n
            }
            val ctl = controls[(i / 3) % controls.size]
            val twoTone = i % 3
            val cycles = if (i % 17 == 0) 29868 else 1 + (i * 137 % 1200)
            val restart = i % 11 == 0
            val stimer = i % 19 == 0
            reference.advance(cycles, ctl, twoTone, restart, stimer)
            actual.advance(cycles, ctl, twoTone, restart, stimer)
            assertArrayEquals("batch $i ctl=$ctl twoTone=$twoTone", reference.fires, actual.fires)
        }
        // Long stable joined/two-tone spans exercise recurring-state skips, including timers that starve.
        for (ctl in controls) for (tone in 0..2) {
            actual.audf.fill(0)
            reference.audf.fill(0)
            reference.advance(29868 * 32, ctl, tone, false, false)
            actual.advance(29868 * 32, ctl, tone, false, false)
            assertArrayEquals("bulk ctl=$ctl twoTone=$tone", reference.fires, actual.fires)
        }
    }

    /** Deliberately cycle-stepped: no event skipping, period formula, or implementation helper reuse.
     * Counts before fires; 3/4 before resyncs, 1/2 after. Mirrors the chip's documented transition rules.
     */
    private class CycleTimers {
        val audf = IntArray(4)
        val fires = LongArray(4)
        private val remaining = IntArray(4) { 1 }
        private val pending = LongArray(4) { -1 }
        private val resets = BooleanArray(3)
        private var now = 0L
        private var exit = 0L

        fun advance(cycles: Int, ctl: Int, tone: Int, restart: Boolean, stimer: Boolean) {
            fires.fill(0)
            if (restart) exit = now
            if (stimer) {
                for (c in 0..3) { remaining[c] = audf[c] + 1; pending[c] = -1 }
                resets.fill(false)
            }
            repeat(cycles) {
                ++now
                val first = exit + if (ctl and 1 != 0) 78 else 19
                val unit = if (ctl and 1 != 0) 114 else 28
                val pulse = now >= first && (now - first) % unit == 0L
                for (c in 0..3) {
                    val fast = c == 0 && ctl and 0x40 != 0 || c == 2 && ctl and 0x20 != 0
                    val linked = c == 1 && ctl and 0x10 != 0 || c == 3 && ctl and 0x08 != 0
                    if (fast || (!linked && pulse)) count(c)
                }
                if (pending[2] == now) fire(2, ctl, tone)
                if (pending[3] == now) fire(3, ctl, tone)
                val resetSlot = (now % 3).toInt()
                if (resets[resetSlot]) {
                    resets[resetSlot] = false
                    for (c in 0..1) { remaining[c] = audf[c] + 1; pending[c] = -1 }
                }
                if (pending[0] == now) fire(0, ctl, tone)
                if (pending[1] == now) fire(1, ctl, tone)
            }
        }

        private fun count(c: Int) {
            if (--remaining[c] == 0) {
                remaining[c] = 256
                if (pending[c] < 0) pending[c] = now + 3
            }
        }
        private fun fire(c: Int, ctl: Int, tone: Int) {
            pending[c] = -1
            ++fires[c]
            when (c) {
                0 -> if (ctl and 0x10 != 0) count(1) else {
                    remaining[0] = audf[0] + 1
                    if (tone == 1) resets[((now + 2) % 3).toInt()] = true
                }
                1 -> {
                    if (ctl and 0x10 != 0) remaining[0] = audf[0] + 1
                    remaining[1] = audf[1] + 1
                    if (tone != 0) resets[((now + 2) % 3).toInt()] = true
                }
                2 -> if (ctl and 0x08 != 0) count(3) else remaining[2] = audf[2] + 1
                3 -> {
                    if (ctl and 0x08 != 0) remaining[2] = audf[2] + 1
                    remaining[3] = audf[3] + 1
                }
            }
        }
    }
}
