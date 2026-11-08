package org.thortracker.backend

/**
 * Test-only NES "player": a 6502 (documented opcodes, no decimal mode — the 2A03 has none) running the embedded
 * FamiStudio player like an NSF host — init once, then play each frame — and recording the APU register writes of
 * every frame. Two song datas that write the same registers in the same order with the same values on every frame
 * sound identical on any APU; that is the golden comparison between FamiStudio's own export and our lowering.
 */
class NesTracer(songData: ByteArray) : Cpu6502() {
    private val frameWrites = ArrayList<Int>()

    init {
        NesPlayerImage.BYTES.forEachIndexed { i, b -> mem[NesPlayerImage.LOAD + i] = b.toInt() and 0xff }
        songData.forEachIndexed { i, b -> mem[NesPlayerImage.SONG_BASE + i] = b.toInt() and 0xff }
        call(NesPlayerImage.INIT, a = 0, x = 0)
        frameWrites.clear()
    }

    /** One frame (the play routine): APU writes as `reg shl 8 or value`, in order. */
    fun frame(): IntArray {
        frameWrites.clear()
        call(NesPlayerImage.PLAY, a, x)
        return frameWrites.toIntArray()
    }

    override fun read(addr: Int): Int = when (addr) {
        in 0x4000..0x4017 -> 0
        else -> mem[addr and 0xffff]
    }

    override fun write(addr: Int, v: Int) {
        val ad = addr and 0xffff
        if (ad in 0x4000..0x4017) frameWrites += (ad - 0x4000) shl 8 or (v and 0xff)
        else if (ad < 0x0800) mem[ad] = v and 0xff
        // ROM and unmapped space ignore writes.
    }
}
