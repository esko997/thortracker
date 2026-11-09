package org.thortracker.backend

/**
 * Test-only NES "player": a 6502 (documented opcodes, no decimal mode — the 2A03 has none) running an embedded
 * FamiStudio player build ([player]) like an NSF host — init once, then play each frame — and recording the sound
 * register writes of every frame: the APU's `$4000-$4017` and, on an expansion build, the chip's (VRC6 `$9000-$9003`,
 * `$A000-$A002`, `$B000-$B002`; VRC7 the register select `$9010`, data `$9030` and the `$E000` audio reset latch; N163
 * its address port `$F800` and data port `$4800`, so register choices and wave uploads are compared; FDS `$4040-$408A`,
 * its wave RAM, frequency, envelope, modulation and master volume registers, so wave and mod-table uploads are compared
 * too; Sunsoft 5B register select `$C000-$DFFF` and data `$E000-$FFFF`, which as on the cartridge leave the ROM
 * unchanged; MMC5 `$5000-$5015`, its pulses and enable; EPSM the YMF288's address and data ports `$401C-$401F`). Two
 * song datas that write the same registers in the same order with the same values on every frame sound identical on any
 * chip; that is the golden comparison between FamiStudio's own export and our lowering.
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
        if (ad in 0x4000..0x4017 || player.nsfChips and VRC6 != 0 && ad in VRC6_REGISTERS || player.nsfChips and VRC7 != 0 && ad in VRC7_REGISTERS ||
            player.nsfChips and N163 != 0 && (ad == N163_DATA || ad == N163_ADDRESS) || player.nsfChips and FDS != 0 && ad in FDS_REGISTERS ||
            player.nsfChips and S5B != 0 && ad >= 0xC000 || player.nsfChips and MMC5 != 0 && ad in MMC5_REGISTERS ||
            player.nsfChips and EPSM != 0 && ad in EPSM_PORTS
        ) {
            frameWrites += ad shl 8 or (v and 0xff)
        } else if (ad < 0x0800) mem[ad] = v and 0xff
        // ROM and unmapped space ignore writes.
    }

    companion object {
        private const val VRC6 = 0x01
        private const val VRC7 = 0x02
        private const val N163 = 0x10
        private const val FDS = 0x04
        private val FDS_REGISTERS = 0x4040..0x408A
        private const val S5B = 0x20
        private const val MMC5 = 0x08
        private val MMC5_REGISTERS = 0x5000..0x5015
        private const val N163_DATA = 0x4800
        private const val N163_ADDRESS = 0xF800
        private val VRC6_REGISTERS = setOf(0x9000, 0x9001, 0x9002, 0x9003, 0xA000, 0xA001, 0xA002, 0xB000, 0xB001, 0xB002)
        private val VRC7_REGISTERS = setOf(0x9010, 0x9030, 0xE000)
        private const val EPSM = 0x80

        /** The EPSM's ports: `$401C`/`$401D` address/data with A1 = 0, `$401E`/`$401F` with A1 = 1. */
        val EPSM_PORTS = 0x401C..0x401F

        /** A frame's writes as text: `4000=3f 9002=81 …`. */
        fun text(t: IntArray) = t.joinToString(" ") { "%04x=%02x".format(it shr 8, it and 0xff) }

        /**
         * [trace] without the EPSM patch-register writes that store the value the register already holds ([held]: per
         * port bank, `$401C`/`$401D` for A1 = 0 and `$401E`/`$401F` for A1 = 1, the last value of each register, -1 =
         * none yet; updated): the LFO `$22` and the FM operator and channel registers `$30-$9F` and `$B0-$B6`, which take
         * effect only through their value. Every other write, the first write of each register and every changed value
         * are kept in order. Our streams load each note's own slot and re-send an FM channel's sound per order, which
         * rewrites those registers with the values they hold where FamiStudio's export loads nothing.
         */
        fun withoutUnchangedEpsmPatchWrites(trace: IntArray, held: Array<IntArray>): IntArray {
            val out = ArrayList<Int>(trace.size)
            var i = 0
            while (i < trace.size) {
                val port = trace[i] ushr 8
                val bank = when (port) { 0x401C -> 0; 0x401E -> 1; else -> -1 }
                if (bank >= 0 && i + 1 < trace.size && trace[i + 1] ushr 8 == port + 1) {
                    val register = trace[i] and 0xff
                    val value = trace[i + 1] and 0xff
                    val patch = register == 0x22 || register in 0x30..0x9F || register in 0xB0..0xB6
                    if (!patch || held[bank][register] != value) {
                        out += trace[i]
                        out += trace[i + 1]
                        if (patch) held[bank][register] = value
                    }
                    i += 2
                } else {
                    out += trace[i++]
                }
            }
            return out.toIntArray()
        }

        /** A fresh [withoutUnchangedEpsmPatchWrites] state: nothing written yet on either bank. */
        fun epsmPatchState(): Array<IntArray> = Array(2) { IntArray(256) { -1 } }
    }
}
