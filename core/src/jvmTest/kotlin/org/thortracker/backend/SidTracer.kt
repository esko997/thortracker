package org.thortracker.backend

/**
 * Test-only C64 "player" for the SID golden tests: a 6502 over 64 KiB of RAM with the SID at `$D400-$D7FF` (writes
 * recorded as `reg shl 8 or value`, reads 0) and the CIAs at `$DC00-$DDFF` (writes kept in [cia], reads 0). [psid] runs
 * a PSID like a SID player (init once, then play once per call of [frame]); [prg] runs a `.prg` like a C64 after `RUN`
 * (its start code until it returns to BASIC, then its IRQ handler per [frame] until it jumps to the KERNAL at `$EA31`).
 * Two exports whose writes are equal in every frame sound identical on any SID.
 */
class SidTracer private constructor() : Cpu6502() {
    private val writes = ArrayList<Int>()

    /** CIA registers written ($DC00-$DDFF, index = address − $DC00). */
    val cia = IntArray(0x200)
    private var playAddress = 0
    private var irqHandler = -1

    /** SID writes of the init call (PSID) or start code (PRG). */
    lateinit var initWrites: IntArray
        private set

    /** One play call (PSID) or IRQ (PRG): its SID writes in order. */
    fun frame(): IntArray {
        writes.clear()
        if (irqHandler >= 0) runUntil(irqHandler, KERNAL_IRQ) else call(playAddress, 0, 0)
        return writes.toIntArray()
    }

    /** The IRQ vector a `.prg` installed (`$0314`). */
    val irqVector: Int get() = mem[0x314] or (mem[0x315] shl 8)

    override fun read(addr: Int): Int = when (addr and 0xffff) {
        in 0xD400..0xD7FF, in 0xDC00..0xDDFF -> 0
        else -> mem[addr and 0xffff]
    }

    override fun write(addr: Int, v: Int) {
        val ad = addr and 0xffff
        when (ad) {
            in 0xD400..0xD7FF -> writes += ((ad - 0xD400) and 0x1F) shl 8 or (v and 0xff)
            in 0xDC00..0xDDFF -> cia[ad - 0xDC00] = v and 0xff
            else -> mem[ad] = v and 0xff
        }
    }

    companion object {
        /** Where the KERNAL's IRQ code continues after a `.prg`'s handler (`JMP $EA31`). */
        const val KERNAL_IRQ = 0xEA31

        fun psid(file: ByteArray): SidTracer {
            fun be16(at: Int) = (file[at].toInt() and 0xff) shl 8 or (file[at + 1].toInt() and 0xff)
            require(file.decodeToString(0, 4) == "PSID") { "not a PSID" }
            val offset = be16(6)
            var load = be16(8)
            var data = offset
            if (load == 0) { load = (file[data].toInt() and 0xff) or ((file[data + 1].toInt() and 0xff) shl 8); data += 2 }
            return SidTracer().apply {
                for (i in data until file.size) mem[load + i - data] = file[i].toInt() and 0xff
                playAddress = be16(12)
                call(be16(10), 0, 0)
                initWrites = writes.toIntArray()
            }
        }

        fun prg(file: ByteArray, start: Int): SidTracer = SidTracer().apply {
            val load = (file[0].toInt() and 0xff) or ((file[1].toInt() and 0xff) shl 8)
            for (i in 2 until file.size) mem[load + i - 2] = file[i].toInt() and 0xff
            call(start, 0, 0)
            initWrites = writes.toIntArray()
            irqHandler = irqVector
        }
    }
}
