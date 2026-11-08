package org.thortracker.backend

/**
 * Test-only NES "player": a 6502 (documented opcodes, no decimal mode — the 2A03 has none) running an embedded
 * FamiStudio player build ([player]) like an NSF host — init once, then play each frame — and recording the sound
 * register writes of every frame: the APU's `$4000-$4017` and, on an expansion build, the chip's (VRC6 `$9000-$9003`,
 * `$A000-$A002`, `$B000-$B002`; N163 its address port `$F800` and data port `$4800`; FDS `$4040-$408A`, its wave RAM,
 * frequency, envelope, modulation and master volume registers, so register choices and wave and mod-table uploads are
 * compared). Two song datas that write the same registers in the same order with the same values on every
 * frame sound identical on any chip; that is the golden comparison between FamiStudio's own export and our lowering.
 */
class NesTracer(songData: ByteArray, private val player: NesPlayer = NesPlayer.APU) : Cpu6502() {
    private val frameWrites = ArrayList<Int>()

    init {
        player.bytes.forEachIndexed { i, b -> mem[player.load + i] = b.toInt() and 0xff }
        songData.forEachIndexed { i, b -> mem[player.songBase + i] = b.toInt() and 0xff }
        call(player.init, a = 0, x = 0)
        frameWrites.clear()
    }

    /** One frame (the play routine): register writes as `address shl 8 or value`, in order. */
    fun frame(): IntArray {
        frameWrites.clear()
        call(player.play, a, x)
        return frameWrites.toIntArray()
    }

    override fun read(addr: Int): Int = when (addr) {
        in 0x4000..0x4017 -> 0
        else -> mem[addr and 0xffff]
    }

    override fun write(addr: Int, v: Int) {
        val ad = addr and 0xffff
        if (ad in 0x4000..0x4017 || player.nsfChips and VRC6 != 0 && ad in VRC6_REGISTERS ||
            player.nsfChips and N163 != 0 && (ad == N163_DATA || ad == N163_ADDRESS) ||
            player.nsfChips and FDS != 0 && ad in FDS_REGISTERS) frameWrites += ad shl 8 or (v and 0xff)
        else if (ad < 0x0800) mem[ad] = v and 0xff
        // ROM and unmapped space ignore writes.
    }

    companion object {
        private const val VRC6 = 0x01
        private const val N163 = 0x10
        private const val FDS = 0x04
        private val FDS_REGISTERS = 0x4040..0x408A
        private const val N163_DATA = 0x4800
        private const val N163_ADDRESS = 0xF800
        private val VRC6_REGISTERS = setOf(0x9000, 0x9001, 0x9002, 0x9003, 0xA000, 0xA001, 0xA002, 0xB000, 0xB001, 0xB002)

        /** A frame's writes as text: `4000=3f 9002=81 …`. */
        fun text(t: IntArray) = t.joinToString(" ") { "%04x=%02x".format(it shr 8, it and 0xff) }
    }
}
